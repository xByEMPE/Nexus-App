package mx.com.nexusio.fly.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import mx.com.nexusio.fly.drone.MainActivity
import mx.com.nexusio.fly.ui.splash.NexusSplashScreen

class SplashActivity : ComponentActivity() {

    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // El splash del sistema (Android 12+) queda en negro y vacío; el splash real es el de Compose.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        setContent {
            NexusSplashScreen(onFinished = ::goNext)
        }
    }

    private fun goNext() {
        if (navigated) return
        navigated = true
        // Única inspección disponible: aerogeneradores. Siempre directo a su pantalla principal.
        val target = MainActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
