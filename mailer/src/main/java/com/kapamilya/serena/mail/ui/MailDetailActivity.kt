package com.kapamilya.serena.mail.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kapamilya.serena.mail.R
import com.kapamilya.serena.mail.ai.Gemma4MailEngine
import com.kapamilya.serena.mail.crypto.OpenPgpManager
import com.kapamilya.serena.mail.data.AccountPreferences
import com.kapamilya.serena.mail.data.MailMessage
import com.kapamilya.serena.mail.service.GmailService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * MailDetailActivity
 * メール詳細閲覧 ＆ Gemma 4 オンデバイス要約 ＆ AI返信 ＆ 標準IMAPアクション（スター・アーカイブ・未読・ゴミ箱）画面
 */
class MailDetailActivity : AppCompatActivity() {

    private var currentMail: MailMessage? = null
    private val gemma4Engine by lazy { Gemma4MailEngine.getInstance(this) }
    private val gmailService = GmailService()
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    private lateinit var btnSummarizeGemma4: Button
    private lateinit var btnAiReply: Button
    private lateinit var btnToggleStar: Button
    private lateinit var btnArchive: Button
    private lateinit var btnMarkUnread: Button
    private lateinit var btnDeleteMail: Button
    private lateinit var layoutSummaryCard: LinearLayout
    private lateinit var tvSummaryContent: TextView
    private lateinit var tvSubject: TextView
    private lateinit var tvSender: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvBody: TextView
    private lateinit var btnReply: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mail_detail)
        com.kapamilya.serena.mail.sound.KapamilyaKrisSoundEngine.attachToActivity(this, playStartupSound = false)

        currentMail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("MAIL_ITEM", MailMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("MAIL_ITEM") as? MailMessage
        }

        val mail = currentMail
        if (mail == null) {
            finish()
            return
        }

        btnSummarizeGemma4 = findViewById(R.id.btnSummarizeGemma4)
        btnAiReply = findViewById(R.id.btnAiReply)
        btnToggleStar = findViewById(R.id.btnToggleStar)
        btnArchive = findViewById(R.id.btnArchive)
        btnMarkUnread = findViewById(R.id.btnMarkUnread)
        btnDeleteMail = findViewById(R.id.btnDeleteMail)
        layoutSummaryCard = findViewById(R.id.layoutSummaryCard)
        tvSummaryContent = findViewById(R.id.tvSummaryContent)
        tvSubject = findViewById(R.id.tvSubject)
        tvSender = findViewById(R.id.tvSender)
        tvDate = findViewById(R.id.tvDate)
        tvBody = findViewById(R.id.tvBody)
        btnReply = findViewById(R.id.btnReply)

        val tvPgpVerificationBanner = findViewById<TextView>(R.id.tvPgpVerificationBanner)
        val layoutReadReceiptBanner = findViewById<LinearLayout>(R.id.layoutReadReceiptBanner)
        val tvReadReceiptInfo = findViewById<TextView>(R.id.tvReadReceiptInfo)
        val btnSendReadReceipt = findViewById<Button>(R.id.btnSendReadReceipt)

        // OpenPGP クリアテキスト署名の自動検証
        val pgpResult = OpenPgpManager.verifyCleartextMessage(
            context = this,
            senderEmail = mail.senderAddress.ifEmpty { mail.sender },
            rawBody = mail.body,
            headerPubKeyB64 = mail.openPgpPubKeyHeader
        )
        if (pgpResult.isSigned) {
            tvPgpVerificationBanner.visibility = View.VISIBLE
            tvPgpVerificationBanner.text = pgpResult.statusSummary
            tvPgpVerificationBanner.contentDescription = pgpResult.statusSummary
        }

        tvSubject.text = mail.subject
        tvSender.text = getString(R.string.mail_label_from, mail.sender)
        val dateStr = if (mail.receivedDate != null) dateFormat.format(mail.receivedDate) else ""
        tvDate.text = getString(R.string.mail_label_date, dateStr)
        tvBody.text = if (pgpResult.isSigned) pgpResult.cleanBodyText else mail.body

        // アクセシビリティヘッダー設定
        tvSubject.contentDescription = getString(R.string.mail_label_subject, mail.subject)
        tvSender.contentDescription = getString(R.string.mail_label_from, mail.sender)
        updateStarButtonUI(mail.isStarred)

        val prefs = AccountPreferences(this)

        // 開封確認通知 (MDN / Read Receipt) 要求の検出＆ワンタップ返信送信
        if (mail.readReceiptRequestedTo.isNotBlank()) {
            layoutReadReceiptBanner.visibility = View.VISIBLE
            val receiptMsg = "📩 差出人（${mail.readReceiptRequestedTo}）から開封確認通知（Read Receipt）が要求されています。"
            tvReadReceiptInfo.text = receiptMsg
            tvReadReceiptInfo.contentDescription = receiptMsg
            btnSendReadReceipt.setOnClickListener {
                val myEmail = prefs.getEmail()
                val myCred = prefs.getAuthCredential()
                val isOAuth = prefs.isOAuth()
                if (myEmail.isNotEmpty() && myCred.isNotEmpty()) {
                    btnSendReadReceipt.isEnabled = false
                    Toast.makeText(this, "開封確認通知を送信しています...", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        val res = gmailService.sendReadReceiptMdn(
                            context = applicationContext,
                            fromEmail = myEmail,
                            tokenOrPassword = myCred,
                            receiptToAddress = mail.readReceiptRequestedTo,
                            originalSubject = mail.subject,
                            originalMessageId = mail.rfcMessageIdHeader,
                            isOAuth = isOAuth
                        )
                        res.onSuccess {
                            btnSendReadReceipt.text = "✅ 開封確認通知を送信済み"
                            Toast.makeText(this@MailDetailActivity, "差出人へ開封確認通知（開封済み）を送信しました！", Toast.LENGTH_LONG).show()
                        }.onFailure { err ->
                            btnSendReadReceipt.isEnabled = true
                            Toast.makeText(this@MailDetailActivity, "開封確認通知の送信に失敗しました: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        // 未読メールの場合、バックグラウンドでGmailサーバーに即座に既読フラグ（SEEN）を同期
        if (mail.isUnread) {
            mail.isUnread = false
            val email = prefs.getEmail()
            val credential = prefs.getAuthCredential()
            val isOAuth = prefs.isOAuth()
            val uid = mail.uid
            if (email.isNotEmpty() && credential.isNotEmpty() && uid > 0) {
                lifecycleScope.launch {
                    withContext(NonCancellable) {
                        gmailService.markAsRead(email, credential, uid, isOAuth, mail.folderType, isRead = true)
                    }
                }
            }
        }

        // スター切り替えボタン
        btnToggleStar.setOnClickListener {
            val targetMail = currentMail ?: return@setOnClickListener
            val newStarred = !targetMail.isStarred
            targetMail.isStarred = newStarred
            updateStarButtonUI(newStarred)

            val email = prefs.getEmail()
            val credential = prefs.getAuthCredential()
            val isOAuth = prefs.isOAuth()
            if (email.isNotEmpty() && credential.isNotEmpty() && targetMail.uid > 0) {
                lifecycleScope.launch {
                    withContext(NonCancellable) {
                        gmailService.setStarred(email, credential, targetMail.uid, newStarred, isOAuth, targetMail.folderType)
                    }
                    Toast.makeText(this@MailDetailActivity, getString(R.string.mail_status_star_updated), Toast.LENGTH_SHORT).show()
                }
            }
        }

        // アーカイブボタン
        btnArchive.setOnClickListener {
            val targetMail = currentMail ?: return@setOnClickListener
            val email = prefs.getEmail()
            val credential = prefs.getAuthCredential()
            val isOAuth = prefs.isOAuth()
            if (email.isNotEmpty() && credential.isNotEmpty() && targetMail.uid > 0) {
                btnArchive.isEnabled = false
                lifecycleScope.launch {
                    withContext(NonCancellable) {
                        gmailService.archiveMessage(email, credential, targetMail.uid, isOAuth, targetMail.folderType)
                    }
                    Toast.makeText(this@MailDetailActivity, getString(R.string.mail_status_archived), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        // 未読に戻すボタン
        btnMarkUnread.setOnClickListener {
            val targetMail = currentMail ?: return@setOnClickListener
            targetMail.isUnread = true
            val email = prefs.getEmail()
            val credential = prefs.getAuthCredential()
            val isOAuth = prefs.isOAuth()
            if (email.isNotEmpty() && credential.isNotEmpty() && targetMail.uid > 0) {
                btnMarkUnread.isEnabled = false
                lifecycleScope.launch {
                    withContext(NonCancellable) {
                        gmailService.markAsRead(email, credential, targetMail.uid, isOAuth, targetMail.folderType, isRead = false)
                    }
                    Toast.makeText(this@MailDetailActivity, getString(R.string.mail_status_marked_unread), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        // ゴミ箱へ移動ボタン
        btnDeleteMail.setOnClickListener {
            val targetMail = currentMail ?: return@setOnClickListener
            val email = prefs.getEmail()
            val credential = prefs.getAuthCredential()
            val isOAuth = prefs.isOAuth()
            if (email.isNotEmpty() && credential.isNotEmpty() && targetMail.uid > 0) {
                btnDeleteMail.isEnabled = false
                lifecycleScope.launch {
                    withContext(NonCancellable) {
                        gmailService.moveToTrash(email, credential, targetMail.uid, isOAuth, targetMail.folderType)
                    }
                    Toast.makeText(this@MailDetailActivity, getString(R.string.mail_status_moved_trash), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        // Gemma 4 で要約ボタン
        btnSummarizeGemma4.setOnClickListener {
            val targetMail = currentMail ?: return@setOnClickListener
            btnSummarizeGemma4.isEnabled = false
            layoutSummaryCard.visibility = View.VISIBLE
            tvSummaryContent.text = getString(R.string.mail_status_summarizing)

            lifecycleScope.launch {
                try {
                    val summary = gemma4Engine.summarizeEmail(targetMail.sender, targetMail.subject, targetMail.body)
                    tvSummaryContent.text = summary
                    layoutSummaryCard.contentDescription = getString(R.string.mail_summary_result_fmt, summary)
                } finally {
                    btnSummarizeGemma4.isEnabled = true
                }
            }
        }

        // AI返信文作成ボタン
        btnAiReply.setOnClickListener {
            val targetMail = currentMail ?: return@setOnClickListener
            val replyIntent = Intent(this, ComposeMailActivity::class.java).apply {
                putExtra("REPLY_TO", targetMail.senderAddress.ifEmpty { targetMail.sender })
                putExtra("REPLY_SUBJECT", if (targetMail.subject.startsWith("Re:", ignoreCase = true)) targetMail.subject else "Re: ${targetMail.subject}")
                putExtra("ORIGINAL_BODY", targetMail.body)
                putExtra("AUTO_GENERATE_AI", true)
            }
            startActivity(replyIntent)
        }

        // 通常返信ボタン
        btnReply.setOnClickListener {
            val targetMail = currentMail ?: return@setOnClickListener
            val replyIntent = Intent(this, ComposeMailActivity::class.java).apply {
                putExtra("REPLY_TO", targetMail.senderAddress.ifEmpty { targetMail.sender })
                putExtra("REPLY_SUBJECT", if (targetMail.subject.startsWith("Re:", ignoreCase = true)) targetMail.subject else "Re: ${targetMail.subject}")
                putExtra("ORIGINAL_BODY", targetMail.body)
            }
            startActivity(replyIntent)
        }
    }

    private fun updateStarButtonUI(isStarred: Boolean) {
        val label = if (isStarred) getString(R.string.mail_btn_star_off) else getString(R.string.mail_btn_star_on)
        btnToggleStar.text = label
        btnToggleStar.contentDescription = label
    }
}
