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
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
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
        @Volatile private var cachedToken = ""
        @Volatile var lastViewedMangaId = ""
        @Volatile var lastViewedMangaTitle = ""
        private const val PREF_TOKEN = "auth_token"
        private const val PREF_EMAIL = "email"
        private const val PREF_PASSWORD = "password"
    }

    // Override as computed property so the token is always fresh (parent is lazy).
    override val headers: Headers get() = headersBuilder().build()

    override fun headersBuilder() = super.headersBuilder().let { b ->
        val t = cachedToken
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
        return if (body.trim().startsWith("[")) {
            val arr = JSONArray(body)
            MangasPage((0 until arr.length()).map { parseMangaItem(arr.getJSONObject(it)) }, false)
        } else {
            val json = JSONObject(body)
            // /manhwa/nuevos returns { utimos_mangas_creados: [...], utimos_mangas_creados_18: [...], ... }
            val nuevos = json.optJSONArray("utimos_mangas_creados")
            if (nuevos != null) {
                MangasPage((0 until nuevos.length()).map { parseMangaItem(nuevos.getJSONObject(it)) }, false)
            } else {
                parseLibraryBody(body)
            }
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

    override fun mangaDetailsRequest(manga: SManga): Request {
        lastViewedMangaId = manga.url.removePrefix("/")
        return GET("$api/manhwa/see/${manga.url.removePrefix("/")}", headers)
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
                                    .headers(headers)
                                    .post(body)
                                    .build(),
                            ).execute().close()
                        }
                    }.start()
                }
            }
        }
        return GET("$api/chapters/see/${chapter.url.removePrefix("/")}", headers)
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // android.preference.PreferenceManager is deprecated but available in android.jar (compileSdk 34)
        // and avoids calling getPreferenceManager() which is absent from the extensions-lib PreferenceScreen stub.
        @Suppress("DEPRECATION")
        val sp = android.preference.PreferenceManager.getDefaultSharedPreferences(screen.context)

        if (cachedToken.isEmpty()) {
            cachedToken = sp.getString(PREF_TOKEN, "") ?: ""
        }

        EditTextPreference(screen.context).apply {
            key = PREF_EMAIL
            title = "Email"
            summary = sp.getString(PREF_EMAIL, "") ?: ""
            setOnPreferenceChangeListener { pref: Preference, value: Any ->
                sp.edit().putString(PREF_EMAIL, value.toString()).apply()
                pref.summary = value.toString()
                true
            }
        }.also { screen.addPreference(it) }

        // Saving the password triggers login automatically.
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
                sp.edit().putString(PREF_PASSWORD, pass).apply()
                if (email.isEmpty()) {
                    Toast.makeText(screen.context, "Ingresa tu email primero", Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceChangeListener true
                }
                Thread {
                    runCatching {
                        val tok = doLogin(email, pass)
                        Handler(Looper.getMainLooper()).post {
                            if (tok != null) {
                                sp.edit().putString(PREF_TOKEN, tok).apply()
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

        // Follow toggle — abre el último manga visto, su ID ya está pre-cargado.
        // Guardar activa el toggle (follow si no seguido, unfollow si ya seguido).
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
                                .headers(headers)
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
            // Pre-fill with the last viewed manga ID so user just taps OK
            if (lastViewedMangaId.isNotEmpty()) pref.setText(lastViewedMangaId)
        }
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
        val data = json.optJSONObject("data") ?: json
        return data.optString("jwt").takeIf { it.isNotEmpty() }
    }
}
