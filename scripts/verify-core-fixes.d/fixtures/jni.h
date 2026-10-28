#ifndef MOCK_JNI_H
#define MOCK_JNI_H
#include <stdint.h>
typedef uint8_t jboolean;
typedef int32_t jint;
typedef int32_t jsize;
typedef void* jobject;
typedef void* jclass;
typedef void* jstring;
typedef void* jobjectArray;
typedef void* jintArray;
#define JNI_TRUE 1
#define JNI_FALSE 0
#define JNIEXPORT
#define JNICALL
struct JNINativeInterface {
    jsize (*GetArrayLength)(void*, jobjectArray);
    jobject (*GetObjectArrayElement)(void*, jobjectArray, jsize);
    const char* (*GetStringUTFChars)(void*, jstring, jboolean*);
    void (*ReleaseStringUTFChars)(void*, jstring, const char*);
    void (*DeleteLocalRef)(void*, jobject);
    jintArray (*NewIntArray)(void*, jsize);
    void (*SetIntArrayRegion)(void*, jintArray, jsize, jsize, const jint*);
};
typedef const struct JNINativeInterface* JNIEnv;
#endif
