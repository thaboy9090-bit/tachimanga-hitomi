package eu.kanade.tachiyomi.extension.es.capibaratraductor

import android.os.Handler
import android.os.Looper
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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

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

        private const val COOKIE_JWT = "ct_jwt"
        private const val COOKIE_EMAIL = "ct_email"
        private const val COOKIE_PASS = "ct_pass"
        private const val MAX_AGE = "max-age=31536000; path=/"

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

    private fun loadGenres() {
        val reqBuilder = Request.Builder().url("$api/api/genres?limit=200")
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
        val selectedGenre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selectedSlug ?: ""
        val q = query.trim()

        val url = buildString {
            append("$api/api/manga-custom?page=$page&limit=24&order=latest&nsfw=false")
            if (q.isNotEmpty()) append("&search=${q.encodeUrl()}")
            if (selectedStatus.isNotEmpty()) append("&status=$selectedStatus")
            if (selectedGenre.isNotEmpty()) append("&genre=$selectedGenre")
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

        val token = cachedToken
        if (token.isNotEmpty() && pages.isNotEmpty()) {
            val seg = response.request.url.pathSegments
            // path: api / manga-custom / {slug} / chapter / {num} / pages
            val mangaSlug = seg.getOrElse(2) { "" }
            val chapterNum = seg.getOrElse(4) { "" }
            if (mangaSlug.isNotEmpty() && chapterNum.isNotEmpty()) {
                Thread {
                    runCatching {
                        val authHeader = "Bearer $token"
                        // Same as the "mark as read" button on the manga page
                        network.client.newCall(
                            Request.Builder()
                                .url("$api/api/user-chapter-history/manga-custom/$mangaSlug/chapter/$chapterNum")
                                .get()
                                .header("Authorization", authHeader)
                                .build(),
                        ).execute().close()
                        // Track page progress to last page
                        network.client.newCall(
                            Request.Builder()
                                .url("$api/api/user-chapter-history/manga-custom/$mangaSlug/chapter/$chapterNum/pages/${pages.size}")
                                .post("{}".toRequestBody("application/json".toMediaType()))
                                .header("Authorization", authHeader)
                                .build(),
                        ).execute().close()
                    }
                }.start()
            }
        }

        return pages
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
        list.add(StatusFilter())
        if (genres.isNotEmpty()) list.add(GenreFilter(genres))
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
        arrayOf("(Cualquier Estado)", "Publicándose", "Finalizado", "En pausa"),
    ) {
        private val apiValues = listOf("", "ongoing", "completed", "hiatus")
        val selectedValue: String get() = apiValues.getOrElse(state) { "" }
    }

    class GenreFilter(genreList: List<Pair<String, String>>) : Filter.Select<String>(
        "Género",
        arrayOf("(Todos los Géneros)") + genreList.map { it.second }.toTypedArray(),
    ) {
        private val slugs = listOf("") + genreList.map { it.first }
        val selectedSlug: String get() = slugs.getOrElse(state) { "" }
    }

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
