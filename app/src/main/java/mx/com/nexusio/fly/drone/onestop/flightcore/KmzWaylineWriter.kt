package mx.com.nexusio.fly.drone.onestop.flightcore

import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Identificación del dron y la cámara en el KMZ (WPML). Los valores deben coincidir con el aparato real;
 * por defecto Matrice 3T (dron 77/1, cámara 67). Ver tabla "droneInfo/payloadInfo" del WPML de DJI.
 */
data class KmzAircraftInfo(
    val droneEnum: Int = 77,
    val droneSubEnum: Int = 1,
    val payloadEnum: Int = 67,
    /** `wpml:payloadLensIndex` del takePhoto (obligatorio): zoom, wide, ir, narrow_band o visable. */
    val payloadLensIndex: String = "wide",
    /**
     * Pausa (s) entre girar el gimbal y disparar, para que se estabilice. El original solo la usa en el Matrice 4E
     * (`M4E_GIMBAL_SETTLE_HOVER` = 1 s); 0 = sin pausa.
     */
    val gimbalSettleHoverS: Double = 0.0,
)

/** Genera `wpmz/template.kml` + `wpmz/waylines.wpml` empaquetados en un KMZ para `pushKMZFileToAircraft`. */
object KmzWaylineWriter {
    private const val NS_KML = "http://www.opengis.net/kml/2.2"
    private const val NS_WPML = "http://www.dji.com/wpmz/1.0.6"

    /** Escribe el KMZ en [outDir]/[MissionPlan.id].kmz y devuelve el archivo. */
    fun write(plan: MissionPlan, outDir: File, aircraft: KmzAircraftInfo = KmzAircraftInfo(), nowMillis: Long = System.currentTimeMillis()): File {
        require(plan.waypoints.isNotEmpty()) { "plan without waypoints" }
        outDir.mkdirs()
        val file = File(outDir, "${plan.id}.kmz")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("wpmz/template.kml"))
            zip.write(templateKml(plan, aircraft, nowMillis).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("wpmz/waylines.wpml"))
            zip.write(waylinesWpml(plan, aircraft, nowMillis).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }

    private fun f(v: Double) = String.format(Locale.US, "%.8f", v)
    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)

    /**
     * Ante pérdida de enlace el dron sale de la misión y vuelve a casa en el acto (`goBack`), con la altura de RTH que la
     * app deja puesta para ese tramo ([ReturnHomeSafety.schedule]). La app auditada usa `hover`: el dron se queda junto
     * a la pala hasta la batería baja y, desde detrás del rotor, la simulación acaba en contacto.
     */
    private fun header(nowMillis: Long, aircraft: KmzAircraftInfo, speed: Double) = """
    <wpml:author>Nexus</wpml:author>
    <wpml:createTime>$nowMillis</wpml:createTime>
    <wpml:updateTime>$nowMillis</wpml:updateTime>
    <wpml:missionConfig>
      <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>
      <wpml:finishAction>noAction</wpml:finishAction>
      <wpml:exitOnRCLost>executeLostAction</wpml:exitOnRCLost>
      <wpml:executeRCLostAction>goBack</wpml:executeRCLostAction>
      <wpml:globalTransitionalSpeed>${f2(speed)}</wpml:globalTransitionalSpeed>
      <wpml:droneInfo>
        <wpml:droneEnumValue>${aircraft.droneEnum}</wpml:droneEnumValue>
        <wpml:droneSubEnumValue>${aircraft.droneSubEnum}</wpml:droneSubEnumValue>
      </wpml:droneInfo>
      <wpml:payloadInfo>
        <wpml:payloadEnumValue>${aircraft.payloadEnum}</wpml:payloadEnumValue>
        <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
      </wpml:payloadInfo>
    </wpml:missionConfig>"""

    private fun templateKml(plan: MissionPlan, aircraft: KmzAircraftInfo, nowMillis: Long): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="$NS_KML" xmlns:wpml="$NS_WPML">
  <Document>""")
        sb.append(header(nowMillis, aircraft, plan.speedMps))
        sb.append("""
    <Folder>
      <wpml:templateType>waypoint</wpml:templateType>
      <wpml:templateId>0</wpml:templateId>
      <wpml:waylineCoordinateSysParam>
        <wpml:coordinateMode>WGS84</wpml:coordinateMode>
        <wpml:heightMode>relativeToStartPoint</wpml:heightMode>
      </wpml:waylineCoordinateSysParam>
      <wpml:autoFlightSpeed>${f2(plan.speedMps)}</wpml:autoFlightSpeed>
      <wpml:globalHeight>${f2(plan.waypoints.first().position.alt)}</wpml:globalHeight>
      <wpml:caliFlightEnable>0</wpml:caliFlightEnable>
      <wpml:gimbalPitchMode>usePointSetting</wpml:gimbalPitchMode>
      <wpml:globalWaypointHeadingParam>
        <wpml:waypointHeadingMode>smoothTransition</wpml:waypointHeadingMode>
        <wpml:waypointHeadingAngle>0</wpml:waypointHeadingAngle>
        <wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint>
        <wpml:waypointHeadingAngleEnable>1</wpml:waypointHeadingAngleEnable>
        <wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode>
      </wpml:globalWaypointHeadingParam>
      <wpml:globalWaypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:globalWaypointTurnMode>
      <wpml:globalUseStraightLine>1</wpml:globalUseStraightLine>""")
        plan.waypoints.forEachIndexed { i, w -> sb.append(placemark(i, w, plan.speedMps, withExecuteHeight = false, aircraft)) }
        sb.append("""
    </Folder>
  </Document>
</kml>
""")
        return sb.toString()
    }

    private fun waylinesWpml(plan: MissionPlan, aircraft: KmzAircraftInfo, nowMillis: Long): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="$NS_KML" xmlns:wpml="$NS_WPML">
  <Document>""")
        sb.append(header(nowMillis, aircraft, plan.speedMps))
        sb.append("""
    <Folder>
      <wpml:templateId>0</wpml:templateId>
      <wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>
      <wpml:waylineId>0</wpml:waylineId>
      <wpml:autoFlightSpeed>${f2(plan.speedMps)}</wpml:autoFlightSpeed>""")
        plan.waypoints.forEachIndexed { i, w -> sb.append(placemark(i, w, plan.speedMps, withExecuteHeight = true, aircraft)) }
        sb.append("""
    </Folder>
  </Document>
</kml>
""")
        return sb.toString()
    }

    /** El dron para en el waypoint o lo atraviesa sin detenerse (curvatura continua), como el original. */
    internal fun turnMode(w: PlannedWaypoint) =
        if (w.stop) "toPointAndStopWithDiscontinuityCurvature" else "toPointAndPassWithContinuityCurvature"

    private fun placemark(i: Int, w: PlannedWaypoint, planSpeed: Double, withExecuteHeight: Boolean, aircraft: KmzAircraftInfo): String {
        val speed = w.speedMps ?: planSpeed
        val p = w.position
        val sb = StringBuilder()
        sb.append("""
      <Placemark>
        <Point><coordinates>${f(p.lon)},${f(p.lat)}</coordinates></Point>
        <wpml:index>$i</wpml:index>""")
        sb.append(if (withExecuteHeight) "\n        <wpml:executeHeight>${f2(p.alt)}</wpml:executeHeight>" else "\n        <wpml:ellipsoidHeight>${f2(p.alt)}</wpml:ellipsoidHeight>\n        <wpml:height>${f2(p.alt)}</wpml:height>")
        // En la plantilla, cada punto usa su propia altura, velocidad, rumbo y modo de giro (no los globales).
        if (!withExecuteHeight) sb.append(
            "\n        <wpml:useGlobalHeight>0</wpml:useGlobalHeight>\n        <wpml:useGlobalSpeed>0</wpml:useGlobalSpeed>" +
                "\n        <wpml:useGlobalHeadingParam>0</wpml:useGlobalHeadingParam>\n        <wpml:useGlobalTurnParam>0</wpml:useGlobalTurnParam>"
        )
        sb.append("""
        <wpml:waypointSpeed>${f2(speed)}</wpml:waypointSpeed>
        <wpml:waypointHeadingParam>
          <wpml:waypointHeadingMode>fixed</wpml:waypointHeadingMode>
          <wpml:waypointHeadingAngle>${f2(w.yawDeg)}</wpml:waypointHeadingAngle>
          <wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint>
          <wpml:waypointHeadingAngleEnable>1</wpml:waypointHeadingAngleEnable>
          <wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode>
        </wpml:waypointHeadingParam>
        <wpml:waypointTurnParam>
          <wpml:waypointTurnMode>${turnMode(w)}</wpml:waypointTurnMode>
          <wpml:waypointTurnDampingDist>0</wpml:waypointTurnDampingDist>
        </wpml:waypointTurnParam>
        <wpml:useStraightLine>1</wpml:useStraightLine>""")
        sb.append(actionGroup(i, w, aircraft.payloadLensIndex, aircraft.gimbalSettleHoverS))
        sb.append("\n      </Placemark>")
        return sb.toString()
    }

    /**
     * Un grupo secuencial por waypoint, en el orden del original (`WaypointMissionConverter.getWaypointActions`), por acción:
     * giro del dron → gimbal → pausa previa a la foto (si toca) → pausa de estabilización del gimbal (si giró y hay foto)
     * → foto; al final, la pausa posterior.
     */
    private fun actionGroup(i: Int, w: PlannedWaypoint, lensIndex: String, gimbalSettleS: Double): String {
        val acts = w.actions.ifEmpty {
            listOf(PlannedAction(w.yawDeg, w.gimbalPitchDeg, w.takePhoto, rotateGimbal = true, rotateDroneYaw = false))
        }
        val body = StringBuilder()
        var id = 0
        fun action(func: String, params: String) {
            body.append("""
          <wpml:action>
            <wpml:actionId>${id++}</wpml:actionId>
            <wpml:actionActuatorFunc>$func</wpml:actionActuatorFunc>
            <wpml:actionActuatorFuncParam>$params</wpml:actionActuatorFuncParam>
          </wpml:action>""")
        }
        val pauseBefore = w.hoverBeforePhoto && w.hoverSeconds > 0.0
        acts.forEach { a ->
            if (a.rotateDroneYaw) action("rotateYaw", """
              <wpml:aircraftHeading>${f2(a.yawDeg)}</wpml:aircraftHeading>
              <wpml:aircraftPathMode>counterClockwise</wpml:aircraftPathMode>""")
            if (a.rotateGimbal) action("gimbalRotate", """
              <wpml:gimbalHeadingYawBase>north</wpml:gimbalHeadingYawBase>
              <wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode>
              <wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>
              <wpml:gimbalPitchRotateAngle>${f2(a.gimbalPitchDeg)}</wpml:gimbalPitchRotateAngle>
              <wpml:gimbalRollRotateEnable>0</wpml:gimbalRollRotateEnable>
              <wpml:gimbalRollRotateAngle>0</wpml:gimbalRollRotateAngle>
              <wpml:gimbalYawRotateEnable>0</wpml:gimbalYawRotateEnable>
              <wpml:gimbalYawRotateAngle>0</wpml:gimbalYawRotateAngle>
              <wpml:gimbalRotateTimeEnable>0</wpml:gimbalRotateTimeEnable>
              <wpml:gimbalRotateTime>0</wpml:gimbalRotateTime>
              <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>""")
            val beforeThisPhoto = pauseBefore && a.takePhoto
            if (beforeThisPhoto) action("hover", "\n              <wpml:hoverTime>${f2(w.hoverSeconds)}</wpml:hoverTime>")
            if (gimbalSettleS > 0.0 && a.rotateGimbal && a.takePhoto && !beforeThisPhoto)
                action("hover", "\n              <wpml:hoverTime>${f2(gimbalSettleS)}</wpml:hoverTime>")
            if (a.takePhoto) action("takePhoto", """
              <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
              <wpml:payloadLensIndex>$lensIndex</wpml:payloadLensIndex>
              <wpml:useGlobalPayloadLensIndex>0</wpml:useGlobalPayloadLensIndex>""")
        }
        if (!w.hoverBeforePhoto && w.hoverSeconds > 0.0) action("hover", "\n              <wpml:hoverTime>${f2(w.hoverSeconds)}</wpml:hoverTime>")
        if (id == 0) return ""
        return """
        <wpml:actionGroup>
          <wpml:actionGroupId>$i</wpml:actionGroupId>
          <wpml:actionGroupStartIndex>$i</wpml:actionGroupStartIndex>
          <wpml:actionGroupEndIndex>$i</wpml:actionGroupEndIndex>
          <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
          <wpml:actionTrigger>
            <wpml:actionTriggerType>reachPoint</wpml:actionTriggerType>
          </wpml:actionTrigger>$body
        </wpml:actionGroup>"""
    }
}
