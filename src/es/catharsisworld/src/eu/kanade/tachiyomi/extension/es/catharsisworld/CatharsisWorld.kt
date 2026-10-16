package eu.kanade.tachiyomi.extension.es.catharsisworld

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstance
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.xor
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

@Source
abstract class CatharsisWorld : KeiSource() {

    private val apiUrl get() = "$baseUrl/api"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3, 1.seconds) { it.host == baseUrl.toHttpUrl().host }
        .addInterceptor(::signatureInterceptor)
        .addInterceptor(::pageInterceptor)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("System", "catharsis")
        .add("X-FK-Sistema", "3")

    override suspend fun getPopularManga(page: Int) = searchMangas(page, "", "-n_visitas", null, emptyList())

    override suspend fun getLatestUpdates(page: Int) = searchMangas(page, "", "-fecha_ultimo_capitulo", null, emptyList())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genres = filters.firstInstanceOrNull<GenreFilter>()?.state.orEmpty().filter { it.state }.map { it.id }
        return searchMangas(
            page = page,
            query = query,
            sort = filters.firstInstance<SortFilter>().value,
            status = filters.firstInstance<StatusFilter>().value,
            genres = genres,
        )
    }

    private suspend fun searchMangas(page: Int, query: String, sort: String, status: String?, genres: List<String>): MangasPage {
        val url = "$apiUrl/mangas".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", PAGE_SIZE.toString())
            if (query.isNotBlank()) addQueryParameter("name", query.trim())
            if (genres.isNotEmpty()) addQueryParameter("genre", genres.joinToString("|"))
            status?.let { addQueryParameter("status", it) }
            addQueryParameter("sort", sort)
        }.build()
        val result = client.get(url).parseAs<MangaListDto>()
        return MangasPage(result.data.map { it.toSManga(baseUrl) }, page < result.totalPages)
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$apiUrl/mangas/genres").parseAs<List<GenreDto>>().toJsonElement()

    override fun getFilterList(data: JsonElement?) = FilterList(
        buildList {
            add(SortFilter())
            add(StatusFilter())
            data?.parseAs<List<GenreDto>>()?.let { genres ->
                add(GenreFilter(genres.map { GenreCheckBox(it.nombre, it.id) }))
            }
        },
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "manga") return null
        return client.get("$apiUrl/mangas/${segments[1]}").parseAs<MangaDto>().toSMangaDetails(baseUrl)
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/manga/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val (mangaId, number) = chapter.url.split("/", limit = 2)
        return "$baseUrl/manga/$mangaId/chapter/$number"
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val dto = client.get("$apiUrl/mangas/${manga.url}").parseAs<MangaDto>()
        return SMangaUpdate(
            dto.toSMangaDetails(baseUrl),
            dto.capitulos
                .filter { it.estado == null || it.estado == "publicado" }
                .sortedByDescending { it.number }
                .map { it.toSChapter(manga.url) },
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = "$apiUrl/mangas/${chapter.url}"
        Log.d(TAG, "getPageList: GET $url")
        val dto = try {
            client.get(url).parseAs<PagesDto>()
        } catch (e: HttpException) {
            Log.e(TAG, "getPageList: failed for $url: ${e.message}", e)
            if (e.code == 401) {
                throw Exception("This chapter requires login on the website")
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getPageList: failed for $url: ${e.message}", e)
            throw e
        }
        val token = dto.chapterSessionToken
            ?: throw IllegalStateException("Missing chapter session token")
        Log.d(TAG, "getPageList: ok, pages=${dto.paginas.size}")
        return dto.paginas.mapIndexed { i, pageDto ->
            val ticketRequest = Request.Builder()
                .url("$apiUrl/mangas/chapters/${pageDto.id}/page-ticket")
                .post(PageTicketRequest(pageDto.index, token).toJsonRequestBody())
                .headers(headers)
                .build()
            val ticket = client.newCall(ticketRequest).execute().use { response ->
                if (!response.isSuccessful) throw HttpException(response.code)
                response.parseAs<PageTicketResponse>()
            }
            xorKeys[ticket.signedToken] = ticket.xorByte
            Log.d(TAG, "getPageList: ticket $i ok, xorByte=${ticket.xorByte}")
            Page(i, imageUrl = "$apiUrl/mangas/pages/${ticket.signedToken}")
        }
    }

    private val xorKeys = ConcurrentHashMap<String, Int>()

    override fun imageRequest(page: Page): Request {
        val reqHeaders = headers.newBuilder()
            .add("X-Catharsis-Reader", "1")
            .build()
        return GET(page.imageUrl!!, reqHeaders)
    }

    // Page images are XOR-ed with a single-byte key unless they already start with an image signature
    private fun signatureInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.startsWith("/api/")) {
            Log.d(TAG, "signatureInterceptor: skip non-api path=${request.url.encodedPath}")
            return chain.proceed(request)
        }
        val timestamp = System.currentTimeMillis().toString()
        val nonce = generateNonce()
        val signature = hmacSha256("$timestamp:$nonce:${request.method}", HMAC_KEY)
        Log.d(TAG, "signatureInterceptor: signing path=${request.url.encodedPath} time=$timestamp")
        val newRequest = request.newBuilder()
            .addHeader("x-catharsis-time", timestamp)
            .addHeader("x-catharsis-nonce", nonce)
            .addHeader("x-catharsis-sig", signature)
            .build()
        return chain.proceed(newRequest)
    }

    private fun generateNonce(): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..16).map { chars[Random.nextInt(chars.size)] }.joinToString("")
    }

    private fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun pageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        if (!path.startsWith("/api/mangas/pages/")) return chain.proceed(request)
        val response = chain.proceed(request)
        Log.d(TAG, "pageInterceptor: ${request.method} $path -> ${response.code}")
        if (!response.isSuccessful) return response

        val xorByte = xorKeys[request.url.pathSegments.lastOrNull()] ?: return response

        val body = response.body
        val source = body.source()
        if (!source.request(HEADER_SIZE.toLong())) return response
        if (source.buffer.size < HEADER_SIZE) return response
        val header = source.buffer.snapshot(HEADER_SIZE).toByteArray()
        val decoded = ByteArray(HEADER_SIZE) { (header[it].toInt() xor xorByte).toByte() }
        if (isImage(header) || !isImage(decoded)) return response

        return response.xor(xorByte)
    }

    private fun isImage(bytes: ByteArray): Boolean {
        val b = IntArray(HEADER_SIZE) { bytes[it].toInt() and 0xFF }
        return (b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46) ||
            (b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) ||
            (b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF) ||
            (b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46) ||
            (b[4] == 0x66 && b[5] == 0x74 && b[6] == 0x79 && b[7] == 0x70)
    }

    companion object {
        private const val TAG = "CatharsisWorld"
        private const val PAGE_SIZE = 24
        private const val HMAC_KEY = "4$^£]C8j%La3"
        private const val HEADER_SIZE = 12
    }
}
