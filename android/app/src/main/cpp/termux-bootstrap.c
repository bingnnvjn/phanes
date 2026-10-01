#include <jni.h>

extern jbyte blob[];
extern int blob_size;

static jbyteArray get_zip(JNIEnv *env)
{
    jbyteArray ret = (*env)->NewByteArray(env, blob_size);
    if (ret == NULL) {
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, ret, 0, blob_size, blob);
    return ret;
}

JNIEXPORT jbyteArray JNICALL
Java_com_gph_fable_app_FableInstaller_getZip(JNIEnv *env, jobject This)
{
    (void)This;
    return get_zip(env);
}

/* Keep the pre-Kotlin-renaming entry point for older callers. */
JNIEXPORT jbyteArray JNICALL
Java_com_gph_fable_app_TermuxInstaller_getZip(JNIEnv *env, jobject This)
{
    (void)This;
    return get_zip(env);
}
