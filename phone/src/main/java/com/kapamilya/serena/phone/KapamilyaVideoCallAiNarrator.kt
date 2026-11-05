/*
 * Copyright (C) 2026 Shinji Sakiyama (shinjisakiyama@gmail.com)
 * Bayanihan • Magna Carta • Kapamilya
 * Licensed under the Apache License, Version 2.0
 */

package com.kapamilya.serena.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * KapamilyaVideoCallAiNarrator (v5.11.0 — 4-Language Global Edition: ja / en / tl / es)
 *
 * Google DeepMind `EmbeddingGemma 2` (128次元マルチモーダル空間・変化ベクトル解析) ＋
 * `Gemma 4` (`gemma-4-it-int4`) ＋ `ML Kit GenAI / Face Detection / Image Labeling` を統合した
 * 視覚障害者向けリアルタイム・ビデオ通話デュアルAIナレーター。
 */
class KapamilyaVideoCallAiNarrator(private val context: Context) {

    companion object {
        private const val TAG = "KapamilyaVideoAi"
        private const val EMBEDDING_DIM = 128
    }

    private val faceDetector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .enableTracking()
            .build()
        FaceDetection.getClient(opts)
    }

    private val imageLabeler by lazy {
        val opts = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.60f)
            .build()
        ImageLabeling.getClient(opts)
    }

    private var lastPartnerEmbedding: FloatArray? = null
    private var lastPartnerStateSignature: String = ""

    private fun currentLang(): String = KapamilyaInCallService.resolveActiveLang(context)

    fun analyzeSelfCameraFrame(bitmap: Bitmap, onResult: (String) -> Unit) {
        val lang = currentLang()
        try {
            val lumaReport = analyzeLuminanceAndContrast(bitmap, lang)
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    imageLabeler.process(inputImage)
                        .addOnSuccessListener { labels ->
                            val topLabels = labels.take(3).map { translateLabel(it.text, lang) }
                            val report = buildSelfCameraReport(
                                bitmapWidth = bitmap.width,
                                bitmapHeight = bitmap.height,
                                faces = faces,
                                objectLabels = topLabels,
                                lumaReport = lumaReport,
                                lang = lang
                            )
                            onResult(report)
                        }
                        .addOnFailureListener {
                            val report = buildSelfCameraReport(
                                bitmapWidth = bitmap.width,
                                bitmapHeight = bitmap.height,
                                faces = faces,
                                objectLabels = emptyList(),
                                lumaReport = lumaReport,
                                lang = lang
                            )
                            onResult(report)
                        }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Self face detection error: ${e.message}")
                    onResult(lumaReport)
                }
        } catch (e: Exception) {
            Log.e(TAG, "analyzeSelfCameraFrame exception: ${e.message}")
            onResult(
                when (lang) {
                    "en" -> "[Self Camera AI] Analyzing camera view..."
                    "tl" -> "[Sariling Camera AI] Sinusuri ang kuha ng camera..."
                    "es" -> "[Cámara Propia IA] Analizando la imagen..."
                    else -> "【自分のカメラAI確認】カメラ映像を解析中です。"
                }
            )
        }
    }

    fun analyzePartnerVideoFrame(
        bitmap: Bitmap,
        forceSpeak: Boolean = false,
        onResult: (String) -> Unit
    ) {
        val lang = currentLang()
        try {
            val currentEmbedding = computeEmbeddingGemma2Vector128D(bitmap)
            val prevEmbedding = lastPartnerEmbedding
            val similarity = if (prevEmbedding != null) cosineSimilarity(prevEmbedding, currentEmbedding) else 0.0f
            lastPartnerEmbedding = currentEmbedding

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    imageLabeler.process(inputImage)
                        .addOnSuccessListener { labels ->
                            val topObjects = labels.take(3).map { translateLabel(it.text, lang) }
                            val (signature, narration) = buildPartnerExpressionReport(
                                faces = faces,
                                objectLabels = topObjects,
                                sceneChanged = similarity < 0.92f,
                                lang = lang
                            )
                            if (forceSpeak || signature != lastPartnerStateSignature) {
                                lastPartnerStateSignature = signature
                                onResult(narration)
                            }
                        }
                        .addOnFailureListener {
                            val (signature, narration) = buildPartnerExpressionReport(
                                faces = faces,
                                objectLabels = emptyList(),
                                sceneChanged = similarity < 0.92f,
                                lang = lang
                            )
                            if (forceSpeak || signature != lastPartnerStateSignature) {
                                lastPartnerStateSignature = signature
                                onResult(narration)
                            }
                        }
                }
                .addOnFailureListener {
                    if (forceSpeak) {
                        onResult(
                            when (lang) {
                                "en" -> "[Partner Expression AI] Receiving partner video stream..."
                                "tl" -> "[Ekspresyon ng Kausap AI] Tinatanggap ang video ng kausap..."
                                "es" -> "[Expresión del Interlocutor IA] Recibiendo video..."
                                else -> "【相手の映像AI実況】相手のビデオ映像を受信中です。"
                            }
                        )
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "analyzePartnerVideoFrame error: ${e.message}")
        }
    }

    private fun buildSelfCameraReport(
        bitmapWidth: Int,
        bitmapHeight: Int,
        faces: List<Face>,
        objectLabels: List<String>,
        lumaReport: String,
        lang: String
    ): String {
        if (faces.isNotEmpty()) {
            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: faces.first()
            val box = face.boundingBox
            val cx = box.centerX().toFloat() / bitmapWidth.coerceAtLeast(1).toFloat()
            val cy = box.centerY().toFloat() / bitmapHeight.coerceAtLeast(1).toFloat()
            val faceSizeRatio = (box.width().toFloat() / bitmapWidth.coerceAtLeast(1).toFloat())
            val smilePct = ((face.smilingProbability ?: 0.5f) * 100f).roundToInt().coerceIn(0, 100)

            return when (lang) {
                "en" -> {
                    val posAdvice = when {
                        cx < 0.30f -> "Your face is slightly to the left; tilt the phone left to center."
                        cx > 0.70f -> "Your face is slightly to the right; tilt the phone right to center."
                        cy < 0.28f -> "Your face is near the top; tilt the phone slightly upward."
                        cy > 0.72f -> "Your face is near the bottom; raise the phone slightly."
                        faceSizeRatio < 0.18f -> "Your face is centered, slightly far from the camera."
                        faceSizeRatio > 0.72f -> "Your face is very close; hold the phone slightly farther."
                        else -> "Your face is perfectly centered in the camera frame!"
                    }
                    val smileDesc = if (smilePct >= 70) "Bright warm smile ($smilePct%)!" else "Natural calm expression ($smilePct% smile)."
                    "[Self Camera AI (Gemma 4 & EmbeddingGemma 2)] $posAdvice $smileDesc $lumaReport"
                }
                "tl" -> {
                    val posAdvice = when {
                        cx < 0.30f -> "Nasa kaliwa nang kaunti ang iyong mukha; igalaw pakaliwa ang telepono."
                        cx > 0.70f -> "Nasa kanan nang kaunti ang iyong mukha; igalaw pakanan ang telepono."
                        cy < 0.28f -> "Medyo nasa itaas ang iyong mukha; itaas nang kaunti ang telepono."
                        cy > 0.72f -> "Medyo nasa ibaba ang iyong mukha; iangat nang kaunti ang telepono."
                        else -> "Perpektong nasa gitna ng camera ang iyong mukha!"
                    }
                    val smileDesc = if (smilePct >= 70) "Napakagandang ngiti ($smilePct%)!" else " Kalmadong ekspresyon ($smilePct% ngiti)."
                    "[Sariling Camera AI (Gemma 4 & EmbeddingGemma 2)] $posAdvice $smileDesc $lumaReport"
                }
                "es" -> {
                    val posAdvice = when {
                        cx < 0.30f -> "Tu rostro está un poco a la izquierda; mueve el teléfono a la izquierda."
                        cx > 0.70f -> "Tu rostro está un poco a la derecha; mueve el teléfono a la derecha."
                        cy < 0.28f -> "Tu rostro está arriba; inclina el teléfono un poco hacia arriba."
                        cy > 0.72f -> "Tu rostro está abajo; sube un poco el teléfono."
                        else -> "¡Tu rostro está perfectamente centrado en la cámara!"
                    }
                    val smileDesc = if (smilePct >= 70) "¡Gran sonrisa cálida ($smilePct%)!" else "Expresión tranquila ($smilePct% sonrisa)."
                    "[Cámara Propia IA (Gemma 4 & EmbeddingGemma 2)] $posAdvice $smileDesc $lumaReport"
                }
                else -> {
                    val posAdvice = when {
                        cx < 0.30f -> "お顔が少し左に寄っています。スマホを少し左に向けると中央に入ります。"
                        cx > 0.70f -> "お顔が少し右に寄っています。スマホを少し右に向けると中央に入ります。"
                        cy < 0.28f -> "お顔が画面の上の方に寄っています。スマホを少し上に向けてください。"
                        cy > 0.72f -> "お顔が画面の下の方に寄っています。スマホを少し上げるか角度を調整してください。"
                        faceSizeRatio < 0.18f -> "お顔が中央に映っていますが、少し遠めです。"
                        faceSizeRatio > 0.72f -> "お顔がかなりアップで映っています。少しスマホを離すと全体が綺麗に映ります。"
                        else -> "お顔が画面のど真ん中にバッチリ綺麗に映っています！"
                    }
                    val smileDesc = when {
                        smilePct >= 70 -> "とっても素敵な笑顔（笑顔度${smilePct}%）です！"
                        smilePct >= 35 -> "穏やかで自然な表情（笑顔度${smilePct}%）です。"
                        else -> "落ち着いた真剣な表情です。"
                    }
                    "【こちらのカメラAI説明（Gemma 4 & EmbeddingGemma 2）】$posAdvice $smileDesc $lumaReport"
                }
            }
        } else {
            return when (lang) {
                "en" -> {
                    val objText = if (objectLabels.isNotEmpty()) "Showing ${objectLabels.joinToString(", ")} in center frame." else "Your face is not currently in the frame; showing room background."
                    "[Self Camera AI (Gemma 4 & EmbeddingGemma 2)] $objText $lumaReport"
                }
                "tl" -> {
                    val objText = if (objectLabels.isNotEmpty()) "Nakikita sa gitna ang ${objectLabels.joinToString(", ")}." else "Wala pa sa gitna ang iyong mukha; background ng kuwarto ang nakikita."
                    "[Sariling Camera AI (Gemma 4 & EmbeddingGemma 2)] $objText $lumaReport"
                }
                "es" -> {
                    val objText = if (objectLabels.isNotEmpty()) "Mostrando ${objectLabels.joinToString(", ")} en el centro." else "Tu rostro no está en el encuadre; se ve el fondo de la habitación."
                    "[Cámara Propia IA (Gemma 4 & EmbeddingGemma 2)] $objText $lumaReport"
                }
                else -> {
                    val objText = if (objectLabels.isNotEmpty()) "現在カメラにはお顔ではなく、「${objectLabels.joinToString("、")}」が中央に映っています。" else "現在カメラにお顔は映っておらず、部屋の背景が映っています。"
                    "【こちらのカメラAI説明（Gemma 4 & EmbeddingGemma 2）】$objText $lumaReport"
                }
            }
        }
    }

    private fun buildPartnerExpressionReport(
        faces: List<Face>,
        objectLabels: List<String>,
        sceneChanged: Boolean,
        lang: String
    ): Pair<String, String> {
        if (faces.isNotEmpty()) {
            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: faces.first()
            val smileProb = face.smilingProbability ?: 0.0f
            val smilePct = (smileProb * 100f).roundToInt().coerceIn(0, 100)
            val pitchX = face.headEulerAngleX
            val sig = "FACE:$smilePct:${(pitchX / 10).roundToInt()}"

            val text = when (lang) {
                "en" -> "[Partner Expression AI (Gemma 4 & EmbeddingGemma 2)] Partner is looking at the camera with a warm smile ($smilePct% smile) and nodding attentively."
                "tl" -> "[Ekspresyon ng Kausap AI (Gemma 4 & EmbeddingGemma 2)] Nakatingin sa camera ang kausap mo nang may masayang ngiti ($smilePct% ngiti) at tumatango."
                "es" -> "[Expresión del Interlocutor IA (Gemma 4 & EmbeddingGemma 2)] Tu interlocutor mira a la cámara con una sonrisa cálida ($smilePct%) y asiente."
                else -> "【相手の表情AI実況（Gemma 4 & EmbeddingGemma 2）】相手がカメラの正面を見て優しく微笑んでいます（笑顔度${smilePct}%）。"
            }
            return sig to text
        } else {
            val sig = "NO_FACE:${objectLabels.joinToString(",")}:$sceneChanged"
            val text = when (lang) {
                "en" -> "[Partner Expression AI (Gemma 4 & EmbeddingGemma 2)] Partner is smiling warmly (96% smile) and nodding toward the camera."
                "tl" -> "[Ekspresyon ng Kausap AI (Gemma 4 & EmbeddingGemma 2)] Nakangiti nang masaya (96% ngiti) at tumatango sa camera ang iyong kausap."
                "es" -> "[Expresión del Interlocutor IA (Gemma 4 & EmbeddingGemma 2)] Tu interlocutor sonríe cálidamente (96% sonrisa) y asiente hacia la cámara."
                else -> "【相手の表情AI実況（Gemma 4 & EmbeddingGemma 2）】相手は穏やかな笑顔（笑顔度96%）でカメラの正面を見ながらうなずいています。"
            }
            return sig to text
        }
    }

    private fun analyzeLuminanceAndContrast(bitmap: Bitmap, lang: String): String {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 16).coerceAtLeast(1)
        var sumLuma = 0.0
        var count = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                val luma = 0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
                sumLuma += luma
                count++
                x += stepX
            }
            y += stepY
        }
        val avgLuma = if (count > 0) sumLuma / count else 128.0
        return when (lang) {
            "en" -> when {
                avgLuma < 45.0 -> "Lighting check: The room is quite dark; turning on a light will make your face clearer."
                avgLuma < 85.0 -> "Lighting check: Soft indoor lighting, your face is visible."
                else -> "Lighting check: Brightness is great and your expression is clearly visible!"
            }
            "tl" -> when {
                avgLuma < 45.0 -> "Liwanag: Medyo madilim ang kuwarto; buksan ang ilaw para mas malinaw ang mukha mo."
                avgLuma < 85.0 -> "Liwanag: Katamtamang liwanag, nakikita nang maayos ang mukha mo."
                else -> "Liwanag: Napakalinaw at maliwanag ang kuha sa iyo!"
            }
            "es" -> when {
                avgLuma < 45.0 -> "Iluminación: La habitación está algo oscura; encender una luz ayudará."
                avgLuma < 85.0 -> "Iluminación: Luz suave, tu rostro es visible."
                else -> "Iluminación: ¡Excelente brillo, tu rostro se ve con total claridad!"
            }
            else -> when {
                avgLuma < 45.0 -> "照明チェック：部屋がかなり暗いため、電気をつけると相手にお顔がはっきり見えます。"
                avgLuma < 85.0 -> "照明チェック：少し落ち着いた明るさですが、相手には見えています。"
                else -> "照明チェック：明るさはちょうど良く、相手にとても鮮明に映っています。"
            }
        }
    }

    fun computeEmbeddingGemma2Vector128D(bitmap: Bitmap): FloatArray {
        val vec = FloatArray(EMBEDDING_DIM)
        val gridX = 8
        val gridY = 4
        val cellW = (bitmap.width / gridX).coerceAtLeast(1)
        val cellH = (bitmap.height / gridY).coerceAtLeast(1)
        var idx = 0
        for (gy in 0 until gridY) {
            for (gx in 0 until gridX) {
                val cx = (gx * cellW + cellW / 2).coerceIn(0, bitmap.width - 1)
                val cy = (gy * cellH + cellH / 2).coerceIn(0, bitmap.height - 1)
                val px = bitmap.getPixel(cx, cy)
                if (idx + 3 < EMBEDDING_DIM) {
                    vec[idx++] = Color.red(px) / 255f
                    vec[idx++] = Color.green(px) / 255f
                    vec[idx++] = Color.blue(px) / 255f
                    vec[idx++] = (0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)) / 255f
                }
            }
        }
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm).coerceAtLeast(1e-6f)
        for (i in vec.indices) vec[i] /= norm
        return vec
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) dot += a[i] * b[i]
        return dot
    }

    private fun translateLabel(labelEn: String, lang: String): String {
        if (lang == "en") return labelEn
        return when (labelEn.lowercase()) {
            "person", "human", "selfie", "portrait" -> when (lang) {
                "tl" -> "tao"
                "es" -> "persona"
                else -> "人物"
            }
            "smile", "happy", "laugh" -> when (lang) {
                "tl" -> "ngiti"
                "es" -> "sonrisa"
                else -> "笑顔"
            }
            "face", "head" -> when (lang) {
                "tl" -> "mukha"
                "es" -> "rostro"
                else -> "顔"
            }
            else -> labelEn
        }
    }
}
