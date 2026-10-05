package buildlogic.prebuilts.source

import buildlogic.platform.FilamentTarget
import java.io.File

/**
 * How to build Filament's static libraries for a target upstream publishes none for.
 *
 * @property hostTools   tools built natively first and imported by the cross build (matc, resgen, …)
 * @property arguments   the target's CMake arguments on top of Filament's release defaults
 * @property patches     source file → text replacements applied before configuring
 * @property install     true: take libraries from `cmake --install`; false: from the build tree
 * @property extraTargets built after the default target (e.g. filamat, off by default on wasm)
 * @property egl         the libraries run Filament's GL backend on EGL: writes `prebuilts/<id>/egl`, which
 *                       c/CMakeLists.txt reads to build Interop.cpp's EGL share (this fork, Linux)
 * @property patchFile   a unified diff from build-logic's resources/patches, applied with `git apply` after [patches]
 * @property angle       the libraries run Filament's GL backend on ANGLE (this fork, Windows): the build compiles and
 *                       links against [AngleSdk], which lands in `prebuilts/<id>/angle` for c/CMakeLists.txt
 */
class SourceBuildRecipe(
    val hostTools: List<String> = emptyList(),
    val arguments: List<String>,
    val patches: Map<String, List<Pair<String, String>>> = emptyMap(),
    val install: Boolean,
    val extraTargets: List<String> = emptyList(),
    val emscripten: Boolean = false,
    val egl: Boolean = false,
    val patchFile: String? = null,
    val angle: Boolean = false,
) {
    /**
     * Part of the build stamp, so a cached build (CI restores the latest per target) is reused until something
     * that affects this target's recipe changes, not only filaVersion.
     */
    val fingerprint: String get() =
        listOf(hostTools, arguments, patches.toSortedMap().toList(), install, extraTargets, emscripten, egl,
            patchFile?.let { patchText(it) }, angle)
            .toString().hashCode().toUInt().toString(16)
}

internal fun FilamentTarget.sourceBuildRecipe(): SourceBuildRecipe = when (this) {
    FilamentTarget.WASM -> SourceBuildRecipe(
        // upstream build.sh's WEB_HOST_TOOLS
        hostTools = listOf("matc", "resgen", "cmgen", "filamesh", "uberz", "mipgen", "glslminifier"),
        arguments = listOf("-G", "Ninja", "-DWASM=1", "-DFILAMENT_BUILD_FILAMAT=ON"),
        install = false,
        extraTargets = listOf("filamat"),
        emscripten = true,
    )
    // Mirrors upstream's build/windows/build-github.bat /MT variant (hardcoded to x64 there), on ANGLE like windows-x64.
    FilamentTarget.WINDOWS_ARM64 -> SourceBuildRecipe(
        arguments = listOf(
            "-A", "ARM64", "-DUSE_STATIC_CRT=ON", "-DFILAMENT_WINDOWS_CI_BUILD=ON", "-DFILAMENT_SUPPORTS_VULKAN=ON",
            "-DFILAMENT_WINDOWS_ANGLE=ON", "-DFILAMENT_OPENGL_HANDLE_ARENA_SIZE_IN_MB=1",
        ),
        patches = mapOf(
            // BlueGL's only 64-bit Windows trampoline is x64 MASM; use the portable C++ one on ARM64.
            "libs/bluegl/CMakeLists.txt" to listOf(
                "if(NOT IS_64_BIT)" to "if(NOT IS_64_BIT OR CMAKE_GENERATOR_PLATFORM STREQUAL \"ARM64\")",
                "if (WIN32 AND IS_64_BIT)" to "if (WIN32 AND IS_64_BIT AND NOT CMAKE_GENERATOR_PLATFORM STREQUAL \"ARM64\")",
            ),
        ),
        // The MSYS2 check (Git Bash forwards \$MSYSTEM even to MSVC builds) goes with the patch, as on windows-x64
        patchFile = "filament-windows-angle.patch",
        install = true,
        angle = true,
    )
    // This fork: upstream's build/windows/build-github.bat /MT variant, with Filament's GL backend on ANGLE (EGL + GLES on
    // D3D11) instead of WGL, as Nucleus windows already draw with ANGLE: filament-compose then renders without the
    // driver's OpenGL nor its D3D11 interop (c/filament/interop/FilaInterop.cpp). The patch also keeps two large
    // allocations out of every process: gltfio's ubershader archive stays compressed until a material is built, and
    // basisu's XUASTC tables (~19 MB) are built by the first XUASTC decode rather than by every KTX2 reader.
    FilamentTarget.WINDOWS_X64 -> SourceBuildRecipe(
        arguments = listOf(
            "-A", "x64", "-DUSE_STATIC_CRT=ON", "-DFILAMENT_WINDOWS_CI_BUILD=ON", "-DFILAMENT_SUPPORTS_VULKAN=ON",
            "-DFILAMENT_WINDOWS_ANGLE=ON",
            // The GL handles' arena: past it handles go to the heap, a few small views needing far less than 4 MB
            "-DFILAMENT_OPENGL_HANDLE_ARENA_SIZE_IN_MB=1",
        ),
        patchFile = "filament-windows-angle.patch",
        install = true,
        angle = true,
    )
    // This fork: no upstream release for macOS x64 (built on an Intel runner).
    FilamentTarget.MACOS_X64 -> SourceBuildRecipe(
        arguments = listOf("-DCMAKE_OSX_ARCHITECTURES=x86_64"),
        install = true,
    )
    // This fork: upstream's Linux build (clang + libc++, which the wrapper links against too) on EGL.
    FilamentTarget.LINUX_X64, FilamentTarget.LINUX_ARM64 -> SourceBuildRecipe(
        arguments = listOf("-DFILAMENT_SUPPORTS_EGL_ON_LINUX=ON", "-DCMAKE_C_COMPILER=clang", "-DCMAKE_CXX_COMPILER=clang++"),
        patches = mapOf(
            // The GL backend runs on GLES here, which loads materials' mobile shaders, while a Linux build only
            // compiles desktop ones (FILAMENT_LINUX_IS_MOBILE would, but it also drops the host tools the build
            // needs). Compile both, so Vulkan keeps desktop too.
            "CMakeLists.txt" to listOf(
                "set(MATC_BASE_FLAGS \${MATC_API_FLAGS} -p \${MATC_TARGET} \${MATC_OPT_FLAGS})" to
                    "set(MATC_BASE_FLAGS \${MATC_API_FLAGS} -p all \${MATC_OPT_FLAGS})",
            ),
            // The EGL build compiles the GL backend against GLES but only links EGL, so Filament's own tools
            // (matc, ...) fail to link; add GLESv2 next to it.
            "filament/backend/CMakeLists.txt" to listOf(
                "target_link_libraries(\${TARGET} PUBLIC EGL)" to "target_link_libraries(\${TARGET} PUBLIC EGL GLESv2)",
            ),
            // FilaInterop's platform runs on the Nucleus window's EGLDisplay (setEglDisplay), which PlatformEGL
            // would eglTerminate when an engine goes away, killing the window's own contexts: a display set from
            // outside stays its owner's.
            "filament/backend/include/backend/platforms/PlatformEGL.h" to listOf(
                "    EGLDisplay mEGLDisplay = EGL_NO_DISPLAY;\n    EGLContext mEGLContext" to
                    "    EGLDisplay mEGLDisplay = EGL_NO_DISPLAY;\n    bool mOwnsEglDisplay = true;\n    EGLContext mEGLContext",
            ),
            "filament/backend/src/opengl/platforms/PlatformEGL.cpp" to listOf(
                "    mEGLDisplay = display;\n}" to "    mEGLDisplay = display;\n    mOwnsEglDisplay = false;\n}",
                "    eglTerminate(mEGLDisplay);\n    eglReleaseThread();" to
                    "    if (mOwnsEglDisplay) {\n        eglTerminate(mEGLDisplay);\n    }\n    eglReleaseThread();",
            ),
        ),
        install = true,
        egl = true,
    )
    else -> error("$id is downloaded from upstream releases, not built from source")
}

/** build-logic's resources/patches/[name]. */
internal fun patchText(name: String): String =
    checkNotNull(SourceBuildRecipe::class.java.getResourceAsStream("/patches/$name")) { "patch $name not found" }
        .use { it.readBytes().decodeToString() }

/** The files [patchFile] changes, by their path in the source tree. */
internal fun SourceBuildRecipe.patchedPaths(): List<String> =
    patchFile?.let { patchText(it) }?.lineSequence()
        ?.filter { it.startsWith("+++ b/") }?.map { it.removePrefix("+++ b/").trim() }?.toList()
        .orEmpty()

/** The static libraries a finished build produced, from its install tree or its build tree. */
internal fun SourceBuildRecipe.collectLibraries(buildDir: File, installDir: File): List<File> = if (install) {
    installDir.resolve("lib").walk().filter { it.isFile && (it.extension == "a" || it.extension == "lib") }.toList()
} else {
    buildDir.walk().onEnter { it.name != "CMakeFiles" }.filter { it.isFile && it.extension == "a" }.toList()
}

/** uberarchive.h as built: resgen bakes this build's archive size in. */
internal fun SourceBuildRecipe.uberarchiveHeader(buildDir: File, installDir: File): File =
    if (install) installDir.resolve("include/gltfio/materials/uberarchive.h")
    else buildDir.resolve("libs/gltfio/materials/uberarchive.h")
