cmake_minimum_required(VERSION 3.18)
project(enhancer)

# ncnn prebuilt (android-vulkan) is placed here by scripts/fetch_assets.sh
set(ncnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(enhancer SHARED enhancer.cpp)

target_link_libraries(enhancer ncnn jnigraphics android log)

# 16 KB page size support (Android 15+)
target_link_options(enhancer PRIVATE -Wl,-z,max-page-size=16384)
