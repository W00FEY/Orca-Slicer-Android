// libslic3r uses nanosvg (SVG import, emboss shapes) but the desktop app
// compiles its implementation inside the GUI (BitmapCache.cpp), which is not
// part of this build.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#define NANOSVG_IMPLEMENTATION
#include "nanosvg/nanosvg.h"
