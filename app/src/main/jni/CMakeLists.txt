project(MattingNetwork)

cmake_minimum_required(VERSION 3.18)

set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-20220526-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(${PROJECT_NAME} SHARED photo_jni.cpp)

target_link_libraries(${PROJECT_NAME} ncnn ${OpenCV_LIBS})
