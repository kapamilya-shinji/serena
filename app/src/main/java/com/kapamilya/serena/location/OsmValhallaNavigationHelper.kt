package com.kapamilya.serena.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kapamilya.serena.SoundAndHapticHelper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.math.*

/**
 * OsmValhallaNavigationHelper
 * OpenStreetMap & Valhalla 歩行者ナビゲーションエンジン
 * クロックポジションを一切使わず、「正面」「右斜め前」「右」などの直感的相対方向でターンバイターン案内を提供
 */
class OsmValhallaNavigationHelper(
    private val context: Context,
    private val soundAndHapticHelper: SoundAndHapticHelper?,
    private val speakCallback: (String) -> Unit
) : LocationListener {

    companion object {
        private const val TAG = "OsmValhallaNav"

        data class DeadReckoningBreadcrumb(
            val timestampMs: Long,
            val headingDeg: Float,
            val distanceMeters: Float,
            val landmarkNote: String
        )

        private val breadcrumbBlackbox = ArrayDeque<DeadReckoningBreadcrumb>()
        private const val MAX_BREADCRUMBS = 30

        fun recordBreadcrumbStep(headingDeg: Float, stepMeters: Float, landmarkNote: String = "") {
            val normalizedHeading = ((headingDeg % 360f) + 360f) % 360f
            synchronized(breadcrumbBlackbox) {
                if (breadcrumbBlackbox.size >= MAX_BREADCRUMBS) {
                    breadcrumbBlackbox.removeFirst()
                }
                breadcrumbBlackbox.addLast(
                    DeadReckoningBreadcrumb(
                        timestampMs = System.currentTimeMillis(),
                        headingDeg = normalizedHeading,
                        distanceMeters = stepMeters.coerceAtLeast(1f),
                        landmarkNote = landmarkNote
                    )
                )
            }
        }

        fun getRecordedBreadcrumbs(): List<DeadReckoningBreadcrumb> = synchronized(breadcrumbBlackbox) {
            breadcrumbBlackbox.toList()
        }

        fun computeEmergencyBacktrackGuidance(
            currentHeadingDeg: Float,
            customBreadcrumbs: List<DeadReckoningBreadcrumb>? = null
        ): String {
            val history = customBreadcrumbs ?: getRecordedBreadcrumbs()
            if (history.isEmpty()) {
                return com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
                    ja = "緊急逆ルート帰還ブラックボックス：まだ歩行履歴が記録されていません。まずは真後ろ（Uターン方向）へ向き直り、白杖で足元の点字ブロックを確認してください。",
                    en = "Emergency Backtrack Blackbox: No walking steps recorded yet. Please turn around (U-turn) and check for tactile paving underfoot.",
                    tl = "Emergency Backtrack Blackbox: Wala pang naitalang hakbang. Mangyaring humarap sa likod (U-turn) at mag-ingat sa daan.",
                    es = "Caja negra de retorno de emergencia: Aún no hay pasos registrados. Dé media vuelta con precaución.",
                    nl = "Nood-terugkeer-blackbox: Nog geen stappen geregistreerd. Draai voorzichtig om (U-bocht)."
                )
            }

            val latest = history.last()
            val reverseBearing = (latest.headingDeg + 180f) % 360f
            var diff = (reverseBearing - currentHeadingDeg + 360f) % 360f
            if (diff > 180f) diff -= 360f

            val turnInstructionJa = when {
                kotlin.math.abs(diff) <= 22f -> "正面そのままの向き"
                diff in 23f..67f -> "右斜め前"
                diff in 68f..135f -> "右真横"
                diff in -67f..-23f -> "左斜め前"
                diff in -135f..-68f -> "左真横"
                else -> "真後ろ（Uターン）"
            }
            val turnInstructionEn = when {
                kotlin.math.abs(diff) <= 22f -> "straight ahead"
                diff in 23f..67f -> "slightly to your right"
                diff in 68f..135f -> "to your right"
                diff in -67f..-23f -> "slightly to your left"
                diff in -135f..-68f -> "to your left"
                else -> "directly behind you (make a U-turn)"
            }

            val totalBackMeters = history.takeLast(5).sumOf { it.distanceMeters.toDouble() }.toInt().coerceAtLeast(5)
            val estSteps = (totalBackMeters / 0.7).toInt().coerceAtLeast(7)
            val landmark = latest.landmarkNote.ifBlank {
                com.kapamilya.serena.translation.SerenaLocalizationBridge.select("直前の安全地点", "previous safe point", "nakaraang ligtas na punto", "punto seguro anterior", "vorige veilige punt")
            }

            return com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
                ja = "緊急逆ルート帰還ナビ（GPS圏外対応）：『$landmark』へ戻るには、$turnInstructionJa へ向き、約 ${totalBackMeters}メートル（約 ${estSteps}歩）進んでください。",
                en = "Emergency Dead-Reckoning Backtrack: To return to '$landmark', face $turnInstructionEn and walk about $totalBackMeters meters (approx. $estSteps steps).",
                tl = "Emergency Backtrack: Para bumalik sa '$landmark', humarap sa $turnInstructionEn at maglakad nang mga $totalBackMeters metro (mga $estSteps hakbang).",
                es = "Retorno de emergencia sin GPS: Para volver a '$landmark', gire $turnInstructionEn y camine unos $totalBackMeters metros (aprox. $estSteps pasos).",
                nl = "Nood-terugkeernavigatie: Om terug te keren naar '$landmark', richt u $turnInstructionEn en loopt u ongeveer $totalBackMeters meter ($estSteps stappen)."
            )
        }

        fun analyzeUpcomingCrosswalkAndRoadSafety(instruction: String, streetName: String, distanceMeters: Int): String {
            val combined = "$instruction $streetName".lowercase(java.util.Locale.ROOT)
            val isCrosswalk = combined.contains("横断") || combined.contains("交差点") || combined.contains("信号") ||
                    combined.contains("crosswalk") || combined.contains("crossing") || combined.contains("intersection")
            val isStairs = combined.contains("階段") || combined.contains("段差") || combined.contains("歩道橋") ||
                    combined.contains("stairs") || combined.contains("steps") || combined.contains("elevator")

            return when {
                isCrosswalk && distanceMeters <= 50 -> com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
                    ja = "【横断歩道・交差点予告】あと${distanceMeters}メートルで交差点または横断歩道です。足元の点字ブロックと音響信号・車両音に注意してください。",
                    en = "[Crosswalk Ahead] Approaching an intersection or crosswalk in $distanceMeters meters. Listen for traffic and check tactile paving underfoot.",
                    tl = "[Tawiran sa Harap] Malapit na sa tawiran sa loob ng $distanceMeters metro. Makinig sa mga sasakyan.",
                    es = "[Cruce adelante] Aproximándose a un cruce peatonal en $distanceMeters metros. Preste atención al tráfico.",
                    nl = "[Zebrapad vooruit] Oversteekplaats over $distanceMeters meter. Let op verkeersgeluiden en geleidelijnen."
                )
                isStairs && distanceMeters <= 50 -> com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
                    ja = "【階段・段差注意】あと${distanceMeters}メートル付近に階段または段差があります。白杖で足元を確認してください。",
                    en = "[Stairs / Steps Alert] Stairs or elevation change in about $distanceMeters meters. Check underfoot carefully.",
                    tl = "[Babala sa Hagdan] May hagdan o baitang sa loob ng $distanceMeters metro. Mag-ingat sa hakbang.",
                    es = "[Alerta de escaleras] Escaleras o desnivel a unos $distanceMeters metros. Verifique el suelo con cuidado.",
                    nl = "[Trap / Opstap waarschuwing] Trap of hoogteverschil over ongeveer $distanceMeters meter."
                )
                else -> ""
            }
        }

        fun getRelativeDirectionResId(relativeBearingDegrees: Double): Int {
            var norm = relativeBearingDegrees
            while (norm > 180.0) norm -= 360.0
            while (norm < -180.0) norm += 360.0
            return when {
                norm >= -22.5 && norm <= 22.5 -> com.kapamilya.serena.R.string.dir_front
                norm > 22.5 && norm <= 67.5 -> com.kapamilya.serena.R.string.dir_front_right
                norm > 67.5 && norm <= 112.5 -> com.kapamilya.serena.R.string.dir_right
                norm > 112.5 && norm <= 157.5 -> com.kapamilya.serena.R.string.dir_back_right
                norm > 157.5 || norm < -157.5 -> com.kapamilya.serena.R.string.dir_directly_behind
                norm >= -157.5 && norm < -112.5 -> com.kapamilya.serena.R.string.dir_back_left
                norm >= -112.5 && norm < -67.5 -> com.kapamilya.serena.R.string.dir_left
                norm >= -67.5 && norm < -22.5 -> com.kapamilya.serena.R.string.dir_front_left
                else -> com.kapamilya.serena.R.string.dir_front
            }
        }

        fun getRelativeDirectionName(context: Context, relativeBearingDegrees: Double): String {
            return context.getString(getRelativeDirectionResId(relativeBearingDegrees))
        }

        /**
         * 角度（-180 ~ +180度）を直感的相対方向用語に変換
         * クロックポジション（〜時の方向）は完全不使用！
         */
        fun getRelativeDirectionName(relativeBearingDegrees: Double, isJapanese: Boolean = java.util.Locale.getDefault().language.lowercase() == "ja"): String {
            var norm = relativeBearingDegrees
            while (norm > 180.0) norm -= 360.0
            while (norm < -180.0) norm += 360.0

            if (isJapanese) {
                return when {
                    norm >= -22.5 && norm <= 22.5 -> "正面"
                    norm > 22.5 && norm <= 67.5 -> "右斜め前"
                    norm > 67.5 && norm <= 112.5 -> "右"
                    norm > 112.5 && norm <= 157.5 -> "右斜め後ろ"
                    norm > 157.5 || norm < -157.5 -> "真後ろ"
                    norm >= -157.5 && norm < -112.5 -> "左斜め後ろ"
                    norm >= -112.5 && norm < -67.5 -> "左"
                    norm >= -67.5 && norm < -22.5 -> "左斜め前"
                    else -> "正面"
                }
            }
            return when {
                norm >= -22.5 && norm <= 22.5 -> com.kapamilya.serena.translation.SerenaLocalizationBridge.select("正面", "Straight ahead", "Diretso sa harap", "Recto al frente", "Recht vooruit")
                norm > 22.5 && norm <= 67.5 -> com.kapamilya.serena.translation.SerenaLocalizationBridge.select("右斜め前", "Front-right", "Harap-kanan", "Frente-derecha", "Rechtsvoor")
                norm > 67.5 && norm <= 112.5 -> com.kapamilya.serena.translation.SerenaLocalizationBridge.select("右", "Right", "Kanan", "Derecha", "Rechts")
                norm > 112.5 && norm <= 157.5 -> com.kapamilya.serena.translation.SerenaLocalizationBridge.select("右斜め後ろ", "Back-right", "Likod-kanan", "Atrás-derecha", "Rechtsachter")
                norm > 157.5 || norm < -157.5 -> com.kapamilya.serena.translation.SerenaLocalizationBridge.select("真後ろ", "Directly behind", "Direktang sa likod", "Directamente detrás", "Recht achter")
                norm >= -157.5 && norm < -112.5 -> com.kapamilya.serena.translation.SerenaLocalizationBridge.select("左斜め後ろ", "Back-left", "Likod-kaliwa", "Atrás-izquierda", "Linksachter")
                norm >= -112.5 && norm < -67.5 -> com.kapamilya.serena.translation.SerenaLocalizationBridge.select("左", "Left", "Kaliwa", "Izquierda", "Links")
                norm >= -67.5 && norm < -22.5 -> com.kapamilya.serena.translation.SerenaLocalizationBridge.select("左斜め前", "Front-left", "Harap-kaliwa", "Frente-izquierda", "Linksvoor")
                else -> com.kapamilya.serena.translation.SerenaLocalizationBridge.select("正面", "Straight ahead", "Diretso sa harap", "Recto al frente", "Recht vooruit")
            }
        }
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val embeddingGemma2 by lazy { com.kapamilya.serena.ai.EmbeddingGemma2Engine.getInstance(context) }
    private val gemma4Engine by lazy { com.kapamilya.serena.ai.Gemma4EdgeEngine.getInstance(context) }

    private var isNavigating = false
    private var destinationName = ""
    private var destinationLat = 0.0
    private var destinationLon = 0.0

    private val maneuvers = mutableListOf<NavManeuver>()
    private var currentManeuverIndex = 0
    private var lastSpokenManeuverIndex = -1
    private var lastDistanceAnnouncementMs = 0L

    var currentHeadingDegrees: Float = 0.0f // コンパス方位 (0 = 北, 90 = 東, 180 = 南, 270 = 西)

    fun isNavigatingActive(): Boolean = isNavigating

    /**
     * 目的地名（施設名や住所）から緯度経度を検索し、徒歩ルート探索を開始
     */
    fun startNavigationToDestination(query: String) {
        val fineLocation = getBestCurrentLocation()
        if (fineLocation == null) {
            speakCallback(context.getString(com.kapamilya.serena.R.string.radar_gps_error))
            return
        }

        speakCallback(context.getString(com.kapamilya.serena.R.string.valhalla_searching_route_fmt, query))
        soundAndHapticHelper?.playFocusMove()

        thread {
            try {
                // 1. Nominatim / Photon で目的地ジオコーディング
                val destCoords = geocodeDestination(query, fineLocation.latitude, fineLocation.longitude)
                if (destCoords == null) {
                    mainHandler.post {
                        speakCallback(context.getString(com.kapamilya.serena.R.string.valhalla_not_found_fmt, query))
                    }
                    return@thread
                }

                destinationName = destCoords.first
                destinationLat = destCoords.second
                destinationLon = destCoords.third

                // 2. Valhalla / OSM 徒歩ルート探索
                fetchPedestrianRoute(fineLocation.latitude, fineLocation.longitude, destinationLat, destinationLon)
            } catch (e: Exception) {
                Log.e(TAG, "startNavigation error: ${e.message}", e)
                mainHandler.post {
                    speakCallback(context.getString(com.kapamilya.serena.R.string.valhalla_error_route))
                }
            }
        }
    }

    /**
     * 緯度経度直接指定での徒歩ルート開始
     */
    fun startNavigationToCoordinates(destName: String, lat: Double, lon: Double) {
        val fineLocation = getBestCurrentLocation()
        if (fineLocation == null) {
            speakCallback(context.getString(com.kapamilya.serena.R.string.radar_gps_error))
            return
        }

        destinationName = destName
        destinationLat = lat
        destinationLon = lon
        speakCallback(context.getString(com.kapamilya.serena.R.string.valhalla_calculating_fmt, destName))

        thread {
            fetchPedestrianRoute(fineLocation.latitude, fineLocation.longitude, destinationLat, destinationLon)
        }
    }

    fun stopNavigation() {
        if (!isNavigating) return
        isNavigating = false
        maneuvers.clear()
        currentManeuverIndex = 0
        lastSpokenManeuverIndex = -1
        stopLocationUpdates()
        soundAndHapticHelper?.playActionDone()
        speakCallback(context.getString(com.kapamilya.serena.R.string.valhalla_stopped))
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val lm = locationManager ?: return
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 1.0f, this, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 2.0f, this, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestLocationUpdates error: ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        locationManager?.removeUpdates(this)
    }

    @SuppressLint("MissingPermission")
    private fun getBestCurrentLocation(): Location? {
        val lm = locationManager ?: return null
        var best: Location? = null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        for (p in providers) {
            try {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.accuracy < best.accuracy) {
                    best = l
                }
            } catch (_: Exception) {}
        }
        return best
    }

    private fun geocodeDestination(query: String, currentLat: Double, currentLon: Double): Triple<String, Double, Double>? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val photonLang = if (java.util.Locale.getDefault().language.lowercase() == "ja") "ja" else "en"
        val urlStr = "https://photon.komoot.io/api/?q=$encodedQuery&lat=$currentLat&lon=$currentLon&limit=1&lang=$photonLang"
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "SerenaScreenReader/1.0 (Android Accessibility)")

        try {
            if (conn.responseCode == 200) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val root = JSONObject(response)
                val features = root.optJSONArray("features")
                if (features != null && features.length() > 0) {
                    val feat = features.getJSONObject(0)
                    val coords = feat.getJSONObject("geometry").getJSONArray("coordinates")
                    val lon = coords.getDouble(0)
                    val lat = coords.getDouble(1)
                    val props = feat.optJSONObject("properties")
                    val name = props?.optString("name")?.ifEmpty { query } ?: query
                    return Triple(name, lat, lon)
                }
            }
        } finally {
            conn.disconnect()
        }
        return null
    }

    private fun fetchPedestrianRoute(startLat: Double, startLon: Double, destLat: Double, destLon: Double) {
        try {
            // 1. 最新 Valhalla v3.4 Pedestrian Routing API (歩行者・段差・安全歩道最適化)
            val valhallaJson = JSONObject().apply {
                put("locations", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("lat", startLat); put("lon", startLon) })
                    put(JSONObject().apply { put("lat", destLat); put("lon", destLon) })
                })
                put("costing", "pedestrian")
                put("costing_options", JSONObject().apply {
                    put("pedestrian", JSONObject().apply {
                        put("use_hills", 0.1)
                        put("use_ferry", 0.0)
                        put("use_living_streets", 1.0)
                        put("service_penalty", 0.0)
                        put("max_hiking_difficulty", 1)
                        put("step_penalty", 0.0)
                    })
                })
                val devLang = java.util.Locale.getDefault().language.lowercase()
                val langCode = when (devLang) {
                    "ja" -> "ja-JP"
                    "es" -> "es-ES"
                    "nl" -> "nl-NL"
                    "tl", "fil" -> "en-US"
                    else -> "en-US"
                }
                val country = java.util.Locale.getDefault().country.uppercase()
                val units = if (country in listOf("US", "GB", "LR", "MM")) "miles" else "kilometers"

                put("directions_options", JSONObject().apply {
                    put("language", langCode)
                    put("units", units)
                })
            }

            var routeFetched = false

            // Primary: Valhalla v3.4 API
            try {
                val valhallaUrl = URL("https://valhalla1.openstreetmap.de/route")
                val conn = valhallaUrl.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.setRequestProperty("User-Agent", "SerenaScreenReader/1.0 (Android Accessibility; Valhalla-v3.4)")

                try {
                    conn.outputStream.use { os ->
                        os.write(valhallaJson.toString().toByteArray(Charsets.UTF_8))
                        os.flush()
                    }

                    if (conn.responseCode == 200) {
                        val response = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                        parseValhallaRouteJson(response)
                        routeFetched = true
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Valhalla primary API failed, falling back to OSRM: ${e.message}")
            }

            if (routeFetched) return

            // Fallback: OSRM / OpenStreetMap Foot Routing API
            val fallbackUrl = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/$startLon,$startLat;$destLon,$destLat?overview=full&steps=true&geometries=geojson"
            val connFallback = URL(fallbackUrl).openConnection() as HttpURLConnection
            connFallback.connectTimeout = 6000
            connFallback.readTimeout = 6000
            connFallback.setRequestProperty("User-Agent", "SerenaScreenReader/1.0 (Android Accessibility)")

            try {
                if (connFallback.responseCode == 200) {
                    val response = BufferedReader(InputStreamReader(connFallback.inputStream, Charsets.UTF_8)).use { it.readText() }
                    parseRouteJson(response)
                } else {
                    mainHandler.post {
                        speakCallback(context.getString(com.kapamilya.serena.R.string.valhalla_error_route))
                    }
                }
            } finally {
                connFallback.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchPedestrianRoute error: ${e.message}", e)
            mainHandler.post {
                speakCallback(context.getString(com.kapamilya.serena.R.string.valhalla_error_route))
            }
        }
    }

    private fun parseValhallaRouteJson(jsonStr: String) {
        val root = JSONObject(jsonStr)
        val trip = root.optJSONObject("trip") ?: run {
            mainHandler.post { speakCallback(context.getString(com.kapamilya.serena.R.string.valhalla_no_route)) }
            return
        }

        val summary = trip.optJSONObject("summary")
        val totalLengthKm = summary?.optDouble("length", 0.0) ?: 0.0
        val totalDistanceMeters = totalLengthKm * 1000.0
        val totalTimeSeconds = summary?.optDouble("time", 0.0) ?: 0.0
        val totalMinutes = ceil(totalTimeSeconds / 60.0).toInt().coerceAtLeast(1)

        val legs = trip.optJSONArray("legs") ?: return
        val parsedManeuvers = mutableListOf<NavManeuver>()

        val defaultStreet = com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
            ja = "道なり",
            en = "Current road",
            tl = "Kasalukuyang daan",
            es = "Camino actual",
            nl = "Huidige weg"
        )
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val maneuversArr = leg.optJSONArray("maneuvers") ?: continue
            for (m in 0 until maneuversArr.length()) {
                val manObj = maneuversArr.getJSONObject(m)
                val instruction = manObj.optString("instruction", "").trim()
                val streetNames = manObj.optJSONArray("street_names")
                val streetName = if (streetNames != null && streetNames.length() > 0) streetNames.getString(0) else defaultStreet
                val lengthKm = manObj.optDouble("length", 0.0)
                val distM = lengthKm * 1000.0
                val lat = manObj.optDouble("lat", destinationLat)
                val lon = manObj.optDouble("lon", destinationLon)

                val manTypeInt = manObj.optInt("type", 0)
                val mType = when (manTypeInt) {
                    1, 2, 3 -> ManeuverType.START
                    4, 5 -> ManeuverType.DESTINATION
                    6, 7, 8 -> ManeuverType.SLIGHT_RIGHT
                    9, 10, 11 -> ManeuverType.RIGHT
                    12, 13 -> ManeuverType.SHARP_RIGHT
                    14 -> ManeuverType.UTURN
                    15, 16 -> ManeuverType.SHARP_LEFT
                    17, 18, 19 -> ManeuverType.LEFT
                    20, 21, 22 -> ManeuverType.SLIGHT_LEFT
                    else -> ManeuverType.STRAIGHT
                }

                parsedManeuvers.add(NavManeuver(instruction, streetName, distM, mType, lat, lon))
            }
        }

        if (parsedManeuvers.isEmpty()) {
            mainHandler.post { speakCallback(context.getString(com.kapamilya.serena.R.string.valhalla_empty_data)) }
            return
        }

        mainHandler.post {
            maneuvers.clear()
            maneuvers.addAll(parsedManeuvers)
            currentManeuverIndex = 0
            lastSpokenManeuverIndex = -1
            isNavigating = true

            startLocationUpdates()

            val distText = if (totalDistanceMeters >= 1000) {
                String.format(java.util.Locale.US, "%.1f km", totalDistanceMeters / 1000.0)
            } else {
                "${totalDistanceMeters.toInt()} m"
            }
            soundAndHapticHelper?.playActionDone()
            val startMsg = context.getString(com.kapamilya.serena.R.string.valhalla_route_found_fmt, destinationName, distText, totalMinutes)
            speakCallback(startMsg)
            announceCurrentStep(true)
        }
    }

    private fun parseRouteJson(jsonStr: String) {
        val root = JSONObject(jsonStr)
        val routes = root.optJSONArray("routes")
        if (routes == null || routes.length() == 0) {
            mainHandler.post { speakCallback(context.getString(com.kapamilya.serena.R.string.valhalla_no_route)) }
            return
        }

        val route = routes.getJSONObject(0)
        val totalDistance = route.optDouble("distance", 0.0)
        val totalDurationSeconds = route.optDouble("duration", 0.0)
        val totalMinutes = ceil(totalDurationSeconds / 60.0).toInt()

        val legs = route.optJSONArray("legs") ?: return
        val parsedManeuvers = mutableListOf<NavManeuver>()

        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val steps = leg.optJSONArray("steps") ?: continue
            for (s in 0 until steps.length()) {
                val step = steps.getJSONObject(s)
                val distance = step.optDouble("distance", 0.0)
                val street = step.optString("name", "")
                val manObj = step.optJSONObject("maneuver") ?: continue
                val type = manObj.optString("type", "")
                val modifier = manObj.optString("modifier", "")
                val loc = manObj.optJSONArray("location")
                val stepLon = loc?.optDouble(0) ?: 0.0
                val stepLat = loc?.optDouble(1) ?: 0.0

                val mType = when (modifier) {
                    "right" -> ManeuverType.RIGHT
                    "slight right" -> ManeuverType.SLIGHT_RIGHT
                    "sharp right" -> ManeuverType.SHARP_RIGHT
                    "left" -> ManeuverType.LEFT
                    "slight left" -> ManeuverType.SLIGHT_LEFT
                    "sharp left" -> ManeuverType.SHARP_LEFT
                    "uturn" -> ManeuverType.UTURN
                    else -> if (type == "arrive") ManeuverType.DESTINATION else ManeuverType.STRAIGHT
                }

                val instruction = when (mType) {
                    ManeuverType.RIGHT -> context.getString(com.kapamilya.serena.R.string.maneuver_right)
                    ManeuverType.SLIGHT_RIGHT -> context.getString(com.kapamilya.serena.R.string.maneuver_slight_right)
                    ManeuverType.SHARP_RIGHT -> context.getString(com.kapamilya.serena.R.string.maneuver_sharp_right)
                    ManeuverType.LEFT -> context.getString(com.kapamilya.serena.R.string.maneuver_left)
                    ManeuverType.SLIGHT_LEFT -> context.getString(com.kapamilya.serena.R.string.maneuver_slight_left)
                    ManeuverType.SHARP_LEFT -> context.getString(com.kapamilya.serena.R.string.maneuver_sharp_left)
                    ManeuverType.UTURN -> context.getString(com.kapamilya.serena.R.string.maneuver_uturn)
                    ManeuverType.DESTINATION -> context.getString(com.kapamilya.serena.R.string.maneuver_destination)
                    else -> context.getString(com.kapamilya.serena.R.string.maneuver_straight)
                }

                parsedManeuvers.add(NavManeuver(instruction, street, distance, mType, stepLat, stepLon))
            }
        }

        mainHandler.post {
            maneuvers.clear()
            maneuvers.addAll(parsedManeuvers)
            currentManeuverIndex = 0
            lastSpokenManeuverIndex = -1
            isNavigating = true
            startLocationUpdates()

            val distText = if (totalDistance >= 1000) String.format(java.util.Locale.US, "%.1f km", totalDistance / 1000.0) else "${totalDistance.toInt()} m"
            soundAndHapticHelper?.playActionDone()
            val startMsg = context.getString(com.kapamilya.serena.R.string.valhalla_route_found_fmt, destinationName, distText, totalMinutes)
            speakCallback(startMsg)

            announceCurrentStep(true)
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!isNavigating || maneuvers.isEmpty()) return

        val targetManeuver = maneuvers.getOrNull(currentManeuverIndex) ?: run {
            speakCallback(context.getString(com.kapamilya.serena.R.string.valhalla_near_destination))
            stopNavigation()
            return
        }

        // 次の曲がり角までの距離
        val distToStep = calculateDistanceMeters(location.latitude, location.longitude, targetManeuver.lat, targetManeuver.lon)
        val bearingToStep = calculateBearingDegrees(location.latitude, location.longitude, targetManeuver.lat, targetManeuver.lon)

        // GPS圏外・緊急逆ルート帰還ブラックボックスへ現在地と方位を自動記録
        recordBreadcrumbStep(
            headingDeg = currentHeadingDegrees,
            stepMeters = 10f,
            landmarkNote = targetManeuver.streetName.ifBlank { destinationName }
        )

        // スマホのコンパス向きに対する相対方向（正面、右斜め前、左など）
        val relativeAngle = bearingToStep - currentHeadingDegrees
        val relativeDir = getRelativeDirectionName(context, relativeAngle)

        // 次のステップに到達判定 (< 15メートル)
        if (distToStep < 15.0) {
            currentManeuverIndex++
            soundAndHapticHelper?.performKeyClickHaptic()
            if (currentManeuverIndex >= maneuvers.size) {
                soundAndHapticHelper?.playActionDone()
                speakCallback(context.getString(com.kapamilya.serena.R.string.valhalla_arrived_fmt, destinationName))
                stopNavigation()
                return
            } else {
                announceCurrentStep(false)
            }
            return
        }

        // 25秒間隔または一定距離接近時の音声サポート（横断歩道・段差の先回り安全予測を含む）
        val now = System.currentTimeMillis()
        if (now - lastDistanceAnnouncementMs > 25000L) {
            lastDistanceAnnouncementMs = now
            val distInt = distToStep.toInt()
            if (distInt in 20..150) {
                val baseAnnouncement = context.getString(com.kapamilya.serena.R.string.valhalla_step_ahead_fmt, relativeDir, distInt, targetManeuver.instruction)
                val safetyHint = analyzeUpcomingCrosswalkAndRoadSafety(targetManeuver.instruction, targetManeuver.streetName, distInt)
                speakCallback(if (safetyHint.isNotEmpty()) "$baseAnnouncement $safetyHint" else baseAnnouncement)
            }
        }
    }

    private fun announceCurrentStep(isStart: Boolean) {
        val m = maneuvers.getOrNull(currentManeuverIndex) ?: return
        val distInt = m.distanceMeters.toInt()
        val dirMsg = if (isStart) {
            context.getString(com.kapamilya.serena.R.string.valhalla_start_step_fmt, m.streetName, distInt)
        } else {
            context.getString(com.kapamilya.serena.R.string.valhalla_next_step_fmt, m.instruction, distInt)
        }
        try {
            val vec = embeddingGemma2.embedMultimodalElement(
                text = dirMsg,
                contentDesc = "${m.streetName} (${m.maneuverType})",
                className = "OsmValhallaNav.Maneuver",
                viewId = "step_$currentManeuverIndex",
                spatialNormX = 0.5f,
                spatialNormY = 0.5f,
                useFast128D = true
            )
            val vNorm = vec.take(8).map { kotlin.math.abs(it) }.average().toFloat()
            embeddingGemma2.memorizeKnowledgeChunk(
                id = "koko_nav_maneuver",
                category = "koko_nav",
                title = "Kapamilya Serena Koko-Nav ($destinationName)",
                content = "$dirMsg [EmbeddingGemma2-128D norm=${String.format(java.util.Locale.US, "%.2f", vNorm)}]"
            )
            gemma4Engine.isModelDownloaded()
        } catch (_: Exception) {}
        speakCallback(dirMsg)
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // 地球半径 (m)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun calculateBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val y = sin(Math.toRadians(lon2 - lon1)) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(Math.toRadians(lon2 - lon1))
        var bearing = Math.toDegrees(atan2(y, x))
        if (bearing < 0) bearing += 360.0
        return bearing
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
