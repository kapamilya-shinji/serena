#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <pthread.h>

#include "mecab.h"
#include "njd.h"
#include "jpcommon.h"
#include "HTS_engine.h"

#include "text2mecab.h"
#include "mecab2njd.h"
#include "njd_set_pronunciation.h"
#include "njd_set_digit.h"
#include "njd_set_accent_phrase.h"
#include "njd_set_accent_type.h"
#include "njd_set_unvoiced_vowel.h"
#include "njd_set_long_vowel.h"
#include "njd2jpcommon.h"

typedef struct _Open_JTalk {
    Mecab mecab;
    NJD njd;
    JPCommon jpcommon;
    HTS_Engine engine;
} Open_JTalk;

static Open_JTalk g_ojt;
static int g_initialized = 0;
static char g_dic_dir[1024] = "";
static char g_voice_path[1024] = "";
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

static void ojt_init(Open_JTalk *ojt) {
    Mecab_initialize(&ojt->mecab);
    NJD_initialize(&ojt->njd);
    JPCommon_initialize(&ojt->jpcommon);
    HTS_Engine_initialize(&ojt->engine);
}

static void ojt_clear(Open_JTalk *ojt) {
    Mecab_clear(&ojt->mecab);
    NJD_clear(&ojt->njd);
    JPCommon_clear(&ojt->jpcommon);
    HTS_Engine_clear(&ojt->engine);
}

static int ojt_load(Open_JTalk *ojt, const char *dic_dir, const char *voice_path) {
    char *fn_voice = (char *)voice_path;
    if (Mecab_load(&ojt->mecab, dic_dir) != TRUE) {
        ojt_clear(ojt);
        return 0;
    }
    if (HTS_Engine_load(&ojt->engine, &fn_voice, 1) != TRUE) {
        ojt_clear(ojt);
        return 0;
    }
    if (strcmp(HTS_Engine_get_fullcontext_label_format(&ojt->engine), "HTS_TTS_JPN") != 0) {
        ojt_clear(ojt);
        return 0;
    }
    return 1;
}

JNIEXPORT jbyteArray JNICALL
Java_com_shinji_serena_speech_KapamilyaOpenJTalkSynthesizer_nativeSynthesizeHts(
    JNIEnv *env,
    jobject thiz,
    jstring jtext,
    jstring jdicDir,
    jstring jvoicePath,
    jfloat rate,
    jfloat pitch
) {
    if (jtext == NULL || jdicDir == NULL || jvoicePath == NULL) {
        return NULL;
    }

    const char *text = (*env)->GetStringUTFChars(env, jtext, NULL);
    const char *dicDir = (*env)->GetStringUTFChars(env, jdicDir, NULL);
    const char *voicePath = (*env)->GetStringUTFChars(env, jvoicePath, NULL);

    if (text == NULL || dicDir == NULL || voicePath == NULL || strlen(text) == 0) {
        if (text) (*env)->ReleaseStringUTFChars(env, jtext, text);
        if (dicDir) (*env)->ReleaseStringUTFChars(env, jdicDir, dicDir);
        if (voicePath) (*env)->ReleaseStringUTFChars(env, jvoicePath, voicePath);
        return NULL;
    }

    pthread_mutex_lock(&g_mutex);

    if (!g_initialized || strcmp(g_dic_dir, dicDir) != 0 || strcmp(g_voice_path, voicePath) != 0) {
        if (g_initialized) {
            ojt_clear(&g_ojt);
            g_initialized = 0;
        }
        ojt_init(&g_ojt);
        if (!ojt_load(&g_ojt, dicDir, voicePath)) {
            pthread_mutex_unlock(&g_mutex);
            (*env)->ReleaseStringUTFChars(env, jtext, text);
            (*env)->ReleaseStringUTFChars(env, jdicDir, dicDir);
            (*env)->ReleaseStringUTFChars(env, jvoicePath, voicePath);
            return NULL;
        }
        strncpy(g_dic_dir, dicDir, sizeof(g_dic_dir) - 1);
        g_dic_dir[sizeof(g_dic_dir) - 1] = '\0';
        strncpy(g_voice_path, voicePath, sizeof(g_voice_path) - 1);
        g_voice_path[sizeof(g_voice_path) - 1] = '\0';
        g_initialized = 1;
    }

    double safe_rate = (rate >= 0.25f && rate <= 4.0f) ? (double)rate : 1.0;
    double half_tone = (double)((pitch - 1.0f) * 12.0f);
    if (half_tone < -12.0) half_tone = -12.0;
    if (half_tone > 12.0) half_tone = 12.0;

    HTS_Engine_set_speed(&g_ojt.engine, safe_rate);
    HTS_Engine_add_half_tone(&g_ojt.engine, half_tone);
    HTS_Engine_set_volume(&g_ojt.engine, 2.0);

    size_t text_len = strlen(text);
    size_t buff_cap = text_len * 4 + 4096;
    char *buff = (char *)malloc(buff_cap);
    jbyteArray result_array = NULL;

    if (buff != NULL) {
        buff[0] = '\0';
        text2mecab(buff, text);
        Mecab_analysis(&g_ojt.mecab, buff);
        mecab2njd(&g_ojt.njd, Mecab_get_feature(&g_ojt.mecab), Mecab_get_size(&g_ojt.mecab));
        njd_set_pronunciation(&g_ojt.njd);
        njd_set_digit(&g_ojt.njd);
        njd_set_accent_phrase(&g_ojt.njd);
        njd_set_accent_type(&g_ojt.njd);
        njd_set_unvoiced_vowel(&g_ojt.njd);
        njd_set_long_vowel(&g_ojt.njd);
        njd2jpcommon(&g_ojt.jpcommon, &g_ojt.njd);
        JPCommon_make_label(&g_ojt.jpcommon);

        if (JPCommon_get_label_size(&g_ojt.jpcommon) > 2) {
            if (HTS_Engine_synthesize_from_strings(
                    &g_ojt.engine,
                    JPCommon_get_label_feature(&g_ojt.jpcommon),
                    JPCommon_get_label_size(&g_ojt.jpcommon)) == TRUE) {
                size_t nsamples = HTS_Engine_get_nsamples(&g_ojt.engine);
                if (nsamples > 0) {
                    short *pcm = (short *)malloc(nsamples * sizeof(short));
                    if (pcm != NULL) {
                        for (size_t i = 0; i < nsamples; i++) {
                            double x = HTS_Engine_get_generated_speech(&g_ojt.engine, i);
                            if (x > 32767.0) pcm[i] = 32767;
                            else if (x < -32768.0) pcm[i] = -32768;
                            else pcm[i] = (short)x;
                        }
                        size_t begin_pos = 0;
                        size_t end_pos = nsamples - 1;
                        const short thres = 40;
                        for (size_t i = 0; i < nsamples; i++) {
                            if (abs(pcm[i]) > thres) {
                                begin_pos = i;
                                break;
                            }
                        }
                        for (size_t i = nsamples - 1; i > begin_pos; i--) {
                            if (abs(pcm[i]) > thres) {
                                end_pos = i;
                                break;
                            }
                        }
                        size_t trimmed_samples = (end_pos >= begin_pos) ? (end_pos - begin_pos + 1) : nsamples;
                        jsize byte_len = (jsize)(trimmed_samples * sizeof(short));
                        result_array = (*env)->NewByteArray(env, byte_len);
                        if (result_array != NULL) {
                            (*env)->SetByteArrayRegion(env, result_array, 0, byte_len, (const jbyte *)(&pcm[begin_pos]));
                        }
                        free(pcm);
                    }
                }
            }
            HTS_Engine_refresh(&g_ojt.engine);
        }
        JPCommon_refresh(&g_ojt.jpcommon);
        NJD_refresh(&g_ojt.njd);
        Mecab_refresh(&g_ojt.mecab);
        free(buff);
    }

    pthread_mutex_unlock(&g_mutex);

    (*env)->ReleaseStringUTFChars(env, jtext, text);
    (*env)->ReleaseStringUTFChars(env, jdicDir, dicDir);
    (*env)->ReleaseStringUTFChars(env, jvoicePath, voicePath);

    return result_array;
}
