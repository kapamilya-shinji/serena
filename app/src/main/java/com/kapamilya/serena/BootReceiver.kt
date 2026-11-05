package com.kapamilya.serena

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver (Direct Boot & Keyguard Breakthrough Coordinator)
 *
 * 初回再起動直後（PINロック解除前: ACTION_LOCKED_BOOT_COMPLETED）および
 * ロック解除後（ACTION_BOOT_COMPLETED）の両方で確実に起動し、
 * SerenaScreenReaderService の TTS エンジン再初期化・起動チャイム・
 * キーガード突破（バウンサー自動展開＆PIN入力パッド自動フォーカス）を強制起動する。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "serenaBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "Device boot completed ($action). Triggering DirectBoot TTS & Keyguard breakthrough.")
            try {
                val safeCtx = context.getSafeContext()
                // TTS準備完了前でも即座にDirect Boot生存確認チャイムを鳴らす
                val sound = SoundAndHapticHelper(safeCtx)
                sound.playSerenaStartupChime()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try { sound.release() } catch (_: Exception) {}
                }, 2500L)
            } catch (e: Exception) {
                Log.w(TAG, "DirectBoot startup chime warning: ${e.message}")
            }

            try {
                SerenaScreenReaderService.instance?.onDirectBootOrUnlockBroadcast(action)
            } catch (e: Exception) {
                Log.w(TAG, "Notify service on boot error: ${e.message}")
            }
        }
    }
}
