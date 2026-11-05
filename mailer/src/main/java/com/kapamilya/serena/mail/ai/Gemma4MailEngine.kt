package com.kapamilya.serena.mail.ai

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gemma4MailEngine (v5.0 — EmbeddingGemma 2 + Gemma 4 Unified Pipeline + LiteRT 2.3.0 GPU)
 * 
 * Kapamilya Serena Mail 向け Gemma 4 オンデバイス基盤AIエンジン。
 * すべてのAI機能（メール要約・AI返信文作成・スマート意図返信・フィッシング検閲・検索RAG記憶）において
 * EmbeddingGemma2Engine (768D Semantic Vector) ⇔ Gemma4MailEngine の双方向パイプラインが100%直結稼働。
 */
class Gemma4MailEngine(private val context: Context) {

    companion object {
        private const val TAG = "Gemma4MailEngine"

        const val ENGINE_SIGNATURE =
            "Gemma4MailEngine v5.0 (EmbeddingGemma 2 + Gemma 4 Dual Core | Anti-Phishing Shield | 0s OTP Extractor | 5-Tone Smart Reply)"

        @Volatile
        private var instance: Gemma4MailEngine? = null

        fun getInstance(context: Context): Gemma4MailEngine {
            return instance ?: synchronized(this) {
                instance ?: Gemma4MailEngine(context.applicationContext).also { instance = it }
            }
        }

        data class EmailSecurityAndActionReport(
            val isSuspiciousPhishing: Boolean,
            val securityVerdict: String,
            val extractedOtpCodes: List<String>,
            val extractedUrls: List<String>,
            val embeddingGemma2PhishingSimilarity: Float = 0f
        )

        /**
         * EmbeddingGemma 2 の768次元フィッシング意味ベクトル類似度と Gemma 4 セキュリティ規則を直結した
         * ハイブリッド・フィッシング検閲＆認証コード(OTP)・URL 0秒抽出パイプライン。
         */
        fun inspectEmailSecurityAndExtractKeyItems(sender: String, subject: String, body: String): EmailSecurityAndActionReport {
            val combined = "$sender $subject $body".lowercase(java.util.Locale.ROOT)
            val phishingKeywords = listOf(
                "アカウントが凍結", "緊急の本人確認", "24時間以内に", "パスワードを再設定", "未払いの税金", "口座情報を更新",
                "verify your account immediately", "account suspended", "urgent action required", "unusual sign-in activity", "confirm your billing"
            )
            val keywordHitCount = phishingKeywords.count { combined.contains(it) }

            // EmbeddingGemma 2 (768D Semantic Vector) によるフィッシング意味類似度スコア算出
            val eg2PhishingSim = EmbeddingGemma2Engine.computePhishingSemanticSimilarity(sender, subject, body)

            val isPhishing = keywordHitCount >= 2 ||
                    (combined.contains("http://") && (combined.contains("login") || combined.contains("ログイン") || combined.contains("認証"))) ||
                    (keywordHitCount >= 1 && eg2PhishingSim >= 0.32f) ||
                    eg2PhishingSim >= 0.55f

            // 4〜8桁のワンタイムパスコード（OTP / 認証コード）抽出
            val otpRegex = Regex("""(?:コード|番号|code|pin|otp|passcode)[:：\s is]*([0-9]{4,8})""", RegexOption.IGNORE_CASE)
            val otps = otpRegex.findAll("$subject $body").mapNotNull { it.groupValues.getOrNull(1) }.distinct().take(3).toList()

            // URL抽出（末尾の句読点や閉じ括弧を除去）
            val urlRegex = Regex("""https?://[^\s"<>()]+""")
            val urls = urlRegex.findAll(body)
                .map { it.value.trimEnd('.', ',', ';', ')', ']', '」', '】', '。', '、') }
                .filter { it.length > 8 }
                .distinct()
                .take(3)
                .toList()

            val isJa = java.util.Locale.getDefault().language.lowercase() == "ja"
            val verdict = if (isPhishing) {
                if (isJa) {
                    "⚠️【AIフィッシング・偽装メール警告】緊急の口座確認やアカウント凍結を装う危険な表現が検出されました。リンクを開いたりパスワードを入力しないでください。"
                } else {
                    "⚠️ [AI Phishing / Scam Alert] Suspicious urgent account or billing language detected. Do not click links or enter passwords."
                }
            } else {
                if (isJa) {
                    "🛡️【AIセキュリティ判定：安全】フィッシングや偽請求の危険パターンは検出されませんでした。"
                } else {
                    "🛡️ [AI Security Check: Safe] No phishing or fake-invoice patterns detected."
                }
            }

            return EmailSecurityAndActionReport(
                isSuspiciousPhishing = isPhishing,
                securityVerdict = verdict,
                extractedOtpCodes = otps,
                extractedUrls = urls,
                embeddingGemma2PhishingSimilarity = eg2PhishingSim
            )
        }

        /**
         * EmbeddingGemma 2 の768次元意図ベクトル分類と連動したスマート返信生成
         */
        fun generateSmartReplyByIntent(originalSender: String, originalSubject: String, intentType: String): String {
            val isJa = java.util.Locale.getDefault().language.lowercase() == "ja"
            val cleanName = originalSender.substringBefore("<").trim().replace("\"", "").ifEmpty {
                if (isJa) "ご担当者" else "there"
            }
            val resolvedIntent = EmbeddingGemma2Engine.classifyReplyIntentSemantic(
                subject = originalSubject,
                body = "",
                intentHint = intentType
            )
            return when (resolvedIntent) {
                "ACCEPT" -> if (isJa) {
                    "${cleanName}様\n\nご連絡ありがとうございます。承知いたしました。ご提案の内容で問題ございませんので、このまま進めていただけますと幸いです。\n\nよろしくお願いいたします。\nShinji"
                } else {
                    "Hi $cleanName,\n\nThank you for reaching out. I gladly accept and agree with the proposed details. Please feel free to proceed.\n\nBest regards,\nShinji"
                }
                "DECLINE" -> if (isJa) {
                    "${cleanName}様\n\nお気遣いいただき誠にありがとうございます。せっかくのご提案ですが、現在のスケジュール都合により今回は見送らせていただけますと幸いです。またの機会がございましたらよろしくお願いいたします。\n\nShinji"
                } else {
                    "Hi $cleanName,\n\nThank you very much for thinking of me. Unfortunately, due to my current schedule, I will have to pass on this opportunity. I hope we can connect again in the future.\n\nBest regards,\nShinji"
                }
                "QUESTION" -> if (isJa) {
                    "${cleanName}様\n\nご連絡ありがとうございます。内容を確認いたしました。1点ほど詳細を確認させていただきたく、折り返しご都合の良いタイミングでお知らせいただけますでしょうか。\n\nよろしくお願いいたします。\nShinji"
                } else {
                    "Hi $cleanName,\n\nThank you for your message. I have reviewed the details and would like to clarify one quick point when you have a moment.\n\nBest regards,\nShinji"
                }
                "FOLLOW_UP" -> if (isJa) {
                    "${cleanName}様\n\nいつもお世話になっております。『$originalSubject』の件につきまして、その後の進捗はいかがでしょうか。お手数をおかけしますが、ご確認のほどよろしくお願いいたします。\n\nShinji"
                } else {
                    "Hi $cleanName,\n\nI hope you are doing well. I wanted to kindly follow up regarding \"$originalSubject\". Please let me know if there are any updates.\n\nBest regards,\nShinji"
                }
                else -> if (isJa) {
                    "${cleanName}様\n\nご連絡ありがとうございます。メールを拝受し、内容を確認いたしました。取り急ぎお礼申し上げます。\n\nよろしくお願いいたします。\nShinji"
                } else {
                    "Hi $cleanName,\n\nThank you for your email. I have received and reviewed your message.\n\nBest regards,\nShinji"
                }
            }
        }
    }

    private var llmInference: LlmInference? = null
    private var isInitializing = false
    private val inferenceLock = Any()
    val embeddingGemma2 by lazy { EmbeddingGemma2Engine.getInstance(context) }

    /**
     * EmbeddingGemma 2 (768D/128D Matryoshka Semantic Vector) を用いて、
     * メール本文から最も重要度の高い文（質問や件名とコサイン類似度が高い文）を上位抽出する。
     */
    suspend fun extractTopSemanticSentencesViaEmbeddingGemma2(
        queryOrSubject: String,
        body: String,
        topK: Int = 3
    ): List<String> = withContext(Dispatchers.Default) {
        val sentences = body.split(Regex("[。．.!！？?\\n]+"))
            .map { it.trim() }
            .filter { it.length >= 6 }
        if (sentences.isEmpty()) return@withContext emptyList()

        val queryVec = embeddingGemma2.computeEmbedding(queryOrSubject.ifBlank { body.take(120) })
        sentences.map { sentence ->
            val sentVec = embeddingGemma2.computeEmbedding(sentence)
            sentence to embeddingGemma2.computeCosineSimilarity(queryVec, sentVec)
        }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    private fun findModelFile(): File? {
        val candidateNames = listOf(
            "gemma-4-e4b-it.litertlm",
            "gemma-4-e4b-it.task",
            "gemma-4-e2b-it.litertlm",
            "gemma-4-e2b-it.task",
            "gemma-4-e2b.task",
            "gemma-4-e2b.bin",
            "gemma-4.task",
            "gemma.task",
            "gemma-3n-e4b-it-int4.task",
            "gemma-3n-e2b-it-int4.task",
            "gemma-2b-it-gpu-int4.bin"
        )

        // 1. アプリ専用内部ストレージ
        val internalDir = File(context.filesDir, "models")
        if (internalDir.exists() && internalDir.isDirectory) {
            for (name in candidateNames) {
                val f = File(internalDir, name)
                if (f.exists() && f.length() > 1024 * 1024) return f
            }
        }

        // 2. 外部ストレージ Downloads
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir != null && downloadDir.exists() && downloadDir.isDirectory) {
                for (name in candidateNames) {
                    val f = File(downloadDir, name)
                    if (f.exists() && f.length() > 1024 * 1024) return f
                }
            }
        } catch (_: Exception) {}

        // 3. アプリ専用外部ストレージ
        try {
            val extDir = context.getExternalFilesDir(null)
            if (extDir != null && extDir.exists()) {
                for (name in candidateNames) {
                    val f = File(extDir, name)
                    if (f.exists() && f.length() > 1024 * 1024) return f
                }
            }
        } catch (_: Exception) {}

        return null
    }

    @Synchronized
    private fun initEngine(): Boolean {
        if (llmInference != null) return true
        if (isInitializing) return false

        val modelFile = findModelFile() ?: return false
        isInitializing = true
        return try {
            val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(2048)

            try {
                optionsBuilder.setPreferredBackend(LlmInference.Backend.GPU)
            } catch (_: Throwable) {
                optionsBuilder.setPreferredBackend(LlmInference.Backend.CPU)
            }

            llmInference = LlmInference.createFromOptions(context, optionsBuilder.build())
            Log.i(TAG, "Gemma 4 Mail Engine (LiteRT 2.3 GPU) initialized successfully!")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Gemma 4: ${e.message}")
            llmInference = null
            false
        } finally {
            isInitializing = false
        }
    }

    /**
     * メールのスマート要約（EmbeddingGemma 2 セマンティック重要文抽出 ⇔ Gemma 4 要約 ⇔ RAG記憶書き戻し）
     */
    suspend fun summarizeEmail(
        sender: String,
        subject: String,
        body: String
    ): String = withContext(Dispatchers.Default) {
        val isJa = java.util.Locale.getDefault().language.lowercase() == "ja"
        val cleanBody = body.trim()
        if (cleanBody.isEmpty()) {
            return@withContext if (isJa) "本文が空です。" else "Email body is empty."
        }

        val ragKey = "$sender|$subject"

        // Step 1: EmbeddingGemma 2 によるセマンティック重要文抽出（768D ベクトルコサイン類似度）
        val topSemanticSentences = extractTopSemanticSentencesViaEmbeddingGemma2(
            queryOrSubject = "$subject $sender",
            body = cleanBody,
            topK = 3
        )
        val eg2Context = if (topSemanticSentences.isNotEmpty()) {
            "【EmbeddingGemma 2 意味ベクトル重要文抽出】\n" + topSemanticSentences.joinToString("\n") { "- $it" }
        } else ""

        val prompt = if (isJa) {
            """
            全盲のユーザー向けに、以下のメールの要点を2〜3文で簡潔かつ分かりやすく要約してください。
            件名: $subject
            送信者: $sender
            $eg2Context
            本文:
            ${cleanBody.take(1500)}
            """.trimIndent()
        } else {
            """
            Summarize the key points of this email for a visually impaired user in 2-3 concise sentences:
            Subject: $subject
            Sender: $sender
            $eg2Context
            Body:
            ${cleanBody.take(1500)}
            """.trimIndent()
        }

        if (initEngine()) {
            llmInference?.let { engine ->
                try {
                    val fullInput = "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
                    val response = synchronized(inferenceLock) {
                        engine.generateResponse(fullInput).trim()
                    }
                    if (response.isNotEmpty()) {
                        val finalOutput = appendHorizonSecurityAndActionReport(sender, subject, cleanBody, response)
                        embeddingGemma2.memorizeGemma4MailInsight(ragKey, finalOutput)
                        return@withContext finalOutput
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Gemma inference failed: ${e.message}")
                }
            }
        }

        // Step 2: EmbeddingGemma 2 768D セマンティック重要文要約 + v5.0 セキュリティ検閲＆認証コード0秒抽出
        val semanticSummary = if (topSemanticSentences.isNotEmpty()) {
            topSemanticSentences.joinToString("。 ")
        } else {
            heuristicSummarize(cleanBody)
        }
        val finalOutput = appendHorizonSecurityAndActionReport(sender, subject, cleanBody, semanticSummary)
        embeddingGemma2.memorizeGemma4MailInsight(ragKey, finalOutput)
        finalOutput
    }

    private fun appendHorizonSecurityAndActionReport(sender: String, subject: String, body: String, baseSummary: String): String {
        val report = inspectEmailSecurityAndExtractKeyItems(sender, subject, body)
        val sb = StringBuilder(baseSummary)
        sb.append("\n")
        sb.append(report.securityVerdict)
        if (report.extractedOtpCodes.isNotEmpty()) {
            val isJa = java.util.Locale.getDefault().language.lowercase() == "ja"
            val label = if (isJa) "🔑【0秒抽出：認証コード/OTP】 " else "🔑 [Instant OTP / Code] "
            sb.append("\n").append(label).append(report.extractedOtpCodes.joinToString(" / "))
        }
        return sb.toString()
    }

    /**
     * AI返信文作成（EmbeddingGemma 2 要点抽出＆意図ベクトル分類 ⇔ Gemma 4 生成 ⇔ RAG記憶書き戻し）
     */
    suspend fun generateReplyDraft(
        sender: String,
        subject: String,
        body: String,
        intent: String = "了解とお礼"
    ): String = withContext(Dispatchers.Default) {
        val isJa = java.util.Locale.getDefault().language.lowercase() == "ja"
        val ragKey = "$sender|$subject"

        // Step 1: EmbeddingGemma 2 で相手のメール本文から返信すべき最重要センテンスを抽出
        val topPoints = extractTopSemanticSentencesViaEmbeddingGemma2("$subject $intent", body, topK = 2)
        // Step 2: EmbeddingGemma 2 の768次元ベクトル空間で最適な返信意図（ACCEPT / DECLINE / QUESTION / FOLLOW_UP）を判定
        val semanticIntent = EmbeddingGemma2Engine.classifyReplyIntentSemantic(subject, body, intent)

        val eg2ReplyContext = if (topPoints.isNotEmpty()) {
            "【EmbeddingGemma 2 抽出要点 ($semanticIntent)】: " + topPoints.joinToString(" / ")
        } else ""

        val prompt = if (isJa) {
            """
            以下のメールに対する「$intent」を伝える丁寧な返信メール本文を作成してください。
            差出人名（自分）は「Shinji」です。
            相手の件名: $subject
            $eg2ReplyContext
            相手の本文:
            ${body.take(1000)}
            """.trimIndent()
        } else {
            """
            Draft a polite and clear email reply expressing "$intent".
            My name is "Shinji".
            Original Subject: $subject
            $eg2ReplyContext
            Original Body:
            ${body.take(1000)}
            """.trimIndent()
        }

        if (initEngine()) {
            llmInference?.let { engine ->
                try {
                    val fullInput = "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
                    val response = synchronized(inferenceLock) {
                        engine.generateResponse(fullInput).trim()
                    }
                    if (response.isNotEmpty()) {
                        embeddingGemma2.memorizeGemma4MailInsight(ragKey, response)
                        return@withContext response
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Gemma reply generation failed: ${e.message}")
                }
            }
        }

        // Step 3: オンデバイス即時生成でも EmbeddingGemma 2 の意図分類＋抽出要点を100%反映した返信文を合成
        val baseSmartReply = generateSmartReplyByIntent(
            originalSender = sender,
            originalSubject = subject,
            intentType = semanticIntent
        )
        val synthesizedDraft = if (topPoints.isNotEmpty()) {
            val quotedPoint = topPoints.first().take(80)
            if (isJa) {
                baseSmartReply.replace(
                    "内容を確認いたしました。",
                    "「${quotedPoint}」の件、内容を確認いたしました。"
                )
            } else {
                baseSmartReply.replace(
                    "I have reviewed the details",
                    "Regarding \"$quotedPoint\", I have reviewed the details"
                )
            }
        } else {
            baseSmartReply
        }
        embeddingGemma2.memorizeGemma4MailInsight(ragKey, synthesizedDraft)
        synthesizedDraft
    }

    private fun heuristicSummarize(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.length > 5 }
        if (lines.isEmpty()) return text.take(100)
        return lines.take(3).joinToString("。 ")
    }
}
