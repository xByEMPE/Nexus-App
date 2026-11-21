package mx.com.nexusio.fly.ui

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sesión de Supabase compartida por toda la app. El access token vence en minutos (1 h por defecto), así que se guarda
 * también el refresh token y se renueva antes de usarlo. Nunca borra el correo ni las listas guardadas: si la sesión
 * no se puede renovar, la app sigue con lo que tiene en caché y solo pide volver a iniciar sesión.
 */
object SessionManager {
    const val PREFS = "Nexus_Safety_Prefs"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES_AT = "token_expires_at"
    private const val KEY_EMAIL = "pilot_email"
    private const val RENEW_MARGIN_S = 120L

    /** Resultado de renovar: el token vigente, o vacío si la sesión ya no es válida y hay que iniciar sesión. */
    sealed interface Token {
        data class Valid(val value: String) : Token
        /** Sin red: el token guardado puede estar vencido; las pantallas deben usar su caché. */
        data class Offline(val value: String) : Token
        object LoginRequired : Token
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Guarda la respuesta de `/auth/v1/token` (login con contraseña o renovación). */
    fun saveSession(context: Context, response: JSONObject, email: String? = null) {
        val expiresIn = response.optLong("expires_in", 3600L)
        prefs(context).edit().apply {
            putString(KEY_ACCESS, response.getString("access_token"))
            response.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { putString(KEY_REFRESH, it) }
            putLong(KEY_EXPIRES_AT, System.currentTimeMillis() / 1000 + expiresIn)
            if (email != null) putString(KEY_EMAIL, email)
            apply()
        }
    }

    fun hasSession(context: Context) = !prefs(context).getString(KEY_ACCESS, "").isNullOrEmpty()

    fun email(context: Context) = prefs(context).getString(KEY_EMAIL, "") ?: ""

    /** Cierra la sesión a petición del piloto. Las listas en caché se conservan. */
    fun logout(context: Context) {
        prefs(context).edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES_AT).remove(KEY_EMAIL).apply()
    }

    /** El servidor rechazó el token y no se pudo renovar: se quita la sesión pero se conserva el correo (y con él la caché). */
    fun expire(context: Context) {
        prefs(context).edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES_AT).apply()
    }

    /**
     * Token listo para usar. Bloqueante (hace una petición de red si hay que renovar): llamar fuera del hilo principal.
     * Sesiones guardadas por versiones anteriores no tienen refresh token: se usan tal cual hasta que el servidor las rechace.
     */
    @Synchronized
    fun freshToken(context: Context, supabaseUrl: String, anonKey: String): Token {
        val p = prefs(context)
        val access = p.getString(KEY_ACCESS, "") ?: ""
        if (access.isEmpty()) return Token.LoginRequired
        val refresh = p.getString(KEY_REFRESH, "") ?: ""
        val expiresAt = p.getLong(KEY_EXPIRES_AT, 0L)
        val now = System.currentTimeMillis() / 1000
        if (refresh.isEmpty() || expiresAt - now > RENEW_MARGIN_S) return Token.Valid(access)

        val conn = URL("$supabaseUrl/auth/v1/token?grant_type=refresh_token").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("apikey", anonKey)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("refresh_token", refresh).toString().toByteArray(Charsets.UTF_8)) }
            when (conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    saveSession(context, JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
                    Token.Valid(p.getString(KEY_ACCESS, "") ?: "")
                }
                400, 401, 403 -> { expire(context); Token.LoginRequired }
                else -> Token.Offline(access)
            }
        } catch (e: java.io.IOException) {
            Token.Offline(access)
        } finally {
            conn.disconnect()
        }
    }
}
