package buildlogic.prebuilts.source

import buildlogic.platform.FilamentTarget
import buildlogic.platform.emsdkEnvironment
import buildlogic.platform.findOnPath
import buildlogic.platform.resolveCmake
import buildlogic.prebuilts.ArchiveCache
import buildlogic.prebuilts.DownloadPrebuiltsTask
import buildlogic.prebuilts.TarExtraction
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Builds Filament's static libraries from the release's source tarball for a target upstream ships none
 * for ([sourceBuildRecipe]), into `prebuilts/<id>/lib` like a download, plus the build's own uberarchive.h
 * in `prebuilts/<id>/include` (c/cmake/Platform.cmake puts it first). Slow the first time (a full Filament
 * build); the stamp keeps it done until filaVersion changes.
 */
abstract class BuildFromSourceTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val filamentVersion: Property<String>
    @get:Input abstract val target: Property<FilamentTarget>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Internal abstract val workDir: DirectoryProperty
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:Internal abstract val emsdkDir: DirectoryProperty
    /** NucleusFramework/angle's release and ANGLE's commit for its headers ([SourceBuildRecipe.angle] targets). */
    @get:Input @get:Optional abstract val angleRelease: Property<String>
    @get:Input @get:Optional abstract val angleCommit: Property<String>

    private fun stamp() = outputDir.get().asFile.resolve("lib/${DownloadPrebuiltsTask.STAMP}")
    private fun stampValue() = "${filamentVersion.get()}|source|${target.get().sourceBuildRecipe().fingerprint}" +
        if (target.get().sourceBuildRecipe().angle) "|angle-${angleRelease.get()}-${angleCommit.get()}" else ""

    @TaskAction
    fun build() {
        // Checked here, not as up-to-dateness: a CI cache restores the libraries without Gradle's task history.
        if (stamp().isFile && stamp().readText().trim() == stampValue()) return
        val target = target.get()
        val recipe = target.sourceBuildRecipe()
        val version = filamentVersion.get()
        val src = workDir.get().asFile.resolve("filament-$version")
        if (!src.resolve("CMakeLists.txt").isFile) {
            TarExtraction.extractTree(ArchiveCache.filamentSource(cacheDir.get().asFile, version, logger), src)
        }
        recipe.patches.forEach { (path, replacements) ->
            val file = src.resolve(path)
            file.writeText(replacements.fold(file.readText()) { text, (old, new) -> text.replace(old, new) })
        }
        recipe.patchFile?.let { applyPatch(src, it) }

        val out = src.resolve("out")
        val common = listOf("-DCMAKE_BUILD_TYPE=Release", "-DFILAMENT_SKIP_SAMPLES=ON", "-DFILAMENT_SKIP_SDL2=ON", "-DFILAMENT_BUILD_TESTING=OFF")
        if (recipe.hostTools.isNotEmpty()) {
            // Filament's cross builds import these via IMPORT_EXECUTABLES_DIR (relative to the source root).
            val tools = out.resolve("cmake-host-tools")
            cmake(src, "-S", src.path, "-B", tools.path, "-G", "Ninja", "-DIMPORT_EXECUTABLES_DIR=out", *common.toTypedArray())
            cmake(src, "--build", tools.path, "--target", *recipe.hostTools.toTypedArray())
        }

        val build = out.resolve("cmake-${target.id}")
        val install = out.resolve(target.id)
        val toolchain = if (recipe.emscripten) {
            // What emcmake adds: the toolchain file confines program lookup to its sysroot, so name ninja.
            val ninja = checkNotNull(findOnPath("ninja")) { "ninja not found on PATH (needed for the wasm build)" }
            listOf("-DCMAKE_TOOLCHAIN_FILE=${emsdkDir.get().asFile.invariantSeparatorsPath}/upstream/emscripten/cmake/Modules/Platform/Emscripten.cmake",
                "-DCMAKE_MAKE_PROGRAM=$ninja")
        } else {
            emptyList()
        }
        val imports = if (recipe.hostTools.isNotEmpty()) listOf("-DIMPORT_EXECUTABLES_DIR=out") else emptyList()
        val angleSdk = out.resolve("angle-sdk")
        val angle = if (recipe.angle) {
            val arch = if (target == FilamentTarget.WINDOWS_ARM64) "arm64" else "x64"
            val lib = findMsvcLib(arch)
            AngleSdk.prepare(angleSdk, angleRelease.get(), angleCommit.get(), arch, cacheDir.get().asFile, logger) { args ->
                exec.exec { commandLine(listOf(lib) + args) }
            }
            val libs = listOf("libEGL", "libGLESv2").joinToString(";") { angleSdk.resolve("$it.lib").invariantSeparatorsPath }
            listOf("-DFILAMENT_ANGLE_LIBS=$libs", "-DFILAMENT_ANGLE_INCLUDE=${angleSdk.resolve("include").invariantSeparatorsPath}")
        } else {
            emptyList()
        }
        cmake(src, "-S", src.path, "-B", build.path, "-DCMAKE_INSTALL_PREFIX=${install.path}",
            *(common + imports + toolchain + recipe.arguments + angle).toTypedArray())
        // An explicit job count: a bare --parallel is an unbounded `make -j` with Makefiles, which exhausts CI
        // runners' memory within minutes.
        val jobs = Runtime.getRuntime().availableProcessors().toString()
        cmake(src, "--build", build.path, "--config", "Release", "--parallel", jobs, *(if (recipe.install) arrayOf("--target", "install") else emptyArray()))
        recipe.extraTargets.forEach { cmake(src, "--build", build.path, "--config", "Release", "--target", it) }

        val outDir = outputDir.get().asFile.apply { deleteRecursively() }
        val libDir = outDir.resolve("lib").apply { mkdirs() }
        val libs = recipe.collectLibraries(build, install)
        libs.forEach { it.copyTo(libDir.resolve(it.name), overwrite = true) }
        // Like upstream's install step: libfilamat is the combined archive (glslang, SPIRV-Tools/Cross).
        libs.firstOrNull { it.name == "libfilamat_combined.a" }?.copyTo(libDir.resolve("libfilamat.a"), overwrite = true)
        recipe.uberarchiveHeader(build, install).copyTo(outDir.resolve("include/gltfio/materials/uberarchive.h"))
        // A patched public header changes what the libraries were built against (a class layout, say), so it
        // must win over include/'s upstream copy too, with its directory: siblings include it by a quoted path.
        (recipe.patches.keys + recipe.patchedPaths()).filter { it.contains("/include/") && it.endsWith(".h") }.forEach { path ->
            val dir = src.resolve(path).parentFile
            dir.copyRecursively(outDir.resolve("include/${dir.relativeTo(src).invariantSeparatorsPath.substringAfter("/include/")}"), overwrite = true)
        }
        if (recipe.egl) outDir.resolve("egl").writeText("")
        // c/CMakeLists.txt's marker for the ANGLE build, with what the C API compiles and links against
        if (recipe.angle) angleSdk.copyRecursively(outDir.resolve("angle"), overwrite = true)
        stamp().writeText(stampValue() + "\n")
        logger.lifecycle("[${target.id}] built ${libs.size} libraries from source")
    }

    /** Applies build-logic's patches/[name] to [src], unless a previous, interrupted build already did. */
    private fun applyPatch(src: File, name: String) {
        val patch = temporaryDir.resolve(name).apply { writeText(patchText(name)) }
        val applied = exec.exec {
            workingDir(src)
            commandLine("git", "apply", "--reverse", "--check", patch.path)
            isIgnoreExitValue = true
            errorOutput = java.io.OutputStream.nullOutputStream()
        }.exitValue == 0
        if (applied) return
        exec.exec {
            workingDir(src)
            commandLine("git", "apply", "--whitespace=nowarn", patch.path)
        }
    }

    private fun cmake(workingDir: File, vararg args: String) {
        val emscripten = target.get().sourceBuildRecipe().emscripten
        exec.exec {
            workingDir(workingDir)
            commandLine(resolveCmake(), *args)
            environment.remove("MSYSTEM")
            if (emscripten) environment(emsdkEnvironment(emsdkDir.get().asFile))
        }
    }
}
