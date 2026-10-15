# Dependency recipes. Ordering follows the dependency graph.
# GitHub-hosted sources are fetched by git tag rather than archive URL, so the
# build also works behind proxies that only allow git and release downloads.

# ---------------------------------------------------------------- compression / images
android_dep(ZLIB
    GIT_REPOSITORY https://github.com/madler/zlib.git
    GIT_TAG v1.2.13
    GIT_SHALLOW TRUE
    PATCH_COMMAND ${PATCH} ${ORCA_DEPS_DIR}/ZLIB/0001-Respect-BUILD_SHARED_LIBS.patch
    CMAKE_ARGS -DSKIP_INSTALL_FILES=ON
)

android_dep(PNG
    GIT_REPOSITORY https://github.com/glennrp/libpng.git
    GIT_TAG v1.6.35
    GIT_SHALLOW TRUE
    DEPENDS dep_ZLIB
    CMAKE_ARGS
        -DPNG_SHARED=OFF
        -DPNG_STATIC=ON
        -DPNG_PREFIX=prusaslicer_
        -DPNG_TESTS=OFF
        -DPNG_ARM_NEON=off
)

android_dep(JPEG
    GIT_REPOSITORY https://github.com/libjpeg-turbo/libjpeg-turbo.git
    GIT_TAG 3.0.1
    GIT_SHALLOW TRUE
    DEPENDS dep_ZLIB
    CMAKE_ARGS
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DWITH_JPEG8=ON
        -DWITH_TURBOJPEG=OFF
)

android_dep(EXPAT
    SOURCE_DIR ${ORCA_DEPS_DIR}/EXPAT/expat
)

android_dep(FREETYPE
    URL https://github.com/SoftFever/orca_deps/releases/download/freetype-2.12.1.tar.gz/freetype-2.12.1.tar.gz
    URL_HASH SHA256=efe71fd4b8246f1b0b1b9bfca13cfff1c9ad85930340c27df469733bbb620938
    CMAKE_ARGS
        -DFT_DISABLE_ZLIB=TRUE
        -DFT_DISABLE_BZIP2=TRUE
        -DFT_DISABLE_PNG=TRUE
        -DFT_DISABLE_HARFBUZZ=TRUE
        -DFT_DISABLE_BROTLI=TRUE
)

# ---------------------------------------------------------------- core C++ libraries
android_dep(Boost
    URL https://github.com/boostorg/boost/releases/download/boost-1.84.0/boost-1.84.0.tar.gz
    URL_HASH SHA256=4d27e9efed0f6f152dc28db6430b9d3dfb40c0345da7342eaa5a987dde57bd95
    DEPENDS dep_ZLIB
    LIST_SEPARATOR |
    CMAKE_ARGS
        -DBOOST_EXCLUDE_LIBRARIES:STRING=contract|fiber|numpy|stacktrace|wave|test|python|mpi|graph_parallel|cobalt
        -DBOOST_LOCALE_ENABLE_ICU:BOOL=OFF
        -DBOOST_LOCALE_ENABLE_ICONV:BOOL=OFF
        -DBUILD_TESTING:BOOL=OFF
        -DBOOST_IOSTREAMS_ENABLE_BZIP2:BOOL=OFF
        -DBOOST_IOSTREAMS_ENABLE_ZSTD:BOOL=OFF
        -DBOOST_IOSTREAMS_ENABLE_LZMA:BOOL=OFF
)

android_dep(TBB
    GIT_REPOSITORY https://github.com/oneapi-src/oneTBB.git
    GIT_TAG v2021.5.0
    GIT_SHALLOW TRUE
    CMAKE_ARGS
        -DTBB_BUILD_SHARED=OFF
        -DTBB_TEST=OFF
        -DTBB_STRICT=OFF
        # oneTBB 2021.5 copies libc++_shared.so from a pre-r23 NDK path when
        # ANDROID_STL is c++_shared; the static archive is STL-agnostic anyway.
        -DANDROID_STL=c++_static
        -DCMAKE_DEBUG_POSTFIX=_debug
)

android_dep(Cereal
    GIT_REPOSITORY https://github.com/USCiLab/cereal.git
    GIT_TAG v1.3.0
    GIT_SHALLOW TRUE
    CMAKE_ARGS
        -DJUST_INSTALL_CEREAL=ON
        -DSKIP_PERFORMANCE_COMPARISON=ON
        -DBUILD_TESTS=OFF
)

android_dep(Qhull
    GIT_REPOSITORY https://github.com/qhull/qhull.git
    GIT_TAG v8.0.2
    GIT_SHALLOW TRUE
    CMAKE_ARGS -DINCLUDE_INSTALL_DIR=include -DBUILD_APPLICATIONS=OFF
)

android_dep(NLopt
    GIT_REPOSITORY https://github.com/stevengj/nlopt.git
    GIT_TAG v2.5.0
    GIT_SHALLOW TRUE
    CMAKE_ARGS
        -DNLOPT_PYTHON:BOOL=OFF
        -DNLOPT_OCTAVE:BOOL=OFF
        -DNLOPT_MATLAB:BOOL=OFF
        -DNLOPT_GUILE:BOOL=OFF
        -DNLOPT_SWIG:BOOL=OFF
        -DNLOPT_TESTS:BOOL=OFF
)

android_dep(libnoise
    GIT_REPOSITORY https://github.com/SoftFever/Orca-deps-libnoise.git
    GIT_TAG 1.0
    GIT_SHALLOW TRUE
)

android_dep(Draco
    GIT_REPOSITORY https://github.com/google/draco.git
    GIT_TAG 1.5.7
    GIT_SHALLOW TRUE
    CMAKE_ARGS -DDRACO_JS_GLUE=OFF -DDRACO_TESTS=OFF
)

# ---------------------------------------------------------------- GMP / MPFR / CGAL
ExternalProject_Add(dep_GMP
    EXCLUDE_FROM_ALL ON
    URL https://github.com/SoftFever/OrcaSlicer_deps/releases/download/gmp-6.2.1/gmp-6.2.1.tar.bz2
    URL_HASH SHA256=eae9326beb4158c386e39a356818031bd28f3124cf915f8c5b1dc4c7a36b4d7c
    DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/GMP
    PATCH_COMMAND ${PATCH} ${ORCA_DEPS_DIR}/GMP/0001-GMP_GCC15.patch
    BUILD_IN_SOURCE ON
    CONFIGURE_COMMAND ${AT_ENV} ./configure --host=${ANDROID_TRIPLE} --prefix=${DESTDIR}
        --enable-cxx --enable-static --disable-shared --with-pic
    BUILD_COMMAND ${AT_ENV} make -j${NPROC}
    INSTALL_COMMAND ${AT_ENV} make install
)

ExternalProject_Add(dep_MPFR
    EXCLUDE_FROM_ALL ON
    URL https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.2.tar.bz2
        https://www.mpfr.org/mpfr-4.2.2/mpfr-4.2.2.tar.bz2
    URL_HASH SHA256=9ad62c7dc910303cd384ff8f1f4767a655124980bb6d8650fe62c815a231bb7b
    DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/MPFR
    DEPENDS dep_GMP
    BUILD_IN_SOURCE ON
    CONFIGURE_COMMAND ${AT_ENV} ./configure --host=${ANDROID_TRIPLE} --prefix=${DESTDIR}
        --with-gmp=${DESTDIR} --enable-static --disable-shared --with-pic
    BUILD_COMMAND ${AT_ENV} make -j${NPROC}
    INSTALL_COMMAND ${AT_ENV} make install
)

android_dep(Eigen
    URL https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.zip
    URL_HASH SHA256=0dbb1f9e3aaad66f352c03227d8c983f6f0b49e0b07e71a7300f4abcc01aee12
    CMAKE_ARGS -DEIGEN_BUILD_TESTING=OFF -DBUILD_TESTING=OFF -DEIGEN_BUILD_DOC=OFF -DEIGEN_BUILD_BLAS=OFF -DEIGEN_BUILD_LAPACK=OFF
)

android_dep(CGAL
    URL https://github.com/CGAL/cgal/releases/download/v5.6.3/CGAL-5.6.3.zip
    URL_HASH SHA256=5d577acb4a9918ccb960491482da7a3838f8d363aff47e14d703f19fd84733d4
    DEPENDS dep_Boost dep_Eigen dep_GMP dep_MPFR
)

# ---------------------------------------------------------------- OpenSSL (MD5 / hashing)
ExternalProject_Add(dep_OpenSSL
    EXCLUDE_FROM_ALL ON
    GIT_REPOSITORY https://github.com/openssl/openssl.git
    GIT_TAG OpenSSL_1_1_1w
    GIT_SHALLOW TRUE
    DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/OpenSSL
    BUILD_IN_SOURCE ON
    CONFIGURE_COMMAND env "PATH=${NDK_BIN}:$ENV{PATH}" "ANDROID_NDK_ROOT=${ANDROID_NDK}" "ANDROID_NDK_HOME=${ANDROID_NDK}"
        ./Configure ${OPENSSL_TARGET} -D__ANDROID_API__=${ANDROID_API_LEVEL}
        --prefix=${DESTDIR} --openssldir=${DESTDIR}
        no-shared no-asm no-ssl3-method no-dynamic-engine no-tests
    BUILD_COMMAND env "PATH=${NDK_BIN}:$ENV{PATH}" "ANDROID_NDK_ROOT=${ANDROID_NDK}" make -j${NPROC} build_libs
    INSTALL_COMMAND env "PATH=${NDK_BIN}:$ENV{PATH}" "ANDROID_NDK_ROOT=${ANDROID_NDK}" make install_dev
)

# ---------------------------------------------------------------- OpenCV (core/imgproc only)
android_dep(OpenCV
    GIT_REPOSITORY https://github.com/opencv/opencv.git
    GIT_TAG 4.6.0
    GIT_SHALLOW TRUE
    PATCH_COMMAND ${PATCH} ${ORCA_DEPS_DIR}/OpenCV/0002-clang19-macos.patch
    DEPENDS dep_ZLIB
    CMAKE_ARGS
        -DBUILD_ANDROID_PROJECTS=OFF
        -DBUILD_ANDROID_EXAMPLES=OFF
        -DBUILD_PERF_TESTS=OFF
        -DBUILD_TESTS=OFF
        -DBUILD_EXAMPLES=OFF
        -DBUILD_DOCS=OFF
        -DBUILD_JAVA=OFF
        -DBUILD_opencv_java=OFF
        -DBUILD_opencv_apps=OFF
        -DBUILD_FAT_JAVA_LIB=OFF
        -DBUILD_KOTLIN_EXTENSIONS=OFF
        -DBUILD_ZLIB=OFF
        -DBUILD_PNG=ON
        -DBUILD_JPEG=ON
        -DBUILD_TIFF=OFF
        -DBUILD_WEBP=OFF
        -DBUILD_OPENEXR=OFF
        -DBUILD_JASPER=OFF
        -DBUILD_TBB=OFF
        -DBUILD_ITT=OFF
        -DWITH_ITT=OFF
        -DWITH_IPP=OFF
        -DWITH_TBB=OFF
        -DWITH_EIGEN=OFF
        -DWITH_JPEG=OFF
        -DWITH_TIFF=OFF
        -DWITH_WEBP=OFF
        -DWITH_OPENEXR=OFF
        -DWITH_OPENJPEG=OFF
        -DWITH_JASPER=OFF
        -DWITH_QUIRC=OFF
        -DWITH_OPENCL=OFF
        -DWITH_ANDROID_MEDIANDK=OFF
        -DWITH_ADE=OFF
        -DWITH_PROTOBUF=OFF
        -DWITH_FFMPEG=OFF
        -DWITH_CUDA=OFF
        -DWITH_LAPACK=OFF
        -DWITH_VTK=OFF
        -DENABLE_PRECOMPILED_HEADERS=OFF
        -DOPENCV_GENERATE_SETUPVARS=OFF
        -DBUILD_LIST=core,imgcodecs,imgproc,world
        -DBUILD_opencv_world=ON
        -DBUILD_opencv_highgui=OFF
        -DBUILD_opencv_python2=OFF
        -DBUILD_opencv_python3=OFF
        -DINSTALL_CREATE_DISTRIB=OFF
        -DOPENCV_INSTALL_BINARIES_PREFIX=
        -DOPENCV_LIB_INSTALL_PATH=lib
        -DOPENCV_3P_LIB_INSTALL_PATH=lib/opencv4/3rdparty
        -DOPENCV_CONFIG_INSTALL_PATH=lib/cmake/opencv4
        -DOPENCV_INCLUDE_INSTALL_PATH=include/opencv4
        -DOPENCV_OTHER_INSTALL_PATH=share/opencv4
)

# ---------------------------------------------------------------- OpenCASCADE (STEP import)
android_dep(OCCT
    GIT_REPOSITORY https://github.com/Open-Cascade-SAS/OCCT.git
    GIT_TAG V7_6_0
    GIT_SHALLOW TRUE
    PATCH_COMMAND ${PATCH} ${ORCA_DEPS_DIR}/OCCT/0001-OCCT-fix.patch
    DEPENDS dep_FREETYPE
    CMAKE_ARGS
        -DCMAKE_CXX_STANDARD=17
        -DBUILD_LIBRARY_TYPE=Static
        -DINSTALL_DIR_LAYOUT=Windows
        -DUSE_TK=OFF
        -DUSE_TBB=OFF
        -DUSE_FFMPEG=OFF
        -DUSE_VTK=OFF
        -DUSE_OPENGL=OFF
        -DUSE_GLES2=OFF
        -DUSE_XLIB=OFF
        -DUSE_FREEIMAGE=OFF
        -DUSE_RAPIDJSON=OFF
        -DUSE_DRACO=OFF
        -DBUILD_DOC_Overview=OFF
        -DBUILD_MODULE_ApplicationFramework=OFF
        -DBUILD_MODULE_Draw=OFF
        -DBUILD_MODULE_FoundationClasses=OFF
        -DBUILD_MODULE_ModelingAlgorithms=OFF
        -DBUILD_MODULE_ModelingData=OFF
        -DBUILD_MODULE_Visualization=OFF
        -D3RDPARTY_FREETYPE_DIR=${DESTDIR}
        -D3RDPARTY_FREETYPE_INCLUDE_DIR_ft2build=${DESTDIR}/include/freetype2
        -D3RDPARTY_FREETYPE_INCLUDE_DIR_freetype2=${DESTDIR}/include/freetype2
        -D3RDPARTY_FREETYPE_LIBRARY_DIR=${DESTDIR}/lib
        -D3RDPARTY_FREETYPE_LIBRARY=${DESTDIR}/lib/libfreetype.a
)

# ---------------------------------------------------------------- OpenVDB (SLA hollowing)
android_dep(Blosc
    GIT_REPOSITORY https://github.com/tamasmeszaros/c-blosc.git
    GIT_TAG v1.17.0_tm
    GIT_SHALLOW TRUE
    DEPENDS dep_ZLIB
    CMAKE_ARGS
        -DBUILD_SHARED=OFF
        -DBUILD_STATIC=ON
        -DBUILD_TESTS=OFF
        -DBUILD_BENCHMARKS=OFF
        -DPREFER_EXTERNAL_ZLIB=ON
        -DDEACTIVATE_SSE2=ON
        -DDEACTIVATE_AVX2=ON
)

android_dep(OpenEXR
    GIT_REPOSITORY https://github.com/AcademySoftwareFoundation/openexr.git
    GIT_TAG v2.5.5
    GIT_SHALLOW TRUE
    PATCH_COMMAND ${PATCH} ${ORCA_DEPS_DIR}/OpenEXR/0001-OpenEXR-GCC13.patch
    DEPENDS dep_ZLIB
    SOURCE_SUBDIR IlmBase
    CMAKE_ARGS
        -DBUILD_TESTING=OFF
        -DPYILMBASE_ENABLE=OFF
)

android_dep(OpenVDB
    GIT_REPOSITORY https://github.com/tamasmeszaros/openvdb.git
    GIT_TAG a68fd58d0e2b85f01adeb8b13d7555183ab10aa5
    PATCH_COMMAND ${PATCH} ${ORCA_DEPS_DIR}/OpenVDB/0001-clang19.patch
    DEPENDS dep_TBB dep_Blosc dep_OpenEXR dep_Boost
    CMAKE_ARGS
        -DOPENVDB_BUILD_PYTHON_MODULE=OFF
        -DUSE_BLOSC=ON
        -DOPENVDB_CORE_SHARED=OFF
        -DOPENVDB_CORE_STATIC=ON
        -DOPENVDB_ENABLE_RPATH=OFF
        -DTBB_STATIC=ON
        -DOPENVDB_BUILD_VDB_PRINT=OFF
        -DOPENVDB_BUILD_BINARIES=OFF
        -DDISABLE_DEPENDENCY_VERSION_CHECKS=ON
        -DBoost_USE_STATIC_LIBS=ON
)
