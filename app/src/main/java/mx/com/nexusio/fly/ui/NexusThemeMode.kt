package mx.com.nexusio.fly.ui

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * Modo día / noche de las pantallas Nexus (no de vuelo). Por defecto: noche.
 * Se aplica por activity (localNightMode), así las pantallas de vuelo no cambian.
 */
object NexusThemeMode {
    private const val PREFS = "Nexus_Safety_Prefs"
    private const val KEY_NIGHT = "nx_night_mode"

    fun isNight(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NIGHT, true)

    /** Llamar en onCreate antes de super.onCreate(). */
    fun apply(activity: AppCompatActivity) {
        activity.delegate.localNightMode =
            if (isNight(activity)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
    }

    /** Cambia de modo y lo guarda; la activity se recrea con el nuevo tema. */
    fun toggle(activity: AppCompatActivity) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_NIGHT, !isNight(activity)).apply()
        apply(activity)
    }
}
