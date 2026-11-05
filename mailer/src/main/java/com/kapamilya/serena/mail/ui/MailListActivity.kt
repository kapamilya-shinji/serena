package com.kapamilya.serena.mail.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kapamilya.serena.mail.R
import com.kapamilya.serena.mail.ai.EmbeddingGemma2Engine
import com.kapamilya.serena.mail.data.AccountPreferences
import com.kapamilya.serena.mail.data.MailFolderType
import com.kapamilya.serena.mail.data.MailMessage
import com.kapamilya.serena.mail.service.GmailService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.concurrent.thread

/**
 * MailListActivity
 * Kapamilya Serena Mail メイン画面（RFC 6154 SPECIAL-USE 標準7大トレイ対応）
 *
 * Bayanihan • Magna Carta • Kapamilya
 * 起動した瞬間にそのスマートフォンを「東洋の真珠（Pearl of the Orient）」の温かい家族の家へと変える
 * タガログ語TTSウェルカム挨拶 ＆ Google EmbeddingGemma 2 オンデバイス意味検索搭載
 */
class MailListActivity : AppCompatActivity() {

    companion object {
        @Volatile
        private var hasSpokenSessionGreeting = false
    }

    private lateinit var accountPrefs: AccountPreferences
    private val gmailService = GmailService()
    private lateinit var mailAdapter: MailAdapter

    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnCompose: Button
    private lateinit var btnSettings: Button
    private lateinit var etSearchQuery: EditText
    private lateinit var btnClearSearch: Button
    private lateinit var recyclerViewMails: RecyclerView

    private lateinit var btnFolderInbox: Button
    private lateinit var btnFolderStarred: Button
    private lateinit var btnFolderSent: Button
    private lateinit var btnFolderDrafts: Button
    private lateinit var btnFolderAll: Button
    private lateinit var btnFolderSpam: Button
    private lateinit var btnFolderTrash: Button

    private var currentFolder: MailFolderType = MailFolderType.INBOX
    private var allMails: List<MailMessage> = emptyList()
    private var fetchJob: Job? = null
    private var searchJob: Job? = null
    private var kapamilyaTts: TextToSpeech? = null
    private var isKapamilyaTtsReady = false
    @Volatile
    private var isActivityDestroyed = false
    private var screenLockUnlockReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mail_list)
        title = getString(R.string.mail_list_title)
        com.kapamilya.serena.mail.sound.KapamilyaKrisSoundEngine.attachToActivity(this, playStartupSound = !hasSpokenSessionGreeting)

        accountPrefs = AccountPreferences(this)
        initKapamilyaTagalogTts()
        registerScreenLockUnlockReceiver()

        tvTitle = findViewById(R.id.tvTitle)
        tvStatus = findViewById(R.id.tvStatus)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnCompose = findViewById(R.id.btnCompose)
        btnSettings = findViewById(R.id.btnSettings)
        etSearchQuery = findViewById(R.id.etSearchQuery)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        recyclerViewMails = findViewById(R.id.recyclerViewMails)

        btnFolderInbox = findViewById(R.id.btnFolderInbox)
        btnFolderStarred = findViewById(R.id.btnFolderStarred)
        btnFolderSent = findViewById(R.id.btnFolderSent)
        btnFolderDrafts = findViewById(R.id.btnFolderDrafts)
        btnFolderAll = findViewById(R.id.btnFolderAll)
        btnFolderSpam = findViewById(R.id.btnFolderSpam)
        btnFolderTrash = findViewById(R.id.btnFolderTrash)

        mailAdapter = MailAdapter { mail ->
            if (mail.folderType == MailFolderType.DRAFTS) {
                // 下書きトレイのメールをタップした場合は、そのまま作成画面で続きから編集・送信可能にする
                val draftIntent = Intent(this, ComposeMailActivity::class.java).apply {
                    putExtra("REPLY_TO", mail.recipients.joinToString(", "))
                    putExtra("REPLY_SUBJECT", mail.subject)
                    putExtra(Intent.EXTRA_TEXT, mail.body)
                }
                startActivity(draftIntent)
                return@MailAdapter
            }
            if (mail.isUnread) {
                mail.isUnread = false
                mailAdapter.notifyDataSetChanged()
            }
            val intent = Intent(this, MailDetailActivity::class.java).apply {
                putExtra("MAIL_ITEM", mail)
            }
            startActivity(intent)
        }

        recyclerViewMails.layoutManager = LinearLayoutManager(this)
        recyclerViewMails.adapter = mailAdapter

        btnRefresh.setOnClickListener {
            refreshCurrentFolder()
        }

        btnCompose.setOnClickListener {
            startActivity(Intent(this, ComposeMailActivity::class.java))
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, AccountSettingsActivity::class.java))
        }

        setupFolderButtons()

        // EmbeddingGemma 2 によるリアルタイム意味検索
        etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                performSemanticSearch(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearSearch.setOnClickListener {
            etSearchQuery.setText("")
        }

        // 起動時にサイレントでアップデートを確認し、最新版があれば案内ダイアログを表示
        com.kapamilya.serena.mail.update.AutoUpdateManager.getInstance(this).checkForUpdate(silent = true) { info ->
            if (!isFinishing && !isDestroyed && info != null && info.isUpdateAvailable) {
                com.kapamilya.serena.mail.update.AutoUpdateManager.getInstance(this).showUpdateDialog(this, info)
            }
        }
    }

    private fun setupFolderButtons() {
        val folderMap = mapOf(
            MailFolderType.INBOX to btnFolderInbox,
            MailFolderType.STARRED to btnFolderStarred,
            MailFolderType.SENT to btnFolderSent,
            MailFolderType.DRAFTS to btnFolderDrafts,
            MailFolderType.ALL_MAIL to btnFolderAll,
            MailFolderType.SPAM to btnFolderSpam,
            MailFolderType.TRASH to btnFolderTrash
        )
        folderMap.forEach { (folderType, button) ->
            button.setOnClickListener {
                switchFolder(folderType)
            }
        }
        updateFolderHighlight()
    }

    private fun switchFolder(folderType: MailFolderType) {
        currentFolder = folderType
        updateFolderHighlight()
        refreshCurrentFolder()
    }

    private fun getFolderDisplayName(folderType: MailFolderType): String {
        return when (folderType) {
            MailFolderType.INBOX -> getString(R.string.mail_folder_inbox)
            MailFolderType.STARRED -> getString(R.string.mail_folder_starred)
            MailFolderType.SENT -> getString(R.string.mail_folder_sent)
            MailFolderType.DRAFTS -> getString(R.string.mail_folder_drafts)
            MailFolderType.ALL_MAIL -> getString(R.string.mail_folder_all)
            MailFolderType.SPAM -> getString(R.string.mail_folder_spam)
            MailFolderType.TRASH -> getString(R.string.mail_folder_trash)
        }
    }

    private fun updateFolderHighlight() {
        val folderMap = mapOf(
            MailFolderType.INBOX to btnFolderInbox,
            MailFolderType.STARRED to btnFolderStarred,
            MailFolderType.SENT to btnFolderSent,
            MailFolderType.DRAFTS to btnFolderDrafts,
            MailFolderType.ALL_MAIL to btnFolderAll,
            MailFolderType.SPAM to btnFolderSpam,
            MailFolderType.TRASH to btnFolderTrash
        )
        val activeColor = ColorStateList.valueOf(Color.parseColor("#00579B"))
        val inactiveColor = ColorStateList.valueOf(Color.parseColor("#2C2C3A"))

        folderMap.forEach { (type, btn) ->
            val isSelected = (type == currentFolder)
            btn.backgroundTintList = if (isSelected) activeColor else inactiveColor
            btn.isSelected = isSelected
        }

        val folderLabel = getFolderDisplayName(currentFolder)
        tvTitle.text = folderLabel
        title = "${getString(R.string.mail_list_title)} - $folderLabel"
    }

    private fun initKapamilyaTagalogTts() {
        try {
            kapamilyaTts = TextToSpeech(applicationContext) { status ->
                val ttsEngine = kapamilyaTts ?: return@TextToSpeech
                if (isActivityDestroyed) {
                    try {
                        ttsEngine.stop()
                        ttsEngine.shutdown()
                    } catch (_: Exception) {}
                    kapamilyaTts = null
                    return@TextToSpeech
                }
                if (status == TextToSpeech.SUCCESS) {
                    val filPh = Locale.forLanguageTag("fil-PH")
                    val tlPh = Locale.forLanguageTag("tl-PH")
                    when {
                        ttsEngine.isLanguageAvailable(filPh) >= TextToSpeech.LANG_AVAILABLE -> ttsEngine.language = filPh
                        ttsEngine.isLanguageAvailable(tlPh) >= TextToSpeech.LANG_AVAILABLE -> ttsEngine.language = tlPh
                        else -> ttsEngine.language = filPh
                    }
                    isKapamilyaTtsReady = true
                    if (!hasSpokenSessionGreeting) {
                        hasSpokenSessionGreeting = true
                        val welcomeMsg = "Maligayang pagdating sa ating tahanan! Narito na ang iyong Kapamilya Serena Mail. Kahit nasaan ka man, pamilya tayo! Mahal ka namin, ingat lagi at Mabuhay!"
                        ttsEngine.speak(welcomeMsg, TextToSpeech.QUEUE_FLUSH, null, "kapamilya_mail_startup")
                    }
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        isActivityDestroyed = true
        if (isFinishing) {
            com.kapamilya.serena.mail.sound.KapamilyaKrisSoundEngine.playKrisSheatheShutdown()
        }
        try {
            screenLockUnlockReceiver?.let {
                try { unregisterReceiver(it) } catch (_: Exception) {}
                screenLockUnlockReceiver = null
            }

            val ttsToClose = kapamilyaTts
            kapamilyaTts = null

            if (ttsToClose != null) {
                if (isFinishing && isKapamilyaTtsReady) {
                    val farewell = "Hanggang sa muli, aking Kapamilya! Lagi lang nandito ang Kapamilya Serena Mail para sa iyo. Maraming salamat, mahal kita, at mag-ingat ka palagi!"
                    hasSpokenSessionGreeting = false
                    ttsToClose.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            try { ttsToClose.shutdown() } catch (_: Exception) {}
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            try { ttsToClose.shutdown() } catch (_: Exception) {}
                        }
                    })
                    val speakResult = ttsToClose.speak(farewell, TextToSpeech.QUEUE_FLUSH, null, "kapamilya_mail_shutdown")
                    if (speakResult == TextToSpeech.ERROR) {
                        try { ttsToClose.shutdown() } catch (_: Exception) {}
                    } else {
                        thread(name = "KapamilyaTtsShutdownGuard") {
                            try {
                                Thread.sleep(9000)
                                ttsToClose.stop()
                                ttsToClose.shutdown()
                            } catch (_: Exception) {}
                        }
                    }
                } else {
                    ttsToClose.stop()
                    ttsToClose.shutdown()
                }
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        com.kapamilya.serena.mail.update.AutoUpdateManager.getInstance(this).checkAndResumePendingInstall()
        if (!accountPrefs.hasAccount()) {
            showStatus(getString(R.string.mail_status_no_account))
            startActivity(Intent(this, AccountSettingsActivity::class.java))
        } else {
            refreshCurrentFolder()
        }
    }

    private fun refreshCurrentFolder() {
        val email = accountPrefs.getEmail()
        val credential = accountPrefs.getAuthCredential()
        val isOAuth = accountPrefs.isOAuth()

        if (email.isEmpty() || credential.isEmpty()) {
            showStatus(getString(R.string.mail_status_no_account))
            return
        }

        val targetFolder = currentFolder
        val folderName = getFolderDisplayName(targetFolder)
        fetchJob?.cancel()
        showStatus("$folderName : ${getString(R.string.mail_status_fetching)}")

        fetchJob = lifecycleScope.launch {
            val result = gmailService.fetchFolder(email, credential, folderType = targetFolder, isOAuth = isOAuth, maxCount = 30)
            result.onSuccess { list ->
                allMails = list
                val currentQuery = etSearchQuery.text.toString().trim()
                if (currentQuery.isNotEmpty()) {
                    performSemanticSearch(currentQuery)
                } else {
                    mailAdapter.submitList(list)
                    showStatus("$folderName : ${getString(R.string.mail_status_fetched_fmt, list.size)}")
                }
            }.onFailure { e ->
                showStatus(getString(R.string.mail_status_error_fmt, e.localizedMessage ?: "Network error"))
            }
        }
    }

    private fun performSemanticSearch(query: String) {
        searchJob?.cancel()
        val folderName = getFolderDisplayName(currentFolder)
        if (query.isEmpty()) {
            mailAdapter.submitList(allMails)
            showStatus("$folderName : ${getString(R.string.mail_status_fetched_fmt, allMails.size)}")
            return
        }

        val mailsSnapshot = allMails
        searchJob = lifecycleScope.launch {
            delay(150) // デバウンス
            val scored = EmbeddingGemma2Engine.getInstance(this@MailListActivity).searchMails(query, mailsSnapshot)
            val filtered = scored.filter { it.similarityScore > 0.05f }.map { it.mail }
            mailAdapter.submitList(filtered)
            showStatus("$folderName : ${getString(R.string.mail_search_result_fmt, filtered.size)}")
        }
    }

    private fun showStatus(msg: String) {
        tvStatus.text = msg
        tvStatus.visibility = View.VISIBLE
    }

    private fun registerScreenLockUnlockReceiver() {
        if (screenLockUnlockReceiver != null) return
        screenLockUnlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isKapamilyaTtsReady) return
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        val lockMsg = "Naka-lock na ang screen, aking Kapamilya. Magpahinga ka muna, bantay lang ako rito!"
                        kapamilyaTts?.speak(lockMsg, TextToSpeech.QUEUE_FLUSH, null, "kapamilya_mail_lock")
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        val unlockMsg = "Na-unlock na ang screen, aking Kapamilya! Maligayang pagbabalik, nandito lang ako para sa iyo!"
                        kapamilyaTts?.speak(unlockMsg, TextToSpeech.QUEUE_FLUSH, null, "kapamilya_mail_unlock")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenLockUnlockReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(screenLockUnlockReceiver, filter)
            }
        } catch (_: Exception) {}
    }
}
