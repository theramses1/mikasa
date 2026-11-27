package com.mikasa.refacciones

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Vehicle(
    val id: Long,
    val year: String,
    val make: String,
    val model: String,
    val engine: String = "",
    val nickname: String = "",
) {
    /** "2015 Nissan Versa 1.6L" */
    val description: String
        get() = listOf(year, make, model, engine).filter { it.isNotBlank() }.joinToString(" ")

    val label: String
        get() = if (nickname.isBlank()) description else "$nickname · $description"

    fun toJson() = JSONObject()
        .put("id", id).put("year", year).put("make", make)
        .put("model", model).put("engine", engine).put("nickname", nickname)

    companion object {
        fun fromJson(o: JSONObject) = Vehicle(
            o.getLong("id"), o.optString("year"), o.optString("make"),
            o.optString("model"), o.optString("engine"), o.optString("nickname"),
        )
    }
}

data class Favorite(val title: String, val url: String) {
    fun toJson() = JSONObject().put("title", title).put("url", url)

    companion object {
        fun fromJson(o: JSONObject) = Favorite(o.optString("title"), o.getString("url"))
    }
}

/** Preferencias del usuario guardadas en el teléfono (SharedPreferences). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var vehicles: List<Vehicle>
        get() = readArray(KEY_VEHICLES).map(Vehicle::fromJson)
        set(value) = writeArray(KEY_VEHICLES, value.map { it.toJson() })

    var activeVehicleId: Long
        get() = sp.getLong(KEY_ACTIVE, -1L)
        set(value) = sp.edit().putLong(KEY_ACTIVE, value).apply()

    val activeVehicle: Vehicle?
        get() = vehicles.firstOrNull { it.id == activeVehicleId }

    var favorites: List<Favorite>
        get() = readArray(KEY_FAVORITES).map(Favorite::fromJson)
        set(value) = writeArray(KEY_FAVORITES, value.map { it.toJson() })

    var lastUrl: String?
        get() = sp.getString(KEY_LAST_URL, null)
        set(value) = sp.edit().putString(KEY_LAST_URL, value).apply()

    /** true = abrir en la última página visitada; false = abrir en el inicio. */
    var resumeLastPage: Boolean
        get() = sp.getBoolean(KEY_RESUME, true)
        set(value) = sp.edit().putBoolean(KEY_RESUME, value).apply()

    var desktopMode: Boolean
        get() = sp.getBoolean(KEY_DESKTOP, false)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP, value).apply()

    /** Incluir el vehículo activo en las búsquedas hechas desde la app. */
    var searchWithVehicle: Boolean
        get() = sp.getBoolean(KEY_SEARCH_VEHICLE, true)
        set(value) = sp.edit().putBoolean(KEY_SEARCH_VEHICLE, value).apply()

    /** Plantilla de URL de búsqueda; {q} se reemplaza por el texto. */
    var searchTemplate: String
        get() = sp.getString(KEY_SEARCH_TEMPLATE, null) ?: DEFAULT_SEARCH_TEMPLATE
        set(value) = sp.edit().putString(KEY_SEARCH_TEMPLATE, value).apply()

    var recentSearches: List<String>
        get() = readArray(KEY_RECENT).map { it.getString("q") }
        set(value) = writeArray(KEY_RECENT, value.take(10).map { JSONObject().put("q", it) })

    private fun readArray(key: String): List<JSONObject> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeArray(key: String, items: List<JSONObject>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        sp.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        const val DEFAULT_SEARCH_TEMPLATE = "https://www.autozone.com.mx/searchresult?searchText={q}"
        private const val KEY_VEHICLES = "vehicles"
        private const val KEY_ACTIVE = "active_vehicle"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_RESUME = "resume_last_page"
        private const val KEY_DESKTOP = "desktop_mode"
        private const val KEY_SEARCH_VEHICLE = "search_with_vehicle"
        private const val KEY_SEARCH_TEMPLATE = "search_template"
        private const val KEY_RECENT = "recent_searches"
    }
}
