package mx.com.nexusio.fly.ui

/**
 * Backend de Nexus (Supabase). Único lugar donde se define: todas las pantallas leen de aquí.
 *
 * PENDIENTE: reemplazar por la URL y la anon key del proyecto Supabase de Nexus
 * (Supabase → Project Settings → API). Mientras sean placeholders, el login, los proyectos
 * y las actualizaciones OTA no funcionarán.
 */
object BackendConfig {
    const val SUPABASE_URL = "https://REEMPLAZAR-PROYECTO-NEXUS.supabase.co"
    const val SUPABASE_ANON_KEY = "REEMPLAZAR_ANON_KEY_NEXUS"
}
