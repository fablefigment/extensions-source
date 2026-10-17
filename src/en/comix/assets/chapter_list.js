// Chapter list through the site's own chapters API method, newest uploads first, up to a page with a known chapter;
// pages past a failure are left out
(knownIds, mangaId, chapterPath, maxPages, pass, reject) => {
    const log = (msg) => console.log('[COMIX-CL] ' + msg);
    log('init mangaId=' + mangaId);
    const known = new Set(knownIds);
    const chapters = [];
    const isChapter = (item) => item &&
        typeof item.id === "number" &&
        typeof item.number === "number" &&
        typeof item.url === "string" &&
        item.url.includes(chapterPath);
    const isRoute = (route) => typeof route === "function" &&
        /^\(?[\w$]+\)?=>`\/manga\/\$\{[\w$]+\}\/chapters`$/.test(String(route));
    const PAGE_TIMEOUT_MS = 25000;
    let page = 1;
    // The site's own first load is by chapter number, so it is skipped and the list read again by upload date
    let siteLoaded = false;
    let expecting = false;
    // Numbers in the page our own call returned (the site's mapped copy), so a page the site loads itself isn't taken for it
    let returnedNumbers = null;
    let done = false;
    let chaptersApi = null;

    const finish = (complete, error) => {
        done = true;
        pass(JSON.stringify({ chapters, complete, error }));
    };

    const matchChaptersApi = (exports) => {
        for (const routes of exports.flatMap((value) => value && typeof value === "object" ? Object.values(value) : [])) {
            if (!routes || typeof routes !== "object") continue;
            const routeKeys = Object.keys(routes);
            const key = routeKeys.find((name) => isRoute(routes[name]));
            if (!key) continue;
            const api = exports.find((value) => value && typeof value === "object" && value !== routes &&
                typeof value[key] === "function" &&
                routeKeys.every((name) => name in value) &&
                String(value[key]).includes("params"));
            if (api) return (params) => api[key](mangaId, params);
        }
        return null;
    };

    // Site's manga API object, matched to the route table by its keys, from the chunks next to the running entry
    // Retries because the script may run before the site's bundle finishes loading
    const findChaptersApi = async (attempts = 10) => {
        for (let i = 0; i < attempts; i++) {
            const scripts = performance.getEntriesByType("resource")
                .map((entry) => entry.name)
                .filter((url) => /\/assets\/.*\.js$/.test(url));
            log('findChaptersApi attempt ' + (i+1) + ' scripts=' + scripts.length);
            const entry = scripts.find((url) => /\/main-[^/]+\.js$/.test(url));
            if (entry) {
                log('main entry=' + entry);
                const directory = entry.slice(0, entry.lastIndexOf("/") + 1);
                const exports = [];
                for (const url of new Set(scripts.filter((url) => url.startsWith(directory)))) {
                    log('trying import ' + url);
                    try {
                        const mod = await Promise.race([
                            import(url),
                            new Promise((_, rej) => setTimeout(() => rej(new Error('import timeout')), 3000)),
                        ]);
                        exports.push(...Object.values(mod));
                        log('import ok ' + url);
                    } catch (e) {
                        log('import fail ' + url + ': ' + (e && e.message));
                    }
                    const api = matchChaptersApi(exports);
                    if (api) {
                        log('API found!');
                        return api;
                    }
                }
            }
            await new Promise((r) => setTimeout(r, 500));
        }
        log('findChaptersApi exhausted');
        return null;
    };

    const requestPage = async (target) => {
        log('requestPage target=' + target);
        let error = `Chapter page ${target} never arrived`;
        try {
            chaptersApi = chaptersApi || await findChaptersApi();
            log('chaptersApi found=' + !!chaptersApi);
            if (!chaptersApi) {
                finish(false, "Could not find the site's chapters API");
                return;
            }
            log('calling chaptersApi...');
            const result = await Promise.race([
                chaptersApi({ page: target, limit: 20, order: { created_at: "desc" } }),
                new Promise((resolve, timeout) => setTimeout(() => timeout(new Error("timed out")), PAGE_TIMEOUT_MS)),
            ]);
            log('result keys=' + (result ? Object.keys(result).join(',') : 'null'));
            const items = result && (result.items || (result.result && result.result.items));
            const meta = result && (result.meta || (result.result && result.result.meta));
            log('items isArray=' + Array.isArray(items) + ' len=' + (items ? items.length : 'n/a'));
            if (!Array.isArray(items) || !items.every(isChapter)) {
                log('items validation failed');
                finish(false, `Chapter page ${target} came back in a shape the extension doesn't recognise`);
                return;
            }
            log('items valid, pushing ' + items.length);
            chapters.push(...items);
            if (items.some((item) => known.has(item.id))) {
                finish(true);
                return;
            }
            const hasNext = meta && (meta.hasNext || (typeof meta.page === "number" && typeof meta.lastPage === "number" && meta.page < meta.lastPage));
            const lastPage = meta && typeof meta.lastPage === "number" ? meta.lastPage : target;
            if (!hasNext || target >= Math.min(lastPage, maxPages)) {
                finish(true);
                return;
            }
            page = target + 1;
            requestPage(page);
            return;
        } catch (e) {
            const status = e && e.response && e.response.status;
            error = status === 502
                ? `Comix blocked chapter page ${target} (HTTP 502). Try again later, or change the user agent in Advanced settings`
                : `Chapter page ${target} failed: ${status ? `HTTP ${status}` : e && e.message || e}`;
        }
        if (!done) finish(false, error);
    };

    // Kick off the first page directly; the holder pattern is kept for compatibility
    // but the API result is processed directly since Vue 3 Proxy bypasses Object.prototype hooks
    requestPage(page);

    return (holder) => {
        const items = holder.items;
        const meta = holder.meta;
        if (done || !meta || !Array.isArray(items) || !items.every(isChapter)) return;
        if (items.length === 0 && meta.total !== 0) return;
        if (typeof meta.page !== "number" || typeof meta.lastPage !== "number") {
            if (chapters.length > 0) {
                finish(false, "Chapter list has no page numbers");
            } else {
                done = true;
                reject("Chapter list has no page numbers");
            }
            return;
        }
        if (!siteLoaded) {
            siteLoaded = true;
            return;
        }
        if (meta.page !== page) return;
        chapters.push(...items.filter((item) => !chapters.some((c) => c.id === item.id)));
        if (items.some((item) => known.has(item.id))) {
            finish(true);
            return;
        }
        if (!meta.hasNext || page >= Math.min(meta.lastPage, maxPages)) {
            const count = new Set(chapters.map((item) => item.id)).size;
            finish(count === meta.total, `Got ${count} of ${meta.total} chapters`);
            return;
        }
        page++;
        requestPage(page);
    };
}
