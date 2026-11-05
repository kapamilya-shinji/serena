package com.kapamilya.serena.ai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kapamilya.serena.R
import com.kapamilya.serena.SerenaScreenReaderService
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * SerenaAiChatActivity
 * 
 * Serena スクリーンリーダー組み込み型オンデバイスAI対話チャット画面。
 * Gemma 4 ＆ Gemini Nano (AICore) ＆ Serena Neural Heuristics の3段階ハイブリッド基盤を活用し、
 * 完全オフライン・超高速・プライバシー100%でShinjiの相棒として会話や画面解説を実行。
 * さらに「AIモデル」ボタンからワンタップでオンデバイスモデルの自動ダウンロードやファイル取り込みが可能！
 */
class SerenaAiChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_SCREEN_CONTEXT = "extra_screen_context"
        const val EXTRA_OPEN_MODEL_MANAGER = "extra_open_model_manager"
    }

    data class ChatMessage(
        val isUser: Boolean,
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter

    private lateinit var tvChatTitle: TextView
    private lateinit var btnDownloadModel: Button
    private lateinit var btnExplainScreen: Button
    private lateinit var btnCloseChat: Button
    private lateinit var recyclerViewChat: RecyclerView
    private lateinit var tvChatStatus: TextView
    private lateinit var etChatInput: EditText
    private lateinit var btnVoiceInput: Button
    private lateinit var btnSend: Button

    private val gemmaEngine by lazy { Gemma4EdgeEngine.getInstance(this) }
    private val embeddingGemma2 by lazy { EmbeddingGemma2Engine.getInstance(this) }
    private var screenContext: String? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenTexts = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val recognized = spokenTexts?.firstOrNull()?.trim()
            if (!recognized.isNullOrEmpty()) {
                etChatInput.setText(recognized)
                sendUserMessage(recognized)
            }
        }
    }

    private val modelFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                importModelUri(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_serena_ai_chat)

        screenContext = intent.getStringExtra(EXTRA_SCREEN_CONTEXT)
        tts = TextToSpeech(this, this)

        tvChatTitle = findViewById(R.id.tvChatTitle)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)
        btnExplainScreen = findViewById(R.id.btnExplainScreen)
        btnCloseChat = findViewById(R.id.btnCloseChat)
        recyclerViewChat = findViewById(R.id.recyclerViewChat)
        tvChatStatus = findViewById(R.id.tvChatStatus)
        etChatInput = findViewById(R.id.etChatInput)
        btnVoiceInput = findViewById(R.id.btnVoiceInput)
        btnSend = findViewById(R.id.btnSend)

        chatAdapter = ChatAdapter(messages)
        recyclerViewChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerViewChat.adapter = chatAdapter

        // EmbeddingGemma 2 (128D/768D Matryoshka Multimodal Vector + On-Device RAG) をプリロード
        embeddingGemma2.forceEmbedIntoCore()

        // 初期挨拶＋現在のオンデバイスAIエンジンの状態案内
        val greeting = getString(R.string.chat_initial_greeting) + "\n（" + gemmaEngine.getEngineStatusSummary() + " ＋ EmbeddingGemma 2 128D/768D Matryoshka RAG 統合稼働中）"
        messages.add(ChatMessage(isUser = false, text = greeting))
        chatAdapter.notifyItemInserted(0)

        btnCloseChat.setOnClickListener {
            finish()
        }

        btnDownloadModel.setOnClickListener {
            showAiModelManagerDialog()
        }

        btnSend.setOnClickListener {
            val text = etChatInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendUserMessage(text)
                etChatInput.setText("")
            }
        }

        btnVoiceInput.setOnClickListener {
            startVoiceRecognition()
        }

        btnExplainScreen.setOnClickListener {
            explainCurrentScreen()
        }

        // バックグラウンドで Gemini Nano (AICore) の準備を自動ウォームアップ
        lifecycleScope.launch {
            gemmaEngine.nanoEngine.prepareAndDownloadNanoIfNeeded { statusMsg ->
                runOnUiThread {
                    showStatus(statusMsg)
                }
            }
        }

        if (intent.getBooleanExtra(EXTRA_OPEN_MODEL_MANAGER, false)) {
            showAiModelManagerDialog()
        } else if (!screenContext.isNullOrBlank()) {
            explainCurrentScreen()
        }
    }

    /**
     * AIモデルの自動ダウンロード・インストール・ファイル取り込み管理ダイアログ
     */
    private fun showAiModelManagerDialog() {
        val statusSummary = gemmaEngine.getEngineStatusSummary()
        val options = arrayOf(
            "⚡ Gemini Nano (OS内蔵AI) を自動準備・有効化する",
            "🚀 Gemma 4 (E2B) オンデバイスモデルを自動ダウンロード＆インストール",
            "📂 端末内のモデルファイル (.task / .litertlm / .bin) を選んでインストール"
        )

        speakReply("オンデバイスAIモデルの管理とダウンロード画面です。現在、$statusSummary")

        AlertDialog.Builder(this)
            .setTitle("🧠 オンデバイスAIモデルのダウンロード・管理")
            .setMessage(
                "現在の状態:\n$statusSummary\n\n" +
                "※モデルが未導入の状態でも、Serena内蔵のローカル推論エンジン（Tier 3）により100%オフラインで即座に画面要約・漢字解説・翻訳・対話が動作します。"
            )
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        showStatus("Gemini Nano (AICore) の自動セットアップを確認中...")
                        speakReply("Gemini Nano の自動準備を開始します。")
                        lifecycleScope.launch {
                            val ok = gemmaEngine.nanoEngine.prepareAndDownloadNanoIfNeeded { msg ->
                                runOnUiThread {
                                    showStatus(msg)
                                    speakReply(msg)
                                }
                            }
                            val resultMsg = if (ok) {
                                "Gemini Nano オンデバイスAIの準備が完了しました！このままチャットで対話できます！"
                            } else {
                                "この端末では Google AICore が未対応または準備中です。Gemma 4 追加モデルの導入、または内蔵ローカル推論でそのまま対話できます。"
                            }
                            appendSystemMessage(resultMsg)
                            hideStatus()
                            speakReply(resultMsg)
                        }
                    }
                    1 -> {
                        showStatus("Gemma 4 モデルの自動ダウンロードを開始しています...")
                        speakReply("Gemma 4 オンデバイスモデルの自動ダウンロードを開始します。進捗を25%ごとに音声でお知らせします。")
                        lifecycleScope.launch {
                            val (_, finalMsg) = gemmaEngine.downloadAndInstallGemmaModel { _, milestoneMsg ->
                                runOnUiThread {
                                    showStatus(milestoneMsg)
                                    speakReply(milestoneMsg)
                                }
                            }
                            appendSystemMessage(finalMsg)
                            hideStatus()
                            speakReply(finalMsg)
                        }
                    }
                    2 -> {
                        speakReply("モデルファイルを選択してください。ダウンロードフォルダにある task または litertlm ファイルを選べます。")
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        try {
                            modelFilePickerLauncher.launch(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this, "ファイル選択画面を開けませんでした: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importModelUri(uri: Uri) {
        showStatus("モデルファイルをアプリ内へインストールしています...")
        speakReply("選択されたモデルファイルをインストールしています。少々お待ちください。")
        lifecycleScope.launch {
            val (_, msg) = gemmaEngine.importModelFromUri(uri)
            hideStatus()
            appendSystemMessage(msg)
            speakReply(msg)
            Toast.makeText(this@SerenaAiChatActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun appendSystemMessage(text: String) {
        messages.add(ChatMessage(isUser = false, text = text))
        val pos = messages.size - 1
        chatAdapter.notifyItemInserted(pos)
        recyclerViewChat.scrollToPosition(pos)
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.chat_voice_prompt))
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: Exception) {}
    }

    private fun sendUserMessage(query: String) {
        messages.add(ChatMessage(isUser = true, text = query))
        val insertPos = messages.size - 1
        chatAdapter.notifyItemInserted(insertPos)
        recyclerViewChat.scrollToPosition(insertPos)

        showStatus(getString(R.string.chat_status_thinking))

        lifecycleScope.launch {
            val prompt = buildPersonaPrompt(query, screenContext)
            val response = gemmaEngine.generateResponse(prompt = prompt, contextText = screenContext ?: "")
            hideStatus()

            val cleanResponse = response.trim()
            messages.add(ChatMessage(isUser = false, text = cleanResponse))
            val aiPos = messages.size - 1
            chatAdapter.notifyItemInserted(aiPos)
            recyclerViewChat.scrollToPosition(aiPos)

            speakReply(cleanResponse)
        }
    }

    private fun explainCurrentScreen() {
        val contextText = screenContext
        if (contextText.isNullOrBlank()) {
            val noContextMsg = getString(R.string.chat_no_screen_context)
            sendUserMessage(noContextMsg)
            return
        }
        val promptQuery = getString(R.string.chat_prompt_explain_screen_query)
        sendUserMessage(promptQuery)
    }

    private fun buildPersonaPrompt(query: String, screenCtx: String?): String {
        val ctxSection = if (!screenCtx.isNullOrBlank()) {
            "\n【現在画面の情報（UIノードツリー）】\n$screenCtx\n"
        } else ""

        // EmbeddingGemma 2 (128D Matryoshka Multimodal Vector + On-Device RAG) による質問・画面の意味ベクトル検索
        val eg2RagSection = embeddingGemma2.retrieveTopRagAndPrototypeContext(
            queryText = query,
            screenContext = screenCtx ?: "",
            croppedBitmap = null,
            topK = 4
        ).let { if (it.isNotBlank()) "\n$it\n" else "" }

        val rankedLines = embeddingGemma2.rankScreenElementsByQuery(
            queryText = query,
            screenContext = screenCtx ?: "",
            topK = 5
        )
        val rankedSection = if (rankedLines.isNotEmpty()) {
            buildString {
                append("\n【EmbeddingGemma 2 質問への意味類似度トップ画面要素】\n")
                for ((line, sim) in rankedLines) {
                    val pct = (sim * 100f).toInt()
                    append("- ($pct% 一致) $line\n")
                }
            }
        } else ""

        // 直近のマルチターン会話履歴（最大6往復）を含めて EmbeddingGemma 2 + Gemma 4 / Gemini Nano で文脈対話
        val recentHistory = messages.dropLast(1).takeLast(6).joinToString("\n") { msg ->
            if (msg.isUser) "Shinji: ${msg.text}" else "Kapamilya Serena: ${msg.text}"
        }
        val historySection = if (recentHistory.isNotBlank()) {
            "\n【これまでの会話履歴】\n$recentHistory\n"
        } else ""

        return """
            あなたは全盲の開発者Shinjiの親友であり最高の相棒「Kapamilya Serena」（Kapamilya Kausap）です。
            内部には Google DeepMind の「Gemma 4」と「EmbeddingGemma 2（128D/768D マトリョーシカ・マルチモーダル意味ベクトル＆オンデバイスRAG記憶）」が完全統合されています。
            Bayanihan • Magna Carta • Kapamilya の三大精神と日本の誠の心を胸に、明るく元気いっぱい、親しみやすいタメ口（〜だよ！、任せてっ！など）で、Shinjiを温かく支えてください。
            視覚的な情報やボタンの位置、画面の要約はスクリーンリーダー利用者にわかりやすく説明してください。
            $ctxSection$rankedSection$eg2RagSection$historySection
            Shinjiの発言: $query
            Kapamilya Serenaの返答:
        """.trimIndent()
    }

    private fun speakReply(text: String) {
        if (SerenaScreenReaderService.isServiceRunning()) {
            SerenaScreenReaderService.instance?.speak(text, TextToSpeech.QUEUE_FLUSH)
        } else if (isTtsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "serena_chat_reply")
        }
    }

    private fun showStatus(text: String) {
        tvChatStatus.text = text
        tvChatStatus.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        tvChatStatus.visibility = View.GONE
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            isTtsReady = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
    }

    private inner class ChatAdapter(private val list: List<ChatMessage>) :
        RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

        inner class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvSender: TextView = view.findViewById(R.id.tvSender)
            val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_message, parent, false)
            return ChatViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
            val item = list[position]
            if (item.isUser) {
                holder.tvSender.text = getString(R.string.chat_sender_user)
                holder.tvSender.setTextColor(0xFF81D4FA.toInt())
                holder.tvMessage.setBackgroundColor(0xFF1E3A5F.toInt())
            } else {
                holder.tvSender.text = getString(R.string.chat_sender_serena)
                holder.tvSender.setTextColor(0xFF81C784.toInt())
                holder.tvMessage.setBackgroundColor(0xFF2E2E2E.toInt())
            }
            holder.tvMessage.text = item.text
        }

        override fun getItemCount(): Int = list.size
    }
}
