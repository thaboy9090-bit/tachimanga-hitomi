package eu.kanade.tachiyomi.extension.es.manhwaweb

import android.app.Application
import android.content.SharedPreferences
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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ManhwaWeb : HttpSource(), ConfigurableSource {

    override val name = "ManhwaWeb"
    override val baseUrl = "https://manhwaweb.com"
    override val lang = "es"
    override val supportsLatest = true

    private val api = "https://manhwawebbackend-production.up.railway.app"

    private val prefs: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${id}", android.content.Context.MODE_PRIVATE)
    }

    private val token: String get() = prefs.getString(PREF_TOKEN, "") ?: ""

    override fun headersBuilder() = super.headersBuilder().let { b ->
        val t = token
        if (t.isNotEmpty()) b.add("Authorization", "Bearer $t") else b
    }

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$api/manhwa/library?buscar=&estado=&tipo=&erotico=&demografia=&order_item=visitas&order_dir=desc&page=${page - 1}&generes=", headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseLibraryResponse(response)

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request =
        if (page == 1) {
            GET("$api/manhwa/nuevos", headers)
        } else {
            GET("$api/manhwa/library?buscar=&estado=&tipo=&erotico=&demografia=&order_item=reciente&order_dir=desc&page=${page - 1}&generes=", headers)
        }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val body = response.body!!.string()
        // /manhwa/nuevos returns a JSON array; /manhwa/library returns {data:[...], next:bool}
        return if (body.trim().startsWith("[")) {
            val arr = JSONArray(body)
            MangasPage((0 until arr.length()).map { parseMangaItem(arr.getJSONObject(it)) }, false)
        } else {
            parseLibraryBody(body)
        }
    }

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (filters.filterIsInstance<FavoritesFilter>().firstOrNull()?.state == true) {
            return GET("$api/follow/manhwa/siguiendo?page=${page - 1}", headers)
        }

        val estado = filters.filterIsInstance<EstadoFilter>().firstOrNull()?.toApiValue() ?: ""
        val tipo = filters.filterIsInstance<TipoFilter>().firstOrNull()?.toApiValue() ?: ""
        val erotico = filters.filterIsInstance<EroticoFilter>().firstOrNull()?.toApiValue() ?: ""
        val order = filters.filterIsInstance<OrderFilter>().firstOrNull()?.toApiValue() ?: "alfabetico"

        return GET(
            "$api/manhwa/library?buscar=${query.trim()}&estado=$estado&tipo=$tipo&erotico=$erotico&demografia=&order_item=$order&order_dir=desc&page=${page - 1}&generes=",
            headers,
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
        val arr = json.optJSONArray("data") ?: JSONArray()
        return MangasPage(
            (0 until arr.length()).map { parseMangaItem(arr.getJSONObject(it)) },
            json.optBoolean("next", false),
        )
    }

    private fun parseMangaItem(obj: JSONObject): SManga {
        val id = obj.optString("_id").ifEmpty { obj.optString("real_id") }
        return SManga.create().apply {
            url = "/$id"
            title = obj.optString("name_esp").ifEmpty { obj.optString("the_real_name") }.ifEmpty { id }
            thumbnail_url = obj.optString("_imagen").takeIf { it.isNotEmpty() }
            status = parseStatus(obj.optString("_status"))
        }
    }

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$api/manhwa/see/${manga.url.removePrefix("/")}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = JSONObject(response.body!!.string())
        val id = obj.optString("_id")
        return SManga.create().apply {
            url = "/$id"
            title = obj.optString("name_esp").ifEmpty { obj.optString("the_real_name") }.ifEmpty { id }
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

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$api/chapters/see/${chapter.url.removePrefix("/")}", headers)

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
        EstadoFilter(),
        TipoFilter(),
        EroticoFilter(),
        OrderFilter(),
    )

    class FavoritesFilter : Filter.CheckBox("Favoritos", false)

    class EstadoFilter : Filter.Select<String>(
        "Estado",
        arrayOf("Todos", "Publicando", "Finalizado", "Pausado"),
    ) {
        fun toApiValue() = arrayOf("", "publicandose", "finalizado", "pausado")[state]
    }

    class TipoFilter : Filter.Select<String>(
        "Tipo",
        arrayOf("Todos", "Manhwa", "Manga", "Manhua", "Novela"),
    ) {
        fun toApiValue() = arrayOf("", "manhwa", "manga", "manhua", "novela")[state]
    }

    class EroticoFilter : Filter.Select<String>(
        "Contenido",
        arrayOf("Todos", "Solo adultos", "Sin adultos"),
    ) {
        fun toApiValue() = arrayOf("", "si", "no")[state]
    }

    class OrderFilter : Filter.Select<String>(
        "Ordenar por",
        arrayOf("Alfabético", "Visitas", "Reciente"),
    ) {
        fun toApiValue() = arrayOf("alfabetico", "visitas", "reciente")[state]
    }

    // ======================== Login / Preferencias ========================

    companion object {
        private const val PREF_TOKEN = "auth_token"
        private const val PREF_EMAIL = "email"
        private const val PREF_PASSWORD = "password"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_EMAIL
            title = "Email"
            summary = prefs.getString(PREF_EMAIL, "") ?: ""
            setOnPreferenceChangeListener { pref, value ->
                val v = value.toString()
                prefs.edit().putString(PREF_EMAIL, v).apply()
                pref.summary = v
                true
            }
        }.also { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "Contraseña"
            summary = if (prefs.getString(PREF_PASSWORD, "").isNullOrEmpty()) "No configurada" else "••••••••"
            setOnBindEditTextListener { et ->
                et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { pref, _ ->
                pref.summary = "••••••••"
                true
            }
        }.also { screen.addPreference(it) }

        Preference(screen.context).apply {
            title = "Iniciar sesión"
            summary = if (token.isNotEmpty()) "✓ Sesión activa" else "Sin sesión — ingresa email y contraseña primero"
            setOnPreferenceClickListener { pref ->
                val email = prefs.getString(PREF_EMAIL, "") ?: ""
                val pass = prefs.getString(PREF_PASSWORD, "") ?: ""
                if (email.isEmpty() || pass.isEmpty()) {
                    Toast.makeText(screen.context, "Ingresa email y contraseña primero", Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }
                Thread {
                    runCatching {
                        val tok = doLogin(email, pass)
                        Handler(Looper.getMainLooper()).post {
                            if (tok != null) {
                                prefs.edit().putString(PREF_TOKEN, tok).apply()
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

    private fun doLogin(email: String, password: String): String? {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$api/user/login")
            .headers(headers)
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val json = JSONObject(response.body!!.string())
        return listOf("token", "accessToken", "jwt", "access_token")
            .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotEmpty() } }
    }
}
