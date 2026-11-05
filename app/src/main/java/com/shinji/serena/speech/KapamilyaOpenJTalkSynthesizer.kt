package com.shinji.serena.speech

/**
 * Native JNI symbol bridge for prebuilt libopenjtalk_jni.so
 */
object KapamilyaOpenJTalkSynthesizer {
    @JvmStatic
    external fun nativeSynthesizeHts(
        text: String,
        dicDir: String,
        voicePath: String,
        rate: Float,
        pitch: Float
    ): ByteArray?
}
