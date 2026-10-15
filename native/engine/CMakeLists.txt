# Builds OrcaSlicer's slicing engine (libslic3r) and the JNI bridge used by the
# Android app. Invoked by Gradle (app/build.gradle.kts -> externalNativeBuild).
#
# Required cache variables:
#   ORCA_SOURCE_DIR   OrcaSlicer checkout (scripts/fetch_orca.sh)
#   ORCA_DEPS_PREFIX  Install prefix produced by scripts/build_deps.sh
#                     (defaults to build/deps-<ABI>/prefix in this repository)

cmake_minimum_required(VERSION 3.22)
if(${CMAKE_VERSION} VERSION_GREATER_EQUAL "4.0")
    set(CMAKE_POLICY_VERSION_MINIMUM 3.13 CACHE STRING "" FORCE)
endif()
if(POLICY CMP0167)
    cmake_policy(SET CMP0167 NEW)
endif()

project(OrcaEngine C CXX)

set(ORCA_SOURCE_DIR "" CACHE PATH "OrcaSlicer source checkout")
set(ORCA_DEPS_PREFIX "${CMAKE_CURRENT_SOURCE_DIR}/../../build/deps-${ANDROID_ABI}/prefix" CACHE PATH "Android dependency install prefix")
foreach(_v ORCA_SOURCE_DIR ORCA_DEPS_PREFIX)
    if(NOT EXISTS "${${_v}}")
        message(FATAL_ERROR "${_v} is not set or does not exist (${${_v}}). "
                            "Run scripts/fetch_orca.sh and scripts/build_deps.sh first.")
    endif()
    get_filename_component(${_v} "${${_v}}" ABSOLUTE)
endforeach()

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()
# Debug builds of libslic3r are unusably slow on a phone; always optimise.
set(CMAKE_C_FLAGS_DEBUG "-O2 -g")
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g")

# libslic3r derives the OCCT config path from CMAKE_PREFIX_PATH, so it must be a single entry.
set(CMAKE_PREFIX_PATH "${ORCA_DEPS_PREFIX}")
list(PREPEND CMAKE_FIND_ROOT_PATH "${ORCA_DEPS_PREFIX}")
list(APPEND CMAKE_MODULE_PATH "${ORCA_SOURCE_DIR}/cmake/modules")

# ---------------------------------------------------------------- settings mirrored from OrcaSlicer/CMakeLists.txt
include("${ORCA_SOURCE_DIR}/version.inc")
set(SLIC3R_RESOURCES_DIR "${ORCA_SOURCE_DIR}/resources")
set(SLIC3R_GUI OFF)
set(SLIC3R_STATIC ON)
set(SLIC3R_PCH OFF)
set(SLIC3R_PROFILE OFF)
set(IS_CROSS_COMPILE TRUE)
set(SLIC3R_ENC_CHECK OFF CACHE BOOL "" FORCE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_compile_definitions("$<IF:$<CONFIG:Debug>,DEBUG,NDEBUG>" BBL_RELEASE_TO_PUBLIC=1)
add_definitions(-DwxUSE_UNICODE -D_UNICODE -DUNICODE -DWXINTL_NO_GETTEXT_MACRO -DwxNO_UNSAFE_WXSTRING_CONV)
add_compile_options(-fsigned-char -Wno-reorder -Werror=return-type
    -Wno-unused-function -Wno-unused-variable -Wno-unused-but-set-variable -Wno-unused-label
    -Wno-unused-local-typedefs -Wno-sign-compare -Wno-misleading-indentation -Wno-switch
    -Wno-ignored-attributes -Wno-deprecated-declarations -Wno-error=enum-constexpr-conversion
    -Wno-unknown-pragmas -Wno-unknown-warning-option)

set(LIBDIR "${ORCA_SOURCE_DIR}/src")
set(LIBDIR_BIN "${CMAKE_CURRENT_BINARY_DIR}/src")
include_directories(SYSTEM ${LIBDIR})
include_directories(SYSTEM ${LIBDIR_BIN}/dev-utils/platform)
include_directories(SYSTEM ${LIBDIR}/libigl)

# Used by Orca's CMake files to remap MSVC build configurations; nothing to do here.
function(slic3r_remap_configs targets from_Cfg to_Cfg)
endfunction()

# ---------------------------------------------------------------- third-party packages
set(Boost_USE_STATIC_LIBS ON)
set(Boost_NO_SYSTEM_PATHS TRUE)
find_package(Boost 1.83.0 REQUIRED COMPONENTS system filesystem thread log log_setup locale regex chrono atomic date_time iostreams program_options nowide)
find_package(Eigen3 5.0.1 REQUIRED)

add_library(boost_libs INTERFACE)
add_library(boost_headeronly INTERFACE)
target_include_directories(boost_headeronly SYSTEM INTERFACE ${Boost_INCLUDE_DIRS})
target_link_libraries(boost_libs INTERFACE boost_headeronly ${Boost_LIBRARIES})

set(TBB_STATIC 1)
find_package(TBB REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(Freetype REQUIRED)
find_package(ZLIB REQUIRED)
find_package(EXPAT REQUIRED)
find_package(PNG REQUIRED)
find_package(cereal REQUIRED)
if(NOT TARGET cereal::cereal)
    set_target_properties(cereal PROPERTIES IMPORTED_GLOBAL TRUE)
    add_library(cereal::cereal ALIAS cereal)
else()
    set_target_properties(cereal::cereal PROPERTIES IMPORTED_GLOBAL TRUE)
endif()
find_package(NLopt 1.4 REQUIRED)

set(OPENVDB_USE_STATIC_LIBS ON)
set(USE_BLOSC TRUE)
find_package(OpenVDB 5.0 COMPONENTS openvdb)
if(NOT OpenVDB_FOUND)
    message(FATAL_ERROR "OpenVDB not found in ${ORCA_DEPS_PREFIX}")
endif()

# ---------------------------------------------------------------- OrcaSlicer sources
# Bundled third-party code (deps_src) minus the GUI-only pieces.
set(_deps_src "${ORCA_SOURCE_DIR}/deps_src")
foreach(_d agg ankerl earcut fast_float nanosvg nlohmann stb_dxt
           admesh clipper clipper2 glu-libtess libigl libnest2d mcut miniz qhull qoi semver)
    add_subdirectory(${_deps_src}/${_d} ${CMAKE_CURRENT_BINARY_DIR}/deps_src/${_d})
endforeach()

add_subdirectory(${LIBDIR}/dev-utils ${LIBDIR_BIN}/dev-utils)
find_package(libnoise REQUIRED)
add_subdirectory(${LIBDIR}/libslic3r ${LIBDIR_BIN}/libslic3r)

# ---------------------------------------------------------------- JNI bridge
add_library(orca_jni SHARED
    jni/OrcaEngine.cpp
    jni/JniUtils.cpp
    jni/NanoSvg.cpp
)
target_include_directories(orca_jni PRIVATE ${LIBDIR} ${LIBDIR_BIN}/libslic3r)
target_link_libraries(orca_jni PRIVATE libslic3r boost_libs android log)
target_link_options(orca_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
