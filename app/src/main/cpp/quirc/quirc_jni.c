#include <jni.h>
#include <stdint.h>
#include <string.h>

#include "quirc.h"

JNIEXPORT jlong JNICALL
Java_com_joel_litesail_scan_decoder_QuircDecoder_createContext(JNIEnv *env, jobject instance)
{
    return (jlong)(intptr_t)quirc_new();
}

JNIEXPORT void JNICALL
Java_com_joel_litesail_scan_decoder_QuircDecoder_destroyContext(JNIEnv *env, jobject instance, jlong context)
{
    quirc_destroy((struct quirc *)(intptr_t)context);
}

JNIEXPORT jbyteArray JNICALL
Java_com_joel_litesail_scan_decoder_QuircDecoder_decodeFrame(JNIEnv *env, jobject instance, jlong context,
        jobject luminance, jint offset, jint limit, jint width, jint height, jint row_stride)
{
    struct quirc *decoder = (struct quirc *)(intptr_t)context;
    const uint8_t *pixels = (*env)->GetDirectBufferAddress(env, luminance);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, luminance);
    if (!decoder || !pixels || width <= 0 || height <= 0 || row_stride < width ||
            offset < 0 || limit > capacity ||
            (jlong)offset + (jlong)(height - 1) * row_stride + width > limit) {
        return NULL;
    }

    int previous_width, previous_height;
    quirc_begin(decoder, &previous_width, &previous_height);
    if ((width != previous_width || height != previous_height) &&
            quirc_resize(decoder, width, height) != 0) {
        return NULL;
    }

    uint8_t *target = quirc_begin(decoder, NULL, NULL);
    for (int row = 0; row < height; row++) {
        memcpy(target + (size_t)row * width, pixels + offset + (size_t)row * row_stride, width);
    }
    quirc_end(decoder);

    int count = quirc_count(decoder);
    for (int index = 0; index < count; index++) {
        struct quirc_code code;
        struct quirc_data data;
        quirc_extract(decoder, index, &code);
        quirc_decode_error_t error = quirc_decode(&code, &data);
        if (error == QUIRC_ERROR_DATA_ECC) {
            quirc_flip(&code);
            error = quirc_decode(&code, &data);
        }
        if (error != QUIRC_SUCCESS || data.payload_len == 0) continue;

        /* Prefix the payload with the QR character-set assignment, if present. */
        uint32_t eci = data.eci;
        if (eci == 0 && data.data_type == QUIRC_DATA_TYPE_KANJI) eci = QUIRC_ECI_SHIFT_JIS;
        jbyte charset = eci <= UINT8_MAX ? (jbyte)eci : 0;
        jbyteArray result = (*env)->NewByteArray(env, data.payload_len + 1);
        if (!result) return NULL;
        (*env)->SetByteArrayRegion(env, result, 0, 1, &charset);
        (*env)->SetByteArrayRegion(env, result, 1, data.payload_len, (const jbyte *)data.payload);
        return result;
    }
    return NULL;
}
