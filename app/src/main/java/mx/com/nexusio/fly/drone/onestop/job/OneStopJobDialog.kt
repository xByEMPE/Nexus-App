package mx.com.nexusio.fly.drone.onestop.job

import androidx.core.content.ContextCompat
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import mx.com.nexusio.fly.R
import mx.com.nexusio.fly.drone.onestop.flightcore.TurbineDimensions
import mx.com.nexusio.fly.drone.onestop.wtgmfile.WtgmException
import mx.com.nexusio.fly.drone.onestop.wtgmfile.WtgmFile
import mx.com.nexusio.fly.drone.onestop.wtgmfile.WtgmReader
import mx.com.nexusio.fly.drone.onestop.wtgmfile.toTurbineJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Sección "Cargar JOB": carga el `.job` desde el almacenamiento del control, muestra los valores resultantes,
 * pide la altura de buje (obligatoria) y confirma el dron/cámara. Todo se guarda por proyecto.
 *
 * El selector de archivos lo lanza la activity ([pickFile]) y devuelve el resultado a [onFilePicked].
 * También lista los jobs de la base de datos ([remote]): al elegir uno pide confirmación antes de descargarlo.
 */
class OneStopJobDialog(
    private val activity: AppCompatActivity,
    private val store: ProjectTurbineStore,
    private val catalog: DroneCatalog,
    private val remote: WtgmRemoteSource,
    private val pickFile: () -> Unit,
    private val onSaved: () -> Unit,
) {
    private class Row(val viewId: Int, val key: String, val unitRes: Int)

    private val rows = listOf(
        Row(R.id.tv_job_rotor_diameter, JobFormat.ROTOR_DIAMETER, R.string.onestop_unit_m),
        Row(R.id.tv_job_hub_length, "hubLength", R.string.onestop_unit_m),
        Row(R.id.tv_job_blade_flange_radius, "bladeFlangeRadius", R.string.onestop_unit_m),
        Row(R.id.tv_job_blade_max_chord, "bladeMaxChordWidth", R.string.onestop_unit_m),
        Row(R.id.tv_job_hub_to_flange, "hubCenterToBladeFlange", R.string.onestop_unit_m),
        Row(R.id.tv_job_hub_to_max_chord, "hubCenterToMaxChordWidth", R.string.onestop_unit_m),
        Row(R.id.tv_job_blade_tilt, "bladeTiltAngle", R.string.onestop_unit_deg),
        Row(R.id.tv_job_rotor_axis, "rotorAxisAngle", R.string.onestop_unit_deg),
        Row(R.id.tv_job_tower_to_hub, "towerCenterToHubCenter", R.string.onestop_unit_m),
        Row(R.id.tv_job_tower_bottom_r, JobFormat.TOWER_BOTTOM_R, R.string.onestop_unit_m),
        Row(R.id.tv_job_tower_top_r, JobFormat.TOWER_TOP_R, R.string.onestop_unit_m),
        Row(R.id.tv_job_nacelle, "nacelleThickness", R.string.onestop_unit_m),
    )

    private companion object { const val BLADE_MISMATCH_TOLERANCE_M = 1.0 }

    private var dialog: Dialog? = null
    private lateinit var root: View
    private lateinit var etHub: EditText
    private lateinit var btnConfirm: Button
    private lateinit var spinnerDrone: Spinner
    private lateinit var spinnerPayload: Spinner
    private lateinit var spinnerLens: Spinner

    private var projectId = ""
    private var windFarm: String? = null
    private var loadingRemote = false
    private var bladeLengthM = 0.0
    private var job: TurbineJob? = null
    private var detection: DroneDetection = DroneDetection.Unknown
    private var linked = false

    val isShowing get() = dialog?.isShowing == true

    fun show(projectId: String, bladeLengthM: Double, windFarm: String? = null) {
        this.projectId = projectId
        this.windFarm = windFarm
        this.bladeLengthM = bladeLengthM
        this.job = store.loadJob(projectId)

        root = LayoutInflater.from(activity).inflate(R.layout.layout_onestop_job, null)
        etHub = root.findViewById(R.id.et_hub_height)
        btnConfirm = root.findViewById(R.id.btn_job_confirm)
        spinnerDrone = root.findViewById(R.id.spinner_job_drone)
        spinnerPayload = root.findViewById(R.id.spinner_job_payload)
        spinnerLens = root.findViewById(R.id.spinner_job_lens)
        spinnerPayload.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshLens()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnerLens.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshFov()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        store.loadHubHeight(projectId)?.let { etHub.setText(trimmed(it)) }
        etHub.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { validateHub(showEmptyError = false) }
            override fun afterTextChanged(s: Editable?) {}
        })

        root.findViewById<Button>(R.id.btn_job_load).setOnClickListener { pickFile() }
        root.findViewById<Button>(R.id.btn_job_remote).setOnClickListener { listRemote() }
        root.findViewById<Button>(R.id.btn_job_cancel).setOnClickListener { dialog?.dismiss() }
        btnConfirm.setOnClickListener { confirm() }

        refreshJob()
        refreshDrone()
        validateHub(showEmptyError = false)

        dialog = Dialog(activity, R.style.Theme_Nexus_Dialog_Fullscreen).apply {
            setContentView(root)
            setCancelable(true)
            show()
        }
    }

    /** Resultado del selector de archivos (null si el piloto canceló). */
    fun onFilePicked(uri: Uri?) {
        if (uri == null || !isShowing) return
        val fileName = displayName(uri)
        root.findViewById<TextView>(R.id.tv_job_file_name).text = fileName
        if (fileName.endsWith(".${WtgmReader.EXTENSION}", ignoreCase = true)) { onWtgmPicked(uri); return }
        val text = try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCapped(JobFormat.MAX_BYTES + 1)
                if (bytes.size > JobFormat.MAX_BYTES) { setStatus(R.string.onestop_job_too_big, error = true); return }
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) { null }
        if (text == null) { setStatus(R.string.onestop_job_read_error, error = true); return }

        when (val r = JobParser.parse(text)) {
            is JobParseResult.Invalid -> setStatus(r.reason, error = true)
            is JobParseResult.Ok -> {
                job = r.job
                store.saveJob(projectId, r.job)
                refreshJob()
                val msg = if (r.rejected.isEmpty()) activity.getString(R.string.onestop_job_loaded_ok)
                else activity.getString(R.string.onestop_job_loaded_rejected, r.rejected.joinToString(", "))
                setStatus(msg, error = r.rejected.isNotEmpty())
                if (TurbineDimensionsBuilder.rotorDiameterMismatch(r.job, bladeLengthM)) {
                    val declared = r.job.dimensions.getValue(JobFormat.ROTOR_DIAMETER)
                    setStatus(msg + "\n" + activity.getString(R.string.onestop_job_rotor_mismatch, declared, bladeLengthM * 2.0), error = true)
                }
            }
        }
    }

    /** `.wtgm` (geometría medida con WtgCalibrator): reemplaza los genéricos igual que un `.job` y adelanta la altura de buje. */
    private fun onWtgmPicked(uri: Uri) {
        val text = try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCapped(WtgmReader.MAX_BYTES + 1)
                if (bytes.size > WtgmReader.MAX_BYTES) { setStatus(R.string.onestop_job_too_big, error = true); return }
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) { null }
        if (text == null) { setStatus(R.string.onestop_job_read_error, error = true); return }
        applyWtgm(text)
    }

    /** Carga un `.wtgm` ya leído (de un archivo o de la base de datos) igual que un `.job`; adelanta la altura de buje. */
    private fun applyWtgm(text: String) {
        val file: WtgmFile = try { WtgmReader.parse(text) } catch (e: WtgmException) {
            setStatus(e.message ?: activity.getString(R.string.onestop_job_read_error), error = true); return
        }
        val loaded = file.toTurbineJob()
        job = loaded
        store.saveJob(projectId, loaded)
        refreshJob()

        // La altura de buje la confirma siempre el piloto; el .wtgm solo la propone si el campo está vacío
        val hub = file.turbine.towerHeight
        if (hub != null && etHub.text.isNullOrBlank()) etHub.setText(trimmed(hub))

        val notes = ArrayList<String>()
        notes += activity.getString(R.string.onestop_wtgm_loaded_ok, loaded.name)
        file.warnings.forEach { notes += it }
        val blade = file.turbine.bladeLength
        if (blade != null && bladeLengthM > 0.0 && abs(blade - bladeLengthM) > BLADE_MISMATCH_TOLERANCE_M) {
            notes += activity.getString(R.string.onestop_wtgm_blade_mismatch, blade, bladeLengthM)
        }
        setStatus(notes.joinToString("\n"), error = notes.size > 1)
    }

    // ---------- Jobs de la base de datos ----------

    private fun listRemote() {
        if (loadingRemote) return
        loadingRemote = true
        setStatus(R.string.onestop_remote_loading, error = false)
        activity.lifecycleScope.launch {
            val result = runCatching { remote.list() }
            loadingRemote = false
            if (!isShowing) return@launch
            result.onFailure { setStatus(it.message ?: activity.getString(R.string.onestop_job_read_error), error = true) }
            result.onSuccess { rows ->
                if (rows.isEmpty()) { setStatus(R.string.onestop_remote_empty, error = true); return@onSuccess }
                root.findViewById<TextView>(R.id.tv_job_status).visibility = View.GONE
                showRemoteList(RemoteWtgmParser.sorted(rows, windFarm))
            }
        }
    }

    private fun showRemoteList(rows: List<RemoteWtgm>) {
        val none = activity.getString(R.string.onestop_remote_item_none)
        val labels = rows.map { r ->
            val specs = activity.getString(R.string.onestop_remote_item_specs, meters(r.towerHeightM, none), meters(r.rotorDiameterM, none), meters(r.bladeLengthM, none))
            val title = listOf(r.windFarm.ifEmpty { none }, listOf(r.manufacturer, r.model).filter { it.isNotEmpty() }.joinToString(" ")).filter { it.isNotEmpty() }.joinToString(" · ")
            val mark = if (RemoteWtgmParser.sameFarm(r.windFarm, windFarm)) "★ " else ""
            activity.getString(R.string.onestop_remote_item_fmt, mark, title, listOf(specs, r.createdDay).filter { it.isNotEmpty() }.joinToString(" · "))
        }
        AlertDialog.Builder(activity, R.style.Theme_Nexus_Dialog)
            .setTitle(R.string.onestop_remote_list_title)
            .setItems(labels.toTypedArray()) { _, index -> confirmRemote(rows[index], rows) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Pregunta si es el job correcto antes de descargarlo; si no, vuelve a la lista. */
    private fun confirmRemote(item: RemoteWtgm, rows: List<RemoteWtgm>) {
        val none = activity.getString(R.string.onestop_remote_item_none)
        var message = activity.getString(
            R.string.onestop_remote_confirm_body, item.windFarm.ifEmpty { none },
            listOf(item.manufacturer, item.model).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { none },
            meters(item.towerHeightM, none), meters(item.rotorDiameterM, none), meters(item.bladeLengthM, none), item.createdDay.ifEmpty { none },
        )
        val projectFarm = windFarm
        if (!projectFarm.isNullOrBlank() && item.windFarm.isNotEmpty() && !RemoteWtgmParser.sameFarm(item.windFarm, projectFarm)) {
            message += activity.getString(R.string.onestop_remote_confirm_farm_warn, item.windFarm, projectFarm)
        }
        val blade = item.bladeLengthM
        if (blade != null && bladeLengthM > 0.0 && abs(blade - bladeLengthM) > BLADE_MISMATCH_TOLERANCE_M) {
            message += activity.getString(R.string.onestop_remote_confirm_blade_warn, blade, bladeLengthM)
        }
        AlertDialog.Builder(activity, R.style.Theme_Nexus_Dialog)
            .setTitle(R.string.onestop_remote_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.onestop_remote_confirm_yes) { _, _ -> downloadRemote(item) }
            .setNegativeButton(R.string.onestop_remote_back) { _, _ -> showRemoteList(rows) }
            .setCancelable(false)
            .show()
    }

    private fun downloadRemote(item: RemoteWtgm) {
        if (loadingRemote) return
        loadingRemote = true
        setStatus(R.string.onestop_remote_downloading, error = false)
        activity.lifecycleScope.launch {
            val result = runCatching { remote.download(item.id) }
            loadingRemote = false
            if (!isShowing) return@launch
            result.onFailure { setStatus(it.message ?: activity.getString(R.string.onestop_job_read_error), error = true) }
            result.onSuccess { text ->
                root.findViewById<TextView>(R.id.tv_job_file_name).text =
                    activity.getString(R.string.onestop_remote_from_db, listOf(item.windFarm, item.model).filter { it.isNotEmpty() }.joinToString(" · "))
                applyWtgm(text)
            }
        }
    }

    private fun meters(v: Double?, none: String) = if (v == null) none else trimmed(v) + " " + activity.getString(R.string.onestop_unit_m)

    // ---------- Vistas ----------

    private fun refreshJob() {
        val j = job
        root.findViewById<TextView>(R.id.tv_job_file_name).apply {
            if (j != null && text == activity.getString(R.string.onestop_job_no_file)) text = activity.getString(R.string.onestop_job_from_store, j.name)
        }
        root.findViewById<TextView>(R.id.tv_job_turbine_name).text = j?.name ?: activity.getString(R.string.onestop_job_value_none)
        root.findViewById<TextView>(R.id.tv_job_turbine_model).text =
            listOfNotNull(j?.manufacturer, j?.model).joinToString(" ").ifEmpty { activity.getString(R.string.onestop_job_value_none) }
        root.findViewById<TextView>(R.id.tv_job_blade_length).text =
            if (bladeLengthM > 0.0) activity.getString(R.string.onestop_job_blade_length_fmt, bladeLengthM)
            else activity.getString(R.string.onestop_job_no_blade_length)

        val effective = TurbineDimensionsBuilder.build(bladeLengthM, 1.0, j)
        rows.forEach { row ->
            val fromJob = row.key != JobFormat.ROTOR_DIAMETER && j?.dimensions?.containsKey(row.key) == true
            val fromApp = row.key == JobFormat.ROTOR_DIAMETER && bladeLengthM > 0.0
            root.findViewById<TextView>(row.viewId).apply {
                text = trimmed(effective.byKey(row.key)) + " " + activity.getString(row.unitRes)
                setTextColor(ContextCompat.getColor(activity, if (fromJob || fromApp) R.color.nx_telemetry else R.color.nx_fg_muted))
            }
        }
    }

    private fun refreshDrone() {
        val tv = root.findViewById<TextView>(R.id.tv_job_drone)
        val payloadLabel = root.findViewById<TextView>(R.id.tv_job_payload_label)
        spinnerDrone.visibility = View.GONE
        spinnerPayload.visibility = View.GONE
        payloadLabel.visibility = View.GONE

        linked = DroneDetector.isLinked()
        detection = if (linked) DroneDetector.detect(catalog) else DroneDetection.Unknown
        when (val d = detection) {
            is DroneDetection.Recognized -> tv.text = activity.getString(R.string.onestop_job_drone_fmt, d.drone.displayName, d.payload.replace('_', ' '))
            is DroneDetection.NeedsPayload -> {
                tv.text = d.drone.displayName
                showPayloadChoices(d.drone, store.loadPayload(d.drone.key))
            }
            DroneDetection.Unknown -> if (!linked) {
                tv.text = activity.getString(R.string.onestop_job_drone_pending)
            } else {
                tv.text = activity.getString(R.string.onestop_job_drone_pick)
                spinnerDrone.adapter = ArrayAdapter(activity, R.layout.item_nx_spinner, catalog.drones.map { it.displayName }).apply { setDropDownViewResource(R.layout.item_nx_spinner_dropdown) }
                spinnerDrone.setSelection(catalog.drones.indexOfFirst { it.key == store.loadManualDrone() }.coerceAtLeast(0))
                spinnerDrone.visibility = View.VISIBLE
                spinnerDrone.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        val drone = catalog.drones[pos]
                        showPayloadChoices(drone, store.loadPayload(drone.key))
                        refreshLens()
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            }
        }
        refreshLens()
    }

    /** Dron y cámara que quedarían elegidos con el estado actual de la pantalla (null si aún no hay dron). */
    private fun currentSelection(): Pair<DroneModel, String>? = when (val d = detection) {
        is DroneDetection.Recognized -> d.drone to d.payload
        is DroneDetection.NeedsPayload -> d.drone to selectedPayload(d.drone)
        DroneDetection.Unknown -> if (!linked) null else catalog.drones.getOrNull(spinnerDrone.selectedItemPosition)?.let { it to selectedPayload(it) }
    }

    private fun selectedPayload(drone: DroneModel): String =
        if (drone.hasSinglePayload) drone.payloads.keys.first()
        else drone.payloads.keys.toList().getOrElse(spinnerPayload.selectedItemPosition) { drone.payloads.keys.first() }

    /** Lista las lentes de luz visible de la cámara elegida; el selector solo aparece si hay más de una. */
    private fun refreshLens() {
        val label = root.findViewById<TextView>(R.id.tv_job_lens_label)
        val sel = currentSelection()
        val lenses = sel?.let { (drone, payload) -> catalog.lensesFor(drone, payload) }.orEmpty()
        val multiple = lenses.size > 1
        label.visibility = if (multiple) View.VISIBLE else View.GONE
        spinnerLens.visibility = if (multiple) View.VISIBLE else View.GONE
        spinnerLens.adapter = ArrayAdapter(activity, R.layout.item_nx_spinner, lenses.map { "${it.displayName} (${trimmed(it.diagonalFovDeg)}°)" }).apply { setDropDownViewResource(R.layout.item_nx_spinner_dropdown) }
        sel?.let { (drone, payload) ->
            val saved = store.loadLens(catalog.cameraKey(drone, payload))
            spinnerLens.setSelection(lenses.indexOfFirst { it.name == saved }.coerceAtLeast(0))
        }
        refreshFov()
    }

    private fun refreshFov() {
        val tv = root.findViewById<TextView>(R.id.tv_job_fov)
        val sel = currentSelection()
        val lens = sel?.let { (drone, payload) -> catalog.lensesFor(drone, payload).getOrNull(spinnerLens.selectedItemPosition.coerceAtLeast(0)) }
        val setup = sel?.let { (drone, payload) -> catalog.setup(drone.key, payload, lens?.name) }
        if (setup == null) { tv.visibility = View.GONE; return }
        tv.text = activity.getString(R.string.onestop_job_fov_fmt, setup.cameraFov.horizontal, setup.cameraFov.vertical, setup.cameraFov.diagonal)
        tv.visibility = View.VISIBLE
    }

    /** Muestra el selector de cámara solo si el dron admite varias. */
    private fun showPayloadChoices(drone: DroneModel, selected: String?) {
        val show = !drone.hasSinglePayload
        root.findViewById<TextView>(R.id.tv_job_payload_label).visibility = if (show) View.VISIBLE else View.GONE
        spinnerPayload.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val names = drone.payloads.keys.toList()
        spinnerPayload.adapter = ArrayAdapter(activity, R.layout.item_nx_spinner, names.map { it.replace('_', ' ') }).apply { setDropDownViewResource(R.layout.item_nx_spinner_dropdown) }
        spinnerPayload.setSelection(names.indexOf(selected).coerceAtLeast(0))
    }

    private fun validateHub(showEmptyError: Boolean): Double? {
        val err = root.findViewById<TextView>(R.id.tv_job_hub_height_error)
        val result = HubHeightValidator.validate(etHub.text.toString(), bladeLengthM)
        val message = when (result) {
            HubHeightValidator.Result.Empty -> if (showEmptyError) activity.getString(R.string.onestop_job_hub_required) else null
            HubHeightValidator.Result.OutOfRange ->
                activity.getString(R.string.onestop_job_hub_range, HubHeightValidator.MIN_M, HubHeightValidator.MAX_M)
            HubHeightValidator.Result.BelowBlade -> activity.getString(R.string.onestop_job_hub_below_blade, bladeLengthM)
            is HubHeightValidator.Result.Valid -> null
        }
        err.text = message
        err.visibility = if (message == null) View.GONE else View.VISIBLE
        val valid = (result as? HubHeightValidator.Result.Valid)?.meters
        btnConfirm.isEnabled = valid != null && bladeLengthM > 0.0
        return valid
    }

    private fun confirm() {
        val hub = validateHub(showEmptyError = true) ?: return
        store.saveHubHeight(projectId, hub)

        // Guarda la elección manual de dron/cámara solo cuando la detección no bastó
        when (val d = detection) {
            is DroneDetection.NeedsPayload -> saveSelectedPayload(d.drone)
            DroneDetection.Unknown -> if (linked) {
                val drone = catalog.drones[spinnerDrone.selectedItemPosition]
                store.saveManualDrone(drone.key)
                saveSelectedPayload(drone)
            }
            is DroneDetection.Recognized -> Unit
        }
        currentSelection()?.let { (drone, payload) ->
            catalog.lensesFor(drone, payload).getOrNull(spinnerLens.selectedItemPosition)
                ?.let { store.saveLens(catalog.cameraKey(drone, payload), it.name) }
        }
        dialog?.dismiss()
        onSaved()
    }

    private fun saveSelectedPayload(drone: DroneModel) {
        if (drone.hasSinglePayload) return
        drone.payloads.keys.toList().getOrNull(spinnerPayload.selectedItemPosition)?.let { store.savePayload(drone.key, it) }
    }

    private fun setStatus(resId: Int, error: Boolean) = setStatus(activity.getString(resId), error)

    private fun setStatus(text: String, error: Boolean) {
        root.findViewById<TextView>(R.id.tv_job_status).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(activity, if (error) R.color.nx_caution else R.color.nx_telemetry))
            visibility = View.VISIBLE
        }
    }

    private fun displayName(uri: Uri): String =
        activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "job"

    private fun trimmed(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    private fun TurbineDimensions.byKey(key: String): Double = when (key) {
        JobFormat.ROTOR_DIAMETER -> rotorDiameter
        "hubLength" -> hubLength
        "bladeFlangeRadius" -> bladeFlangeRadius
        "bladeMaxChordWidth" -> bladeMaxChordWidth
        "hubCenterToBladeFlange" -> hubCenterToBladeFlange
        "hubCenterToMaxChordWidth" -> hubCenterToMaxChordWidth
        "bladeTiltAngle" -> bladeTiltAngle
        "rotorAxisAngle" -> rotorAxisAngle
        "towerCenterToHubCenter" -> towerCenterToHubCenter
        JobFormat.TOWER_BOTTOM_R -> towerBottomRadius
        JobFormat.TOWER_TOP_R -> towerTopRadius
        "nacelleThickness" -> nacelleThickness
        else -> error("unknown dimension $key")
    }

    /** Lee como máximo [limit] bytes (evita cargar en memoria un archivo enorme). */
    private fun java.io.InputStream.readNBytesCapped(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - total))
            if (n < 0) break
            out.write(buf, 0, n); total += n
        }
        return out.toByteArray()
    }
}
