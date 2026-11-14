package eu.kanade.tachiyomi.extension.es.capibaratraductor

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CapibaraTraductor : HttpSource(), ConfigurableSource {

    override val name = "CapibaraTraductor"
    override val baseUrl = "https://capibaratraductor.com"
    override val lang = "es"
    override val supportsLatest = true

    private val api = baseUrl

    companion object {
        @Volatile var cachedToken = ""
        @Volatile var cachedEmail = ""
        @Volatile var cachedPassword = ""
        @Volatile var scanGroups: List<Pair<String, String>> = emptyList()
        @Volatile var genres: List<Pair<String, String>> = emptyList()
        @Volatile var demographies: List<Pair<String, String>> = emptyList()

        private const val COOKIE_JWT = "ct_jwt"
        private const val COOKIE_EMAIL = "ct_email"
        private const val COOKIE_PASS = "ct_pass"
        private const val MAX_AGE = "max-age=31536000; path=/"

        // El lector tarda en hidratar y descifrar, asi que sondeo hasta ~45 s.
        private const val POLL_MS = 600L
        private const val MAX_POLLS = 75
        private const val TIMEOUT_S = 55L
        private const val WV_WIDTH = 1080
        private const val WV_HEIGHT = 1920

        // Para que el mensaje de error diga en que se quedo el lector.
        @Volatile var lastReaderState = ""
        @Volatile var lastReaderTries = 0

        @Volatile var savedContext: Context? = null

        private fun cookieMap(domain: String): Map<String, String> = runCatching {
            android.webkit.CookieManager.getInstance().getCookie(domain)
                ?.split(";")
                ?.associate { part ->
                    val idx = part.indexOf('=')
                    if (idx < 0) part.trim() to ""
                    else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
                } ?: emptyMap()
        }.getOrDefault(emptyMap())

        fun readCreds(domain: String): Triple<String, String, String>? = runCatching {
            val map = cookieMap(domain)
            val token = map[COOKIE_JWT]?.takeIf { it.isNotEmpty() } ?: return@runCatching null
            Triple(map[COOKIE_EMAIL] ?: "", map[COOKIE_PASS] ?: "", token)
        }.getOrNull()

        fun saveCreds(domain: String, email: String, password: String, token: String) {
            runCatching {
                val cm = android.webkit.CookieManager.getInstance()
                cm.setAcceptCookie(true)
                if (token.isNotEmpty()) cm.setCookie(domain, "$COOKIE_JWT=$token; $MAX_AGE")
                if (email.isNotEmpty()) cm.setCookie(domain, "$COOKIE_EMAIL=$email; $MAX_AGE")
                if (password.isNotEmpty()) cm.setCookie(domain, "$COOKIE_PASS=$password; $MAX_AGE")
                cm.flush()
            }
        }

        fun saveToken(domain: String, token: String) {
            runCatching {
                val cm = android.webkit.CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setCookie(domain, "$COOKIE_JWT=$token; $MAX_AGE")
                cm.flush()
            }
        }
    }

    init {
        readCreds(baseUrl)?.let { (email, pass, token) ->
            if (cachedEmail.isEmpty()) cachedEmail = email
            if (cachedPassword.isEmpty()) cachedPassword = pass
            if (cachedToken.isEmpty()) cachedToken = token
        }
        if (scanGroups.isEmpty()) Thread { runCatching { loadScanGroups() } }.start()
        if (genres.isEmpty()) Thread { runCatching { loadGenres() } }.start()
        if (demographies.isEmpty()) Thread { runCatching { loadDemographies() } }.start()
    }

    private fun loadScanGroups() {
        val reqBuilder = Request.Builder().url("$api/api/landing/scans?limit=500&sort=name")
        val t = cachedToken
        if (t.isNotEmpty()) reqBuilder.header("Authorization", "Bearer $t")
        val response = network.client.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful) { response.close(); return }
        val body = JSONObject(response.body!!.string())
        response.close()
        val arr = body.optJSONObject("data")?.optJSONArray("items")
            ?: body.optJSONArray("data")
            ?: return
        val groups = (0 until arr.length()).mapNotNull { i ->
            val item = arr.getJSONObject(i)
            val slug = item.optString("slug").ifEmpty { null } ?: return@mapNotNull null
            val displayName = item.optString("name").ifEmpty { slug }
            slug to displayName
        }
        if (groups.isNotEmpty()) scanGroups = groups
    }

    private fun loadDemographies() {
        val response = network.client.newCall(
            Request.Builder().url("$api/api/demography").build(),
        ).execute()
        if (!response.isSuccessful) { response.close(); return }
        val body = JSONObject(response.body!!.string())
        response.close()
        val arr = body.optJSONArray("data") ?: return
        val list = (0 until arr.length()).mapNotNull { i ->
            val item = arr.getJSONObject(i)
            val slug = item.optString("slug").ifEmpty { null } ?: return@mapNotNull null
            slug to item.optString("name").ifEmpty { slug }
        }
        if (list.isNotEmpty()) demographies = list
    }

    private fun loadGenres() {
        val reqBuilder = Request.Builder().url("$api/api/genre")
        val t = cachedToken
        if (t.isNotEmpty()) reqBuilder.header("Authorization", "Bearer $t")
        val response = network.client.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful) { response.close(); return }
        val body = JSONObject(response.body!!.string())
        response.close()
        val arr = body.optJSONObject("data")?.optJSONArray("items")
            ?: body.optJSONArray("data")
            ?: return
        val list = (0 until arr.length()).mapNotNull { i ->
            val item = arr.getJSONObject(i)
            val slug = item.optString("slug").ifEmpty { null } ?: return@mapNotNull null
            val name = item.optString("name").ifEmpty { slug }
            slug to name
        }
        if (list.isNotEmpty()) genres = list
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            try { chain.proceed(chain.request()) }
            catch (e: java.net.SocketTimeoutException) { chain.proceed(chain.request()) }
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.code != 401 || request.url.encodedPath.endsWith("/auth/login")) {
                return@addInterceptor response
            }
            if (cachedEmail.isEmpty() || cachedPassword.isEmpty()) return@addInterceptor response
            val newToken = runCatching { performLogin(cachedEmail, cachedPassword) }.getOrNull()
                ?: return@addInterceptor response
            cachedToken = newToken
            saveToken(baseUrl, newToken)
            response.close()
            chain.proceed(request.newBuilder().header("Authorization", "Bearer $newToken").build())
        }
        .build()

    override fun headersBuilder() = super.headersBuilder().let { b ->
        if (cachedToken.isEmpty()) {
            readCreds(baseUrl)?.let { (email, pass, token) ->
                if (cachedEmail.isEmpty()) cachedEmail = email
                if (cachedPassword.isEmpty()) cachedPassword = pass
                cachedToken = token
            }
        }
        val t = cachedToken
        if (t.isNotEmpty()) b.add("Authorization", "Bearer $t") else b
    }

    private fun orgHeaders(org: String) = headersBuilder().add("x-organization", org).build()

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$api/api/manga-custom?order=popular&limit=500&nsfw=false&page=$page", headersBuilder().build())

    override fun popularMangaParse(response: Response): MangasPage =
        parseMangaCustomList(response, "")

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$api/api/manga-custom?order=latest&limit=500&nsfw=false&page=$page", headersBuilder().build())

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseMangaCustomList(response, "")

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (filters.filterIsInstance<FavoritesFilter>().firstOrNull()?.state == true) {
            if (cachedEmail.isNotEmpty() && cachedPassword.isNotEmpty()) {
                runCatching {
                    val tok = performLogin(cachedEmail, cachedPassword)
                    if (tok != null) { cachedToken = tok; saveToken(baseUrl, tok) }
                }
            }
            return GET("$api/api/user-list?limit=500", headersBuilder().build())
        }

        val selectedOrg = filters.filterIsInstance<ScanFilter>().firstOrNull()?.selectedSlug ?: ""
        val selectedStatus = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selectedValue ?: ""
        val selectedSort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selectedValue ?: "latest"
        val selectedType = filters.filterIsInstance<TypeFilter>().firstOrNull()?.selectedValue
            ?: "manga,manhwa,manhua"
        val selectedDemography = filters.filterIsInstance<DemographyFilter>().firstOrNull()?.selectedSlug ?: ""
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val nsfw = filters.filterIsInstance<NsfwFilter>().firstOrNull()?.state == true
        val q = query.trim()

        val url = buildString {
            append("$api/api/manga-custom?page=$page&limit=24")
            append("&order=$selectedSort&type=$selectedType&nsfw=$nsfw")
            if (q.isNotEmpty()) append("&search=${q.encodeUrl()}")
            if (selectedStatus.isNotEmpty()) append("&status=$selectedStatus")
            if (selectedDemography.isNotEmpty()) append("&demography=$selectedDemography")
            genreFilter?.included?.takeIf { it.isNotEmpty() }
                ?.let { append("&genre=${it.joinToString(",")}") }
            genreFilter?.excluded?.takeIf { it.isNotEmpty() }
                ?.let { append("&excludeGenre=${it.joinToString(",")}") }
        }

        val headers = if (selectedOrg.isNotEmpty()) {
            headersBuilder().add("x-organization", selectedOrg).build()
        } else {
            headersBuilder().build()
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url.toString()
        return if (url.contains("/user-list")) {
            parseFavoritesList(response.body!!.string())
        } else {
            val org = response.request.header("x-organization") ?: ""
            parseMangaCustomList(response, org)
        }
    }

    private fun parseFavoritesList(body: String): MangasPage {
        return runCatching {
            val obj = JSONObject(body)
            val data = obj.optJSONObject("data") ?: return MangasPage(emptyList(), false)
            val items = data.optJSONArray("items") ?: data.optJSONArray("data")
                ?: return MangasPage(emptyList(), false)
            val mangas = (0 until items.length()).mapNotNull { i ->
                val item = items.getJSONObject(i)
                val mc = item.optJSONObject("mangaCustom") ?: item
                parseMangaCustomItem(mc)
            }
            MangasPage(mangas, data.optInt("maxPage", 1) > 1)
        }.getOrDefault(MangasPage(emptyList(), false))
    }

    // ======================== Parse Helpers ========================

    private fun parseMangaCustomList(response: Response, filterOrg: String): MangasPage {
        val body = response.body!!.string()
        val obj = JSONObject(body)
        val data = obj.optJSONObject("data") ?: return MangasPage(emptyList(), false)
        val items = data.optJSONArray("items") ?: return MangasPage(emptyList(), false)
        val mangas = (0 until items.length())
            .mapNotNull { parseMangaCustomItem(items.getJSONObject(it)) }
            .filter { filterOrg.isEmpty() || it.url.startsWith("/$filterOrg/") }
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val maxPage = data.optInt("maxPage", data.optInt("total", 0) / 24 + 1)
        return MangasPage(mangas, maxPage > currentPage)
    }

    private fun parseMangaCustomItem(obj: JSONObject): SManga? {
        val orgObj = obj.optJSONObject("organization")
        val oSlug = orgObj?.optString("slug")?.ifEmpty { null } ?: return null
        val mangaObj = obj.optJSONObject("manga")
        val mSlug = mangaObj?.optString("slug")?.ifEmpty { null }
            ?: obj.optString("slug").ifEmpty { null }
            ?: return null
        return SManga.create().apply {
            url = "/$oSlug/$mSlug"
            title = obj.optString("title").ifEmpty { mangaObj?.optString("title") ?: mSlug }
            thumbnail_url = obj.optString("imageUrl").takeIf { it.isNotEmpty() }
                ?: obj.optString("bannerUrl").takeIf { it.isNotEmpty() }
            status = parseStatus(obj.optString("status"))
            genre = obj.optString("workType").takeIf { it.isNotEmpty() }
        }
    }

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val (org, slug) = parseUrl(manga.url)
        return GET("$api/api/manga-custom/$slug", orgHeaders(org))
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = JSONObject(response.body!!.string()).optJSONObject("data") ?: return SManga.create()
        val orgObj = obj.optJSONObject("organization")
        val oSlug = orgObj?.optString("slug")?.ifEmpty { null }
            ?: response.request.header("x-organization") ?: ""
        val mangaObj = obj.optJSONObject("manga")
        val mSlug = mangaObj?.optString("slug")?.ifEmpty { null } ?: obj.optString("slug")
        return SManga.create().apply {
            url = "/$oSlug/$mSlug"
            title = obj.optString("title")
            thumbnail_url = obj.optString("imageUrl").takeIf { it.isNotEmpty() }
                ?: obj.optString("bannerUrl").takeIf { it.isNotEmpty() }
            description = obj.optString("description").takeIf { it.isNotEmpty() }
                ?: obj.optString("shortDescription").takeIf { it.isNotEmpty() }
            status = parseStatus(obj.optString("status"))
            val genresArr = obj.optJSONArray("genres")
            if (genresArr != null) {
                genre = (0 until genresArr.length())
                    .joinToString { genresArr.optJSONObject(it)?.optString("name") ?: "" }
                    .ifEmpty { null }
            }
            val authors = mangaObj?.optJSONArray("authors")
            if (authors != null && authors.length() > 0) {
                author = (0 until authors.length())
                    .joinToString { authors.getJSONObject(it).optString("name") }
                artist = author
            }
        }
    }

    // ======================== Chapter List ========================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val obj = JSONObject(response.body!!.string()).optJSONObject("data") ?: return emptyList()
        val orgObj = obj.optJSONObject("organization")
        val oSlug = orgObj?.optString("slug")?.ifEmpty { null }
            ?: response.request.header("x-organization") ?: ""
        val mangaObj = obj.optJSONObject("manga")
        val mSlug = mangaObj?.optString("slug")?.ifEmpty { null } ?: obj.optString("slug")
        val chapters = obj.optJSONArray("chapters") ?: return emptyList()
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        return (0 until chapters.length()).mapNotNull { i ->
            val ch = chapters.getJSONObject(i)
            val num = ch.optDouble("number", Double.NaN)
            if (num.isNaN()) return@mapNotNull null
            val numStr = if (num == num.toLong().toDouble()) num.toLong().toString() else num.toString()
            val title = ch.optString("title")
            SChapter.create().apply {
                name = if (title.isNotEmpty()) "$numStr - $title" else "Capítulo $numStr"
                url = "/$oSlug/$mSlug/$numStr"
                chapter_number = num.toFloat()
                date_upload = runCatching {
                    fmt.parse(ch.optString("releasedAt"))?.time ?: 0L
                }.getOrDefault(0L)
            }
        }
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.removePrefix("/").split("/")
        val org = parts[0]
        val slug = parts[1]
        val num = parts[2]
        return GET("$api/api/manga-custom/$slug/chapter/$num/pages", orgHeaders(org))
    }

    override fun pageListParse(response: Response): List<Page> {
        val arr = JSONObject(response.body!!.string()).optJSONArray("data") ?: return emptyList()
        val pages = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Page(p.optInt("number", i + 1) - 1, "", p.optString("imageUrl"))
        }.sortedBy { it.index }

        // La API entrega todas las paginas apuntando a una sola imagen señuelo: la URL real
        // va cifrada y su clave vive en el HTML del lector. En vez de replicar ese cifrado
        // dejo que el lector del sitio lo resuelva en un WebView y le saco el resultado.
        if (pages.size > 1 && pages.distinctBy { it.imageUrl }.size == 1) {
            val seg = response.request.url.pathSegments
            val slug = seg.getOrElse(2) { "" }
            val num = seg.getOrElse(4) { "" }
            val org = response.request.header("x-organization").orEmpty()
            if (slug.isEmpty() || num.isEmpty() || org.isEmpty()) {
                throw Exception("No pude reconstruir la dirección del capítulo")
            }
            val real = pagesFromReader("$baseUrl/$org/manga/$slug/chapters/$num?page=1")
            if (real.isEmpty()) {
                throw Exception(
                    "El lector no entregó las imágenes " +
                        "(estado: ${lastReaderState.ifEmpty { "sin iniciar" }}, " +
                        "intentos: $lastReaderTries)",
                )
            }
            return real.mapIndexed { i, url -> Page(i, "", url) }
        }

        val token = cachedToken
        if (token.isNotEmpty() && pages.isNotEmpty()) {
            val seg = response.request.url.pathSegments
            // path: api / manga-custom / {slug} / chapter / {num} / pages
            val mangaSlug = seg.getOrElse(2) { "" }
            val chapterNum = seg.getOrElse(4) { "" }
            val org = response.request.header("x-organization") ?: ""
            if (mangaSlug.isNotEmpty() && chapterNum.isNotEmpty()) {
                Thread {
                    runCatching {
                        val authHeader = "Bearer $cachedToken"
                        val markRead = Request.Builder()
                            .url("$api/api/user-chapter-history/manga-custom/$mangaSlug/chapter/$chapterNum")
                            .get()
                            .header("Authorization", authHeader)
                        if (org.isNotEmpty()) markRead.header("x-organization", org)
                        client.newCall(markRead.build()).execute().close()
                        val trackPages = Request.Builder()
                            .url("$api/api/user-chapter-history/manga-custom/$mangaSlug/chapter/$chapterNum/pages/${pages.size}")
                            .post("{}".toRequestBody("application/json".toMediaType()))
                            .header("Authorization", authHeader)
                        if (org.isNotEmpty()) trackPages.header("x-organization", org)
                        client.newCall(trackPages.build()).execute().close()
                    }
                }.start()
            }
        }

        return pages
    }

    // Recorro el arbol de fibers de React para encontrar el array de paginas que el lector
    // ya descifro. Devuelve null mientras siga mostrando el señuelo, y entonces reintento.
    private val readerScript = """
        (function () {
          function isPages(l) {
            return Array.isArray(l) && l.length > 0 && l.every(function (p) {
              return p && typeof p.imageUrl === 'string' && /^https?:/.test(p.imageUrl) &&
                     typeof p.number === 'number' && typeof p.chapterId === 'number';
            });
          }
          var pages = null;
          function walk(f) {
            for (; f; f = f.sibling) {
              for (var h = f.memoizedState; h && typeof h === 'object' && 'next' in h && 'queue' in h; h = h.next) {
                if (isPages(h.memoizedState) && (!pages || h.memoizedState.length > pages.length)) pages = h.memoizedState;
              }
              if (f.child) walk(f.child);
            }
          }
          document.querySelectorAll('*').forEach(function (el) {
            Object.keys(el).forEach(function (k) {
              if (k.indexOf('__reactContainer${'$'}') === 0) {
                var f = el[k];
                walk(f.stateNode && f.stateNode.current || f);
              }
            });
          });
          if (!pages) return null;
          var u = pages.map(function (p) { return p.imageUrl; });
          return u.length > 1 && new Set(u).size === 1 ? null : JSON.stringify(u);
        })();
    """.trimIndent()

    // Tachimanga no trae android.app.ActivityThread, asi que el Context lo guardo cuando
    // abres los ajustes y dejo la reflexion solo como respaldo.
    private fun appContext(): Context {
        savedContext?.let { return it }

        val porReflexion = runCatching {
            val cls = Class.forName("android.app.ActivityThread")
            val app = cls.getMethod("currentApplication").invoke(null)
                ?: cls.getMethod("currentActivityThread").invoke(null)
                    ?.let { cls.getMethod("getApplication").invoke(it) }
            app as? Application
        }.getOrNull()

        if (porReflexion != null) {
            savedContext = porReflexion
            return porReflexion
        }
        throw Exception("Abre los ajustes de la extensión una vez y vuelve a intentar")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun pagesFromReader(readerUrl: String): List<String> {
        val context = appContext()
        val latch = CountDownLatch(1)
        val result = AtomicReference<List<String>>(emptyList())
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var tries = 0

        handler.post {
            val wv = WebView(context)
            webView = wv
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.databaseEnabled = true
            wv.settings.userAgentString = headers["User-Agent"]
            // Sin ventana el WebView queda en 0x0 y React no hidrata, asi que lo mido a mano.
            wv.measure(WV_WIDTH, WV_HEIGHT)
            wv.layout(0, 0, WV_WIDTH, WV_HEIGHT)
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    lastReaderState = "cargado"
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) lastReaderState = "error de carga"
                }
            }
            lastReaderState = "cargando"
            wv.loadUrl(readerUrl)

            lateinit var poll: Runnable
            poll = Runnable {
                tries++
                wv.evaluateJavascript(readerScript) { value ->
                    val urls = parseScriptResult(value)
                    when {
                        urls.isNotEmpty() -> { result.set(urls); latch.countDown() }
                        tries >= MAX_POLLS -> latch.countDown()
                        else -> handler.postDelayed(poll, POLL_MS)
                    }
                }
            }
            handler.postDelayed(poll, POLL_MS)
        }

        latch.await(TIMEOUT_S, TimeUnit.SECONDS)
        lastReaderTries = tries
        handler.post { webView?.destroy() }
        return result.get()
    }

    // evaluateJavascript devuelve el valor como literal JSON, asi que el array llega
    // envuelto en una cadena y hay que desempaquetarlo dos veces.
    private fun parseScriptResult(value: String?): List<String> {
        if (value.isNullOrEmpty() || value == "null") return emptyList()
        return runCatching {
            val inner = JSONArray("[$value]").optString(0)
            if (inner.isEmpty()) return emptyList()
            val arr = JSONArray(inner)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, super.headersBuilder().add("Referer", baseUrl).build())

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ======================== Filters ========================

    override fun getFilterList(): FilterList {
        val list = mutableListOf<Filter<*>>()
        val filtersLoaded = scanGroups.isNotEmpty() && genres.isNotEmpty()
        if (!filtersLoaded) {
            list.add(Filter.Header("Abre los filtros de nuevo para cargar scans y géneros"))
        }
        if (scanGroups.isNotEmpty()) list.add(ScanFilter(scanGroups))
        list.add(SortFilter())
        list.add(TypeFilter())
        list.add(StatusFilter())
        if (demographies.isNotEmpty()) list.add(DemographyFilter(demographies))
        if (genres.isNotEmpty()) list.add(GenreFilter(genres))
        list.add(NsfwFilter())
        list.add(FavoritesFilter())
        return FilterList(list)
    }

    class ScanFilter(groups: List<Pair<String, String>>) : Filter.Select<String>(
        "Scan Group",
        arrayOf("(Todos los Scans)") + groups.map { it.second }.toTypedArray(),
    ) {
        private val slugs = listOf("") + groups.map { it.first }
        val selectedSlug: String get() = slugs.getOrElse(state) { "" }
    }

    class StatusFilter : Filter.Select<String>(
        "Estado",
        arrayOf("(Cualquier Estado)", "Publicándose", "Finalizado", "En pausa", "Cancelado"),
    ) {
        private val apiValues = listOf("", "ongoing", "completed", "hiatus", "cancelled")
        val selectedValue: String get() = apiValues.getOrElse(state) { "" }
    }

    class GenreOption(name: String, val slug: String) : Filter.TriState(name)

    // Tri-estado: un toque incluye el genero, dos lo excluyen.
    class GenreFilter(genreList: List<Pair<String, String>>) :
        Filter.Group<GenreOption>("Géneros", genreList.map { GenreOption(it.second, it.first) }) {
        val included: List<String> get() = state.filter { it.isIncluded() }.map { it.slug }
        val excluded: List<String> get() = state.filter { it.isExcluded() }.map { it.slug }
    }

    class SortFilter : Filter.Select<String>(
        "Ordenar por",
        arrayOf("Recientes", "Popularidad", "Nuevos", "Más capítulos", "A-Z"),
    ) {
        private val apiValues = listOf("latest", "popular", "newest", "most_chapters", "alphabetical")
        val selectedValue: String get() = apiValues.getOrElse(state) { "latest" }
    }

    class TypeFilter : Filter.Select<String>(
        "Tipo",
        arrayOf("(Todos)", "Manga", "Manhwa", "Manhua"),
    ) {
        private val apiValues = listOf("manga,manhwa,manhua", "manga", "manhwa", "manhua")
        val selectedValue: String get() = apiValues.getOrElse(state) { "manga,manhwa,manhua" }
    }

    class DemographyFilter(list: List<Pair<String, String>>) : Filter.Select<String>(
        "Demografía",
        arrayOf("(Cualquiera)") + list.map { it.second }.toTypedArray(),
    ) {
        private val slugs = listOf("") + list.map { it.first }
        val selectedSlug: String get() = slugs.getOrElse(state) { "" }
    }

    class NsfwFilter : Filter.CheckBox("Incluir +18", false)

    class FavoritesFilter : Filter.CheckBox("Favoritos (requiere sesión)", false)

    // ======================== Helpers ========================

    private fun parseUrl(url: String): Pair<String, String> {
        val parts = url.removePrefix("/").split("/")
        return Pair(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
    }

    private fun parseStatus(s: String) = when (s.lowercase()) {
        "ongoing", "publicandose" -> SManga.ONGOING
        "completed", "finalizado" -> SManga.COMPLETED
        "hiatus", "pausado", "descanso" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

    // ======================== Login / Preferencias ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        savedContext = screen.context

        if (cachedToken.isEmpty()) {
            readCreds(baseUrl)?.let { (email, pass, token) ->
                if (cachedEmail.isEmpty()) cachedEmail = email
                if (cachedPassword.isEmpty()) cachedPassword = pass
                cachedToken = token
            }
        }

        val emailPref = EditTextPreference(screen.context).apply {
            key = "email"
            title = "Email"
            summary = cachedEmail.ifEmpty { "Ingresa tu email (para favoritos)" }
            if (cachedEmail.isNotEmpty()) text = cachedEmail
            setOnPreferenceChangeListener { pref: Preference, value: Any ->
                val email = value.toString().trim()
                if (email.isEmpty()) return@setOnPreferenceChangeListener false
                cachedEmail = email
                pref.summary = email
                true
            }
        }.also { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = "password"
            title = "Contraseña"
            summary = if (cachedToken.isNotEmpty()) "✓ Sesión activa" else "Ingresa tu contraseña para iniciar sesión"
            setOnBindEditTextListener { et ->
                et.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { pref: Preference, value: Any ->
                val email = cachedEmail.ifEmpty { emailPref.text ?: "" }.trim()
                val pass = value.toString()
                if (email.isEmpty()) {
                    Toast.makeText(screen.context, "Ingresa tu email primero", Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceChangeListener true
                }
                if (cachedEmail != email) cachedEmail = email
                cachedPassword = pass
                Thread {
                    runCatching {
                        val tok = performLogin(email, pass)
                        Handler(Looper.getMainLooper()).post {
                            if (tok != null) {
                                cachedToken = tok
                                saveCreds(baseUrl, email, pass, tok)
                                pref.summary = "✓ Sesión activa"
                                Toast.makeText(screen.context, "Login exitoso", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(screen.context, "Login fallido — revisa tus datos", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.onFailure { e ->
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(screen.context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
                true
            }
        }.also { screen.addPreference(it) }
    }

    private fun performLogin(email: String, password: String): String? {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val response = network.client.newCall(
            Request.Builder()
                .url("$api/api/auth/login")
                .post(body)
                .header("Content-Type", "application/json")
                .build(),
        ).execute()
        if (!response.isSuccessful) { response.close(); return null }
        val json = JSONObject(response.body!!.string())
        response.close()
        val data = json.optJSONObject("data") ?: json
        return data.optString("token").ifEmpty { null }
            ?: data.optString("jwt").ifEmpty { null }
            ?: data.optString("accessToken").ifEmpty { null }
    }
}
