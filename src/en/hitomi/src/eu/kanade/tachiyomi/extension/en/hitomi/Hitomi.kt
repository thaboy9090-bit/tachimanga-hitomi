package eu.kanade.tachiyomi.extension.en.hitomi

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
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
    private val ltnHitomiUrl = "https://ltn.hitomi.la"
    private val pageSize = 25

    @Volatile
    private var pendingIds: List<Int>? = null

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request =
        nozomiRequest("popular/year-all.nozomi", page)

    override fun popularMangaParse(response: Response): MangasPage =
        nozomiParse(response)

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request =
        nozomiRequest("index-all.nozomi", page)

    override fun latestUpdatesParse(response: Response): MangasPage =
        nozomiParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val langFilter = filters.filterIsInstance<LanguageFilter>().firstOrNull()
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()

        val lang = when (langFilter?.state ?: 0) {
            1 -> "english"
            2 -> "spanish"
            3 -> "chinese"
            4 -> "japanese"
            else -> "all"
        }

        when {
            query.isNotBlank() -> {
                val q = query.trim().lowercase().replace('_', ' ')
                val nozomiPath = when {
                    q.startsWith("female:") || q.startsWith("male:") ->
                        "tag/${q.replace(" ", "%20")}-all.nozomi"
                    q.contains(':') -> {
                        val area = q.substringBefore(':').trim()
                        val tag = q.substringAfter(':').trim().replace(" ", "%20")
                        "$area/$tag-all.nozomi"
                    }
                    else -> {
                        val ids = titleSearch(q, page)
                        if (ids != null) {
                            pendingIds = ids
                            return GET(
                                "$ltnUrl/index-all.nozomi",
                                headersBuilder().add("Range", "bytes=0-3").build(),
                            )
                        }
                        resolveTagPath(q)
                    }
                }
                return nozomiRequest(nozomiPath, page)
            }
            typeFilter != null && typeFilter.state > 0 -> {
                val type = typeFilter.values[typeFilter.state]
                return nozomiRequest("type/$type-all.nozomi", page)
            }
            else -> {
                val path = when (sortFilter?.state ?: 0) {
                    1 -> "date/published-$lang.nozomi"
                    2 -> "popular/today-$lang.nozomi"
                    3 -> "popular/week-$lang.nozomi"
                    4 -> "popular/month-$lang.nozomi"
                    5 -> "popular/year-$lang.nozomi"
                    else -> "index-$lang.nozomi"
                }
                return nozomiRequest(path, page)
            }
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val ids = pendingIds
        if (ids != null) {
            pendingIds = null
            return idsToMangasPage(ids)
        }
        return nozomiParse(response)
    }

    private fun titleSearch(query: String, page: Int): List<Int>? {
        try {
            val words = query.trim().lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null

            val ts = System.currentTimeMillis()
            val version = client.newCall(
                GET("$ltnHitomiUrl/galleriesindex/version?_=$ts", headersBuilder().build()),
            ).execute().body?.string()?.trim() ?: return null

            val indexUrl = "$ltnHitomiUrl/galleriesindex/galleries.$version.index"
            val dataUrl = "$ltnHitomiUrl/galleriesindex/galleries.$version.data"
            val md = java.security.MessageDigest.getInstance("SHA-256")

            var resultIds: Set<Int>? = null
            for (word in words) {
                val key = md.digest(word.toByteArray(Charsets.UTF_8)).take(4).toByteArray()
                val dataRef = bTreeNodeSearch(indexUrl, key)
                if (dataRef == null) return emptyList()
                val wordIds = fetchAllIds(dataUrl, dataRef.first, dataRef.second)
                resultIds = if (resultIds == null) wordIds.toHashSet()
                            else resultIds intersect wordIds.toHashSet()
                if (resultIds.isEmpty()) return emptyList()
            }

            val allIds = resultIds?.toList() ?: return null
            val start = (page - 1) * pageSize
            return allIds.drop(start).take(pageSize)
        } catch (_: Exception) {}
        return null
    }

    private fun resolveTagPath(query: String): String {
        try {
            val encoded = query.replace(" ", "_").replace("/", "slash").replace(".", "dot")
            val path = encoded.map { it.toString() }.joinToString("/")
            val resp = client.newCall(
                GET(
                    "https://tagindex.hitomi.la/global/$path.json",
                    headersBuilder().set("Referer", baseUrl).build(),
                ),
            ).execute()
            if (resp.isSuccessful) {
                val arr = org.json.JSONArray(resp.body?.string() ?: "[]")
                if (arr.length() > 0) {
                    val item = arr.getJSONArray(0)
                    val name = item.getString(0).replace(" ", "%20")
                    val namespace = item.getString(2)
                    return when (namespace) {
                        "female", "male" -> "tag/$namespace:$name-all.nozomi"
                        "tag", "" -> "tag/$name-all.nozomi"
                        else -> "$namespace/$name-all.nozomi"
                    }
                }
            }
        } catch (_: Exception) {}
        return "tag/${query.replace(" ", "%20")}-all.nozomi"
    }

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
            genre = doc.select(".relatedtags li a").joinToString { el ->
                val tag = java.net.URLDecoder.decode(
                    el.attr("href").removePrefix("/tag/").removeSuffix("-all.html"),
                    "UTF-8",
                )
                when {
                    tag.startsWith("female:") -> tag.removePrefix("female:")
                    tag.startsWith("male:") -> tag.removePrefix("male:")
                    else -> tag
                }
            }
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
        Filter.Header("Sintaxis: female:big ass | male:shotacon | artist:nombre"),
        Filter.Header("character:nombre | series:nombre | type:doujinshi"),
        Filter.Separator(),
        LanguageFilter(),
        SortFilter(),
        Filter.Separator(),
        Filter.Header("O filtrar por tipo:"),
        TypeFilter(),
    )

    class LanguageFilter : Filter.Select<String>(
        "Idioma",
        arrayOf("Todos", "English", "Spanish", "Chinese", "Japanese"),
    )

    class SortFilter : Filter.Select<String>(
        "Ordenar por",
        arrayOf("Date Added", "Date Published", "Popular: Today", "Popular: Week", "Popular: Month", "Popular: Year"),
    )

    class TypeFilter : Filter.Select<String>(
        "Tipo",
        arrayOf("Todos", "doujinshi", "manga", "artistcg", "gamecg", "anime"),
    )

    // ======================== Helpers ========================

    private fun idsToMangasPage(ids: List<Int>): MangasPage {
        if (ids.isEmpty()) return MangasPage(emptyList(), false)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(minOf(ids.size, 5))
        val futures = ids.map { id ->
            executor.submit<SManga> {
                val blockResp = client.newCall(
                    GET("$ltnUrl/galleryblock/$id.html", headersBuilder().build()),
                ).execute()
                parseGalleryBlock(id, blockResp)
            }
        }
        val mangas = futures.map { it.get() }
        executor.shutdown()
        return MangasPage(mangas, mangas.size >= pageSize)
    }

    private fun nozomiParse(response: Response): MangasPage {
        val ids = parseNozomiBinary(response.body!!.bytes())
        return idsToMangasPage(ids).let {
            MangasPage(it.mangas, ids.size == pageSize)
        }
    }

    private fun parseGalleryBlock(id: Int, response: Response): SManga {
        val doc = Jsoup.parse(response.body!!.string())
        return SManga.create().apply {
            url = "/galleries/$id"
            title = doc.selectFirst("h1.lillie a")?.text() ?: "Gallery #$id"
            thumbnail_url = doc.selectFirst(".dj-img1 img")?.attr("data-src")
                ?.let { if (it.startsWith("//")) "https:$it" else it }
        }
    }

    private fun nozomiRequest(path: String, page: Int): Request {
        val start = (page - 1) * pageSize * 4
        val end = start + pageSize * 4 - 1
        return GET(
            "$ltnUrl/$path",
            headersBuilder().add("Range", "bytes=$start-$end").build(),
        )
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

    private fun buildImageUrl(hash: String, name: String, hasWebp: Boolean, hasAvif: Boolean, gg: GgData): String {
        if (hash.isEmpty()) return ""
        val s = ggS(hash)
        val m = if (s in gg.mCases) 1 else 0
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

    // ======================== B-Tree Title Search ========================

    private fun fetchRange(url: String, start: Long, end: Long): ByteArray? {
        val resp = client.newCall(
            GET(url, headersBuilder().add("Range", "bytes=$start-$end").build()),
        ).execute()
        return if (resp.isSuccessful || resp.code == 206) resp.body?.bytes() else null
    }

    private fun bTreeNodeSearch(indexUrl: String, key: ByteArray): Pair<Long, Int>? {
        var nodeAddress = 0L
        repeat(64) {
            val nodeData = fetchRange(indexUrl, nodeAddress, nodeAddress + 463) ?: return null
            val buf = ByteBuffer.wrap(nodeData).order(ByteOrder.BIG_ENDIAN)

            val numKeys = buf.int
            if (numKeys == 0) return null

            // Fixed 16 key slots: each slot = 4 bytes (size) + 4 bytes (key data)
            val keys = mutableListOf<ByteArray>()
            for (i in 0 until 16) {
                val size = buf.int
                val keyData = ByteArray(4)
                buf.get(keyData)
                if (i < numKeys && size > 0) keys.add(keyData.take(size).toByteArray())
            }

            // Fixed 16 data slots: each = 8 bytes (offset) + 4 bytes (length)
            val numDatas = buf.int
            val datas = mutableListOf<Pair<Long, Int>>()
            for (i in 0 until 16) {
                val offset = buf.long
                val length = buf.int
                if (i < numDatas) datas.add(Pair(offset, length))
            }

            // 17 subnode addresses (B+1)
            val subnodes = (0..16).map { buf.long }

            var nextAddr = 0L
            for (i in keys.indices) {
                val cmp = compareByteArrays(key, keys[i])
                when {
                    cmp == 0 -> return datas.getOrNull(i)
                    cmp < 0 -> { nextAddr = subnodes.getOrElse(i) { 0L }; break }
                    i == keys.lastIndex -> nextAddr = subnodes.getOrElse(i + 1) { 0L }
                }
            }
            if (nextAddr == 0L) return null
            nodeAddress = nextAddr
        }
        return null
    }

    private fun fetchAllIds(dataUrl: String, offset: Long, length: Int): List<Int> {
        val data = fetchRange(dataUrl, offset, offset + length - 1) ?: return emptyList()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buf.int // skip total count
        return buildList { while (buf.remaining() >= 4) add(buf.int) }
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
