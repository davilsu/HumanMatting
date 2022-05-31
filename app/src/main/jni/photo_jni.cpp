#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <memory>

#include "benchmark.h"
#include "cpu.h"
#include "net.h"

///////////////////////////////////////////////////////////////////////////////////////////////
// Model parameter files
///////////////////////////////////////////////////////////////////////////////////////////////
constexpr size_t kModels = 4;
constexpr std::array<const char *, kModels> kModelPaths{
    "mobilenet_v2.bin", "hrnet_w18.bin", "mobilenet_v2_int8.bin",
    "hrnet_w18_int8.bin"};
constexpr std::array<const char *, kModels> kParamPaths{
    "mobilenet_v2.param", "hrnet_w18.param", "mobilenet_v2_int8.param",
    "hrnet_w18_int8.param"};

///////////////////////////////////////////////////////////////////////////////////////////////
// Input parameters
///////////////////////////////////////////////////////////////////////////////////////////////
constexpr int kResolution = 512;

///////////////////////////////////////////////////////////////////////////////////////////////
// Constant values
///////////////////////////////////////////////////////////////////////////////////////////////
constexpr const char *kTag = "MattingNetwork";
constexpr std::array<float, 3> kMean{(255.0 / 2.0), (255.0 / 2.0),
                                     (255.0 / 2.0)};
constexpr std::array<float, 3> kNorm{(2.0 / 255.0), (2.0 / 255.0),
                                     (2.0 / 255.0)};

///////////////////////////////////////////////////////////////////////////////////////////////
// Resource
///////////////////////////////////////////////////////////////////////////////////////////////
static int kThreads = -1;
static ncnn::UnlockedPoolAllocator g_blob_pool_allocator;
static ncnn::PoolAllocator g_workspace_pool_allocator;
static std::array<std::shared_ptr<ncnn::Net>, kModels> mattingNet{nullptr,
                                                                  nullptr};

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
  ncnn::Option opt;
  opt.num_threads = kThreads;
  opt.lightmode = true;
  opt.blob_allocator = &g_blob_pool_allocator;
  opt.workspace_allocator = &g_workspace_pool_allocator;
#ifdef NCNN_VULKAN
  opt.use_vulkan_compute = (ncnn::get_gpu_count() != 0);
#else
  __android_log_print(ANDROID_LOG_WARN, kTag,
                      "NCNN is not compiled with Vulkan, please recompile");
#endif
  opt.use_fp16_arithmetic = enableFP16 || enableInt8;
  opt.use_fp16_packed = enableFP16 || enableInt8;
  opt.use_fp16_storage = enableFP16 || enableInt8;
  opt.use_int8_arithmetic = true;
  opt.use_int8_inference = true;
  opt.use_int8_packed = true;
  opt.use_int8_storage = true;
  mattingNet.at(modelIndex)->opt = opt;
  AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);

  double t0 = ncnn::get_current_time();
  bool ret0 =
      mattingNet.at(modelIndex)->load_param(mgr, kParamPaths.at(modelIndex)) == 0;
  bool ret1 =
      mattingNet.at(modelIndex)->load_model(mgr, kModelPaths.at(modelIndex)) == 0;
  double t1 = ncnn::get_current_time();
  __android_log_print(ANDROID_LOG_DEBUG, kTag, "%s\tLoadTime: %.2fms",
                      kParamPaths.at(modelIndex), (t1 - t0));
  return ret0 && ret1;
}

void alphaPremultiply(ncnn::Mat &img, const ncnn::Mat &alpha) {
  auto img_ptr = reinterpret_cast<float *>(img.data);
  auto alpha_ptr = reinterpret_cast<float *>(alpha.data);
  ptrdiff_t n_pixels = img.w * img.h;
  auto clamp{[](float x, float min_, float max_) {
    return std::min<float>(max_, std::max<float>(min_, x));
  }};
  for (ptrdiff_t idx = 0; idx < n_pixels; ++idx) {
    float alpha_ = clamp(alpha_ptr[idx], 0.0F, 1.0F);
    img_ptr[idx + 0 * n_pixels] *= alpha_;
    img_ptr[idx + 1 * n_pixels] *= alpha_;
    img_ptr[idx + 2 * n_pixels] *= alpha_;
    img_ptr[idx + 3 * n_pixels] = alpha_ * 255.0F;
  }
}

bool isNetworkChange(int modelIndex, bool enableFP16, bool enableInt8) {
  if (mattingNet.at(modelIndex) == nullptr) return true;
  return (enableFP16 || enableInt8) !=
      mattingNet.at(modelIndex)->opt.use_fp16_packed;
}

///////////////////////////////////////////////////////////////////////////////////////////////
// Library functions
///////////////////////////////////////////////////////////////////////////////////////////////
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
    __android_log_print(ANDROID_LOG_WARN, kTag,
                        "big.LITTLE is not supported, use all cores");
    return JNI_FALSE;
  }

  return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_davilsu_peoplematting_MattingNetwork_Process(
    JNIEnv *env, jobject thiz, jobject assetManager, jobject bitmap,
    jint modelIndex, jboolean enableFP16, jboolean enableInt8,
    jboolean enableGPU) {
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
    bool ret =
        loadNetwork(env, assetManager, modelIndex, _enableFP16, _enableInt8);
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
  src = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGBA);
  src_resize = ncnn::Mat::from_android_bitmap_resize(
      env, bitmap, ncnn::Mat::PIXEL_RGB, kResolution, kResolution);
  src_resize.substract_mean_normalize(kMean.data(), kNorm.data());
  ncnn::Extractor ex = mattingNet.at(modelIndex)->create_extractor();
#ifdef NCNN_VULKAN
  ex.set_vulkan_compute(_enableGPU);
#endif
  ex.input("input", src_resize);
  ex.extract("output", alpha);
  ncnn::resize_bilinear(alpha, alpha, oriWidth, oriHeight);

  alphaPremultiply(src, alpha);
  src.to_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGBA);

  double elapsed_time = ncnn::get_current_time() - start_time;
  __android_log_print(ANDROID_LOG_DEBUG, kTag, "%.2fms", elapsed_time);

  return static_cast<jint>(elapsed_time * 100);
}
