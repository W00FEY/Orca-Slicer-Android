# Cross-compiles the third-party libraries that OrcaSlicer's slicing engine
# (libslic3r) needs, for Android.
#
# Configure with the NDK toolchain, e.g.:
#   cmake -S native/deps -B build/deps-arm64-v8a -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
#     -DORCA_SOURCE_DIR=third_party/OrcaSlicer
#   cmake --build build/deps-arm64-v8a
#
# Versions, URLs and hashes follow OrcaSlicer's own deps/ recipes so the
# engine is built against the same library versions as the desktop app.
# GUI-only libraries (wxWidgets, GLEW, GLFW, OpenCSG, CURL) are left out.

cmake_minimum_required(VERSION 3.22)

if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()

project(OrcaSlicerAndroidDeps NONE)

# Without the NDK toolchain this builds for the host (Linux), which is used to
# run the engine's JNI bridge in desktop tests (see tests/host).

enable_language(C)
enable_language(CXX)

include(ExternalProject)
include(ProcessorCount)
ProcessorCount(NPROC)
if(NPROC EQUAL 0)
    set(NPROC 1)
endif()

set(ORCA_SOURCE_DIR "" CACHE PATH "OrcaSlicer source checkout (used for its bundled expat and patches)")
if(NOT EXISTS "${ORCA_SOURCE_DIR}/deps")
    message(FATAL_ERROR "ORCA_SOURCE_DIR must point to an OrcaSlicer checkout. Run scripts/fetch_orca.sh first.")
endif()
get_filename_component(ORCA_SOURCE_DIR "${ORCA_SOURCE_DIR}" ABSOLUTE)
set(ORCA_DEPS_DIR "${ORCA_SOURCE_DIR}/deps")

set(DESTDIR "${CMAKE_BINARY_DIR}/prefix" CACHE PATH "Install prefix for the built dependencies")
set(DEP_DOWNLOAD_DIR "${CMAKE_SOURCE_DIR}/../../.deps-download" CACHE PATH "Download cache for source archives")
get_filename_component(DEP_DOWNLOAD_DIR "${DEP_DOWNLOAD_DIR}" ABSOLUTE)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# Settings for autotools / Makefile based projects (GMP, MPFR, OpenSSL).
set(AT_FLAGS "CFLAGS=-O2 -fPIC -DNDEBUG" "CXXFLAGS=-O2 -fPIC -DNDEBUG"
    # Skip texinfo documentation, which would otherwise require makeinfo.
    "MAKEINFO=true")
if(ANDROID)
    set(NDK_BIN "${ANDROID_TOOLCHAIN_ROOT}/bin")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(ANDROID_TRIPLE aarch64-linux-android)
        set(OPENSSL_TARGET android-arm64)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(ANDROID_TRIPLE x86_64-linux-android)
        set(OPENSSL_TARGET android-x86_64)
    else()
        message(FATAL_ERROR "Unsupported ANDROID_ABI: ${ANDROID_ABI} (use arm64-v8a or x86_64)")
    endif()
    set(ANDROID_API_LEVEL ${ANDROID_PLATFORM_LEVEL})
    set(AT_ENV env
        "PATH=${NDK_BIN}:$ENV{PATH}"
        "CC=${NDK_BIN}/${ANDROID_TRIPLE}${ANDROID_API_LEVEL}-clang"
        "CXX=${NDK_BIN}/${ANDROID_TRIPLE}${ANDROID_API_LEVEL}-clang++"
        "AR=${NDK_BIN}/llvm-ar" "RANLIB=${NDK_BIN}/llvm-ranlib"
        "STRIP=${NDK_BIN}/llvm-strip" "NM=${NDK_BIN}/llvm-nm"
        ${AT_FLAGS})
    set(AT_HOST_ARG --host=${ANDROID_TRIPLE})
    set(OPENSSL_ENV env "PATH=${NDK_BIN}:$ENV{PATH}" "ANDROID_NDK_ROOT=${ANDROID_NDK}" "ANDROID_NDK_HOME=${ANDROID_NDK}")
    set(OPENSSL_CONFIGURE ./Configure ${OPENSSL_TARGET} -D__ANDROID_API__=${ANDROID_API_LEVEL})
else()
    set(AT_ENV env "CC=${CMAKE_C_COMPILER}" "CXX=${CMAKE_CXX_COMPILER}" ${AT_FLAGS})
    set(AT_HOST_ARG "")
    set(OPENSSL_ENV env "CC=${CMAKE_C_COMPILER}")
    set(OPENSSL_CONFIGURE ./config)
endif()

# Arguments forwarded to every CMake based dependency.
set(DEP_CMAKE_ARGS
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
    -DCMAKE_INSTALL_PREFIX=${DESTDIR}
    -DCMAKE_PREFIX_PATH=${DESTDIR}
    -DCMAKE_FIND_ROOT_PATH=${DESTDIR}
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DBUILD_SHARED_LIBS=OFF
    -DCMAKE_C_COMPILER_LAUNCHER=${CMAKE_C_COMPILER_LAUNCHER}
    -DCMAKE_CXX_COMPILER_LAUNCHER=${CMAKE_CXX_COMPILER_LAUNCHER}
)
if(ANDROID)
    list(APPEND DEP_CMAKE_ARGS -DANDROID_ABI=${ANDROID_ABI} -DANDROID_PLATFORM=${ANDROID_PLATFORM} -DANDROID_STL=${ANDROID_STL})
else()
    list(APPEND DEP_CMAKE_ARGS -DCMAKE_C_COMPILER=${CMAKE_C_COMPILER} -DCMAKE_CXX_COMPILER=${CMAKE_CXX_COMPILER})
endif()

function(android_dep name)
    cmake_parse_arguments(P "" "" "CMAKE_ARGS" ${ARGN})
    ExternalProject_Add(dep_${name}
        EXCLUDE_FROM_ALL ON
        INSTALL_DIR ${DESTDIR}
        DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/${name}
        CMAKE_ARGS ${DEP_CMAKE_ARGS} ${P_CMAKE_ARGS}
        BUILD_COMMAND ${CMAKE_COMMAND} --build . --config ${CMAKE_BUILD_TYPE} -- -j${NPROC}
        INSTALL_COMMAND ${CMAKE_COMMAND} --build . --target install --config ${CMAKE_BUILD_TYPE}
        ${P_UNPARSED_ARGUMENTS}
    )
endfunction()

# `patch` ignores enclosing git repositories, unlike `git apply`.
set(PATCH sh ${CMAKE_CURRENT_SOURCE_DIR}/apply_patch.sh)

include(recipes.cmake)

add_custom_target(deps ALL DEPENDS
    dep_ZLIB dep_PNG dep_JPEG dep_EXPAT dep_FREETYPE
    dep_Boost dep_TBB dep_Cereal dep_Qhull dep_Eigen
    dep_GMP dep_MPFR dep_CGAL dep_NLopt dep_libnoise
    dep_Draco dep_OpenSSL dep_OpenCV dep_OCCT
    dep_Blosc dep_OpenEXR dep_OpenVDB
)
