// Decoded API responses from the site's decoder
(handle) => {
    const defineProperty = Object.defineProperty;
    const looksLikeApiItems = (value) => Array.isArray(value) && value.length > 0 &&
        value.some((item) => item && typeof item === "object" && typeof item.id === "number");
    const capture = (holder, value) => {
        if (looksLikeApiItems(value)) setTimeout(() => handle(holder));
    };
    defineProperty(Object.prototype, "items", {
        configurable: true,
        set(value) {
            defineProperty(this, "items", {
                value,
                writable: true,
                enumerable: true,
                configurable: true,
            });
            capture(this, value);
        },
    });
    Object.defineProperty = function (target, key, descriptor) {
        const result = defineProperty.apply(this, arguments);
        if (key === "items" && descriptor && "value" in descriptor) capture(target, descriptor.value);
        return result;
    };
}
