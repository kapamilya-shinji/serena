package com.kapamilya.serena

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * OcrCameraHelper (Multi-Script Universal OCR Engine v2.7)
 *
 * ML Kit Text Recognition V2 全5言語スクリプト（日本語・ラテン文字［英語/タガログ語/スペイン語/オランダ語］・
 * 中国語・韓国語・デーヴァナーガリー）を統合制御し、空間ブロック順序ソート（上→下、左→右）と
 * 高精度マルチスクリプト自動フォールバックを提供する最新OCRエンジン。
 */
class OcrCameraHelper(private val context: Context) {

    companion object {
        private const val TAG = "OcrCameraHelper"

        /**
         * 認識された Text オブジェクトを視覚障害者が自然に聞き取れるよう、
         * 画面の上から下・左から右の座標順（Reading Order）に整列してテキスト化
         */
        fun formatVisionTextInReadingOrder(visionText: Text): String {
            if (visionText.textBlocks.isEmpty()) return visionText.text.trim()
            val sortedBlocks = visionText.textBlocks.sortedWith(
                compareBy<Text.TextBlock> { (it.boundingBox?.top ?: 0) / 36 }
                    .thenBy { it.boundingBox?.left ?: 0 }
            )
            return sortedBlocks.mapNotNull { block ->
                block.text.trim().takeIf { it.isNotEmpty() }
            }.joinToString("\n").trim()
        }
    }

    // プライマリ: 日本語 + ラテン文字（英数・タガログ・スペイン・オランダ語も同時カバー）
    private val japaneseRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    // セカンダリ: 純ラテン文字高精度エンジン
    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    // 多言語フォールバック: 中国語 / 韓国語 / デーヴァナーガリー
    private val chineseRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val koreanRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private val devanagariRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    private var lastRecognizedText = ""

    fun launchCameraForTextRecognition() {
        try {
            val intent = Intent(context, LiveVisionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("MODE", "OCR")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch LiveVisionActivity for OCR: ${e.message}")
        }
    }

    /**
     * マルチスクリプト・ハイブリッドOCR認識（日本語 → ラテン文字 → 韓国語/中国語 自動フォールバック）
     */
    fun recognizeTextFromBitmap(bitmap: Bitmap, onResult: (String) -> Unit) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            japaneseRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val orderedText = formatVisionTextInReadingOrder(visionText)
                    if (orderedText.isNotEmpty()) {
                        lastRecognizedText = orderedText
                        onResult(orderedText)
                    } else {
                        // 日本語エンジンで空の場合、ラテン文字・韓国語・中国語の順にフォールバック走査
                        fallbackMultiScriptRecognition(image, onResult)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Primary Japanese OCR failed, trying Latin OCR fallback: ${e.message}")
                    fallbackMultiScriptRecognition(image, onResult)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during OCR: ${e.message}")
            onResult(context.getString(R.string.ocr_error))
        }
    }

    private fun fallbackMultiScriptRecognition(image: InputImage, onResult: (String) -> Unit) {
        latinRecognizer.process(image)
            .addOnSuccessListener { latinVision ->
                val latinText = formatVisionTextInReadingOrder(latinVision)
                if (latinText.isNotEmpty()) {
                    lastRecognizedText = latinText
                    onResult(latinText)
                } else {
                    koreanRecognizer.process(image)
                        .addOnSuccessListener { krVision ->
                            val krText = formatVisionTextInReadingOrder(krVision)
                            if (krText.isNotEmpty()) {
                                lastRecognizedText = krText
                                onResult(krText)
                            } else {
                                chineseRecognizer.process(image)
                                    .addOnSuccessListener { zhVision ->
                                        val zhText = formatVisionTextInReadingOrder(zhVision)
                                        if (zhText.isNotEmpty()) {
                                            lastRecognizedText = zhText
                                            onResult(zhText)
                                        } else {
                                            devanagariRecognizer.process(image)
                                                .addOnSuccessListener { devVision ->
                                                    val devText = formatVisionTextInReadingOrder(devVision)
                                                    if (devText.isNotEmpty()) {
                                                        lastRecognizedText = devText
                                                        onResult(devText)
                                                    } else {
                                                        onResult(context.getString(R.string.ocr_no_text_found))
                                                    }
                                                }
                                                .addOnFailureListener {
                                                    onResult(context.getString(R.string.ocr_no_text_found))
                                                }
                                        }
                                    }
                                    .addOnFailureListener {
                                        onResult(context.getString(R.string.ocr_no_text_found))
                                    }
                            }
                        }
                        .addOnFailureListener {
                            onResult(context.getString(R.string.ocr_no_text_found))
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Multi-script OCR fallback error: ${e.message}")
                onResult(context.getString(R.string.ocr_error))
            }
    }

    fun captureAndRecognize(onResult: (String) -> Unit) {
        // 静的なダミーの連呼を廃止。直近の認識結果または変化時のみコールバック
        if (lastRecognizedText.isNotEmpty()) {
            onResult(lastRecognizedText)
        }
    }

    fun close() {
        try {
            japaneseRecognizer.close()
            latinRecognizer.close()
            chineseRecognizer.close()
            koreanRecognizer.close()
            devanagariRecognizer.close()
        } catch (_: Exception) {}
    }
}
