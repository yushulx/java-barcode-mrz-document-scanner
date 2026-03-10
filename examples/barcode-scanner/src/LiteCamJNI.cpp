#include "Camera.h"
#include <jni.h>
#include <vector>
#include <mutex>
#include <cstring>
#include <string>

// Simple singleton registry for opened cameras referenced by an integer handle.
struct CameraEntry
{
    int id;
    Camera *cam;
};
static std::mutex g_mutex;
static std::vector<CameraEntry> g_cameras;
static int g_nextId = 1;

static Camera *getCamera(int handle)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    for (auto &e : g_cameras)
        if (e.id == handle)
            return e.cam;
    return nullptr;
}

static int registerCamera(Camera *c)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    int id = g_nextId++;
    g_cameras.push_back({id, c});
    return id;
}

static void unregisterCamera(int handle)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    for (auto it = g_cameras.begin(); it != g_cameras.end(); ++it)
    {
        if (it->id == handle)
        {
            delete it->cam;
            g_cameras.erase(it);
            return;
        }
    }
}

static jclass findAndGlobalRef(JNIEnv *env, const char *name)
{
    jclass local = env->FindClass(name);
    return (jclass)env->NewGlobalRef(local);
}

extern "C"
{

    JNIEXPORT jobjectArray JNICALL Java_com_example_litecam_LiteCam_listDevices(JNIEnv *env, jclass)
    {
        auto devices = ListCaptureDevices();
        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray arr = env->NewObjectArray((jsize)devices.size(), stringClass, nullptr);
        for (jsize i = 0; i < (jsize)devices.size(); ++i)
        {
#ifdef _WIN32
            char buffer[512];
            wcstombs_s(nullptr, buffer, devices[i].friendlyName, sizeof(buffer));
            env->SetObjectArrayElement(arr, i, env->NewStringUTF(buffer));
#else
            env->SetObjectArrayElement(arr, i, env->NewStringUTF(devices[i].friendlyName));
#endif
        }
        return arr;
    }

    JNIEXPORT jint JNICALL Java_com_example_litecam_LiteCam_open(JNIEnv *env, jobject self, jint deviceIndex)
    {
        auto cam = new Camera();
        if (!cam->Open(deviceIndex))
        {
            delete cam;
            return 0;
        }
        return registerCamera(cam);
    }

    JNIEXPORT void JNICALL Java_com_example_litecam_LiteCam_nativeClose(JNIEnv *, jobject, jint handle)
    {
        unregisterCamera(handle);
    }

    JNIEXPORT jintArray JNICALL Java_com_example_litecam_LiteCam_listSupportedResolutions(JNIEnv *env, jobject, jint handle)
    {
        Camera *cam = getCamera(handle);
        if (!cam)
            return nullptr;
        auto mts = cam->ListSupportedMediaTypes();
        // Flatten as width,height pairs sequentially.
        jintArray arr = env->NewIntArray((jsize)(mts.size() * 2));
        std::vector<jint> tmp;
        tmp.reserve(mts.size() * 2);
        for (auto &m : mts)
        {
            tmp.push_back((jint)m.width);
            tmp.push_back((jint)m.height);
        }
        env->SetIntArrayRegion(arr, 0, (jsize)tmp.size(), tmp.data());
        return arr;
    }

    JNIEXPORT jboolean JNICALL Java_com_example_litecam_LiteCam_setResolution(JNIEnv *, jobject, jint handle, jint w, jint h)
    {
        Camera *cam = getCamera(handle);
        if (!cam)
            return JNI_FALSE;
        return cam->SetResolution(w, h) ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jboolean JNICALL Java_com_example_litecam_LiteCam_captureFrame(JNIEnv *env, jobject, jint handle, jobject byteBuffer)
    {
        Camera *cam = getCamera(handle);
        if (!cam)
            return JNI_FALSE;
        FrameData frame = cam->CaptureFrame();
        if (!frame.rgbData)
            return JNI_FALSE;
        // Expect a direct ByteBuffer with capacity >= width*height*3
        unsigned char *dst = (unsigned char *)env->GetDirectBufferAddress(byteBuffer);
        if (!dst)
        {
            ReleaseFrame(frame);
            return JNI_FALSE;
        }
        size_t expected = (size_t)(frame.width * frame.height * 3);
        memcpy(dst, frame.rgbData, expected < frame.size ? expected : frame.size);
        ReleaseFrame(frame);
        return JNI_TRUE;
    }

    JNIEXPORT jint JNICALL Java_com_example_litecam_LiteCam_getFrameWidth(JNIEnv *, jobject, jint handle)
    {
        Camera *cam = getCamera(handle);
        if (!cam)
            return 0;
        return (jint)cam->frameWidth;
    }
    JNIEXPORT jint JNICALL Java_com_example_litecam_LiteCam_getFrameHeight(JNIEnv *, jobject, jint handle)
    {
        Camera *cam = getCamera(handle);
        if (!cam)
            return 0;
        return (jint)cam->frameHeight;
    }

} // extern C
