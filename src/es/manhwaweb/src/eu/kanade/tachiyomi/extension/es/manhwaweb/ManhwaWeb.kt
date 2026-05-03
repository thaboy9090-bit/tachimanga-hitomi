package eu.kanade.tachiyomi.extension.es.manhwaweb

import android.os.Handler
import android.os.Looper
import android.text.InputType
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
        @Volatile var cachedSp: android.content.SharedPreferences? = null
        @Volatile var lastViewedMangaId = ""
        @Volatile var lastViewedMangaTitle = ""
        private const val PREF_TOKEN = "auth_token"
        private const val PREF_EMAIL = "email"
        private const val PREF_PASSWORD = "password"
        private const val SP_NAME = "manhwaweb"

        private fun packageName(): String? = runCatching {
            java.io.File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }.toByteArray()
                .toString(Charsets.UTF_8).split(":").first().trim()
        }.getOrNull()

        // Read credentials from our dedicated SP file (manhwaweb.xml) — no Context or reflection.
        // Falls back to the default SP file if the dedicated one doesn't exist yet (migration).
        fun readCredsFromDisk(): Triple<String, String, String>? = runCatching {
            val pkg = packageName() ?: return@runCatching null
            val dedicated = java.io.File("/data/data/$pkg/shared_prefs/$SP_NAME.xml")
            val fallback = java.io.File("/data/data/$pkg/shared_prefs/${pkg}_preferences.xml")
            val f = when {
                dedicated.exists() -> dedicated
                fallback.exists() -> fallback
                else -> return@runCatching null
            }
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(f)
            val nodes = doc.getElementsByTagName("string")
            val map = mutableMapOf<String, String>()
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                val name = n.attributes?.getNamedItem("name")?.nodeValue ?: continue
                map[name] = n.textContent
            }
            val email = map[PREF_EMAIL]?.takeIf { it.isNotEmpty() } ?: return@runCatching null
            val pass = map[PREF_PASSWORD]?.takeIf { it.isNotEmpty() } ?: return@runCatching null
            Triple(email, pass, map[PREF_TOKEN] ?: "")
        }.getOrNull()

        fun saveCreds(email: String, password: String, token: String) {
            cachedSp?.edit()
                ?.putString(PREF_EMAIL, email)
                ?.putString(PREF_PASSWORD, password)
                ?.putString(PREF_TOKEN, token)
                ?.apply()
        }

        fun saveToken(token: String) {
            cachedSp?.edit()?.putString(PREF_TOKEN, token)?.apply()
        }
    }

    init {
        // Read from our dedicated SP file on disk — no reflection, no context needed.
        readCredsFromDisk()?.let { (email, pass, token) ->
            if (cachedEmail.isEmpty()) cachedEmail = email
            if (cachedPassword.isEmpty()) cachedPassword = pass
            if (cachedToken.isEmpty()) cachedToken = token
        }
    }

    // Retry-on-401 interceptor: re-logins and retries once when a token is expired.
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.code != 401 || request.url.encodedPath.endsWith("/user/login")) {
                return@addInterceptor response
            }
            // If credentials not in memory, try loading from disk before giving up
            if (cachedEmail.isEmpty() || cachedPassword.isEmpty()) {
                readCredsFromDisk()?.let { (email, pass, _) ->
                    if (cachedEmail.isEmpty()) cachedEmail = email
                    if (cachedPassword.isEmpty()) cachedPassword = pass
                }
            }
            if (cachedEmail.isEmpty() || cachedPassword.isEmpty()) return@addInterceptor response

            val newToken = runCatching { performLogin(cachedEmail, cachedPassword) }.getOrNull()
                ?: return@addInterceptor response

            cachedToken = newToken
            saveToken(newToken)
            response.close()
            chain.proceed(request.newBuilder().header("Authorization", "Bearer $newToken").build())
        }
        .build()

    override fun headersBuilder() = super.headersBuilder().let { b ->
        if (cachedToken.isEmpty() || cachedEmail.isEmpty() || cachedPassword.isEmpty()) {
            // Try 1: cached SP reference (set when setupPreferenceScreen was opened this session)
            cachedSp?.let { sp ->
                if (cachedEmail.isEmpty()) cachedEmail = sp.getString(PREF_EMAIL, "") ?: ""
                if (cachedPassword.isEmpty()) cachedPassword = sp.getString(PREF_PASSWORD, "") ?: ""
                if (cachedToken.isEmpty()) cachedToken = sp.getString(PREF_TOKEN, "") ?: ""
            }
            // Try 2: read from our SP file on disk — no reflection needed
            if (cachedToken.isEmpty() || cachedEmail.isEmpty() || cachedPassword.isEmpty()) {
                readCredsFromDisk()?.let { (email, pass, token) ->
                    if (cachedEmail.isEmpty()) cachedEmail = email
                    if (cachedPassword.isEmpty()) cachedPassword = pass
                    if (cachedToken.isEmpty()) cachedToken = token
                }
            }
            // Try 3: auto-login if we have email+password but no token
            if (cachedToken.isEmpty() && cachedEmail.isNotEmpty() && cachedPassword.isNotEmpty()) {
                runCatching {
                    val tok = performLogin(cachedEmail, cachedPassword)
                    if (tok != null) {
                        cachedToken = tok
                        saveToken(tok)
                    }
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
        // Fire read-sync in background when user opens a chapter.
        if (cachedToken.isNotEmpty()) {
            val raw = chapter.url.removePrefix("/")
            val lastDash = raw.lastIndexOf('-')
            if (lastDash > 0) {
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
        }
        return GET("$api/chapters/see/${chapter.url.removePrefix("/")}", headersBuilder().build())
    }

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
        // Use a dedicated named SP so we always know the exact file name on disk (manhwaweb.xml).
        val sp = screen.context.getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE)
        cachedSp = sp

        // Load all three on every open so cachedEmail/cachedPassword are always populated.
        if (cachedEmail.isEmpty()) cachedEmail = sp.getString(PREF_EMAIL, "") ?: ""
        if (cachedPassword.isEmpty()) cachedPassword = sp.getString(PREF_PASSWORD, "") ?: ""
        if (cachedToken.isEmpty()) cachedToken = sp.getString(PREF_TOKEN, "") ?: ""

        EditTextPreference(screen.context).apply {
            key = PREF_EMAIL
            title = "Email"
            summary = cachedEmail.ifEmpty { sp.getString(PREF_EMAIL, "") ?: "" }
            setOnPreferenceChangeListener { pref: Preference, value: Any ->
                val email = value.toString()
                sp.edit().putString(PREF_EMAIL, email).apply()
                cachedEmail = email
                pref.summary = email
                true
            }
        }.also { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "Contraseña"
            summary = if (cachedToken.isNotEmpty()) "✓ Sesión activa" else "Guarda la contraseña para iniciar sesión"
            setOnBindEditTextListener { et ->
                et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { pref: Preference, value: Any ->
                val email = sp.getString(PREF_EMAIL, "") ?: ""
                val pass = value.toString()
                if (email.isEmpty()) {
                    Toast.makeText(screen.context, "Ingresa tu email primero", Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceChangeListener true
                }
                sp.edit().putString(PREF_PASSWORD, pass).apply()
                cachedPassword = pass
                Thread {
                    runCatching {
                        val tok = doLogin(email, pass)
                        Handler(Looper.getMainLooper()).post {
                            if (tok != null) {
                                // Save all three together so disk always has a consistent snapshot.
                                saveCreds(email, pass, tok)
                                cachedEmail = email
                                cachedToken = tok
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

    private fun doLogin(email: String, password: String): String? = performLogin(email, password)
}
