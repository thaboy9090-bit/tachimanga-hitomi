package eu.kanade.tachiyomi.extension.es.manhwashot

import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import mihonx.source.utils.sourcePreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar

class ManhwaShot : HttpSource(), ConfigurableSource {

    override val name = "ManhwaShot"
    override val lang = "es"
    override val supportsLatest = true

    private val preferences by lazy { sourcePreferences() }

    // El dominio .lat rota cada tanto, asi que lo dejo editable.
    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN, DEFAULT_DOMAIN)
            ?.let(::normalizeDomain)
            ?: DEFAULT_DOMAIN

    // ======================== Listados ========================

    // El catalogo acepta la seccion en la ruta, y el genero, la busqueda y la
    // pagina como parametros. Los tres se combinan entre si.
    private fun listingUrl(page: Int, section: String, genre: String, query: String): String {
        val path = if (section.isEmpty()) "explorar" else "explorar/$section"
        return "$baseUrl/$path/".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
            if (genre.isNotEmpty()) addQueryParameter("genero", genre)
        }.build().toString()
    }

    override fun popularMangaRequest(page: Int): Request =
        GET(listingUrl(page, "", "", ""), headers)

    override fun popularMangaParse(response: Response): MangasPage =
        listingParse(response.asJsoup())

    // La portada trae la seccion "Ultimas actualizaciones" y no se pagina.
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        listingParse(response.asJsoup())

    // Uso la busqueda del catalogo en vez de /api/search/ porque esa API
    // devuelve solo 8 resultados y no se pagina.
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val section = filters.filterIsInstance<SectionFilter>().firstOrNull()?.value.orEmpty()
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.value.orEmpty()
        return GET(listingUrl(page, section, genre, query), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        listingParse(response.asJsoup())

    private fun listingParse(document: Document): MangasPage {
        val mangas = document.select("div.s-card").map(::mangaFromCard)
        val hasNext = document.selectFirst("div.pager a.pager-btn:contains(Siguiente)") != null
        return MangasPage(mangas, hasNext)
    }

    private fun mangaFromCard(element: Element) = SManga.create().apply {
        val link = element.selectFirst("a.s-card-title")
            ?: element.selectFirst("a.s-card-imglink")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        val cover = element.selectFirst("div.s-card-img img")
        title = link.text().ifBlank { cover?.attr("alt").orEmpty() }
        thumbnail_url = cover?.absUrl("src")
    }

    // ======================== Detalle ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.series-title")?.text().orEmpty()
            thumbnail_url = document.selectFirst("div.series-cover img")?.absUrl("src")
            genre = document.select("a.genre-tag").joinToString { it.text() }
            // La pagina trae cuatro div.series-desc; los tres de relleno llevan
            // atributo style y la sinopsis real es el unico que no.
            description = document
                .selectFirst("div.series-desc:not([style]):not(.series-intro-auto)")
                ?.text()
            status = when {
                document.selectFirst("span.badge-completed") != null -> SManga.COMPLETED
                document.selectFirst("span.badge-ongoing") != null -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // ======================== Capitulos ========================

    // Vienen todos en el detalle; los que pasan de los 30 primeros solo llevan
    // display:none y los muestra el boton "Ver todos los capitulos".
    override fun chapterListParse(response: Response): List<SChapter> =
        response.asJsoup().select("div.chapters-grid a.ch-row").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                val label = element.selectFirst("span.ch-num")?.text().orEmpty()
                name = label.ifBlank { "Capítulo" }
                chapter_number = NUMBER_REGEX.find(label)?.value?.toFloatOrNull() ?: -1f
                date_upload = element.select("span")
                    .lastOrNull { !it.hasClass("ch-num") }
                    ?.let { parseRelativeDate(it.text()) }
                    ?: 0L
            }
        }

    // Las fechas son relativas: "hace 10 min", "hace 3 h", "hace 2 meses".
    private fun parseRelativeDate(text: String): Long {
        val value = NUMBER_REGEX.find(text)?.value?.toIntOrNull() ?: return 0L
        val unit = text.lowercase().substringAfter("hace").replace(Regex("[\\d\\s.]"), "")
        val field = when {
            unit.startsWith("seg") -> Calendar.SECOND
            unit.startsWith("min") -> Calendar.MINUTE
            unit.startsWith("mes") -> Calendar.MONTH
            unit.startsWith("sem") -> Calendar.WEEK_OF_YEAR
            unit.startsWith("h") -> Calendar.HOUR_OF_DAY
            unit.startsWith("d") -> Calendar.DAY_OF_MONTH
            unit.startsWith("a") || unit.startsWith("ñ") -> Calendar.YEAR
            else -> return 0L
        }
        return Calendar.getInstance().apply { add(field, -value) }.timeInMillis
    }

    // ======================== Paginas ========================

    // Las imagenes vienen en el HTML del capitulo y no se pueden armar por
    // patron: la carpeta no calza con el slug de la URL (capitulo-51.1 ->
    // capitulo-51-1, y a veces le pegan el titulo), la extension cambia dentro
    // del mismo capitulo y la numeracion salta. Las leo tal cual.
    override fun pageListParse(response: Response): List<Page> =
        response.asJsoup().select("main.reader-main img[src]")
            .map { it.absUrl("src") }
            .filter { it.startsWith("http") }
            .mapIndexed { index, url -> Page(index, "", url) }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    // ======================== Filtros ========================

    override fun getFilterList() = FilterList(
        Filter.Header("Se pueden combinar con la búsqueda por texto"),
        SectionFilter(),
        GenreFilter(),
    )

    class SectionFilter : Filter.Select<String>(
        "Sección",
        SECTIONS.map { it.first }.toTypedArray(),
    ) {
        val value get() = SECTIONS[state].second
    }

    class GenreFilter : Filter.Select<String>(
        "Género",
        GENRES.map { it.first }.toTypedArray(),
    ) {
        val value get() = GENRES[state].second
    }

    // ======================== Preferencias ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN
            title = "Dominio"
            summary = baseUrl
            text = baseUrl
            dialogTitle = "Dominio de ManhwaShot"
            dialogMessage = "Déjalo vacío para volver a $DEFAULT_DOMAIN.\n" +
                "Reinicia la aplicación para que el cambio se aplique."
            setOnPreferenceChangeListener { preference: Preference, newValue: Any ->
                val domain = normalizeDomain(newValue.toString())
                preferences.edit().putString(PREF_DOMAIN, domain).apply()
                preference.summary = domain
                text = domain
                true
            }
        }.also(screen::addPreference)
    }

    private fun normalizeDomain(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return DEFAULT_DOMAIN
        return if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
    }

    companion object {
        private const val DEFAULT_DOMAIN = "https://manhwashot.lat"
        private const val PREF_DOMAIN = "domain"

        private val NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")

        private val SECTIONS = listOf(
            "Todas" to "",
            "+18" to "adult",
            "BL" to "bl",
            "Shoujo" to "shoujo",
            "Manga" to "manga",
        )

        // Slugs tomados del listado de generos de /explorar/.
        private val GENRES = listOf(
            "Todos" to "",
            "+15" to "15",
            "+18" to "18",
            "+18 Sin Censura" to "18-sin-censura",
            "+19" to "19",
            "3D" to "3d",
            "A Todo Color" to "a-todo-color",
            "Academia" to "academia",
            "Acción" to "accion",
            "Adulto" to "adulto",
            "Apocalíptico" to "apocaliptico",
            "Artes Marciales" to "artes-marciales",
            "Aventura" to "aventura",
            "BDSM" to "bdsm",
            "BL" to "bl",
            "BL Sin Censura" to "bl-sin-censura",
            "Boys Love" to "boys-love",
            "Ciencia Ficción" to "ciencia-ficcion",
            "Comedia" to "comedia",
            "Crimen" to "crimen",
            "Cultivación" to "cultivacion",
            "Demonios" to "demonios",
            "Deportes" to "deportes",
            "Drama" to "drama",
            "Ecchi" to "ecchi",
            "Erótico" to "erotico",
            "Familia" to "familia",
            "Fantasía" to "fantasia",
            "Gender Bender" to "gender-bender",
            "Girls Love" to "girls-love",
            "Gore" to "gore",
            "Guerra" to "guerra",
            "Harem" to "harem",
            "Harem Inverso" to "harem-inverso",
            "Historias cortas" to "historias-cortas",
            "Histórico" to "historico",
            "Horror" to "horror",
            "Incesto" to "incesto",
            "Infidelidad" to "infidelidad",
            "Isekai" to "isekai",
            "Josei" to "josei",
            "Maduro" to "maduro",
            "Magia" to "magia",
            "Manga" to "manga",
            "Manhua" to "manhua",
            "Manhwa" to "manhwa",
            "Manhwa +19" to "manhwa-19",
            "Mecha" to "mecha",
            "Medieval" to "medieval",
            "Milf" to "milf",
            "Misterio" to "misterio",
            "Mujer casada" to "mujer-casada",
            "Mujer madura" to "mujer-madura",
            "Murim" to "murim",
            "NTR" to "ntr",
            "Novela" to "novela",
            "Oficina" to "oficina",
            "Omegaverse" to "omegaverse",
            "Primer amor" to "primer-amor",
            "Psicológico" to "psicologico",
            "Recuentos de la vida" to "recuentos-de-la-vida",
            "Reencarnación" to "reencarnacion",
            "Regresión" to "regresion",
            "Romance" to "romance",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shounen" to "shounen",
            "Sin censura" to "sin-censura",
            "Sistema" to "sistema",
            "Sistema de Niveles" to "sistema-de-niveles",
            "Smut" to "smut",
            "Sobrenatural" to "sobrenatural",
            "Superpoderes" to "superpoderes",
            "Supervivencia" to "supervivencia",
            "Thriller" to "thriller",
            "Tragedia" to "tragedia",
            "Transmigración" to "transmigracion",
            "Universidad" to "universidad",
            "Vampiros" to "vampiros",
            "Venganza" to "venganza",
            "Vida Escolar" to "vida-escolar",
            "Villana" to "villana",
            "Webtoon" to "webtoon",
            "Wuxia" to "wuxia",
            "Xianxia" to "xianxia",
            "Yaoi" to "yaoi",
            "Yuri" to "yuri",
            "Zombis" to "zombis",
        )
    }
}
