/* جسر JNI بسيط فوق whisper.cpp — تحميل موديل GGUF، تفريغ PCM 16kHz mono، تحرير.
   الخيوط والـNEON يتكفّل بهما ggml؛ عدد الخيوط يمرّره الجانب الجافي. */
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include "whisper.h"

/* تشخيص: معلومات النموذج المحمَّل — يثبت أن GGUF كامل وصالح (لا تالف/ناقص المفردات) */
JNIEXPORT jstring JNICALL
Java_com_ddcbusiness_noor_WhisperNativePlugin_nativeModelInfo(JNIEnv *env, jclass cls, jlong ptr) {
    (void) cls;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ptr;
    if (!ctx) return (*env)->NewStringUTF(env, "no-ctx");
    char buf[512];
    snprintf(buf, sizeof(buf),
        "n_vocab=%d n_audio_ctx=%d n_text_ctx=%d n_mels=%d ftype=%d multilingual=%d type=%s",
        whisper_model_n_vocab(ctx), whisper_model_n_audio_ctx(ctx), whisper_model_n_text_ctx(ctx),
        whisper_model_n_mels(ctx), whisper_model_ftype(ctx), whisper_is_multilingual(ctx),
        whisper_model_type_readable(ctx));
    return (*env)->NewStringUTF(env, buf);
}

/* تشخيص: معاملات whisper الفعلية المستخدمة في التفريغ (نفس ضبط nativeTranscribe) */
JNIEXPORT jstring JNICALL
Java_com_ddcbusiness_noor_WhisperNativePlugin_nativeConfig(JNIEnv *env, jclass cls) {
    (void) cls;
    struct whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.language = "ar"; wp.translate = false; wp.no_timestamps = true; wp.suppress_blank = true;
    char buf[512];
    snprintf(buf, sizeof(buf),
        "strategy=greedy language=%s translate=%d single_segment=%d no_timestamps=%d suppress_blank=%d temperature=%.2f n_max_text_ctx=%d",
        wp.language ? wp.language : "?", wp.translate ? 1 : 0, wp.single_segment ? 1 : 0,
        wp.no_timestamps ? 1 : 0, wp.suppress_blank ? 1 : 0, (double) wp.temperature, wp.n_max_text_ctx);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jlong JNICALL
Java_com_ddcbusiness_noor_WhisperNativePlugin_nativeInit(JNIEnv *env, jclass cls, jstring path) {
    (void) cls;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return 0;
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(p, cparams);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT jstring JNICALL
Java_com_ddcbusiness_noor_WhisperNativePlugin_nativeTranscribe(JNIEnv *env, jclass cls,
                                                               jlong ptr, jshortArray pcm,
                                                               jstring lang, jint threads,
                                                               jstring prompt) {
    (void) cls;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ptr;
    if (!ctx) return NULL;

    jsize n = (*env)->GetArrayLength(env, pcm);
    if (n <= 0) return (*env)->NewStringUTF(env, "");
    jshort *s = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (!s) return NULL;
    float *f = (float *) malloc(sizeof(float) * (size_t) n);
    if (!f) { (*env)->ReleaseShortArrayElements(env, pcm, s, JNI_ABORT); return NULL; }
    for (jsize i = 0; i < n; i++) f[i] = (float) s[i] / 32768.0f;
    (*env)->ReleaseShortArrayElements(env, pcm, s, JNI_ABORT);

    const char *lg = (*env)->GetStringUTFChars(env, lang, NULL);
    const char *pr = prompt ? (*env)->GetStringUTFChars(env, prompt, NULL) : NULL;

    struct whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.language        = lg ? lg : "ar";
    wp.translate       = false;
    wp.n_threads       = threads > 0 ? threads : 4;
    wp.print_progress  = false;
    wp.print_realtime  = false;
    wp.print_special   = false;
    wp.print_timestamps= false;
    wp.no_timestamps   = true;
    wp.suppress_blank  = true;
    /* إعدادات إلزامية للمحرّك الهجين: حتمية كاملة وبلا سياق سابق */
    wp.temperature     = 0.0f;
    wp.temperature_inc = 0.0f;   /* لا تصعيد حرارة عند الفشل — حتمي دائماً */
    wp.no_context      = true;   /* لا condition_on_previous_text */
    /* prompt يصل فارغاً دائماً من مسار الهجين — يُمنع تمرير النص المتوقع */
    if (pr && pr[0]) wp.initial_prompt = pr;

    int rc = whisper_full(ctx, wp, f, (int) n);
    free(f);
    if (lg) (*env)->ReleaseStringUTFChars(env, lang, lg);
    if (pr) (*env)->ReleaseStringUTFChars(env, prompt, pr);
    if (rc != 0) return NULL;

    int ns = whisper_full_n_segments(ctx);
    size_t cap = 1024, len = 0;
    char *out = (char *) malloc(cap);
    if (!out) return NULL;
    out[0] = 0;
    for (int i = 0; i < ns; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (!t) continue;
        size_t tl = strlen(t);
        if (len + tl + 2 > cap) {
            cap = (len + tl + 2) * 2;
            char *nw = (char *) realloc(out, cap);
            if (!nw) { free(out); return NULL; }
            out = nw;
        }
        memcpy(out + len, t, tl);
        len += tl;
        out[len] = 0;
    }
    jstring res = (*env)->NewStringUTF(env, out);
    free(out);
    return res;
}

JNIEXPORT void JNICALL
Java_com_ddcbusiness_noor_WhisperNativePlugin_nativeFree(JNIEnv *env, jclass cls, jlong ptr) {
    (void) env; (void) cls;
    if (ptr) whisper_free((struct whisper_context *) (intptr_t) ptr);
}
