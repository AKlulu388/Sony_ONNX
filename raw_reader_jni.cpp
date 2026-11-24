#include <jni.h>
#include <libraw/libraw.h>
#include <cstring>

static LibRaw* to_ptr(jlong handle) {
    return reinterpret_cast<LibRaw*>(static_cast<void*>(handle));
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_sony_sdm_jni_RawReader_open(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    LibRaw* reader = new LibRaw();
    int ret = reader->open_file(path);
    if (path) env->ReleaseStringUTFChars(jpath, path);
    if (ret != LIBRAW_SUCCESS) {
        delete reader;
        return 0;
    }
    ret = reader->unpack();
    if (ret != LIBRAW_SUCCESS) {
        reader->recycle();
        delete reader;
        return 0;
    }
    return reinterpret_cast<jlong>(static_cast<void*>(reader));
}

JNIEXPORT jintArray JNICALL
Java_com_sony_sdm_jni_RawReader_getDimensions(JNIEnv* env, jobject, jlong handle) {
    LibRaw* r = to_ptr(handle);
    if (!r) return nullptr;
    jintArray out = env->NewIntArray(2);
    jint dims[2] = { r->imgdata.sizes.raw_width, r->imgdata.sizes.raw_height };
    env->SetIntArrayRegion(out, 0, 2, dims);
    return out;
}

JNIEXPORT jshortArray JNICALL
Java_com_sony_sdm_jni_RawReader_getRawPlane(JNIEnv* env, jobject, jlong handle) {
    LibRaw* r = to_ptr(handle);
    if (!r || !r->imgdata.raw_image) return nullptr;
    int w = r->imgdata.sizes.raw_width;
    int h = r->imgdata.sizes.raw_height;
    int len = w * h;
    jshortArray out = env->NewShortArray(len);
    env->SetShortArrayRegion(out, 0, len,
        reinterpret_cast<const jshort*>(r->imgdata.raw_image));
    return out;
}

JNIEXPORT jintArray JNICALL
Java_com_sony_sdm_jni_RawReader_getCfaPattern(JNIEnv* env, jobject, jlong handle) {
    LibRaw* r = to_ptr(handle);
    if (!r) return nullptr;
    jintArray out = env->NewIntArray(4);
    jint pat[4];
    for (int i = 0; i < 2; i++)
        for (int j = 0; j < 2; j++)
            pat[i * 2 + j] = r->fcol(i, j);
    env->SetIntArrayRegion(out, 0, 4, pat);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_sony_sdm_jni_RawReader_getColorDesc(JNIEnv* env, jobject, jlong handle) {
    LibRaw* r = to_ptr(handle);
    if (!r) return nullptr;
    char desc[5] = {0};
    for (int i = 0; i < 4 && r->imgdata.idata.cdesc[i]; i++)
        desc[i] = r->imgdata.idata.cdesc[i];
    return env->NewStringUTF(desc);
}

JNIEXPORT jdoubleArray JNICALL
Java_com_sony_sdm_jni_RawReader_getBlackLevels(JNIEnv* env, jobject, jlong handle) {
    LibRaw* r = to_ptr(handle);
    if (!r) return nullptr;
    jdoubleArray out = env->NewDoubleArray(4);
    double black[4];
    for (int i = 0; i < 4; i++) {
        unsigned cb = r->imgdata.color.cblack[i];
        black[i] = cb != 0 ? cb : r->imgdata.color.black;
    }
    env->SetDoubleArrayRegion(out, 0, 4, black);
    return out;
}

JNIEXPORT jdouble JNICALL
Java_com_sony_sdm_jni_RawReader_getWhiteLevel(JNIEnv* env, jobject, jlong handle) {
    LibRaw* r = to_ptr(handle);
    return r ? static_cast<jdouble>(r->imgdata.color.maximum) : 0.0;
}

JNIEXPORT jfloatArray JNICALL
Java_com_sony_sdm_jni_RawReader_getWhiteBalance(JNIEnv* env, jobject, jlong handle) {
    LibRaw* r = to_ptr(handle);
    if (!r) return nullptr;
    jfloatArray out = env->NewFloatArray(3);
    float wb[3];
    for (int i = 0; i < 3; i++) wb[i] = r->imgdata.color.cam_mul[i];
    env->SetFloatArrayRegion(out, 0, 3, wb);
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_com_sony_sdm_jni_RawReader_getDaylightWb(JNIEnv* env, jobject, jlong handle) {
    LibRaw* r = to_ptr(handle);
    if (!r) return nullptr;
    jfloatArray out = env->NewFloatArray(3);
    float wb[3];
    for (int i = 0; i < 3; i++) wb[i] = r->imgdata.color.pre_mul[i];
    env->SetFloatArrayRegion(out, 0, 3, wb);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_sony_sdm_jni_RawReader_getMake(JNIEnv* env, jobject, jlong handle) {
    LibRaw* r = to_ptr(handle);
    if (!r) return nullptr;
    return env->NewStringUTF(r->imgdata.idata.make);
}

JNIEXPORT jstring JNICALL
Java_com_sony_sdm_jni_RawReader_getModel(JNIEnv* env, jobject, jlong handle) {
    LibRaw* r = to_ptr(handle);
    if (!r) return nullptr;
    return env->NewStringUTF(r->imgdata.idata.model);
}

JNIEXPORT jfloatArray JNICALL
Java_com_sony_sdm_jni_RawReader_getCameraMatrix(JNIEnv* env, jobject, jlong handle) {
    LibRaw* r = to_ptr(handle);
    if (!r) return nullptr;
    jfloatArray out = env->NewFloatArray(9);
    float m[9];
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++)
            m[i * 3 + j] = r->imgdata.color.cam_xyz[i][j];
    env->SetFloatArrayRegion(out, 0, 9, m);
    return out;
}

JNIEXPORT jintArray JNICALL
Java_com_sony_sdm_jni_RawReader_getVisibleArea(JNIEnv* env, jobject, jlong handle) {
    LibRaw* r = to_ptr(handle);
    if (!r) return nullptr;
    jintArray out = env->NewIntArray(4);
    jint v[4] = { r->imgdata.sizes.left_margin, r->imgdata.sizes.top_margin,
                  r->imgdata.sizes.width, r->imgdata.sizes.height };
    env->SetIntArrayRegion(out, 0, 4, v);
    return out;
}

JNIEXPORT void JNICALL
Java_com_sony_sdm_jni_RawReader_close(JNIEnv*, jobject, jlong handle) {
    LibRaw* r = to_ptr(handle);
    if (!r) return;
    r->recycle();
    delete r;
}

}
