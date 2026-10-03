#ifndef FILA_INTEROP_FILAINTEROP_H
#define FILA_INTEROP_FILAINTEROP_H

#include "../generated/Types.h"

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Zero-copy interop with a host toolkit's compositor (filament-compose's Nucleus surfaces): an
// OpenGL Filament engine renders into textures the host imports without a CPU copy.
//   Linux:   a GLES context on the host's EGLDisplay, shared with Filament; textures are exported
//            as EGLImages. Needs Filament built with FILAMENT_SUPPORTS_EGL_ON_LINUX
//            (scripts/dev/build-host-libs.sh) — upstream Linux prebuilts are GLX-only.
//   Windows: a WGL context shared with Filament; textures alias shareable D3D11 textures through
//            WGL_NV_DX_interop2 and are exported as legacy DXGI shared handles.
// Every function exists on every platform; where unsupported, creation returns NULL.

typedef struct FilaGpuShare FilaGpuShare;

// hostEglDisplay: the host's initialized EGLDisplay on Linux, ignored elsewhere. NULL when the
// platform, driver or Filament build cannot do it.
FilaGpuShare* FilaGpuShare_create(void* hostEglDisplay);
void FilaGpuShare_destroy(FilaGpuShare* share);
// Linux: the EGLDisplay of the calling thread's current context (the host's, while it is current). NULL elsewhere.
void* FilaGpuShare_currentEglDisplay(void);

// Configures the builder to render on the share's device: OpenGL backend, shared context and,
// on Linux, the host's EGLDisplay. The share must outlive the engine.
void FilaEngineBuilder_gpuShare(FilaEngineBuilder* builder, FilaGpuShare* share);

// An RGBA8 texture importable into Filament (FilaTextureBuilder_importTexture with glName)
// and by the host (handle: an EGLImage on Linux, a DXGI shared handle on Windows).
typedef struct FilaGpuTexture FilaGpuTexture;
FilaGpuTexture* FilaGpuTexture_create(FilaGpuShare* share, int32_t width, int32_t height);
uint32_t FilaGpuTexture_glName(FilaGpuTexture* texture);
void* FilaGpuTexture_handle(FilaGpuTexture* texture);
// Filament may only render into the texture between lock and unlock (Windows interop locking;
// always succeeds on Linux).
bool FilaGpuTexture_lock(FilaGpuTexture* texture);
bool FilaGpuTexture_unlock(FilaGpuTexture* texture);
void FilaGpuTexture_destroy(FilaGpuTexture* texture);

// macOS: an RGBA8 render-target id<MTLTexture> on the host's device (the host samples it in place),
// +1 retained; balance with FilaMetalTexture_release. NULL elsewhere. Filament's texture import
// adopts a +1 reference, so take one with FilaMetalTexture_retain before handing it over.
void* FilaMetalTexture_create(void* mtlDevice, int32_t width, int32_t height);
void FilaMetalTexture_retain(void* mtlTexture);
void FilaMetalTexture_release(void* mtlTexture);

#ifdef __cplusplus
}
#endif

#endif
