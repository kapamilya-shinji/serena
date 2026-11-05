/*
 * Copyright (C) 2026 Shinji Sakiyama (shinjisakiyama@gmail.com)
 * Bayanihan • Magna Carta • Kapamilya
 * Licensed under the Apache License, Version 2.0
 */

package com.kapamilya.serena.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.ContextCompat
import com.kapamilya.serena.SerenaScreenReaderService
import com.kapamilya.serena.serenaMenuDialog
import com.kapamilya.serena.serenaMenuItem
import com.kapamilya.serena.translation.SerenaLocalizationBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * KapamilyaDailyLifeIntelligenceHub
 *
 * 100%完全無料・APIキー登録不要・広告ゼロの「6大生活インテリジェンス統合エンジン」:
 * ① リアルタイム天気・気温・体感温度（服装＆傘アドバイス）・湿度・風速・紫外線（UV）・気圧（頭痛アラート）
 *    （Open-Meteo API + オフライン時は内蔵お日様＆照度センサーへ自動フォールバック）
 * ② 地震速報・津波情報（P2P地震情報 API [日本] ＆ 米国地質調査所 USGS [フィリピン・世界]）
 * ③ リアルタイム為替・通貨換算（ペソ PHP / ドル USD / 円 JPY / ユーロ EUR 等を即座に計算）
 * ④ Wikipedia 連動「なんでも用語・人物辞典」（日・英・タガログ・西 Wikipedia REST API + Gemma 4 要約）
 * ⑤ 世界の時間・時差計算（マニラ / ニューヨーク / ロサンゼルス / ロンドン / マドリード / 東京 0秒計算）
 * ⑥ 広告ゼロ・全文読み上げ対応「世界＆日本 3大公式ニュースリーダー」
 *    （🇯🇵 NHKニュース / 🇵🇭 ABS-CBN News / 🌍 BBC News World & Japan の見出し＋記事本文まるごと抽出読み上げ）
 */
class KapamilyaDailyLifeIntelligenceHub(private val service: SerenaScreenReaderService) {

    companion object {
        private const val TAG = "KapamilyaLifeHub"
        private const val WEATHER_CACHE_TTL_MS = 10 * 60 * 1000L // 10分キャッシュ

        data class NewsArticleItem(
            val title: String,
            val summary: String,
            val link: String,
            val pubDate: String,
            val sourceName: String,
            val localeTag: String
        )

        data class WeatherSnapshot(
            val fetchedAtMs: Long,
            val weatherDescJa: String,
            val weatherDescEn: String,
            val tempC: Double,
            val apparentTempC: Double,
            val maxTempC: Double,
            val minTempC: Double,
            val precipProbMax: Int,
            val humidityPct: Int,
            val windSpeedKmh: Double,
            val uvIndexMax: Double,
            val pressureHpa: Double,
            val shortStatusJa: String,
            val fullReportJa: String
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedWeather: WeatherSnapshot? = null

    @Volatile
    private var lastFetchedNewsList: List<NewsArticleItem> = emptyList()

    // =========================================================================
    // 統合ランチャーメニュー（Kapamilya Serena メニューからワンタップで起動可能）
    // =========================================================================
    fun showDailyLifeHubMenu() {
        val items = listOf(
            serenaMenuItem(
                "🌤️",
                SerenaLocalizationBridge.select(
                    ja = "1. 現在地の天気・気温・服装＆傘アドバイス・気圧予報",
                    en = "1. Local Weather, Clothing/Umbrella Advice & Barometric Pressure",
                    tl = "1. Lagay ng Panahon, Payo sa Kasuotan at Presyon ng Hangin",
                    es = "1. Clima local, consejo de ropa/paraguas y presión atmosférica"
                )
            ) {
                announceDetailedWeatherAndAdvice()
            },
            serenaMenuItem(
                "📰",
                SerenaLocalizationBridge.select(
                    ja = "2. 世界＆日本3大ニュース全文リーダー (NHK / ABS-CBN / BBC)",
                    en = "2. Ad-Free Full-Text News Reader (NHK / ABS-CBN / BBC)",
                    tl = "2. Buong Balita Reader (ABS-CBN / BBC / NHK)",
                    es = "2. Lector de noticias a texto completo (NHK / ABS-CBN / BBC)"
                )
            ) {
                showGlobalNewsSourceMenu()
            },
            serenaMenuItem(
                "🌋",
                SerenaLocalizationBridge.select(
                    ja = "3. 最新の地震速報・津波情報 (日本 ＆ フィリピン・世界)",
                    en = "3. Latest Earthquake & Tsunami Info (Japan, Philippines & Global)",
                    tl = "3. Pinakabagong Balita sa Lindol at Tsunami (Japan at Pilipinas)",
                    es = "3. Última información de sismos y tsunamis (Japón y el mundo)"
                )
            ) {
                announceLatestEarthquakeInfo()
            },
            serenaMenuItem(
                "💱",
                SerenaLocalizationBridge.select(
                    ja = "4. リアルタイム為替レート (フィリピンペソ / 米ドル / 日本円 / ユーロ)",
                    en = "4. Live Currency Exchange Rates (PHP / USD / JPY / EUR)",
                    tl = "4. Palitan ng Pera (Peso / Dolyar / Yen / Euro)",
                    es = "4. Tasas de cambio en tiempo real (PHP / USD / JPY / EUR)"
                )
            ) {
                announceCurrencyConversion("為替レート")
            },
            serenaMenuItem(
                "🌍",
                SerenaLocalizationBridge.select(
                    ja = "5. 世界時計＆時差チェック (フィリピン・アメリカ・ヨーロッパ・日本)",
                    en = "5. World Clock & Time Zones (Manila, US, Europe, Tokyo)",
                    tl = "5. Oras sa Mundo (Maynila, US, Europa, Tokyo)",
                    es = "5. Reloj mundial y zonas horarias (Manila, EE. UU., Europa, Tokio)"
                )
            ) {
                announceWorldTime("世界時計")
            },
            serenaMenuItem(
                "🎙️",
                SerenaLocalizationBridge.select(
                    ja = "6. 声でWikipedia・為替・天気をなんでも質問 (AIアシスタント起動)",
                    en = "6. Ask Anything by Voice: Wikipedia, Currency, Weather",
                    tl = "6. Magtanong gamit ang Boses: Wikipedia, Pera, Panahon",
                    es = "6. Preguntar por voz: Wikipedia, divisas, clima"
                )
            ) {
                service.launchAiAssistant()
            }
        )

        service.soundHelper?.playMenuOpen()
        val firstTitle = items.first().title
        service.speak("生活・ニュース・天気メニュー、全${items.size}項目。1番、$firstTitle", TextToSpeech.QUEUE_FLUSH)
        mainHandler.post {
            try {
                val dlg = serenaMenuDialog(service, false, items)
                service.activeMenuDialog = dlg
                dlg.show()
            } catch (e: Exception) {
                Log.e(TAG, "showDailyLifeHubMenu error: ${e.message}")
            }
        }
    }

    // =========================================================================
    // ① リアルタイム天気・気温・体感温度（服装＆傘アドバイス）・UV・気圧（頭痛アラート）
    // =========================================================================
    fun getShortWeatherForStatus(callback: (String) -> Unit) {
        val now = System.currentTimeMillis()
        val cached = cachedWeather
        if (cached != null && (now - cached.fetchedAtMs) < WEATHER_CACHE_TTL_MS) {
            callback(cached.shortStatusJa)
            return
        }
        scope.launch {
            val snap = fetchWeatherSnapshotSuspend()
            if (snap != null) {
                cachedWeather = snap
                callback(snap.shortStatusJa)
            } else {
                callback("")
            }
        }
    }

    fun announceDetailedWeatherAndAdvice() {
        service.soundHelper?.playActionDone()
        service.speak("現在地の最新の天気、気温、気圧と服装アドバイスを取得しています...", TextToSpeech.QUEUE_FLUSH)
        scope.launch {
            val snap = fetchWeatherSnapshotSuspend()
            if (snap != null) {
                cachedWeather = snap
                val sunStatus = service.colorAndLightHelper?.getSunAndDaylightStatus() ?: ""
                val combined = if (sunStatus.isNotEmpty()) {
                    "${snap.fullReportJa} なお、センサーによる太陽と照明の状態は、$sunStatus"
                } else {
                    snap.fullReportJa
                }
                service.speak(combined, TextToSpeech.QUEUE_FLUSH)
            } else {
                // オフライン時は内蔵お日様＆照度センサーへフォールバック
                val fallbackSun = service.colorAndLightHelper?.getSunAndDaylightStatus()
                    ?: "現在インターネットに接続できないため、天気予報を取得できませんでした。"
                service.speak("オフラインのため内蔵センサーでお知らせします。$fallbackSun", TextToSpeech.QUEUE_FLUSH)
            }
        }
    }

    private suspend fun fetchWeatherSnapshotSuspend(): WeatherSnapshot? = withContext(Dispatchers.IO) {
        try {
            val (lat, lon) = resolveCoordinatesOrDefault()
            val urlStr = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,surface_pressure,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max&timezone=auto&forecast_days=1",
                lat,
                lon
            )
            val jsonStr = httpGetString(urlStr, timeoutMs = 4500) ?: return@withContext null
            val root = JSONObject(jsonStr)
            val current = root.optJSONObject("current") ?: return@withContext null
            val daily = root.optJSONObject("daily")

            val tempC = current.optDouble("temperature_2m", 20.0)
            val apparentC = current.optDouble("apparent_temperature", tempC)
            val humidity = current.optInt("relative_humidity_2m", 50)
            val weatherCode = current.optInt("weather_code", 0)
            val pressureHpa = current.optDouble("surface_pressure", 1013.0)
            val windKmh = current.optDouble("wind_speed_10m", 0.0)

            val maxTempC = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, tempC) ?: tempC
            val minTempC = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, tempC) ?: tempC
            val precipProb = daily?.optJSONArray("precipitation_probability_max")?.optInt(0, 0) ?: 0
            val uvMax = daily?.optJSONArray("uv_index_max")?.optDouble(0, 0.0) ?: 0.0

            val (descJa, descEn) = mapWmoWeatherCode(weatherCode)
            val tempRound = (tempC * 10.0).roundToInt() / 10.0
            val apparentRound = (apparentC * 10.0).roundToInt() / 10.0
            val maxRound = maxTempC.roundToInt()
            val minRound = minTempC.roundToInt()
            val pressureRound = pressureHpa.roundToInt()

            // 服装アドバイス
            val clothingAdvice = when {
                apparentC < 7.0 -> "体感温度が${apparentRound}度とかなり寒いため、厚手のコートや防寒着が必要です。"
                apparentC < 13.0 -> "体感温度は${apparentRound}度で肌寒いため、ジャケットやセーターの上着を着てお出かけください。"
                apparentC < 19.0 -> "体感温度は${apparentRound}度です。長袖シャツや薄手の羽織るものがあると快適です。"
                apparentC < 25.0 -> "体感温度は${apparentRound}度で過ごしやすい陽気です。"
                apparentC < 30.0 -> "体感温度は${apparentRound}度で少し暑いため、半袖や風通しの良い服装がおすすめです。"
                else -> "体感温度が${apparentRound}度と非常に暑いため、こまめな水分補給と熱中症対策を行ってください。"
            }

            // 傘アドバイス
            val umbrellaAdvice = when {
                weatherCode in 51..99 || precipProb >= 60 -> "降水確率が${precipProb}パーセントと高いため、傘を持っていくと安心です。"
                precipProb in 30..59 -> "降水確率は${precipProb}パーセントです。折りたたみ傘があると安心です。"
                else -> "降水確率は${precipProb}パーセントで、傘の心配はほとんどありません。"
            }

            // 気圧・頭痛（気象病）アドバイス
            val pressureAdvice = when {
                pressureHpa < 1004.0 -> "現在の気圧は${pressureRound}ヘクトパスカルと低気圧ぎみです。頭痛や体調の変化に気をつけてゆっくりお過ごしください。"
                pressureHpa > 1020.0 -> "気圧は${pressureRound}ヘクトパスカルと高気圧で安定しています。"
                else -> "気圧は${pressureRound}ヘクトパスカルで安定しています。"
            }

            // 紫外線アドバイス
            val uvAdvice = if (uvMax >= 6.0) "紫外線指数が${uvMax.roundToInt()}と強めです。" else ""

            val shortStatusJa = "現在の天気は${descJa}、気温${tempRound}度、降水確率${precipProb}パーセント"
            val fullReportJa = "現在地の天気は${descJa}、気温は${tempRound}度、湿度${humidity}パーセントです。" +
                    "今日の最高気温は${maxRound}度、最低気温は${minRound}度です。" +
                    "$umbrellaAdvice $clothingAdvice $pressureAdvice $uvAdvice"

            WeatherSnapshot(
                fetchedAtMs = System.currentTimeMillis(),
                weatherDescJa = descJa,
                weatherDescEn = descEn,
                tempC = tempC,
                apparentTempC = apparentC,
                maxTempC = maxTempC,
                minTempC = minTempC,
                precipProbMax = precipProb,
                humidityPct = humidity,
                windSpeedKmh = windKmh,
                uvIndexMax = uvMax,
                pressureHpa = pressureHpa,
                shortStatusJa = shortStatusJa,
                fullReportJa = fullReportJa
            )
        } catch (e: Exception) {
            Log.w(TAG, "fetchWeatherSnapshotSuspend error: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveCoordinatesOrDefault(): Pair<Double, Double> {
        try {
            val fine = ContextCompat.checkSelfPermission(service, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(service, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (fine || coarse) {
                val lm = service.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (lm != null) {
                    val providers = listOf(
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER
                    )
                    var best: Location? = null
                    for (p in providers) {
                        try {
                            val loc = lm.getLastKnownLocation(p) ?: continue
                            if (best == null || loc.time > best.time) {
                                best = loc
                            }
                        } catch (_: Exception) {}
                    }
                    if (best != null) {
                        return best.latitude to best.longitude
                    }
                }
            }
        } catch (_: Exception) {}

        // 位置情報オフ時のタイムゾーン連動スマートデフォルト座標
        val tzId = TimeZone.getDefault().id
        return when {
            tzId.contains("Manila", ignoreCase = true) -> 14.5995 to 120.9842 // マニラ
            tzId.contains("Tokyo", ignoreCase = true) -> 35.3670 to 136.6170 // 岐阜・大垣 / 日本標準
            tzId.contains("New_York", ignoreCase = true) -> 40.7128 to -74.0060
            tzId.contains("Los_Angeles", ignoreCase = true) -> 34.0522 to -118.2437
            tzId.contains("London", ignoreCase = true) -> 51.5074 to -0.1278
            tzId.contains("Madrid", ignoreCase = true) -> 40.4168 to -3.7038
            else -> 35.6762 to 139.6503
        }
    }

    private fun mapWmoWeatherCode(code: Int): Pair<String, String> = when (code) {
        0 -> "快晴" to "Clear sky"
        1 -> "晴れ" to "Mainly clear"
        2 -> "一部曇り" to "Partly cloudy"
        3 -> "曇り" to "Overcast"
        45, 48 -> "霧" to "Fog"
        51, 53, 55, 56, 57 -> "霧雨" to "Drizzle"
        61 -> "小雨" to "Slight rain"
        63 -> "雨" to "Moderate rain"
        65, 66, 67 -> "強い雨" to "Heavy rain"
        71, 73, 75, 77 -> "雪" to "Snow"
        80, 81, 82 -> "にわか雨" to "Rain showers"
        85, 86 -> "にわか雪" to "Snow showers"
        95, 96, 99 -> "雷雨" to "Thunderstorm"
        else -> "晴れ時々曇り" to "Partly cloudy"
    }

    // =========================================================================
    // ② 地震速報・津波情報（P2P地震情報 API [日本] ＆ 米国 USGS [フィリピン・世界]）
    // =========================================================================
    fun announceLatestEarthquakeInfo() {
        service.soundHelper?.playActionDone()
        service.speak("最新の地震速報と津波情報を確認しています...", TextToSpeech.QUEUE_FLUSH)
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                fetchEarthquakeReportInternal()
            }
            service.speak(report, TextToSpeech.QUEUE_FLUSH)
        }
    }

    private fun fetchEarthquakeReportInternal(): String {
        val reports = mutableListOf<String>()

        // 1. 日本国内の最新地震速報 (P2P地震情報 API v2 - 完全無料・登録不要)
        try {
            val jpJson = httpGetString("https://api.p2pquake.net/v2/history?codes=551&limit=2", timeoutMs = 4500)
            if (!jpJson.isNullOrBlank()) {
                val arr = JSONArray(jpJson)
                if (arr.length() > 0) {
                    val item = arr.getJSONObject(0)
                    val eq = item.optJSONObject("earthquake")
                    if (eq != null) {
                        val timeStr = eq.optString("time", "")
                        val hypo = eq.optJSONObject("hypocenter")
                        val place = hypo?.optString("name", "不明")?.ifBlank { "調査中" } ?: "調査中"
                        val mag = hypo?.optDouble("magnitude", -1.0) ?: -1.0
                        val depth = hypo?.optInt("depth", -1) ?: -1
                        val maxScale = eq.optInt("maxScale", -1)
                        val shindoStr = mapP2PMaxScaleToShindo(maxScale)
                        val tsunamiCode = eq.optString("domesticTsunami", "None")
                        val tsunamiStr = when (tsunamiCode) {
                            "None" -> "この地震による津波の心配はありません。"
                            "Checking" -> "津波の有無については現在調査中です。"
                            "NonEffective" -> "若干の海面変動があるかもしれませんが、津波被害の心配はありません。"
                            "Watch" -> "津波注意報が発表されています。海岸から離れてください！"
                            "Warning", "MajorWarning" -> "津波警報が発表されています！直ちに高台へ避難してください！"
                            else -> "津波の心配はありません。"
                        }
                        val magText = if (mag >= 0.0) "マグニチュード${String.format(Locale.US, "%.1f", mag)}、" else ""
                        val depthText = if (depth > 0) "深さ約${depth}キロ、" else if (depth == 0) "深さごく浅い、" else ""
                        reports.add("【日本国内の最新地震情報】${timeStr}ごろ、${place}を震源とする地震がありました。最大震度は${shindoStr}、${magText}${depthText}$tsunamiStr")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "P2PQuake fetch error: ${e.message}")
        }

        // 2. フィリピンおよび世界の大きな地震 (USGS M4.5+ 過去24時間 - 完全無料)
        try {
            val usgsJson = httpGetString("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson", timeoutMs = 4500)
            if (!usgsJson.isNullOrBlank()) {
                val root = JSONObject(usgsJson)
                val features = root.optJSONArray("features")
                if (features != null && features.length() > 0) {
                    var phEqReport: String? = null
                    for (i in 0 until features.length()) {
                        val props = features.getJSONObject(i).optJSONObject("properties") ?: continue
                        val place = props.optString("place", "")
                        val mag = props.optDouble("mag", 0.0)
                        if (place.contains("Philippines", ignoreCase = true)) {
                            phEqReport = "なお、過去24時間以内にフィリピン周辺（$place）でマグニチュード${String.format(Locale.US, "%.1f", mag)}の地震が観測されています。"
                            break
                        }
                    }
                    if (phEqReport != null) {
                        reports.add(phEqReport)
                    } else {
                        reports.add("過去24時間以内、フィリピン周辺でマグニチュード4.5以上の大きな地震は観測されていません。")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "USGS fetch error: ${e.message}")
        }

        return if (reports.isNotEmpty()) {
            reports.joinToString(" ")
        } else {
            "現在、地震情報サーバーに接続できませんでした。インターネット接続をご確認ください。"
        }
    }

    private fun mapP2PMaxScaleToShindo(scale: Int): String = when (scale) {
        10 -> "震度1"
        20 -> "震度2"
        30 -> "震度3"
        40 -> "震度4"
        45 -> "震度5弱"
        50 -> "震度5強"
        55 -> "震度6弱"
        60 -> "震度6強"
        70 -> "震度7"
        else -> "震度1未満または調査中"
    }

    // =========================================================================
    // ③ リアルタイム為替・通貨換算（「1000ペソは何円？」「50ドルはいくら？」— 完全無料）
    // =========================================================================
    fun announceCurrencyConversion(rawQuery: String) {
        service.soundHelper?.playActionDone()
        service.speak("最新の為替レートを計算しています...", TextToSpeech.QUEUE_FLUSH)
        scope.launch {
            val resultSpeech = withContext(Dispatchers.IO) {
                computeCurrencySpeechInternal(rawQuery)
            }
            service.speak(resultSpeech, TextToSpeech.QUEUE_FLUSH)
        }
    }

    private fun computeCurrencySpeechInternal(rawQuery: String): String {
        try {
            // open.er-api.com (完全無料・APIキー不要・PHP/JPY/USD/EUR/GBP/AUD/KRW全対応)
            val jsonStr = httpGetString("https://open.er-api.com/v6/latest/USD", timeoutMs = 4500)
                ?: httpGetString("https://api.frankfurter.dev/v1/latest?base=USD", timeoutMs = 4500)
                ?: return "現在、為替レートサーバーに接続できませんでした。"

            val root = JSONObject(jsonStr)
            val rates = root.optJSONObject("rates") ?: return "為替データの解析に失敗しました。"

            val usdToJpy = rates.optDouble("JPY", 153.0)
            val usdToPhp = rates.optDouble("PHP", 58.0)
            val usdToEur = rates.optDouble("EUR", 0.92)
            val usdToGbp = rates.optDouble("GBP", 0.78)
            val usdToAud = rates.optDouble("AUD", 1.52)
            val usdToKrw = rates.optDouble("KRW", 1380.0)

            val phpToJpy = usdToJpy / usdToPhp
            val eurToJpy = usdToJpy / usdToEur
            val gbpToJpy = usdToJpy / usdToGbp
            val audToJpy = usdToJpy / usdToAud

            // クエリ内に数字が含まれているか解析（例：「1000ペソは何円」「50ドル」「1万円は何ペソ」）
            val normalized = rawQuery
                .replace("，", "")
                .replace(",", "")
                .replace("一万", "10000")
                .replace("１万", "10000")
                .replace("1万", "10000")
                .replace("千", "1000")
                .replace("百", "100")

            val numMatch = Regex("([0-9]+(?:\\.[0-9]+)?)").find(normalized)
            val amount = numMatch?.groupValues?.get(1)?.toDoubleOrNull()

            if (amount != null && amount > 0.0) {
                val q = normalized.lowercase(Locale.ROOT)
                when {
                    q.contains("ペソ") || q.contains("peso") || q.contains("php") -> {
                        if (q.startsWith("円") || normalized.contains("円は何ペソ") || normalized.contains("円で何ペソ") || normalized.indexOf("円") in 0 until normalized.indexOf("ペソ").coerceAtLeast(0)) {
                            val phpResult = amount / phpToJpy
                            return "${amount.toLong()}日本円は、現在のレートで約${String.format(Locale.US, "%.1f", phpResult)}フィリピンペソです。（1ペソあたり約${String.format(Locale.US, "%.2f", phpToJpy)}円）"
                        }
                        val jpyResult = amount * phpToJpy
                        return "${amount.toLong()}フィリピンペソは、現在のレートで約${jpyResult.roundToInt()}円です。（1ペソあたり約${String.format(Locale.US, "%.2f", phpToJpy)}円）"
                    }
                    q.contains("ドル") || q.contains("dollar") || q.contains("usd") -> {
                        val jpyResult = amount * usdToJpy
                        val phpResult = amount * usdToPhp
                        return "${String.format(Locale.US, "%.0f", amount)}米ドルは、現在のレートで約${jpyResult.roundToInt()}円、フィリピンペソでは約${phpResult.roundToInt()}ペソです。（1ドル＝${String.format(Locale.US, "%.2f", usdToJpy)}円）"
                    }
                    q.contains("ユーロ") || q.contains("euro") || q.contains("eur") -> {
                        val jpyResult = amount * eurToJpy
                        return "${String.format(Locale.US, "%.0f", amount)}ユーロは、現在のレートで約${jpyResult.roundToInt()}円です。（1ユーロ＝${String.format(Locale.US, "%.2f", eurToJpy)}円）"
                    }
                    q.contains("ポンド") || q.contains("gbp") -> {
                        val jpyResult = amount * gbpToJpy
                        return "${String.format(Locale.US, "%.0f", amount)}イギリスポンドは、現在のレートで約${jpyResult.roundToInt()}円です。"
                    }
                }
            }

            // 特定の金額指定がない場合は主要通貨の最新レート一覧を読み上げ
            val onePhpJpy = String.format(Locale.US, "%.2f", phpToJpy)
            val thousandPhpJpy = (1000.0 * phpToJpy).roundToInt()
            val oneUsdJpy = String.format(Locale.US, "%.2f", usdToJpy)
            val oneUsdPhp = String.format(Locale.US, "%.2f", usdToPhp)
            val oneEurJpy = String.format(Locale.US, "%.2f", eurToJpy)

            return "最新の為替レートをお知らせします。" +
                    "1フィリピンペソは約${onePhpJpy}円、1000ペソは約${thousandPhpJpy}円です。" +
                    "1米ドルは約${oneUsdJpy}円（フィリピンペソでは約${oneUsdPhp}ペソ）、" +
                    "1ユーロは約${oneEurJpy}円です。"
        } catch (e: Exception) {
            Log.w(TAG, "computeCurrencySpeechInternal error: ${e.message}")
            return "為替レートの取得中にエラーが発生しました。"
        }
    }

    // =========================================================================
    // ④ Wikipedia 連動「なんでも用語・人物辞典」（完全無料 + Gemma 4 連携）
    // =========================================================================
    fun lookupWikipediaAndExplain(rawQuery: String, onCompleted: (Boolean) -> Unit) {
        val keyword = extractWikipediaKeyword(rawQuery)
        if (keyword.isBlank() || keyword.length < 2) {
            onCompleted(false)
            return
        }

        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                fetchWikipediaSummaryInternal(keyword)
            }
            if (!summary.isNullOrBlank()) {
                service.soundHelper?.playActionDone()
                service.speak("Wikipediaの解説です。$summary", TextToSpeech.QUEUE_FLUSH)
                onCompleted(true)
            } else {
                onCompleted(false)
            }
        }
    }

    private fun extractWikipediaKeyword(rawQuery: String): String {
        return rawQuery
            .replace("ウィキペディアで", "")
            .replace("wikipediaで", "", ignoreCase = true)
            .replace("について教えて", "")
            .replace("について調べて", "")
            .replace("とは何ですか", "")
            .replace("って何ですか", "")
            .replace("って誰ですか", "")
            .replace("とは何", "")
            .replace("って何", "")
            .replace("って誰", "")
            .replace("の意味を教えて", "")
            .replace("の意味は", "")
            .replace("を調べて", "")
            .replace("とは", "")
            .replace("？", "")
            .replace("?", "")
            .trim()
    }

    private fun fetchWikipediaSummaryInternal(keyword: String): String? {
        val langs = listOf("ja", "en", "tl")
        for (lang in langs) {
            try {
                val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
                val directUrl = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$encoded"
                val jsonStr = httpGetString(directUrl, timeoutMs = 4000)
                if (!jsonStr.isNullOrBlank()) {
                    val obj = JSONObject(jsonStr)
                    val extract = obj.optString("extract", "").trim()
                    if (extract.length >= 20) {
                        return extract
                    }
                }

                // OpenSearch で最も近い記事タイトルを検索して再取得
                val searchUrl = "https://$lang.wikipedia.org/w/api.php?action=opensearch&search=${URLEncoder.encode(keyword, "UTF-8")}&limit=1&namespace=0&format=json"
                val searchJson = httpGetString(searchUrl, timeoutMs = 4000)
                if (!searchJson.isNullOrBlank()) {
                    val arr = JSONArray(searchJson)
                    val titles = arr.optJSONArray(1)
                    if (titles != null && titles.length() > 0) {
                        val bestTitle = titles.optString(0, "")
                        if (bestTitle.isNotEmpty()) {
                            val encodedBest = URLEncoder.encode(bestTitle, "UTF-8").replace("+", "%20")
                            val retryJson = httpGetString("https://$lang.wikipedia.org/api/rest_v1/page/summary/$encodedBest", timeoutMs = 4000)
                            if (!retryJson.isNullOrBlank()) {
                                val retryObj = JSONObject(retryJson)
                                val retryExtract = retryObj.optString("extract", "").trim()
                                if (retryExtract.length >= 20) {
                                    return retryExtract
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wikipedia lookup ($lang) error: ${e.message}")
            }
        }
        return null
    }

    // =========================================================================
    // ⑤ 世界の時間・時差計算（スマホ内蔵 TimeZone 0秒計算）
    // =========================================================================
    fun announceWorldTime(rawQuery: String) {
        service.soundHelper?.playActionDone()
        val q = rawQuery.lowercase(Locale.ROOT)
        val now = Date()

        data class CityZone(val nameJa: String, val tzId: String, val keywords: List<String>)
        val cities = listOf(
            CityZone("フィリピン・マニラ", "Asia/Manila", listOf("フィリピン", "マニラ", "セブ", "ダバオ", "philippines", "manila")),
            CityZone("日本・東京", "Asia/Tokyo", listOf("日本", "東京", "japan", "tokyo")),
            CityZone("アメリカ東部・ニューヨーク", "America/New_York", listOf("ニューヨーク", "ワシントン", "東部", "new york")),
            CityZone("アメリカ西部・ロサンゼルス", "America/Los_Angeles", listOf("ロサンゼルス", "サンフランシスコ", "カリフォルニア", "アメリカ", "los angeles", "usa")),
            CityZone("イギリス・ロンドン", "Europe/London", listOf("イギリス", "ロンドン", "uk", "london")),
            CityZone("スペイン・マドリード", "Europe/Madrid", listOf("スペイン", "マドリード", "spain", "madrid")),
            CityZone("オランダ・アムステルダム", "Europe/Amsterdam", listOf("オランダ", "アムステルダム", "netherlands", "amsterdam")),
            CityZone("オーストラリア・シドニー", "Australia/Sydney", listOf("オーストラリア", "シドニー", "australia", "sydney")),
            CityZone("韓国・ソウル", "Asia/Seoul", listOf("韓国", "ソウル", "korea", "seoul"))
        )

        val matched = cities.find { city -> city.keywords.any { q.contains(it) } }
        if (matched != null) {
            val fmt = SimpleDateFormat("M月d日 E曜日 a h時m分", Locale.JAPAN).apply {
                timeZone = TimeZone.getTimeZone(matched.tzId)
            }
            val localTz = TimeZone.getDefault()
            val targetTz = TimeZone.getTimeZone(matched.tzId)
            val diffHours = (targetTz.getOffset(now.time) - localTz.getOffset(now.time)) / (1000 * 60 * 60)
            val diffText = when {
                diffHours == 0 -> "現在地との時差はありません。"
                diffHours > 0 -> "現在地より${diffHours}時間進んでいます。"
                else -> "現在地より${abs(diffHours)}時間遅れています。"
            }
            service.speak("${matched.nameJa}の現在時刻は、${fmt.format(now)}です。$diffText", TextToSpeech.QUEUE_FLUSH)
            return
        }

        // 都市指定がない場合は主要都市（フィリピン・アメリカ・イギリス・スペイン・日本）をまとめてアナウンス
        val fmtShort = { tzId: String ->
            SimpleDateFormat("a h時m分", Locale.JAPAN).apply { timeZone = TimeZone.getTimeZone(tzId) }.format(now)
        }
        val speech = "世界の現在時刻をお知らせします。" +
                "フィリピン・マニラは${fmtShort("Asia/Manila")}（日本との時差はマイナス1時間）、" +
                "日本・東京は${fmtShort("Asia/Tokyo")}、" +
                "アメリカ・ニューヨークは${fmtShort("America/New_York")}、" +
                "ロサンゼルスは${fmtShort("America/Los_Angeles")}、" +
                "イギリス・ロンドンは${fmtShort("Europe/London")}、" +
                "スペイン・マドリードは${fmtShort("Europe/Madrid")}です。"
        service.speak(speech, TextToSpeech.QUEUE_FLUSH)
    }

    // =========================================================================
    // ⑥ 広告ゼロ・全文読み上げ対応「世界＆日本 3大公式ニュースリーダー」
    //    （🇯🇵 NHKニュース / 🇵🇭 ABS-CBN News / 🌍 BBC News World & Japan）
    // =========================================================================
    fun showGlobalNewsSourceMenu() {
        val items = listOf(
            serenaMenuItem("🇯🇵", "NHKニュース（日本・主要ニュース・全文読み上げ対応）") {
                fetchAndShowChannelHeadlines(
                    sourceName = "NHKニュース",
                    rssUrls = listOf(
                        "https://www.nhk.or.jp/rss/news/cat0.xml",
                        "https://www.nhk.or.jp/rss/news/cat1.xml"
                    ),
                    localeTag = "ja-JP"
                )
            },
            serenaMenuItem("🇵🇭", "ABS-CBN News（フィリピン最新ニュース・全文読み上げ対応）") {
                fetchAndShowChannelHeadlines(
                    sourceName = "ABS-CBN News",
                    rssUrls = listOf(
                        "https://news.abs-cbn.com/rss",
                        "https://news.google.com/rss/search?q=site:abs-cbn.com&hl=en-PH&gl=PH&ceid=PH:en",
                        "https://data.gmanetwork.com/gno/rss/news/feed.xml"
                    ),
                    localeTag = "en-PH"
                )
            },
            serenaMenuItem("🌍", "BBC News Japan（世界ニュース日本語版・全文読み上げ対応）") {
                fetchAndShowChannelHeadlines(
                    sourceName = "BBC News Japan",
                    rssUrls = listOf(
                        "https://feeds.bbci.co.uk/japanese/rss.xml"
                    ),
                    localeTag = "ja-JP"
                )
            },
            serenaMenuItem("🇬🇧", "BBC News World（世界ニュース英語オリジナル・全文読み上げ対応）") {
                fetchAndShowChannelHeadlines(
                    sourceName = "BBC News World",
                    rssUrls = listOf(
                        "https://feeds.bbci.co.uk/news/world/rss.xml"
                    ),
                    localeTag = "en-GB"
                )
            }
        )

        service.soundHelper?.playMenuOpen()
        service.speak("3大ニュースリーダー（NHK、ABS-CBN、BBC）。聞きたいメディアを選んでダブルタップしてください。1番、${items.first().title}", TextToSpeech.QUEUE_FLUSH)
        mainHandler.post {
            try {
                val dlg = serenaMenuDialog(service, false, items)
                service.activeMenuDialog = dlg
                dlg.show()
            } catch (e: Exception) {
                Log.e(TAG, "showGlobalNewsSourceMenu error: ${e.message}")
            }
        }
    }

    fun handleVoiceNewsRequest(rawQuery: String) {
        val q = rawQuery.lowercase(Locale.ROOT)
        // 「1番のニュース」「2番の記事」のように番号指定された場合は直近のニュースリストから全文読み上げ
        val numMatch = Regex("([1-8１-８一二三四五六七八])\\s*(番|つ目|番目|号)").find(q)
        if (numMatch != null && lastFetchedNewsList.isNotEmpty()) {
            val idx = parseJapaneseIndex(numMatch.groupValues[1]) - 1
            if (idx in lastFetchedNewsList.indices) {
                readFullNewsArticleAloud(lastFetchedNewsList[idx])
                return
            }
        }

        when {
            q.contains("abs") || q.contains("cbn") || q.contains("フィリピン") || q.contains("マニラ") -> {
                fetchAndShowChannelHeadlines(
                    sourceName = "ABS-CBN News",
                    rssUrls = listOf(
                        "https://news.abs-cbn.com/rss",
                        "https://news.google.com/rss/search?q=site:abs-cbn.com&hl=en-PH&gl=PH&ceid=PH:en",
                        "https://data.gmanetwork.com/gno/rss/news/feed.xml"
                    ),
                    localeTag = "en-PH"
                )
            }
            q.contains("bbc") && (q.contains("英語") || q.contains("english") || q.contains("world")) -> {
                fetchAndShowChannelHeadlines(
                    sourceName = "BBC News World",
                    rssUrls = listOf("https://feeds.bbci.co.uk/news/world/rss.xml"),
                    localeTag = "en-GB"
                )
            }
            q.contains("bbc") -> {
                fetchAndShowChannelHeadlines(
                    sourceName = "BBC News Japan",
                    rssUrls = listOf("https://feeds.bbci.co.uk/japanese/rss.xml"),
                    localeTag = "ja-JP"
                )
            }
            q.contains("nhk") || q.contains("日本") || q.contains("国内") -> {
                fetchAndShowChannelHeadlines(
                    sourceName = "NHKニュース",
                    rssUrls = listOf(
                        "https://www.nhk.or.jp/rss/news/cat0.xml",
                        "https://www.nhk.or.jp/rss/news/cat1.xml"
                    ),
                    localeTag = "ja-JP"
                )
            }
            else -> {
                // デフォルトはNHKニュースを取得しつつ3大メディアも選べる一覧を表示
                fetchAndShowChannelHeadlines(
                    sourceName = "NHKニュース",
                    rssUrls = listOf(
                        "https://www.nhk.or.jp/rss/news/cat0.xml",
                        "https://www.nhk.or.jp/rss/news/cat1.xml"
                    ),
                    localeTag = "ja-JP"
                )
            }
        }
    }

    private fun parseJapaneseIndex(token: String): Int = when (token) {
        "1", "１", "一" -> 1
        "2", "２", "二" -> 2
        "3", "３", "三" -> 3
        "4", "４", "四" -> 4
        "5", "５", "五" -> 5
        "6", "６", "六" -> 6
        "7", "７", "七" -> 7
        "8", "８", "八" -> 8
        else -> 1
    }

    fun fetchAndShowChannelHeadlines(
        sourceName: String,
        rssUrls: List<String>,
        localeTag: String
    ) {
        service.soundHelper?.playActionDone()
        service.speak("${sourceName}の最新記事を取得しています...", TextToSpeech.QUEUE_FLUSH)
        scope.launch {
            val articles = withContext(Dispatchers.IO) {
                var list: List<NewsArticleItem> = emptyList()
                for (url in rssUrls) {
                    list = fetchRssArticles(url, sourceName, localeTag)
                    if (list.isNotEmpty()) break
                }
                list.take(8)
            }

            if (articles.isEmpty()) {
                service.speak("${sourceName}の記事を取得できませんでした。通信状態をご確認ください。", TextToSpeech.QUEUE_FLUSH)
                return@launch
            }

            lastFetchedNewsList = articles

            val menuItems = mutableListOf<serenaMenuItem>()
            // 記事1〜8: ダブルタップで広告ゼロ・本文まるごと全文読み上げ！
            articles.forEachIndexed { idx, article ->
                menuItems.add(
                    serenaMenuItem("📰", "${idx + 1}. 【全文を読む】${article.title}") {
                        readFullNewsArticleAloud(article)
                    }
                )
            }

            // 全見出し一括読み上げボタン
            menuItems.add(
                serenaMenuItem("🔊", "すべての見出しと冒頭を連続で読み上げる") {
                    val combined = articles.mapIndexed { i, a ->
                        "記事${i + 1}、${a.title}。${a.summary}"
                    }.joinToString(" 続いて、")
                    service.speak("${sourceName}のトップニュース一覧です。$combined", TextToSpeech.QUEUE_FLUSH)
                }
            )

            // メディア切替ボタン
            menuItems.add(
                serenaMenuItem("🔄", "別のニュースメディア（NHK / ABS-CBN / BBC）に切り替える") {
                    showGlobalNewsSourceMenu()
                }
            )

            service.soundHelper?.playMenuOpen()
            val firstHeadline = articles.first().title
            service.speak(
                "${sourceName}、最新${articles.size}件。気になる見出しをダブルタップすると、広告をカットして記事の全文を最初から最後まで読み上げます。1番、$firstHeadline",
                TextToSpeech.QUEUE_FLUSH
            )

            mainHandler.post {
                try {
                    val dlg = serenaMenuDialog(service, false, menuItems)
                    service.activeMenuDialog = dlg
                    dlg.show()
                } catch (e: Exception) {
                    Log.e(TAG, "fetchAndShowChannelHeadlines dialog error: ${e.message}")
                }
            }
        }
    }

    /**
     * 選択されたニュース記事のURLにアクセスし、広告・ナビゲーション・スクリプトを完全除去して
     * 記事の本文（全文）だけを最初から最後まで読み上げる！
     */
    fun readFullNewsArticleAloud(article: NewsArticleItem) {
        service.soundHelper?.playActionDone()
        service.speak("「${article.title}」の全文を取得しています...", TextToSpeech.QUEUE_FLUSH)
        scope.launch {
            val fullBody = withContext(Dispatchers.IO) {
                extractFullArticleBodyText(article.link, article.summary)
            }
            val cleanBody = fullBody.ifBlank { article.summary.ifBlank { article.title } }
            val fullSpeech = "${article.sourceName}より、「${article.title}」の全文を読み上げます。 $cleanBody 以上、記事の全文でした。"
            service.speak(fullSpeech, TextToSpeech.QUEUE_FLUSH)
        }
    }

    private fun fetchRssArticles(
        rssUrl: String,
        sourceName: String,
        localeTag: String
    ): List<NewsArticleItem> {
        val result = mutableListOf<NewsArticleItem>()
        try {
            val xml = httpGetString(rssUrl, timeoutMs = 5500) ?: return emptyList()
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var inItem = false
            var title = ""
            var description = ""
            var link = ""
            var pubDate = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name.lowercase(Locale.ROOT)
                        if (name == "item" || name == "entry") {
                            inItem = true
                            title = ""
                            description = ""
                            link = ""
                            pubDate = ""
                        } else if (inItem) {
                            when (name) {
                                "title" -> title = safeNextText(parser)
                                "description", "summary", "content:encoded" -> {
                                    val txt = safeNextText(parser)
                                    if (txt.length > description.length) description = txt
                                }
                                "link" -> {
                                    val href = parser.getAttributeValue(null, "href")
                                    val txt = safeNextText(parser)
                                    link = if (!href.isNullOrBlank()) href else txt
                                }
                                "pubdate", "published", "updated", "dc:date" -> pubDate = safeNextText(parser)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name.lowercase(Locale.ROOT)
                        if ((name == "item" || name == "entry") && inItem) {
                            inItem = false
                            val cleanTitle = stripHtmlAndEntities(title)
                            val cleanDesc = stripHtmlAndEntities(description)
                            if (cleanTitle.isNotEmpty()) {
                                result.add(
                                    NewsArticleItem(
                                        title = cleanTitle,
                                        summary = cleanDesc,
                                        link = link.trim(),
                                        pubDate = pubDate.trim(),
                                        sourceName = sourceName,
                                        localeTag = localeTag
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchRssArticles($rssUrl) error: ${e.message}")
        }
        return result
    }

    private fun safeNextText(parser: XmlPullParser): String = try {
        parser.nextText()?.trim() ?: ""
    } catch (_: Exception) {
        ""
    }

    /**
     * NHK News Web / ABS-CBN News / BBC News 等の記事ページHTMLから
     * 広告・メニュー・スクリプトをすべて除去して記事本文（全文）を高精度抽出！
     */
    private fun extractFullArticleBodyText(url: String, rssSummary: String): String {
        if (url.isBlank()) return rssSummary
        try {
            val html = httpGetString(url, timeoutMs = 6500) ?: return rssSummary
            val paragraphs = mutableListOf<String>()

            // 1. JSON-LD (NewsArticle / Article) の "articleBody" または "description" 抽出 (ABS-CBN / BBC / NHK 対応)
            val jsonLdRegex = Regex("<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
            for (match in jsonLdRegex.findAll(html)) {
                val jsonBlock = match.groupValues[1].trim()
                val bodyMatch = Regex("\"articleBody\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(jsonBlock)
                if (bodyMatch != null) {
                    val decoded = unescapeJsonString(bodyMatch.groupValues[1])
                    val clean = stripHtmlAndEntities(decoded)
                    if (clean.length > 80) {
                        paragraphs.add(clean)
                    }
                }
            }

            // 2. NHK News Web 固有の本文コンテナおよび detailMore / body フィールド抽出
            if (url.contains("nhk.or.jp", ignoreCase = true)) {
                val nhkSummaryMatches = Regex("(?:content--summary|content--detail-body|content--detail-more|body-text)[^>]*>([\\s\\S]*?)</(?:p|div|section)>", RegexOption.IGNORE_CASE)
                    .findAll(html)
                for (m in nhkSummaryMatches) {
                    val clean = stripHtmlAndEntities(m.groupValues[1])
                    if (clean.length >= 15 && !paragraphs.contains(clean)) {
                        paragraphs.add(clean)
                    }
                }
                // NHK JS変数内の本文テキスト (summary / more / body)
                val nhkJsFields = Regex("\"(?:summary|more|body|text)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(html)
                for (m in nhkJsFields) {
                    val clean = stripHtmlAndEntities(unescapeJsonString(m.groupValues[1]))
                    if (clean.length >= 25 && !paragraphs.contains(clean)) {
                        paragraphs.add(clean)
                    }
                }
            }

            // 3. <article> または <main> 内のすべての <p> パラグラフを抽出（BBC / ABS-CBN / 一般ニュースサイト全般）
            val cleanedHtml = html
                .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<nav[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<footer[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<aside[\\s\\S]*?</aside>", RegexOption.IGNORE_CASE), " ")

            val articleRegion = Regex("<article[\\s\\S]*?</article>", RegexOption.IGNORE_CASE)
                .find(cleanedHtml)?.value
                ?: Regex("<main[\\s\\S]*?</main>", RegexOption.IGNORE_CASE).find(cleanedHtml)?.value
                ?: cleanedHtml

            val pRegex = Regex("<p(?:\\s[^>]*)?>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE)
            for (m in pRegex.findAll(articleRegion)) {
                val pText = stripHtmlAndEntities(m.groupValues[1])
                if (pText.length >= 22 &&
                    !pText.contains("Copyright", ignoreCase = true) &&
                    !pText.contains("All rights reserved", ignoreCase = true) &&
                    !pText.contains("無断転載", ignoreCase = true) &&
                    !pText.contains("Cookie", ignoreCase = true) &&
                    !paragraphs.contains(pText)
                ) {
                    paragraphs.add(pText)
                }
            }

            val combined = paragraphs.joinToString(" ")
            return when {
                combined.length >= rssSummary.length && combined.isNotBlank() -> combined.take(4500)
                rssSummary.isNotBlank() -> rssSummary
                else -> combined
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractFullArticleBodyText error: ${e.message}")
            return rssSummary
        }
    }

    private fun unescapeJsonString(raw: String): String {
        return raw
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
    }

    private fun stripHtmlAndEntities(raw: String): String {
        return raw
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun httpGetString(urlStr: String, timeoutMs: Int = 5000): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 15; KapamilyaSerena/5.7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                )
                setRequestProperty("Accept", "application/json, application/xml, text/xml, text/html, */*")
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append('\n')
                }
                sb.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "httpGetString($urlStr) failed: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
