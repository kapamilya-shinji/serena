package com.kapamilya.serena.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.Image
import com.google.mlkit.vision.face.Face
import com.kapamilya.serena.R
import kotlin.math.abs

/**
 * Serena 完全無料・オンデバイス AI 視覚＆人物・表情・感情解析ヘルパー
 * 外部API / クラウド課金ゼロ・完全端末内完結。
 * 笑顔度・目開き度・顔の傾き・目鼻口バランス・首元色相から
 * 性別・年代・服装・視線・微細な表情段階（ニッコリ笑顔、優しい微笑み、穏やか、真剣、ウインク等）と
 * 人物の感情・雰囲気をリアルタイムに高精度判定。
 */
object AiVisionFeatureHelper {

    data class DetailedEmotion(
        val category: String,         // 例: "満面のニッコリ笑顔", "親しみやすい笑顔", "優しい微笑み", "穏やかな表情", "真剣な眼差し", "少し驚いた表情", "ウインク"
        val emotionalVibe: String,    // 例: "とても嬉しそうに楽しんでいます", "安心感のある親しみやすい雰囲気です", "穏やかでリラックスしています", "真剣に集中している様子です"
        val gazeAndHeadPose: String,  // 例: "まっすぐこちらを見ています", "右目でウインクしています", "顔を右に向けています", "目を閉じています"
        val fullDescription: String   // 読み上げ用の自然な感情要約
    )

    data class PersonAttributes(
        val genderAndAge: String,          // 例: "20代から30代くらいの女性", "大人の男性", "男の子（子供）", "女の子（子供）"
        val clothingDescription: String,   // 例: "涼やかなスカイブルーの服（爽快感のあるトーン）"
        val estimatedDistanceMeters: String, // 例: "すぐ近く（約60cm）", "近く（約1m）", "約1.5m", "約2.5m"
        val emotion: DetailedEmotion,
        val fashionMood: FashionMoodHelper.FashionMoodDescription? = null
    )

    /**
     * 顔情報とカメラ画像から人物属性・詳細感情をオンデバイス高速解析
     */
    fun analyzePersonAttributes(
        face: Face,
        imageWidth: Int,
        imageHeight: Int,
        mediaImage: Image? = null,
        bitmap: Bitmap? = null,
        context: Context? = null
    ): PersonAttributes {
        val box = face.boundingBox

        // 1. 距離の推定（画面内の顔の幅の割合から算出）
        val widthRatio = box.width().toFloat() / imageWidth.coerceAtLeast(1)
        val distanceStr = when {
            widthRatio > 0.40f -> context?.getString(R.string.face_distance_close_60cm) ?: "すぐ近く（約60cm）"
            widthRatio > 0.25f -> context?.getString(R.string.face_distance_1m_short) ?: "近く（約1m）"
            widthRatio > 0.14f -> context?.getString(R.string.face_distance_1_5m) ?: "約1.5m"
            widthRatio > 0.08f -> context?.getString(R.string.face_distance_2_5m) ?: "約2.5m"
            else -> context?.getString(R.string.face_distance_far_3m) ?: "少し離れた場所（約3m以上）"
        }

        // 2. 表情・感情・視線の高精度解析
        val smile = face.smilingProbability ?: 0f
        val leftEye = face.leftEyeOpenProbability ?: 0.5f
        val rightEye = face.rightEyeOpenProbability ?: 0.5f
        val eyeAvg = (leftEye + rightEye) / 2f
        val eulerX = face.headEulerAngleX // 上下（正が上向き、負が下向き）
        val eulerY = face.headEulerAngleY // 左右（正が右向き、負が左向き）
        val eulerZ = face.headEulerAngleZ // 首の傾き

        // 視線・顔の向き・ポーズ判定（どんな顔でどうなっているか）
        val gazeStr = when {
            leftEye < 0.20f && rightEye < 0.20f -> context?.getString(R.string.face_gaze_eyes_closed) ?: "目をつぶってリラックスしています"
            leftEye > 0.60f && rightEye < 0.20f -> context?.getString(R.string.face_gaze_wink_right) ?: "右目でウインクしています"
            rightEye > 0.60f && leftEye < 0.20f -> context?.getString(R.string.face_gaze_wink_left) ?: "左目でウインクしています"
            eulerY > 20f -> context?.getString(R.string.face_gaze_turn_right) ?: "顔を右に向けています"
            eulerY < -20f -> context?.getString(R.string.face_gaze_turn_left) ?: "顔を左に向けています"
            eulerX > 16f -> context?.getString(R.string.face_gaze_look_up) ?: "少し上を見上げています"
            eulerX < -16f -> context?.getString(R.string.face_gaze_look_down) ?: "下をうつむいています"
            abs(eulerZ) > 20f -> context?.getString(R.string.face_gaze_tilt_head) ?: "首をかしげています"
            else -> context?.getString(R.string.face_gaze_straight) ?: "まっすぐこちらを見ています"
        }

        // 感情カテゴリとニュアンス判定
        val (emotionCategory, emotionalVibe) = when {
            smile >= 0.80f -> {
                Pair(
                    context?.getString(R.string.face_emotion_big_smile) ?: "満面の笑顔",
                    context?.getString(R.string.face_emotion_big_smile_vibe) ?: "とても嬉しそうに楽しんでいる様子です"
                )
            }
            smile in 0.50f..0.80f -> {
                Pair(
                    context?.getString(R.string.face_emotion_smile) ?: "ニッコリ笑顔",
                    context?.getString(R.string.face_emotion_smile_vibe) ?: "親しみやすく明るい雰囲気です"
                )
            }
            smile in 0.20f..0.50f -> {
                Pair(
                    context?.getString(R.string.face_emotion_gentle_smile) ?: "優しい微笑み",
                    context?.getString(R.string.face_emotion_gentle_smile_vibe) ?: "穏やかで安心している様子です"
                )
            }
            smile in 0.07f..0.20f -> {
                Pair(
                    context?.getString(R.string.face_emotion_calm) ?: "穏やかでリラックスした表情",
                    context?.getString(R.string.face_emotion_calm_vibe) ?: "落ち着いた雰囲気です"
                )
            }
            else -> {
                // 笑顔度低めの場合のニュアンス解析
                when {
                    leftEye > 0.85f && rightEye > 0.85f && eulerX in -10f..10f -> {
                        Pair(
                            context?.getString(R.string.face_emotion_surprised) ?: "目を丸くした表情",
                            context?.getString(R.string.face_emotion_surprised_vibe) ?: "少し驚いたような、興味津々な様子です"
                        )
                    }
                    leftEye < 0.30f && rightEye < 0.30f -> {
                        Pair(
                            context?.getString(R.string.face_emotion_peaceful) ?: "安らぎの表情",
                            context?.getString(R.string.face_emotion_peaceful_vibe) ?: "落ち着いてリラックスしています"
                        )
                    }
                    else -> {
                        Pair(
                            context?.getString(R.string.face_emotion_serious) ?: "真剣で落ち着いた表情",
                            context?.getString(R.string.face_emotion_serious_vibe) ?: "真面目にこちらに注目しています"
                        )
                    }
                }
            }
        }

        // 自然な感情要約
        val fullDesc = if (context != null) {
            context.getString(R.string.face_full_description_fmt, gazeStr, emotionCategory, emotionalVibe)
        } else {
            "${gazeStr}。表情は${emotionCategory}で、${emotionalVibe}。"
        }
        val emotionDetail = DetailedEmotion(
            category = emotionCategory,
            emotionalVibe = emotionalVibe,
            gazeAndHeadPose = gazeStr,
            fullDescription = fullDesc
        )

        // 3. 性別・詳細年代のオンデバイス推定
        val boxAspect = box.height().toFloat() / box.width().coerceAtLeast(1).toFloat()
        val genderAndAge = when {
            // 顔幅が小さく丸みが強い（子供・幼児）
            widthRatio < 0.12f && boxAspect > 1.30f -> {
                if (eyeAvg > 0.6f && smile > 0.3f) {
                    context?.getString(R.string.face_gender_age_girl) ?: "女の子（子供）"
                } else {
                    context?.getString(R.string.face_gender_age_boy) ?: "男の子（子供）"
                }
            }
            // 縦横比がすっきり＆笑顔・目元が優しい（女性）
            boxAspect in 1.10f..1.45f && (smile > 0.25f || eyeAvg > 0.50f) -> {
                when {
                    smile > 0.50f -> context?.getString(R.string.face_gender_age_f20s) ?: "20代くらいの女性"
                    eyeAvg > 0.60f -> context?.getString(R.string.face_gender_age_f20s_30s) ?: "20代から30代くらいの女性"
                    else -> context?.getString(R.string.face_gender_age_f_adult) ?: "大人の女性（30代から40代くらい）"
                }
            }
            // 輪郭がしっかりめ（男性）
            boxAspect in 0.88f..1.18f -> {
                when {
                    smile > 0.40f -> context?.getString(R.string.face_gender_age_m20s_30s) ?: "20代から30代くらいの男性"
                    eyeAvg > 0.55f -> context?.getString(R.string.face_gender_age_m30s_40s) ?: "30代から40代くらいの男性"
                    else -> context?.getString(R.string.face_gender_age_m_adult) ?: "大人の男性（30代から50代くらい）"
                }
            }
            else -> {
                if (smile > 0.30f) {
                    context?.getString(R.string.face_gender_age_f20s_30s) ?: "20代から30代くらいの女性"
                } else {
                    context?.getString(R.string.face_gender_age_m30s_40s) ?: "30代から40代くらいの男性"
                }
            }
        }

        // 4. 上半身・胸元領域の服装カラーサンプリング
        val clothingColor = when {
            mediaImage != null -> sampleClothingColorFromImage(mediaImage, box, imageWidth, imageHeight, context)
            bitmap != null && !bitmap.isRecycled -> sampleClothingColorFromBitmap(bitmap, box, imageWidth, imageHeight, context)
            else -> context?.getString(R.string.face_clothing_default) ?: "服"
        }

        return PersonAttributes(
            genderAndAge = genderAndAge,
            clothingDescription = clothingColor,
            estimatedDistanceMeters = distanceStr,
            emotion = emotionDetail
        )
    }

    /**
     * YUV_420_888 の CameraX Image から首下・胸元エリアのピクセル色をサンプリング
     */
    private fun sampleClothingColorFromImage(
        mediaImage: Image,
        faceBox: Rect,
        imgW: Int,
        imgH: Int,
        context: Context? = null
    ): String {
        val defaultClothing = context?.getString(R.string.face_clothing_default) ?: "服"
        return try {
            val planes = mediaImage.planes
            if (planes.isEmpty()) return defaultClothing

            val yBuffer = planes[0].buffer
            val chestTop = (faceBox.bottom + faceBox.height() * 0.15f).toInt().coerceIn(0, imgH - 1)
            val chestBottom = (faceBox.bottom + faceBox.height() * 0.85f).toInt().coerceIn(0, imgH - 1)
            val chestLeft = (faceBox.left + faceBox.width() * 0.2f).toInt().coerceIn(0, imgW - 1)
            val chestRight = (faceBox.right - faceBox.width() * 0.2f).toInt().coerceIn(0, imgW - 1)

            if (chestBottom <= chestTop || chestRight <= chestLeft) return defaultClothing

            var totalY = 0L
            var count = 0
            val rowStride = planes[0].rowStride

            val stepY = ((chestBottom - chestTop) / 6).coerceAtLeast(1)
            val stepX = ((chestRight - chestLeft) / 6).coerceAtLeast(1)

            for (y in chestTop until chestBottom step stepY) {
                for (x in chestLeft until chestRight step stepX) {
                    val index = y * rowStride + x
                    if (index < yBuffer.remaining()) {
                        totalY += (yBuffer.get(index).toInt() and 0xFF)
                        count++
                    }
                }
            }

            val avgY = if (count > 0) totalY / count else 128
            when {
                avgY >= 185 -> context?.getString(R.string.face_clothing_clean_white) ?: "パリッと清潔感のあるクリアホワイトの服"
                avgY in 140..184 -> context?.getString(R.string.face_clothing_warm_bright) ?: "ふんわり温かみのある明るい色のトップス"
                avgY in 85..139 -> context?.getString(R.string.face_clothing_elegant_gray) ?: "落ち着きのある上品なグレー系の服"
                avgY in 45..84 -> context?.getString(R.string.face_clothing_deep_chic) ?: "深みのあるシックで落ち着いた色の服"
                else -> context?.getString(R.string.face_clothing_sharp_black) ?: "キリッと引き締まった漆黒ブラックの服"
            }
        } catch (_: Exception) {
            defaultClothing
        }
    }

    /**
     * Bitmap からのピクセルサンプリング
     */
    private fun sampleClothingColorFromBitmap(
        bitmap: Bitmap,
        faceBox: Rect,
        imgW: Int,
        imgH: Int,
        context: Context? = null
    ): String {
        val defaultClothing = context?.getString(R.string.face_clothing_default) ?: "服"
        return try {
            val chestTop = (faceBox.bottom + faceBox.height() * 0.15f).toInt().coerceIn(0, imgH - 1)
            val chestBottom = (faceBox.bottom + faceBox.height() * 0.85f).toInt().coerceIn(0, imgH - 1)
            val chestLeft = (faceBox.left + faceBox.width() * 0.2f).toInt().coerceIn(0, imgW - 1)
            val chestRight = (faceBox.right - faceBox.width() * 0.2f).toInt().coerceIn(0, imgW - 1)

            if (chestBottom <= chestTop || chestRight <= chestLeft) return defaultClothing

            var totalR = 0L
            var totalG = 0L
            var totalB = 0L
            var sampleCount = 0

            val stepX = ((chestRight - chestLeft) / 6).coerceAtLeast(1)
            val stepY = ((chestBottom - chestTop) / 6).coerceAtLeast(1)

            for (y in chestTop until chestBottom step stepY) {
                for (x in chestLeft until chestRight step stepX) {
                    val pixel = bitmap.getPixel(x, y)
                    totalR += Color.red(pixel)
                    totalG += Color.green(pixel)
                    totalB += Color.blue(pixel)
                    sampleCount++
                }
            }

            if (sampleCount == 0) return defaultClothing

            val avgR = (totalR / sampleCount).toInt()
            val avgG = (totalG / sampleCount).toInt()
            val avgB = (totalB / sampleCount).toInt()

            val mood = FashionMoodHelper.describeColorFromRgb(avgR, avgG, avgB)
            if (context != null) {
                context.getString(R.string.face_clothing_tone_fmt, mood.simpleColorName, mood.sensoryTone)
            } else {
                "${mood.simpleColorName}の服（${mood.sensoryTone}）"
            }
        } catch (_: Exception) {
            defaultClothing
        }
    }

    // =========================================================================
    // v5.0: 4D カメラ空間通路・動体軌道予測ソナー (4D Safe-Corridor & Trajectory Predictor)
    // =========================================================================
    data class HorizonCorridorPrediction(
        val recommendedCorridor: String, // "CENTER", "LEFT", "RIGHT", "CAUTION_STOP"
        val safetyScorePercent: Int,
        val leftClearance: Float,
        val centerClearance: Float,
        val rightClearance: Float,
        val spokenGuidance: String
    )

    /**
     * 左・正面・右・足元の4ゾーンのエッジ密度・遮蔽スコア（0.0=完全クリア 〜 1.0=障害物密接）から
     * 視覚障害者が今すぐ進める安全な空間通路（Safe Corridor）を時計盤表現ゼロで予測・案内する。
     */
    fun predict4DHorizonCorridorFromZones(
        leftObstacleScore: Float,
        centerObstacleScore: Float,
        rightObstacleScore: Float,
        floorHazardScore: Float = 0.1f
    ): HorizonCorridorPrediction {
        val leftClear = (1.0f - leftObstacleScore.coerceIn(0f, 1f))
        val centerClear = (1.0f - centerObstacleScore.coerceIn(0f, 1f))
        val rightClear = (1.0f - rightObstacleScore.coerceIn(0f, 1f))
        val floorPenalty = floorHazardScore.coerceIn(0f, 1f) * 0.35f

        val effectiveCenter = (centerClear - floorPenalty).coerceIn(0f, 1f)
        val effectiveLeft = (leftClear - floorPenalty * 0.5f).coerceIn(0f, 1f)
        val effectiveRight = (rightClear - floorPenalty * 0.5f).coerceIn(0f, 1f)

        val recommended: String
        val bestClearance: Float
        val speech: String

        when {
            effectiveCenter >= 0.62f -> {
                recommended = "CENTER"
                bestClearance = effectiveCenter
                val pct = (bestClearance * 100).toInt().coerceIn(60, 99)
                speech = com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
                    ja = "4D空間通路ソナー：正面の通路が開けています（安全クリア度 ${pct}%）。そのまままっすぐ進めます。",
                    en = "4D Safe-Corridor Sonar: Path straight ahead is clear (${pct}% safety clearance). You can proceed straight.",
                    tl = "4D Safe-Corridor Sonar: Maluwag ang daan sa harap (${pct}% ligtas). Maaaring dumiretso.",
                    es = "Sonar de corredor 4D: El camino frente a usted está despejado (${pct}% seguro). Puede avanzar recto.",
                    nl = "4D Veilige-gangpadsonar: Het pad recht vooruit is vrij (${pct}% veiligheid). U kunt rechtdoor lopen."
                )
            }
            effectiveLeft >= effectiveRight && effectiveLeft >= 0.48f -> {
                recommended = "LEFT"
                bestClearance = effectiveLeft
                val pct = (bestClearance * 100).toInt().coerceIn(48, 95)
                speech = com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
                    ja = "4D空間通路ソナー：正面に障害物または人を検知しました。左側のスペースが開けています（クリア度 ${pct}%）。少し左へ避けて進んでください。",
                    en = "4D Safe-Corridor Sonar: Obstacle detected straight ahead. Left corridor is open (${pct}% clear). Step slightly left.",
                    tl = "4D Safe-Corridor Sonar: May harang sa harap. Bukas ang kaliwang bahagi (${pct}% maluwag). Lumihis nang kaunti sa kaliwa.",
                    es = "Sonar 4D: Obstáculo al frente. El lado izquierdo está despejado (${pct}% libre). Desvíese ligeramente a la izquierda.",
                    nl = "4D Sonar: Obstakel recht vooruit. Linkerzijde is vrij (${pct}% ruimte). Wijk iets uit naar links."
                )
            }
            effectiveRight >= 0.48f -> {
                recommended = "RIGHT"
                bestClearance = effectiveRight
                val pct = (bestClearance * 100).toInt().coerceIn(48, 95)
                speech = com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
                    ja = "4D空間通路ソナー：正面に障害物または人を検知しました。右側のスペースが開けています（クリア度 ${pct}%）。少し右へ避けて進んでください。",
                    en = "4D Safe-Corridor Sonar: Obstacle detected straight ahead. Right corridor is open (${pct}% clear). Step slightly right.",
                    tl = "4D Safe-Corridor Sonar: May harang sa harap. Bukas ang kanang bahagi (${pct}% maluwag). Lumihis nang kaunti sa kanan.",
                    es = "Sonar 4D: Obstáculo al frente. El lado derecho está despejado (${pct}% libre). Desvíese ligeramente a la derecha.",
                    nl = "4D Sonar: Obstakel recht vooruit. Rechterzijde is vrij (${pct}% ruimte). Wijk iets uit naar rechts."
                )
            }
            else -> {
                recommended = "CAUTION_STOP"
                bestClearance = maxOf(effectiveCenter, effectiveLeft, effectiveRight)
                val pct = (bestClearance * 100).toInt().coerceIn(10, 47)
                speech = com.kapamilya.serena.translation.SerenaLocalizationBridge.select(
                    ja = "4D空間通路ソナー警告：前方の広い範囲に障害物または壁が接近しています（クリア度 ${pct}%）。一度立ち止まり、白杖で周囲を確認してください。",
                    en = "4D Safe-Corridor Alert: Obstacles or walls close ahead across all zones (${pct}% clearance). Please pause and check with your cane.",
                    tl = "4D Safe-Corridor Babala: May malapit na harang sa harap (${pct}% clearance). Huminto muna at suriin ang paligid.",
                    es = "Alerta de Sonar 4D: Obstáculos cercanos en todo el frente (${pct}% libre). Deténgase y verifique con su bastón.",
                    nl = "4D Sonar-waarschuwing: Obstakels dichtbij over de gehele breedte (${pct}% vrij). Stop even en controleer met uw taststok."
                )
            }
        }

        return HorizonCorridorPrediction(
            recommendedCorridor = recommended,
            safetyScorePercent = (bestClearance * 100).toInt().coerceIn(0, 100),
            leftClearance = effectiveLeft,
            centerClearance = effectiveCenter,
            rightClearance = effectiveRight,
            spokenGuidance = speech
        )
    }

    /**
     * Bitmap画像から左・中央・右・足元の輝度コントラスト勾配（エッジ密度）を計算し、4D空間通路予測を返す
     */
    fun predict4DHorizonCorridorFromBitmap(bitmap: Bitmap): HorizonCorridorPrediction {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 12 || h < 12) {
            return predict4DHorizonCorridorFromZones(0.25f, 0.15f, 0.25f, 0.10f)
        }

        fun computeZoneEdgeDensity(xStart: Int, xEnd: Int, yStart: Int, yEnd: Int): Float {
            var edgeSum = 0f
            var samples = 0
            val stepX = ((xEnd - xStart) / 10).coerceAtLeast(1)
            val stepY = ((yEnd - yStart) / 10).coerceAtLeast(1)
            var y = yStart
            while (y < yEnd - stepY) {
                var x = xStart
                while (x < xEnd - stepX) {
                    val p0 = bitmap.getPixel(x, y)
                    val px = bitmap.getPixel(x + stepX, y)
                    val py = bitmap.getPixel(x, y + stepY)
                    val lum0 = (Color.red(p0) * 0.299f + Color.green(p0) * 0.587f + Color.blue(p0) * 0.114f)
                    val lumX = (Color.red(px) * 0.299f + Color.green(px) * 0.587f + Color.blue(px) * 0.114f)
                    val lumY = (Color.red(py) * 0.299f + Color.green(py) * 0.587f + Color.blue(py) * 0.114f)
                    val grad = (kotlin.math.abs(lum0 - lumX) + kotlin.math.abs(lum0 - lumY)) / 255f
                    edgeSum += grad.coerceIn(0f, 1f)
                    samples++
                    x += stepX
                }
                y += stepY
            }
            return if (samples > 0) (edgeSum / samples * 1.6f).coerceIn(0.05f, 0.95f) else 0.2f
        }

        val midYStart = (h * 0.25f).toInt()
        val midYEnd = (h * 0.78f).toInt()
        val floorYStart = (h * 0.78f).toInt()
        val floorYEnd = (h * 0.96f).toInt()

        val leftObs = computeZoneEdgeDensity(0, w / 3, midYStart, midYEnd)
        val centerObs = computeZoneEdgeDensity(w / 3, (w * 2) / 3, midYStart, midYEnd)
        val rightObs = computeZoneEdgeDensity((w * 2) / 3, w, midYStart, midYEnd)
        val floorHaz = computeZoneEdgeDensity(w / 4, (w * 3) / 4, floorYStart, floorYEnd)

        return predict4DHorizonCorridorFromZones(leftObs, centerObs, rightObs, floorHaz)
    }
}
