# libcricket07.so: the recompiled game (out_all/*.c from translator/x86toc.py), runtime/, host/ with the
# Android window layer in place of host/window.c, and SDL2 built from source as libSDL2.so.
cmake_minimum_required(VERSION 3.22)
project(cricket07 C)

set(RC_REPO "" CACHE PATH "repository root")
set(SDL2_SRC "" CACHE PATH "SDL2 source tree")
set(RC_OPT "-O2" CACHE STRING "optimisation flags for the game code")
option(RC_LTO "link-time optimisation (slow to link)" OFF)
set(WGPU "${RC_REPO}/third_party/wgpu" CACHE PATH "wgpu-native for aarch64-linux-android")
file(TO_CMAKE_PATH "${RC_REPO}" RC_REPO)   # forward slashes: RC_PROFILE below becomes a C string
get_filename_component(RC_REPO "${RC_REPO}" REALPATH)
set(GEN "${RC_REPO}/out_all")

if(NOT EXISTS "${GEN}/recomp_stubs.c")
  message(FATAL_ERROR "no generated code in ${GEN}: run android/build.ps1 (it translates the game first)")
endif()

add_subdirectory("${SDL2_SRC}" "${CMAKE_BINARY_DIR}/SDL2")

file(GLOB GEN_SRC CONFIGURE_DEPENDS "${GEN}/*.c")
file(GLOB HOST_SRC "${RC_REPO}/host/*.c")
# Mac window (Metal/objc), browser glue and the signal-based watchpoints (needs API 33) stay out
list(REMOVE_ITEM HOST_SRC "${RC_REPO}/host/window.c" "${RC_REPO}/host/web.c" "${RC_REPO}/host/watch.c")

add_library(wgpu_native STATIC IMPORTED)
set_target_properties(wgpu_native PROPERTIES IMPORTED_LOCATION "${WGPU}/lib/libwgpu_native.a")

add_library(cricket07 SHARED
  ${GEN_SRC}
  "${RC_REPO}/runtime/runtime.c" "${RC_REPO}/runtime/interp.c"
  ${HOST_SRC}
  "${RC_REPO}/android/host/window_android.c")

target_include_directories(cricket07 PRIVATE "${RC_REPO}/runtime" "${RC_REPO}/host" "${GEN}" "${WGPU}/include")
target_compile_definitions(cricket07 PRIVATE "RC_PROFILE=\"${RC_REPO}/android/profile_cricket07.h\"")
# the x87 rounding mode is mirrored into the host FPU at runtime (as in build.sh)
target_compile_options(cricket07 PRIVATE -frounding-math -w)
separate_arguments(RC_OPT_LIST UNIX_COMMAND "${RC_OPT}")
set_source_files_properties(${GEN_SRC} "${RC_REPO}/runtime/runtime.c" "${RC_REPO}/runtime/interp.c"
                            PROPERTIES COMPILE_OPTIONS "${RC_OPT_LIST}")
set_source_files_properties(${HOST_SRC} "${RC_REPO}/android/host/window_android.c"
                            PROPERTIES COMPILE_OPTIONS "-O2")
if(RC_LTO)
  target_compile_options(cricket07 PRIVATE -flto=thin)
  target_link_options(cricket07 PRIVATE -flto=thin -Wl,--thinlto-jobs=all)
endif()

target_link_libraries(cricket07 PRIVATE SDL2 wgpu_native android aaudio log z m dl)
