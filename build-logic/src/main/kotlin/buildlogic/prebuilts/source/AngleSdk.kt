package buildlogic.prebuilts.source

import buildlogic.prebuilts.ArchiveCache
import org.gradle.api.logging.Logger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * What this fork's Windows build of Filament compiles and links against to run its GL backend on ANGLE
 * ([SourceBuildRecipe.angle]): ANGLE's EGL/GLES headers at a commit, and import libraries for libEGL.dll and
 * libGLESv2.dll, made from the DLLs of NucleusFramework/angle's release (the ANGLE Nucleus ships). ANGLE publishes no
 * import libraries; the image links them delay-loaded (jni/CMakeLists.txt), so at run time it binds to the copies the
 * host loaded, whichever revision.
 */
internal object AngleSdk {
    /** The headers Filament's GL backend and c/filament/interop/FilaInterop.cpp include, with what they include. */
    private val HEADERS = listOf(
        "EGL/egl.h", "EGL/eglext.h", "EGL/eglext_angle.h", "EGL/eglplatform.h",
        "GLES2/gl2.h", "GLES2/gl2ext.h", "GLES2/gl2ext_angle.h", "GLES2/gl2platform.h",
        "GLES3/gl3.h", "GLES3/gl31.h", "GLES3/gl3platform.h",
        "KHR/khrplatform.h",
    )
    private val DLLS = listOf("libEGL", "libGLESv2")

    /**
     * Fills [dir] with include/ and libEGL.lib / libGLESv2.lib for [arch] ("x64" | "arm64"); [lib] runs MSVC's
     * lib.exe with the given arguments.
     */
    fun prepare(dir: File, release: String, commit: String, arch: String, cacheDir: File, logger: Logger, lib: (List<String>) -> Unit) {
        HEADERS.forEach { path ->
            ArchiveCache.angleHeader(cacheDir, commit, path, logger).copyTo(dir.resolve("include/$path"), overwrite = true)
        }
        ZipFile(ArchiveCache.angleRelease(cacheDir, release, arch, logger)).use { zip ->
            DLLS.forEach { name ->
                val entry = checkNotNull(zip.getEntry("$name.dll")) { "$name.dll missing from ANGLE's $release release" }
                val exports = zip.getInputStream(entry).use { peExports(it.readBytes()) }
                val def = dir.resolve("$name.def")
                def.writeText("LIBRARY $name.dll\nEXPORTS\n" + exports.joinToString("") { "  $it\n" })
                lib(listOf("/nologo", "/def:${def.path}", "/machine:$arch", "/out:${dir.resolve("$name.lib").path}"))
            }
        }
    }

    /** The names a PE image exports. */
    private fun peExports(image: ByteArray): List<String> {
        val pe = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN)
        val nt = pe.getInt(0x3C)
        check(pe.getInt(nt) == 0x00004550) { "not a PE image" }
        val sections = pe.getShort(nt + 6).toInt()
        val optional = nt + 24
        val optionalSize = pe.getShort(nt + 20).toInt()
        // PE32+ (0x20B) puts the data directories at 112, PE32 at 96
        val directories = optional + if (pe.getShort(optional).toInt() == 0x20B) 112 else 96
        val exportRva = pe.getInt(directories)
        if (exportRva == 0) return emptyList()
        fun offset(rva: Int): Int {
            for (i in 0 until sections) {
                val section = optional + optionalSize + i * 40
                val address = pe.getInt(section + 12)
                if (rva >= address && rva < address + pe.getInt(section + 8)) return rva - address + pe.getInt(section + 20)
            }
            error("RVA 0x${rva.toString(16)} is in no section")
        }
        val exports = offset(exportRva)
        val names = offset(pe.getInt(exports + 32))
        return (0 until pe.getInt(exports + 24)).map { i ->
            val start = offset(pe.getInt(names + 4 * i))
            var end = start
            while (image[end] != 0.toByte()) end++
            String(image, start, end - start, Charsets.US_ASCII)
        }
    }
}

/** MSVC's lib.exe for [arch], through vswhere. */
internal fun findMsvcLib(arch: String): String {
    val vswhere = File(System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)", "Microsoft Visual Studio\\Installer\\vswhere.exe")
    check(vswhere.isFile) { "vswhere.exe not found: the Windows ANGLE build needs Visual Studio's C++ tools" }
    // The host's own tools first: Hostarm64 on an ARM64 machine (the windows-arm64 runner), else Hostx64
    val hosts = if (System.getProperty("os.arch") == "aarch64") listOf("Hostarm64", "Hostx64") else listOf("Hostx64")
    for (host in hosts) {
        val found = ProcessBuilder(vswhere.path, "-latest", "-products", "*", "-find", "VC\\Tools\\MSVC\\**\\bin\\$host\\$arch\\lib.exe")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readLines().map(String::trim)
        found.lastOrNull { it.endsWith("lib.exe", ignoreCase = true) && File(it).isFile }?.let { return it }
    }
    error("MSVC's lib.exe for $arch not found")
}
