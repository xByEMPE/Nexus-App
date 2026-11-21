package mx.com.nexusio.fly.ui

import android.R
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {

    private val TAG = "Nexus_OTA"
    private var downloadId: Long = -1
    private var isDownloading = false

    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null

    // ==========================================
    // 1. CREDENCIALES DE SUPABASE
    // ==========================================
    private val supabaseUrl = BackendConfig.SUPABASE_URL
    private val anonKey = BackendConfig.SUPABASE_ANON_KEY

    fun checkForUpdates(currentVersionCode: Int, isManualCheck: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val endpoint = "$supabaseUrl/rest/v1/app-releases?select=*&order=version_code.desc&limit=1"
                val url = URL(endpoint)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("apikey", anonKey)
                connection.setRequestProperty("Authorization", "Bearer $anonKey")
                connection.setRequestProperty("Accept", "application/json")

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(response)

                    if (jsonArray.length() > 0) {
                        val latestUpdate = jsonArray.getJSONObject(0)
                        val latestVersionCode = latestUpdate.getInt("version_code")
                        val apkUrl = latestUpdate.getString("apk_url")
                        val vName = latestUpdate.getString("version_name")

                        withContext(Dispatchers.Main) {
                            if (latestVersionCode > currentVersionCode) {
                                iniciarProcesoDeActualizacion(apkUrl, "nexusapp_v$latestVersionCode.apk", vName)
                            } else if (isManualCheck) {
                                Toast.makeText(context, "La app ya está en la última versión", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else if (isManualCheck) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, "No hay actualizaciones", Toast.LENGTH_SHORT).show() }
                    }
                } else {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Error Supabase: ${connection.responseCode}", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Error de red: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ==========================================
    // 2. FUNCIÓN MAESTRA (Verifica, Limpia y Descarga)
    // ==========================================
    fun iniciarProcesoDeActualizacion(apkUrl: String, fileName: String, versionDisplay: String) {
        // CAMBIO CLAVE: Usamos la carpeta privada de la app, no la pública
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val file = File(dir, fileName)

        // LIMPIEZA: Buscamos si hay archivos "-1", "-2" o versiones viejas y las borramos
        dir?.listFiles()?.forEach { f ->
            if (f.name.startsWith("nexusapp_") && f.name.endsWith(".apk") && f.name != fileName) {
                f.delete()
            }
        }

        // Si el archivo correcto ya existe y pesa más de 1MB, se instala directo
        if (file.exists() && file.length() > 1024 * 1024) {
            Toast.makeText(context, "Versión ya descargada. Iniciando instalación...", Toast.LENGTH_LONG).show()
            installApk(fileName)
            return
        } else if (file.exists()) {
            file.delete() // Si existe pero está corrupto/incompleto, lo borramos
        }

        mostrarDialogoDeProgreso(versionDisplay)

        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("Actualización Nexus App v$versionDisplay")
            // CAMBIO CLAVE: Indicamos al DownloadManager que guarde en la carpeta privada
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        context.registerReceiver(onDownloadComplete(fileName), IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))

        downloadId = downloadManager.enqueue(request)
        rastrearProgreso(downloadManager)
    }

    // ==========================================
    // 3. UI DE DESCARGA VISUAL Y CANCELACIÓN
    // ==========================================
    private fun mostrarDialogoDeProgreso(version: String) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
            gravity = Gravity.CENTER
        }

        progressBar = ProgressBar(context, null, R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, mx.com.nexusio.fly.R.color.nx_accent))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, mx.com.nexusio.fly.R.color.nx_border_subtle))
        }

        progressText = TextView(context).apply {
            text = "Preparando descarga..."
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(androidx.core.content.ContextCompat.getColor(context, mx.com.nexusio.fly.R.color.nx_fg_muted))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
        }

        layout.addView(progressBar)
        layout.addView(progressText)

        progressDialog = AlertDialog.Builder(context, mx.com.nexusio.fly.R.style.Theme_Nexus_Dialog)
            .setTitle("Descargando versión $version")
            .setCancelable(false)
            .setView(layout)
            .setNegativeButton("CANCELAR") { dialog, _ ->
                cancelarDescarga()
                dialog.dismiss()
            }
            .create()

        progressDialog?.show()
    }

    private fun cancelarDescarga() {
        isDownloading = false
        if (downloadId != -1L) {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.remove(downloadId)
            Toast.makeText(context, "Descarga cancelada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun rastrearProgreso(manager: DownloadManager) {
        isDownloading = true
        CoroutineScope(Dispatchers.IO).launch {
            while (isDownloading) {
                val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                    if (statusIndex >= 0 && bytesDownloadedIndex >= 0 && bytesTotalIndex >= 0) {
                        val status = cursor.getInt(statusIndex)
                        val bytesDownloaded = cursor.getInt(bytesDownloadedIndex)
                        val bytesTotal = cursor.getInt(bytesTotalIndex)

                        if (bytesTotal > 0) {
                            val progress = (bytesDownloaded * 100L / bytesTotal).toInt()
                            val megaBytesDownloaded = String.format("%.1f", bytesDownloaded / (1024f * 1024f))
                            val megaBytesTotal = String.format("%.1f", bytesTotal / (1024f * 1024f))

                            withContext(Dispatchers.Main) {
                                progressBar?.progress = progress
                                progressText?.text = "$progress% ($megaBytesDownloaded MB / $megaBytesTotal MB)"
                            }
                        }
                        if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                            isDownloading = false
                        }
                    }
                } else {
                    isDownloading = false
                }
                cursor?.close()
                delay(300)
            }
        }
    }

    // ==========================================
    // 4. INSTALADOR DE APK
    // ==========================================
    private fun onDownloadComplete(fileName: String) = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (downloadId == id) {
                context.unregisterReceiver(this)
                progressDialog?.dismiss()
                if (isDownloading) {
                    installApk(fileName)
                }
            }
        }
    }

    private fun installApk(fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "Autorice a Nexus App para instalar actualizaciones.", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            }
        }

        // CAMBIO CLAVE: Leemos el archivo desde la carpeta privada
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (!file.exists()) {
            Toast.makeText(context, "Error: El instalador no está listo.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            try {
                val classicUri = Uri.fromFile(file)
                val classicIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(classicUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(classicIntent)
            } catch (ex: Exception) {
                Toast.makeText(context, "Error de instalación: ${ex.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==========================================
    // 5. HISTORIAL DE VERSIONES
    // ==========================================
    fun fetchVersionHistory(onResult: (JSONArray?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val endpoint = "$supabaseUrl/rest/v1/app-releases?select=*&order=version_code.desc"
                val url = URL(endpoint)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("apikey", anonKey)
                connection.setRequestProperty("Authorization", "Bearer $anonKey")
                connection.setRequestProperty("Accept", "application/json")

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    withContext(Dispatchers.Main) { onResult(JSONArray(response)) }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Error historial: ${connection.responseCode}", Toast.LENGTH_SHORT).show()
                        onResult(null)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }
}