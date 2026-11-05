package com.kapamilya.serena.manager

import com.kapamilya.serena.ClipboardHistoryHelper
import com.kapamilya.serena.DeviceSecurityHelper
import com.kapamilya.serena.ScreenCurtainHelper
import com.kapamilya.serena.SerenaScreenReaderService
import com.kapamilya.serena.ShakeDetectorHelper
import com.kapamilya.serena.SmartNotificationFilterHelper
import com.kapamilya.serena.StatusAnnouncementHelper
import com.kapamilya.serena.telephony.SerenaCallManager

/**
 * システム関連ユーティリティ（画面カーテン、シェイク、通知、クリップボード、通話管理等）を一括制御するコントローラー
 */
class SystemUtilityController(
    private val service: SerenaScreenReaderService,
    private val speechEngine: com.kapamilya.serena.speech.SerenaSpeechEngine,
    private val onShakeDetected: () -> Unit
) {
    val screenCurtainHelper: ScreenCurtainHelper = ScreenCurtainHelper(service)
    val notificationFilterHelper: SmartNotificationFilterHelper = SmartNotificationFilterHelper(service)
    val clipboardHelper: ClipboardHistoryHelper = ClipboardHistoryHelper(service)
    val statusAnnouncementHelper: StatusAnnouncementHelper = StatusAnnouncementHelper(service)
    val securityHelper: DeviceSecurityHelper = DeviceSecurityHelper(service)
    val callManager: SerenaCallManager = SerenaCallManager(service, speechEngine)

    private val shakeDetectorHelper: ShakeDetectorHelper = ShakeDetectorHelper(service) {
        onShakeDetected()
    }

    fun startListening() {
        shakeDetectorHelper.start()
    }

    fun stopListening() {
        shakeDetectorHelper.stop()
    }

    fun toggleScreenCurtain(): Boolean {
        return screenCurtainHelper.toggleCurtain()
    }

    fun isScreenCurtainActive(): Boolean {
        return screenCurtainHelper.isCurtainEnabled
    }
}
