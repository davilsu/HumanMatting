#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <array>
#include <memory>
#include <string>

#include "benchmark.h"
#include "cpu.h"
#include "net.h"

///////////////////////////////////////////////////////////////////////////////////////////////
// Model parameter files
///////////////////////////////////////////////////////////////////////////////////////////////
constexpr size_t kModels = 4;
constexpr const char *kModelPaths[kModels]
    {"mobilenet_v2.bin", "hrnet_w18.bin", "mobilenet_v2_int8.bin", "hrnet_w18_int8.bin"};
constexpr const char *kParamPaths[kModels]
    {"mobilenet_v2.param", "hrnet_w18.param", "mobilenet_v2_int8.param", "hrnet_w18_int8.param"};

///////////////////////////////////////////////////////////////////////////////////////////////
// Input parameters
///////////////////////////////////////////////////////////////////////////////////////////////
constexpr int kResolution = 512;

///////////////////////////////////////////////////////////////////////////////////////////////
// Constant values
///////////////////////////////////////////////////////////////////////////////////////////////
constexpr const char *kTag = "MattingNetwork";
constexpr float kBackgroundColor[3]{120, 255, 155};
constexpr float kMean[3]{(255.0 / 2.0), (255.0 / 2.0), (255.0 / 2.0)};
constexpr float kNorm[3]{(2.0 / 255.0), (2.0 / 255.0), (2.0 / 255.0)};

///////////////////////////////////////////////////////////////////////////////////////////////
// Resource
///////////////////////////////////////////////////////////////////////////////////////////////
static int kThreads = -1;
static ncnn::UnlockedPoolAllocator g_blob_pool_allocator;
static ncnn::PoolAllocator g_workspace_pool_allocator;
static std::array<std::shared_ptr<ncnn::Net>, kModels> mattingNet{nullptr, nullptr};

///////////////////////////////////////////////////////////////////////////////////////////////
// JNI initialize
///////////////////////////////////////////////////////////////////////////////////////////////
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  __android_log_print(ANDROID_LOG_DEBUG, kTag, "JNI_OnLoad");
  static_assert(kModels % 2 == 0, "The number of models should be 2n");
  ncnn::create_gpu_instance();
  return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
  __android_log_print(ANDROID_LOG_DEBUG, kTag, "JNI_OnUnload");
  mattingNet.fill(nullptr);
  ncnn::destroy_gpu_instance();
}

///////////////////////////////////////////////////////////////////////////////////////////////
// Helper functions
///////////////////////////////////////////////////////////////////////////////////////////////
bool loadNetwork(JNIEnv *env, jobject assetManager, size_t modelIndex,
                 bool enableFP16, bool enableInt8) {
  if (modelIndex < 0 || modelIndex >= kModels) {
    return false;
  }
  mattingNet.at(modelIndex) = std::make_shared<ncnn::Net>();
  mattingNet[modelIndex]->opt.num_threads = kThreads;
  mattingNet[modelIndex]->opt.lightmode = true;
  mattingNet[modelIndex]->opt.blob_allocator = &g_blob_pool_allocator;
  mattingNet[modelIndex]->opt.workspace_allocator = &g_workspace_pool_allocator;
#ifdef NCNN_VULKAN
  mattingNet[modelIndex]->opt.use_vulkan_compute = (ncnn::get_gpu_count() != 0);
#else
  __android_log_print(ANDROID_LOG_WARN, kTag, "NCNN is not compiled with Vulkan, please recompile");
#endif
  mattingNet[modelIndex]->opt.use_fp16_arithmetic = enableFP16 || enableInt8;
  mattingNet[modelIndex]->opt.use_fp16_packed = enableFP16 || enableInt8;
  mattingNet[modelIndex]->opt.use_fp16_storage = enableFP16 || enableInt8;
  mattingNet[modelIndex]->opt.use_int8_arithmetic = true;
  mattingNet[modelIndex]->opt.use_int8_inference = true;
  mattingNet[modelIndex]->opt.use_int8_packed = true;
  mattingNet[modelIndex]->opt.use_int8_storage = true;
  AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);

  double t0 = ncnn::get_current_time();
  bool ret0 = mattingNet[modelIndex]->load_param(mgr, kParamPaths[modelIndex]) == 0;
  bool ret1 = mattingNet[modelIndex]->load_model(mgr, kModelPaths[modelIndex]) == 0;
  double t1 = ncnn::get_current_time();
  __android_log_print(ANDROID_LOG_DEBUG, kTag, "%s\tLoadTime: %.2fms",
                      kParamPaths[modelIndex], (t1 - t0));
  return ret0 && ret1;
}

void blendImage(ncnn::Mat &img, const ncnn::Mat &alpha) {
  auto img_ptr = reinterpret_cast<float *>(img.data);
  auto alpha_ptr = reinterpret_cast<float *>(alpha.data);
  ptrdiff_t width = img.w;
  ptrdiff_t height = img.h;
  ptrdiff_t n_pixels = width * height;
  for (ptrdiff_t h = 0; h < height; ++h) {
    for (ptrdiff_t w = 0; w < width; ++w) {
      ptrdiff_t pixel_index = h * width + w;
      float alpha_ = alpha_ptr[pixel_index];
      for (ptrdiff_t c = 0; c < 3; ++c) {
        img_ptr[pixel_index + n_pixels * c] =
            alpha_ * img_ptr[pixel_index + n_pixels * c] +
                (1 - alpha_) * kBackgroundColor[c];
      }
    }
  }
}

bool isNetworkChange(int modelIndex, bool enableFP16, bool enableInt8) {
  if (mattingNet[modelIndex] == nullptr)
    return true;
  return (enableFP16 || enableInt8) != mattingNet[modelIndex]->opt.use_fp16_packed;
}

///////////////////////////////////////////////////////////////////////////////////////////////
// Library functions
///////////////////////////////////////////////////////////////////////////////////////////////
extern "C" JNIEXPORT jboolean JNICALL
Java_com_davilsu_peoplematting_MattingNetwork_isNetworkChange(
    JNIEnv *env, jobject thiz, jint modelIndex, jboolean enableFP16, jboolean enableInt8) {
  bool _enableFP16 = (enableFP16 == JNI_TRUE);
  bool _enableInt8 = (enableInt8 == JNI_TRUE);
  modelIndex += _enableInt8 ? kModels / 2 : 0;
  return isNetworkChange(modelIndex, _enableFP16, _enableInt8) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_davilsu_peoplematting_MattingNetwork_Init(JNIEnv *env, jobject thiz,
                                                   jobject assetManager,
                                                   jint perfMode) {
  perfMode = (ncnn::get_little_cpu_count() != 0) ? perfMode : 0;
  if (perfMode == 0) {
    kThreads = ncnn::get_cpu_count();
  } else if (perfMode == 1) {
    kThreads = ncnn::get_little_cpu_count();
  } else {
    kThreads = ncnn::get_big_cpu_count();
  }

  ncnn::set_cpu_powersave(perfMode);
  ncnn::set_omp_num_threads(kThreads);

  __android_log_print(ANDROID_LOG_DEBUG, kTag, "num_threads: %d\tgpu_count: %d",
                      kThreads, ncnn::get_gpu_count());

  if (ncnn::get_little_cpu_count() == 0) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "big.LITTLE is not supported, use all cores");
    return JNI_FALSE;
  }

  return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_davilsu_peoplematting_MattingNetwork_Process(
    JNIEnv *env, jobject thiz, jobject assetManager, jobject bitmap,
    jint modelIndex, jboolean enableFP16, jboolean enableInt8, jboolean enableGPU) {
  const bool _enableGPU = (enableGPU == JNI_TRUE);
  const bool _enableFP16 = (enableFP16 == JNI_TRUE);
  const bool _enableInt8 = (enableInt8 == JNI_TRUE);
  modelIndex += _enableInt8 ? (kModels / 2) : 0;
  if (modelIndex < 0 || modelIndex >= kModels) {
    return -1;
  }
  if (_enableGPU && ncnn::get_gpu_count() == 0) {
    return -1;
  }
  if (isNetworkChange(modelIndex, _enableFP16, _enableInt8)) {
    __android_log_print(ANDROID_LOG_DEBUG, kTag,
                        "Model config change, reload model...");
    bool ret = loadNetwork(env, assetManager, modelIndex, _enableFP16, _enableInt8);
    if (!ret) {
      return -1;
    }
  }
  double start_time = ncnn::get_current_time();

  AndroidBitmapInfo info;
  AndroidBitmap_getInfo(env, bitmap, &info);
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    return -1;
  }
  int oriWidth = static_cast<int>(info.width);
  int oriHeight = static_cast<int>(info.height);

  ncnn::Mat src, src_resize, alpha;
  src = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGB);
  src_resize = ncnn::Mat::from_android_bitmap_resize(
      env, bitmap, ncnn::Mat::PIXEL_RGB, kResolution, kResolution);
  src_resize.substract_mean_normalize(kMean, kNorm);
  ncnn::Extractor ex = mattingNet[modelIndex]->create_extractor();
#ifdef NCNN_VULKAN
  ex.set_vulkan_compute(_enableGPU);
#endif
  ex.input("input", src_resize);
  ex.extract("output", alpha);
  ncnn::resize_bilinear(alpha, alpha, oriWidth, oriHeight);

  blendImage(src, alpha);
  src.to_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGB);

  double elapsed_time = ncnn::get_current_time() - start_time;
  __android_log_print(ANDROID_LOG_DEBUG, kTag, "%.2fms", elapsed_time);

  return static_cast<jint>(elapsed_time * 100);
}
