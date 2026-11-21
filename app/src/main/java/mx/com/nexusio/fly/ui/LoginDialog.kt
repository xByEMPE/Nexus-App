package mx.com.nexusio.fly.ui

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mx.com.nexusio.fly.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Diálogo de inicio de sesión (antes vivía en HomeActivity). Guarda la sesión con
 * [SessionManager.saveSession] y avisa con [onSuccess] para que la pantalla se actualice.
 */
object LoginDialog {

    fun show(activity: Activity, onSuccess: () -> Unit) {
        val dialog = Dialog(activity, R.style.Theme_Nexus_Dialog_Bare)
        dialog.setContentView(R.layout.dialog_login)

        val etEmail = dialog.findViewById<EditText>(R.id.et_login_email)
        val etPassword = dialog.findViewById<EditText>(R.id.et_login_password)
        val btnSubmit = dialog.findViewById<Button>(R.id.btn_login_submit)
        dialog.findViewById<View?>(R.id.btn_login_close)?.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val pass = etPassword.text.toString()
            if (email.isEmpty() || pass.isEmpty()) {
                Toast.makeText(activity, "Llene todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSubmit.isEnabled = false
            btnSubmit.text = "VERIFICANDO..."
            login(activity, email, pass, dialog, btnSubmit, onSuccess)
        }

        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun login(
        activity: Activity, email: String, pass: String,
        dialog: Dialog, btnSubmit: Button, onSuccess: () -> Unit,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL("${BackendConfig.SUPABASE_URL}/auth/v1/token?grant_type=password")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", BackendConfig.SUPABASE_ANON_KEY)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val body = JSONObject().apply {
                    put("email", email)
                    put("password", pass)
                }.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { it.write(body, 0, body.size) }

                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    SessionManager.saveSession(activity, json, email)
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        onSuccess()
                        Toast.makeText(activity, "Sesión iniciada. Proyectos sincronizados.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        resetButton(btnSubmit)
                        Toast.makeText(activity, "Credenciales incorrectas", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    resetButton(btnSubmit)
                    Toast.makeText(activity, "Error de red: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resetButton(btn: Button) {
        btn.isEnabled = true
        btn.text = "ACCEDER Y SINCRONIZAR"
    }
}
