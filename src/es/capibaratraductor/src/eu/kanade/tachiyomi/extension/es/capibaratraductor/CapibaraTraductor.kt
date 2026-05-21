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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

class CapibaraTraductor : HttpSource(), ConfigurableSource {

    override val name = "CapibaraTraductor"
    override val baseUrl = "https://capibaratraductor.com"
    override val lang = "es"
    override val supportsLatest = true

    private val api = baseUrl

    private val orgSlug: String
        get() = cachedOrgSlug.ifEmpty { "dnfansub" }

    companion object {
        @Volatile var cachedToken = ""
        @Volatile var cachedEmail = ""
        @Volatile var cachedPassword = ""
        @Volatile var cachedOrgSlug = "dnfansub"

        private const val COOKIE_JWT = "ct_jwt"
        private const val COOKIE_EMAIL = "ct_email"
        private const val COOKIE_PASS = "ct_pass"
        private const val COOKIE_ORG = "ct_org"
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

        fun readOrg(domain: String): String = runCatching {
            cookieMap(domain)[COOKIE_ORG]?.takeIf { it.isNotEmpty() } ?: "dnfansub"
        }.getOrDefault("dnfansub")

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

        fun saveOrg(domain: String, slug: String) {
            runCatching {
                val cm = android.webkit.CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setCookie(domain, "$COOKIE_ORG=$slug; $MAX_AGE")
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
        val savedOrg = readOrg(baseUrl)
        if (savedOrg.isNotEmpty()) cachedOrgSlug = savedOrg
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            try {
                chain.proceed(chain.request())
            } catch (e: java.net.SocketTimeoutException) {
                chain.proceed(chain.request())
            }
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
        val builder = b.add("x-organization", orgSlug)
        val t = cachedToken
        if (t.isNotEmpty()) builder.add("Authorization", "Bearer $t") else builder
    }

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$api/api/manga-custom?order=popular&limit=50&nsfw=false&page=${page - 1}", headersBuilder().build())

    override fun popularMangaParse(response: Response): MangasPage =
        parseMangaCustomList(response.body!!.string(), orgSlug)

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$api/api/manga-custom?order=latest&limit=50&nsfw=false&page=${page - 1}", headersBuilder().build())

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseMangaCustomList(response.body!!.string(), orgSlug)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (filters.filterIsInstance<FavoritesFilter>().firstOrNull()?.state == true) {
            if (cachedEmail.isNotEmpty() && cachedPassword.isNotEmpty()) {
                runCatching {
                    val tok = performLogin(cachedEmail, cachedPassword)
                    if (tok != null) { cachedToken = tok; saveToken(baseUrl, tok) }
                }
            }
            return GET("$api/api/user/favorites/manga-custom?page=${page - 1}", headersBuilder().build())
        }
        val q = query.trim()
        val url = if (q.isNotEmpty()) {
            "$api/api/manga-custom?order=latest&limit=100&nsfw=false&search=${q.encodeUrl()}&page=${page - 1}"
        } else {
            "$api/api/manga-custom?order=latest&limit=50&nsfw=false&page=${page - 1}"
        }
        return GET(url, headersBuilder().build())
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body!!.string()
        val url = response.request.url.toString()
        return if (url.contains("/favorites/")) {
            parseFavoritesList(body)
        } else {
            parseMangaCustomList(body, orgSlug)
        }
    }

    private fun parseFavoritesList(body: String): MangasPage {
        return runCatching {
            val obj = JSONObject(body)
            val data = obj.optJSONObject("data") ?: return MangasPage(emptyList(), false)
            val items = data.optJSONArray("items") ?: data.optJSONArray("data") ?: return MangasPage(emptyList(), false)
            val mangas = (0 until items.length()).mapNotNull { i ->
                val item = items.getJSONObject(i)
                val mc = item.optJSONObject("mangaCustom") ?: item
                parseMangaCustomItem(mc)
            }
            MangasPage(mangas, data.optBoolean("next", false))
        }.getOrDefault(MangasPage(emptyList(), false))
    }

    // ======================== Parse Helpers ========================

    private fun parseMangaCustomList(body: String, filterOrg: String): MangasPage {
        val obj = JSONObject(body)
        val data = obj.optJSONObject("data") ?: return MangasPage(emptyList(), false)
        val items = data.optJSONArray("items") ?: return MangasPage(emptyList(), false)
        val mangas = (0 until items.length())
            .mapNotNull { parseMangaCustomItem(items.getJSONObject(it)) }
            .filter { filterOrg.isEmpty() || it.url.startsWith("/$filterOrg/") }
        return MangasPage(mangas, data.optBoolean("next", false))
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
        return GET("$api/api/manga-custom/$slug", headersBuilder().add("x-organization", org).build())
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = JSONObject(response.body!!.string()).optJSONObject("data") ?: return SManga.create()
        val orgObj = obj.optJSONObject("organization")
        val oSlug = orgObj?.optString("slug")?.ifEmpty { null } ?: orgSlug
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
            val genres = obj.optJSONArray("genres")
            if (genres != null) {
                genre = (0 until genres.length())
                    .joinToString { genres.optJSONObject(it)?.optString("name") ?: "" }
                    .ifEmpty { null }
            }
            val authors = mangaObj?.optJSONArray("authors")
            if (authors != null && authors.length() > 0) {
                author = (0 until authors.length()).joinToString { authors.getJSONObject(it).optString("name") }
                artist = author
            }
        }
    }

    // ======================== Chapter List ========================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val obj = JSONObject(response.body!!.string()).optJSONObject("data") ?: return emptyList()
        val orgObj = obj.optJSONObject("organization")
        val oSlug = orgObj?.optString("slug")?.ifEmpty { null } ?: orgSlug
        val mangaObj = obj.optJSONObject("manga")
        val mSlug = mangaObj?.optString("slug")?.ifEmpty { null } ?: obj.optString("slug")
        val chapters = obj.optJSONArray("chapters") ?: return emptyList()
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        return (0 until chapters.length()).mapNotNull { i ->
            val ch = chapters.getJSONObject(i)
            val num = ch.optDouble("number", Double.NaN)
            if (num.isNaN()) return@mapNotNull null
            val numStr = if (num == num.toLong().toDouble()) num.toLong().toString() else num.toString()
            SChapter.create().apply {
                name = ch.optString("title").ifEmpty { "Capítulo $numStr" }
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
        return GET("$api/api/manga-custom/$slug/chapter/$num/pages", headersBuilder().add("x-organization", org).build())
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = JSONObject(response.body!!.string())
        val arr = body.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Page(p.optInt("number", i + 1) - 1, "", p.optString("imageUrl"))
        }.sortedBy { it.index }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, super.headersBuilder().add("Referer", baseUrl).build())

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ======================== Filters ========================

    override fun getFilterList() = FilterList(
        FavoritesFilter(),
    )

    class FavoritesFilter : Filter.CheckBox("Favoritos (requiere sesión)", false)

    // ======================== Helpers ========================

    private fun parseUrl(url: String): Pair<String, String> {
        val parts = url.removePrefix("/").split("/")
        return Pair(parts.getOrElse(0) { orgSlug }, parts.getOrElse(1) { "" })
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
        val savedOrg = readOrg(baseUrl)
        if (savedOrg.isNotEmpty()) cachedOrgSlug = savedOrg

        EditTextPreference(screen.context).apply {
            key = "org_slug"
            title = "Scan Group"
            summary = "Actual: $cachedOrgSlug\n\nEjemplos: dnfansub · senshimanga · scanz · rakuen · thebluebox"
            text = cachedOrgSlug
            setOnPreferenceChangeListener { pref: Preference, value: Any ->
                val slug = value.toString().trim().lowercase()
                if (slug.isEmpty()) return@setOnPreferenceChangeListener false
                cachedOrgSlug = slug
                saveOrg(baseUrl, slug)
                pref.summary = "Actual: $slug\n\nEjemplos: dnfansub · senshimanga · scanz · rakuen · thebluebox"
                true
            }
        }.also { screen.addPreference(it) }

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
