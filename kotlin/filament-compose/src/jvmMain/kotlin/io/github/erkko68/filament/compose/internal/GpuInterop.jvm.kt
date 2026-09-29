@file:JvmName("GpuInteropJvm")

package io.github.erkko68.filament.compose.internal

import io.github.erkko68.filament.interop.NativePointer

// c/filament/c/Interop.h, for the Nucleus GPU surfaces. JVM-only externals: the root generateBindings task
// writes their JNI forwarders from this file (jniSources); NativePointer is the raw address (0 = NULL).

internal external fun FilaGpuShare_create(hostEglDisplay: NativePointer): NativePointer
internal external fun FilaGpuShare_destroy(share: NativePointer)
internal external fun FilaGpuShare_currentEglDisplay(): NativePointer

internal external fun FilaEngineBuilder_create(): NativePointer
internal external fun FilaEngineBuilder_gpuShare(builder: NativePointer, share: NativePointer)
internal external fun FilaEngineBuilder_build(builder: NativePointer): NativePointer
internal external fun FilaEngineBuilder_destroy(builder: NativePointer)

internal external fun FilaGpuTexture_create(share: NativePointer, width: Int, height: Int): NativePointer
internal external fun FilaGpuTexture_glName(texture: NativePointer): Int
internal external fun FilaGpuTexture_handle(texture: NativePointer): NativePointer
internal external fun FilaGpuTexture_lock(texture: NativePointer): Boolean
internal external fun FilaGpuTexture_unlock(texture: NativePointer): Boolean
internal external fun FilaGpuTexture_destroy(texture: NativePointer)

internal external fun FilaMetalTexture_create(mtlDevice: NativePointer, width: Int, height: Int): NativePointer
internal external fun FilaMetalTexture_retain(mtlTexture: NativePointer)
internal external fun FilaMetalTexture_release(mtlTexture: NativePointer)
