package eu.kanade.tachiyomi.extension.es.manhwaweb

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

class ManhwaWeb : HttpSource(), ConfigurableSource {

    override val name = "ManhwaWeb"
    override val baseUrl = "https://manhwaweb.com"
    override val lang = "es"
    override val supportsLatest = true

    private val api = "https://manhwawebbackend-production.up.railway.app"

    companion object {
        @Volatile var cachedToken = ""
        @Volatile var cachedEmail = ""
        @Volatile var cachedPassword = ""
        @Volatile var lastViewedMangaId = ""
        @Volatile var lastViewedMangaTitle = ""

        private const val COOKIE_JWT = "mw_jwt"
        private const val COOKIE_EMAIL = "mw_email"
        private const val COOKIE_PASS = "mw_pass"
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
            if (response.code != 401 || request.url.encodedPath.endsWith("/user/login")) {
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
            if (cachedToken.isEmpty() && cachedEmail.isNotEmpty() && cachedPassword.isNotEmpty()) {
                runCatching {
                    val tok = performLogin(cachedEmail, cachedPassword)
                    if (tok != null) { cachedToken = tok; saveToken(baseUrl, tok) }
                }
            }
        }
        val t = cachedToken
        if (t.isNotEmpty()) b.add("Authorization", "Bearer $t") else b
    }

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request =
        GET(
            "$api/manhwa/library?buscar=&estado=&tipo=&erotico=&demografia=&order_item=visitas&order_dir=desc&page=${page - 1}&generes=",
            headersBuilder().build(),
        )

    override fun popularMangaParse(response: Response): MangasPage =
        parseLibraryResponse(response)

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$api/latest/new-manhwa", headersBuilder().build())

    override fun latestUpdatesParse(response: Response): MangasPage {
        // Response: { "manhwas": { "manhwas_esp": [...], "_manhwas": [...], "manhwas_raw": [...] } }
        // Merge all three arrays into a single list.
        val json = JSONObject(response.body!!.string())
        val nested = json.optJSONObject("manhwas") ?: return MangasPage(emptyList(), false)
        val mangas = mutableListOf<SManga>()
        for (key in listOf("manhwas_esp", "_manhwas", "manhwas_raw")) {
            val arr = nested.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) mangas.add(parseMangaItem(arr.getJSONObject(i)))
        }
        return MangasPage(mangas, false)
    }

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (filters.filterIsInstance<FavoritesFilter>().firstOrNull()?.state == true) {
            // Always re-login before fetching favorites: the endpoint returns 200 with
            // empty data for expired tokens (not 401), so we must proactively refresh.
            if (cachedEmail.isNotEmpty() && cachedPassword.isNotEmpty()) {
                runCatching {
                    val tok = performLogin(cachedEmail, cachedPassword)
                    if (tok != null) { cachedToken = tok; saveToken(baseUrl, tok) }
                }
            }
            return GET("$api/follow/manhwa/siguiendo?page=${page - 1}", headersBuilder().build())
        }

        val estado = filters.filterIsInstance<EstadoFilter>().firstOrNull()?.toApiValue() ?: ""
        val tipo = filters.filterIsInstance<TipoFilter>().firstOrNull()?.toApiValue() ?: ""
        val erotico = filters.filterIsInstance<EroticoFilter>().firstOrNull()?.toApiValue() ?: ""
        val demografia = filters.filterIsInstance<DemografiaFilter>().firstOrNull()?.toApiValue() ?: ""
        val order = filters.filterIsInstance<OrderFilter>().firstOrNull()?.toApiValue() ?: "alfabetico"
        val orderDir = filters.filterIsInstance<OrderDirFilter>().firstOrNull()?.toApiValue() ?: "desc"
        val genres = filters.filterIsInstance<GenresFilter>().firstOrNull()
            ?.state?.filter { it.state }?.joinToString("a") { it.id.toString() } ?: ""

        return GET(
            "$api/manhwa/library?buscar=${query.trim()}&estado=$estado&tipo=$tipo&erotico=$erotico&demografia=$demografia&order_item=$order&order_dir=$orderDir&page=${page - 1}&generes=$genres",
            headersBuilder().build(),
        )
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseLibraryResponse(response)

    private fun parseLibraryResponse(response: Response) =
        parseLibraryBody(response.body!!.string())

    private fun parseLibraryBody(body: String): MangasPage {
        if (body.trim().startsWith("[")) {
            val arr = JSONArray(body)
            return MangasPage((0 until arr.length()).map { parseMangaItem(arr.getJSONObject(it)) }, false)
        }
        val json = JSONObject(body)
        // Try every key that any endpoint might use for the items array
        val arr = listOf("manhwas2", "data", "manhwas", "siguiendo", "result", "items", "list")
            .firstNotNullOfOrNull { key -> json.optJSONArray(key)?.takeIf { it.length() > 0 } }
            ?: JSONArray()
        return MangasPage(
            (0 until arr.length()).map { parseMangaItem(arr.getJSONObject(it)) },
            json.optBoolean("next", false),
        )
    }

    private fun parseMangaItem(obj: JSONObject): SManga {
        // Some endpoints wrap the manga inside a "manhwa" sub-object (e.g. follow records)
        val root = obj.optJSONObject("manhwa") ?: obj
        val id = root.optString("_id").ifEmpty { root.optString("real_id") }
            .ifEmpty { root.optString("id_rel") }.ifEmpty { root.optString("id_manhwa") }
        return SManga.create().apply {
            url = "/$id"
            title = root.optString("name_esp").ifEmpty { root.optString("the_real_name") }
                .ifEmpty { root.optString("name_manhwa") }.ifEmpty { id }
            thumbnail_url = root.optString("_imagen").ifEmpty { root.optString("img") }
                .takeIf { it.isNotEmpty() }
            status = parseStatus(root.optString("_status"))
        }
    }

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        lastViewedMangaId = manga.url.removePrefix("/")
        return GET("$api/manhwa/see/${manga.url.removePrefix("/")}", headersBuilder().build())
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = JSONObject(response.body!!.string())
        val id = obj.optString("_id")
        val title = obj.optString("name_esp").ifEmpty { obj.optString("the_real_name") }.ifEmpty { id }
        lastViewedMangaTitle = title
        return SManga.create().apply {
            url = "/$id"
            this.title = title
            thumbnail_url = obj.optString("_imagen").takeIf { it.isNotEmpty() }
            description = obj.optString("_sinopsis").takeIf { it.isNotEmpty() }
            status = parseStatus(obj.optString("_status"))
            genre = buildList {
                val cats = obj.optJSONArray("_categoris")
                if (cats != null) {
                    for (i in 0 until cats.length()) {
                        val v = cats.optString(i)
                        if (v.isNotEmpty()) add(v)
                    }
                }
                val demo = obj.optString("_demografi").takeIf { it.isNotEmpty() }
                if (demo != null) add(demo)
            }.joinToString()
            val autores = obj.optJSONArray("_autor")
            if (autores != null && autores.length() > 0) {
                author = (0 until autores.length()).joinToString { autores.optString(it) }
                artist = author
            }
        }
    }

    // ======================== Chapter List ========================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val obj = JSONObject(response.body!!.string())
        val mangaId = obj.optString("_id")
        val chapters = obj.optJSONArray("chapters") ?: return emptyList()
        return (0 until chapters.length()).mapNotNull { i ->
            val ch = chapters.optJSONObject(i) ?: return@mapNotNull null
            val num = ch.optDouble("chapter", Double.NaN)
            if (num.isNaN()) return@mapNotNull null
            val numStr = if (num == num.toLong().toDouble()) num.toLong().toString() else num.toString()
            SChapter.create().apply {
                name = "Capítulo $numStr"
                url = "/$mangaId-$numStr"
                chapter_number = num.toFloat()
                date_upload = ch.optLong("create", 0L)
            }
        }.reversed()
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        // Ensure we have a valid token before the read-sync fires.
        if (cachedToken.isEmpty() && cachedEmail.isNotEmpty() && cachedPassword.isNotEmpty()) {
            runCatching {
                val tok = performLogin(cachedEmail, cachedPassword)
                if (tok != null) { cachedToken = tok; saveToken(baseUrl, tok) }
            }
        }
        // Fire read-sync in background so the chapter is marked as read on the web.
        val raw = chapter.url.removePrefix("/")
        val lastDash = raw.lastIndexOf('-')
        if (cachedToken.isNotEmpty() && lastDash > 0) {
            val mangaId = raw.substring(0, lastDash)
            val chapterNum = raw.substring(lastDash + 1).toDoubleOrNull()
            if (chapterNum != null) {
                val chapterVal: Number = if (chapterNum == chapterNum.toLong().toDouble())
                    chapterNum.toLong() else chapterNum
                Thread {
                    runCatching {
                        val body = JSONObject()
                            .put("manhwa", mangaId)
                            .put("chapter", chapterVal)
                            .put("order", "b")
                            .toString()
                            .toRequestBody("application/json".toMediaType())
                        client.newCall(
                            Request.Builder()
                                .url("$api/follow/leidosmanhwas")
                                .headers(headersBuilder().build())
                                .post(body)
                                .build(),
                        ).execute().close()
                    }
                }.start()
            }
        }
        return GET("$api/chapters/see/${chapter.url.removePrefix("/")}", headersBuilder().build())
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, super.headersBuilder().add("Referer", baseUrl).build())

    override fun pageListParse(response: Response): List<Page> {
        val obj = JSONObject(response.body!!.string())
        val chObj = obj.optJSONObject("chapter") ?: return emptyList()
        val imgs = chObj.optJSONArray("img") ?: return emptyList()
        return (0 until imgs.length()).map { Page(it, "", imgs.getString(it)) }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ======================== Helpers ========================

    private fun parseStatus(s: String) = when (s) {
        "publicandose" -> SManga.ONGOING
        "finalizado" -> SManga.COMPLETED
        "pausado", "descanso" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // ======================== Filters ========================

    override fun getFilterList() = FilterList(
        FavoritesFilter(),
        Filter.Separator(),
        Filter.Header("Filtros de catálogo:"),
        TipoFilter(),
        DemografiaFilter(),
        EstadoFilter(),
        EroticoFilter(),
        OrderFilter(),
        OrderDirFilter(),
        GenresFilter(),
    )

    class FavoritesFilter : Filter.CheckBox("Favoritos", false)

    class TipoFilter : Filter.Select<String>(
        "Tipo",
        arrayOf("Ver todo", "Manhwa", "Manga", "Manhua", "Doujinshi", "Novela", "One shot"),
    ) {
        fun toApiValue() = arrayOf("", "manhwa", "manga", "manhua", "doujinshi", "novela", "one_shot")[state]
    }

    class DemografiaFilter : Filter.Select<String>(
        "Demografía",
        arrayOf("Ver todo", "Seinen", "Shonen", "Josei", "Shojo"),
    ) {
        fun toApiValue() = arrayOf("", "seinen", "shonen", "josei", "shojo")[state]
    }

    class EstadoFilter : Filter.Select<String>(
        "Estado",
        arrayOf("Ver todo", "Publicandose", "Pausado", "Finalizado"),
    ) {
        fun toApiValue() = arrayOf("", "publicandose", "pausado", "finalizado")[state]
    }

    class EroticoFilter : Filter.Select<String>(
        "Erotico",
        arrayOf("Ver todo", "Si", "No"),
    ) {
        fun toApiValue() = arrayOf("", "si", "no")[state]
    }

    class OrderFilter : Filter.Select<String>(
        "Ordenar por",
        arrayOf("Alfabetico", "Creacion", "Popularidad", "Num. Capitulos"),
    ) {
        fun toApiValue() = arrayOf("alfabetico", "creacion", "popularidad", "num_capitulos")[state]
    }

    class OrderDirFilter : Filter.Select<String>(
        "Dirección",
        arrayOf("DESC", "ASC"),
    ) {
        fun toApiValue() = arrayOf("desc", "asc")[state]
    }

    class GenreFilter(name: String, val id: Int) : Filter.CheckBox(name, false)

    class GenresFilter : Filter.Group<GenreFilter>(
        "Géneros",
        listOf(
            GenreFilter("Acción", 3),
            GenreFilter("Aventura", 29),
            GenreFilter("Comedia", 18),
            GenreFilter("Drama", 1),
            GenreFilter("Recuentos de la vida", 42),
            GenreFilter("Romance", 2),
            GenreFilter("Venganza", 5),
            GenreFilter("Harem", 6),
            GenreFilter("Fantasía", 23),
            GenreFilter("Sobrenatural", 31),
            GenreFilter("Tragedia", 25),
            GenreFilter("Psicológico", 43),
            GenreFilter("Horror", 32),
            GenreFilter("Thriller", 44),
            GenreFilter("Historias cortas", 28),
            GenreFilter("Ecchi", 30),
            GenreFilter("Gore", 34),
            GenreFilter("Girls love", 27),
            GenreFilter("Boys love", 26),
            GenreFilter("Reencarnación", 41),
            GenreFilter("Sistema de niveles", 37),
            GenreFilter("Ciencia ficción", 33),
            GenreFilter("Apocalíptico", 38),
            GenreFilter("Artes marciales", 39),
            GenreFilter("Superpoderes", 40),
            GenreFilter("Cultivación (cultivo)", 35),
            GenreFilter("Milf", 8),
        ),
    )

    // ======================== Login / Preferencias ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        if (cachedToken.isEmpty()) {
            readCreds(baseUrl)?.let { (email, pass, token) ->
                if (cachedEmail.isEmpty()) cachedEmail = email
                if (cachedPassword.isEmpty()) cachedPassword = pass
                cachedToken = token
            }
        }

        object : EditTextPreference(screen.context) {
            override fun performClick() {
                showWebViewLogin(context) { summary = "✓ Sesión activa" }
            }
        }.apply {
            title = "Sincronizar sesión"
            summary = if (cachedToken.isNotEmpty()) "✓ Sesión activa — toca para renovar"
                      else "Haz login en el WebView del app, luego toca aquí"
        }.also { screen.addPreference(it) }

        // Follow toggle: pre-filled with last viewed manga ID, saving calls the toggle endpoint.
        EditTextPreference(screen.context).apply {
            key = "follow_toggle"
            title = "Seguir / Dejar de seguir"
            summary = if (lastViewedMangaId.isNotEmpty())
                "$lastViewedMangaTitle\n$lastViewedMangaId"
            else
                "Abre un manga primero, luego vuelve aquí"
            setOnPreferenceChangeListener { pref: Preference, value: Any ->
                val mangaId = value.toString().trim()
                if (mangaId.isEmpty() || cachedToken.isEmpty()) {
                    Toast.makeText(screen.context, "Sin sesión o ID vacío", Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceChangeListener false
                }
                Thread {
                    runCatching {
                        val body = JSONObject()
                            .put("manhwa", mangaId)
                            .put("manhwa_type", "siguiendo")
                            .put("order", "asc")
                            .toString()
                            .toRequestBody("application/json".toMediaType())
                        val resp = client.newCall(
                            Request.Builder()
                                .url("$api/follow/pushdeletemanhwa")
                                .headers(headersBuilder().build())
                                .post(body)
                                .build(),
                        ).execute()
                        val ok = resp.isSuccessful
                        resp.close()
                        Handler(Looper.getMainLooper()).post {
                            if (ok) {
                                pref.summary = "Toggled: $mangaId"
                                Toast.makeText(screen.context, "Follow toggled ✓", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(screen.context, "Error al hacer follow", Toast.LENGTH_LONG).show()
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
        }.also { pref ->
            screen.addPreference(pref)
            if (lastViewedMangaId.isNotEmpty()) pref.setText(lastViewedMangaId)
        }
    }

    private fun showWebViewLogin(context: android.content.Context, onSuccess: () -> Unit) {
        val webView = android.webkit.WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("ManhwaWeb — Iniciar Sesión")
            .setView(webView)
            .setNegativeButton("Cerrar", null)
            .create()

        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView, url: String) {
                view.evaluateJavascript("localStorage.getItem('tokenCommers')") { result ->
                    val token = result?.trim('"')?.takeIf { it != "null" && it.length > 20 }
                        ?: return@evaluateJavascript
                    Handler(Looper.getMainLooper()).post {
                        cachedToken = token
                        saveToken(baseUrl, token)
                        onSuccess()
                        Toast.makeText(context, "✓ Login exitoso", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }
        }

        webView.loadUrl(baseUrl)
        dialog.show()
    }

    // Uses network.client directly (no interceptors) so login can never trigger itself recursively.
    private fun performLogin(email: String, password: String): String? {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val response = network.client.newCall(
            Request.Builder().url("$api/user/login").post(body).build(),
        ).execute()
        if (!response.isSuccessful) { response.close(); return null }
        val json = JSONObject(response.body!!.string())
        response.close()
        return (json.optJSONObject("data") ?: json).optString("jwt").takeIf { it.isNotEmpty() }
    }

}
