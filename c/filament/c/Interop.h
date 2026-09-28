#ifndef FILAMENT_C_INTEROP_H
#define FILAMENT_C_INTEROP_H

#include "Engine.h"

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Zero-copy interop with a host toolkit's GPU context (filament-compose's Nucleus surfaces).
// Every function exists on every platform; where a path does not apply it is a no-op that
// returns 0/NULL/false, so callers probe instead of branching on the OS.

// ── Linux (Filament built with FILAMENT_SUPPORTS_EGL_ON_LINUX) ────────────────────────────────

// True when this library carries the EGL platform (source-built Linux libs, see
// scripts/dev/build-host-libs.sh). Upstream Linux prebuilts are GLX-only.
bool FilaInterop_hasEglPlatform(void);

// Makes the engine render through EGL on `eglDisplay` (an initialized EGLDisplay, typically the
// host's), so that the EGLContext passed to FilaEngineBuilder_sharedContext can be shared.
// Requires FilaInterop_hasEglPlatform(); forces the OpenGL backend.
void FilaEngineBuilder_eglDisplay(FilaEngineBuilder* builder, void* eglDisplay);

// RGBA8 2D texture in the context current on the calling thread; returns its GL name (0 on
// failure). Import it into Filament with FilaTextureBuilder_importTexture.
uint32_t FilaGl_createTexture(int32_t width, int32_t height);
void FilaGl_deleteTexture(uint32_t name);
// glFlush() on the current context, making its commands visible to shared contexts.
void FilaGl_flush(void);

// ── Windows (WGL_NV_DX_interop2) ─────────────────────────────────────────────────────────────

// A D3D11 device plus a hidden WGL context whose GL objects alias D3D11 textures. Pass
// FilaDxShare_glContext() to FilaEngineBuilder_sharedContext (OpenGL backend) so Filament
// shares its namespace. NULL when the driver lacks WGL_NV_DX_interop2, or not on Windows.
typedef struct FilaDxShare FilaDxShare;
FilaDxShare* FilaDxShare_create(void);
void* FilaDxShare_glContext(FilaDxShare* share);
void FilaDxShare_destroy(FilaDxShare* share);

// A shareable (legacy DXGI handle) BGRA8 D3D11 texture registered as a GL texture.
typedef struct FilaDxTexture FilaDxTexture;
FilaDxTexture* FilaDxTexture_create(FilaDxShare* share, int32_t width, int32_t height);
uint32_t FilaDxTexture_glName(FilaDxTexture* texture);
void* FilaDxTexture_sharedHandle(FilaDxTexture* texture);
// GL may only touch the texture between lock and unlock; D3D11 consumers only outside.
bool FilaDxTexture_lock(FilaDxTexture* texture);
bool FilaDxTexture_unlock(FilaDxTexture* texture);
void FilaDxTexture_destroy(FilaDxTexture* texture);

#ifdef __cplusplus
}
#endif

#endif
