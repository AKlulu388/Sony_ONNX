cmake_minimum_required(VERSION 3.22.1)
project(sdm-raw CXX)

set(CMAKE_CXX_STANDARD 17)

# LibRaw 源码目录（CI 在 prepare 步骤 clone 到这里）。
set(LIBRAW_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libraw)

# LibRaw >= 0.21：实现源码全部在 src/ 下递归分布；internal/ 只放内部头文件。
# 排除 *_ph.cpp 占位桩实现。
file(GLOB_RECURSE LIBRAW_SRC CONFIGURE_DEPENDS
    "${LIBRAW_DIR}/src/*.cpp")
file(GLOB_RECURSE LIBRAW_PH CONFIGURE_DEPENDS
    "${LIBRAW_DIR}/src/*_ph.cpp")
list(REMOVE_ITEM LIBRAW_SRC ${LIBRAW_PH})

add_library(sdm-raw SHARED raw_reader_jni.cpp ${LIBRAW_SRC})

# 公开头文件在 LIBRAW_DIR/libraw/，include 上一级目录即可用 <libraw/libraw.h>。
target_include_directories(sdm-raw PRIVATE
    ${LIBRAW_DIR})

# 与官方 CMake 对齐：单线程静态编入本 so，关闭 DLL 导出装饰。
target_compile_definitions(sdm-raw PRIVATE
    LIBRAW_NOTHREADS
    LIBRAW_NODLL)

find_library(log-lib log)
find_library(math-lib m)
target_link_libraries(sdm-raw ${log-lib} ${math-lib})

if (LIBRAW_SRC)
    message(STATUS "LibRaw sources count: ${LIBRAW_SRC}")
else()
    message(WARNING "未发现 LibRaw 源码：JNI 库无法解码 RAW，请确认 CI 已克隆 libraw 到 ${LIBRAW_DIR}")
endif()
