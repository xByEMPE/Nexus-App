package com.nexus.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.nexus.app.ui.splash.NexusSplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // El splash del sistema (Android 12+) queda en negro y vacío; el splash real es el de Compose.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        setContent {
            // rememberSaveable: al rotar la pantalla no se repite el splash.
            var showSplash by rememberSaveable { mutableStateOf(true) }

            Crossfade(targetState = showSplash, animationSpec = tween(350), label = "splash") { splash ->
                if (splash) {
                    NexusSplashScreen(onFinished = { showSplash = false })
                } else {
                    // TODO: reemplaza por la raíz de tu app (NavHost, pantalla de login, etc.)
                    AppRoot()
                }
            }
        }
    }
}
