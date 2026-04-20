package eu.kanade.tachiyomi.extension.en.mysource

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MySource : ParsedHttpSource() {

    override val name = "My Source"
    override val baseUrl = "https://example.com"
    override val lang = "en"
    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/popular?page=$page", headers)

    override fun popularMangaSelector() = "div.manga-item"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        title = element.select("h3.title").text()
        thumbnail_url = element.select("img").attr("abs:src")
        setUrlWithoutDomain(element.select("a").attr("href"))
    }

    override fun popularMangaNextPageSelector() = "a.next-page"

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/latest?page=$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?q=$query&page=$page", headers)

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ======================== Details ========================

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.select("h1.manga-title").text()
        author = document.select("span.author").text()
        artist = document.select("span.artist").text()
        description = document.select("div.synopsis").text()
        genre = document.select("a.genre").joinToString { it.text() }
        thumbnail_url = document.select("img.cover").attr("abs:src")
        status = when (document.select("span.status").text().lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        update_strategy = SManga.UpdateStrategy.ALWAYS_UPDATE
    }

    // ======================== Chapters ========================

    override fun chapterListSelector() = "ul.chapter-list li"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        name = element.select("a.chapter-name").text()
        setUrlWithoutDomain(element.select("a").attr("href"))
        date_upload = runCatching {
            dateFormat.parse(element.select("span.date").text())?.time ?: 0L
        }.getOrDefault(0L)
    }

    // ======================== Pages ========================

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.reader-images img").mapIndexed { index, element ->
            Page(index, "", element.attr("abs:src"))
        }
    }

    override fun imageUrlParse(document: Document) =
        throw UnsupportedOperationException()
}
