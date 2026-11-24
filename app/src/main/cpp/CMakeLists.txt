cmake_minimum_required(VERSION 3.22.1)
project(sdm-raw CXX)

set(CMAKE_CXX_STANDARD 17)

# LibRaw 源码目录（CI 在 prepare 步骤 clone 到这里，也可放预编译静态库）。
set(LIBRAW_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libraw)

# 方式一：源码接入。CI clone LibRaw 后，把其 src/ 与 internal/ 编进本 so。
file(GLOB LIBRAW_SRC CONFIGURE_DEPENDS
    "${LIBRAW_DIR}/src/*.cpp"
    "${LIBRAW_DIR}/internal/*.cpp")

add_library(sdm-raw SHARED raw_reader_jni.cpp ${LIBRAW_SRC})

target_include_directories(sdm-raw PRIVATE
    ${LIBRAW_DIR}
    ${LIBRAW_DIR}/libraw)

# 方式二（备用）：若已交叉编译好 libraw.a / libraw_r.a，放在 libraw/lib/ 下，
# 取消下面注释并删除上面的 GLOB 源文件即可。
# find_library(LIBRAW_LIB NAMES raw_r raw PATHS ${LIBRAW_DIR}/lib NO_DEFAULT_PATH)
# if (LIBRAW_LIB)
#     target_link_libraries(sdm-raw ${LIBRAW_LIB})
# endif()

if (LIBRAW_SRC)
    message(STATUS "LibRaw sources: ${LIBRAW_SRC}")
else()
    message(WARNING "未发现 LibRaw 源码：JNI 库无法链接 RAW 解码功能，"
                    "请在 CI/本地放入 libraw 源码或预编译静态库。")
endif()

find_library(log-lib log)
target_link_libraries(sdm-raw ${log-lib})
