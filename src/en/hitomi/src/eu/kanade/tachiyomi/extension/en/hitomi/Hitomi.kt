package eu.kanade.tachiyomi.extension.en.hitomi

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Hitomi : HttpSource() {

    override val name = "Hitomi.la"
    override val baseUrl = "https://hitomi.la"
    override val lang = "en"
    override val supportsLatest = true

    private val ltnUrl = "https://ltn.gold-usergeneratedcontent.net"
    private val pageSize = 25

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request =
        nozomiRequest("index-english.nozomi", page)

    override fun popularMangaParse(response: Response): MangasPage =
        nozomiParse(response)

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request =
        nozomiRequest("index-english.nozomi", page)

    override fun latestUpdatesParse(response: Response): MangasPage =
        nozomiParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()

        val nozomiPath = when {
            query.isNotBlank() -> {
                val tag = query.trim().lowercase().replace(" ", "_")
                "n/tag/$tag-english.nozomi"
            }
            typeFilter != null && typeFilter.state > 0 -> {
                val type = typeFilter.values[typeFilter.state]
                "n/type/$type-all.nozomi"
            }
            else -> "index-english.nozomi"
        }

        return nozomiRequest(nozomiPath, page)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        nozomiParse(response)

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.removePrefix("/galleries/")
        return GET("$ltnUrl/galleryblock/$id.html", headersBuilder().build())
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body!!.string())
        val href = doc.selectFirst("h1.lillie a")?.attr("href") ?: ""
        val id = href.substringAfterLast('-').removeSuffix(".html")
        return SManga.create().apply {
            title = doc.selectFirst("h1.lillie a")?.text() ?: "Unknown"
            url = "/galleries/$id"
            artist = doc.select(".artist-list li a").joinToString { it.text() }
            author = artist
            genre = doc.select(".relatedtags li a")
                .joinToString { it.text().trimEnd('♀', '♂', ' ').trim() }
            description = buildString {
                doc.select(".series-list li a").firstOrNull()?.text()
                    ?.takeIf { it.isNotEmpty() }?.let { append("Series: $it\n") }
                doc.select("tr").forEach { row ->
                    val label = row.selectFirst("td")?.text() ?: return@forEach
                    val value = row.select("td").getOrNull(1)?.text() ?: return@forEach
                    when (label) {
                        "Type" -> append("Type: $value\n")
                        "Language" -> append("Language: $value\n")
                    }
                }
            }
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            thumbnail_url = doc.selectFirst(".dj-img1 img")?.attr("data-src")
                ?.let { if (it.startsWith("//")) "https:$it" else it }
        }
    }

    // ======================== Chapter List ========================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body!!.string())
        val href = doc.selectFirst("h1.lillie a")?.attr("href") ?: ""
        val id = href.substringAfterLast('-').removeSuffix(".html")
        val title = doc.selectFirst("h1.lillie a")?.text() ?: "Chapter 1"
        return listOf(
            SChapter.create().apply {
                name = title
                url = "/reader/$id.html"
                chapter_number = 1f
                date_upload = 0L
            }
        )
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.removePrefix("/reader/").removeSuffix(".html")
        return GET(
            "$ltnUrl/galleries/$id.js",
            headersBuilder().set("Referer", "$baseUrl/reader/$id.html").build(),
        )
    }

    override fun pageListParse(response: Response): List<Page> {
        val gg = fetchGg()
        val js = response.body!!.string()
        val obj = parseGalleryJs(js)
        val galleryId = obj.optString("id")
        val files = obj.optJSONArray("files") ?: return emptyList()

        return (0 until files.length()).map { i ->
            val file = files.getJSONObject(i)
            val hash = file.optString("hash")
            val name = file.optString("name")
            val hasWebp = file.optInt("haswebp") == 1
            val hasAvif = file.optInt("hasavif") == 1
            Page(i, "$baseUrl/reader/$galleryId.html", buildImageUrl(hash, name, hasWebp, hasAvif, gg))
        }
    }

    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headersBuilder().set("Referer", "$baseUrl/reader/").build())

    // ======================== Filters ========================

    override fun getFilterList() = FilterList(
        Filter.Header("Buscar por tag (ej: naruto)"),
        Filter.Separator(),
        Filter.Header("O filtrar por tipo:"),
        TypeFilter(),
    )

    class TypeFilter : Filter.Select<String>(
        "Tipo",
        arrayOf("Todos", "doujinshi", "manga", "artistcg", "gamecg", "anime"),
    )

    // ======================== Helpers ========================

    private fun nozomiRequest(path: String, page: Int): Request {
        val start = (page - 1) * pageSize * 4
        val end = start + pageSize * 4 - 1
        return GET(
            "$ltnUrl/$path",
            headersBuilder().add("Range", "bytes=$start-$end").build(),
        )
    }

    private fun nozomiParse(response: Response): MangasPage {
        val ids = parseNozomiBinary(response.body!!.bytes())
        val mangas = ids.map { id ->
            SManga.create().apply {
                url = "/galleries/$id"
                title = "Gallery #$id"
                thumbnail_url = null
            }
        }
        return MangasPage(mangas, ids.size == pageSize)
    }

    private fun parseNozomiBinary(bytes: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val ids = mutableListOf<Int>()
        while (buffer.remaining() >= 4) ids.add(buffer.int)
        return ids
    }

    private fun parseGalleryJs(js: String): JSONObject {
        val cleaned = js.trim()
            .removePrefix("var galleryinfo = ")
            .removePrefix("let galleryinfo = ")
            .removePrefix("const galleryinfo = ")
            .trimEnd(';', '\n', '\r', ' ')
        return JSONObject(cleaned)
    }

    private data class GgData(val b: String, val mCases: Set<Int>)

    private fun fetchGg(): GgData {
        val js = client.newCall(
            GET("$ltnUrl/gg.js", headersBuilder().set("Referer", baseUrl).build()),
        ).execute().body?.string() ?: return GgData("", emptySet())
        val b = Regex("""b:\s*'([^']+)'""").find(js)?.groupValues?.get(1) ?: ""
        val mCases = Regex("""case\s+(\d+):""").findAll(js)
            .map { it.groupValues[1].toInt() }.toSet()
        return GgData(b, mCases)
    }

    private fun ggS(hash: String): Int {
        val last3 = hash.takeLast(3)
        val g2 = last3.last().toString()
        val g1 = last3.dropLast(1)
        return (g2 + g1).toInt(16)
    }

    private fun buildThumbnailUrl(hash: String): String {
        if (hash.length < 3) return ""
        val lastChar = hash.last()
        val dir = hash.takeLast(3).dropLast(1)
        return "https://tn.gold-usergeneratedcontent.net/webpbigtn/$lastChar/$dir/$hash.webp"
    }

    private fun buildImageUrl(hash: String, name: String, hasWebp: Boolean, hasAvif: Boolean, gg: GgData): String {
        if (hash.isEmpty()) return ""
        val s = ggS(hash)
        val m = if (s in gg.mCases) 0 else 1
        val path = "${gg.b}$s/$hash"
        return when {
            hasWebp -> "https://w${1 + m}.gold-usergeneratedcontent.net/$path.webp"
            hasAvif -> "https://a${1 + m}.gold-usergeneratedcontent.net/$path.avif"
            else -> {
                val ext = name.substringAfterLast('.', "jpg")
                "https://${(97 + m).toChar()}b.gold-usergeneratedcontent.net/images/$path.$ext"
            }
        }
    }
}
