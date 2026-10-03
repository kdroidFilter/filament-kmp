#include "FilaInterop.h"

#include <filament/Engine.h>

#include <new>

using namespace filament;

// ── Linux: GLES on the host's EGLDisplay, textures exported as EGLImages ──────────────────────
#if defined(__linux__) && defined(FILA_EGL_PLATFORM)

#include <backend/platforms/PlatformEGLHeadless.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

using filament::backend::PlatformEGLHeadless;

namespace {

// PlatformEGLHeadless on the host's display instead of EGL_DEFAULT_DISPLAY: EGL only shares
// objects between contexts of one display. The display is already initialized by its owner.
class HostDisplayPlatform final : public PlatformEGLHeadless {
public:
    explicit HostDisplayPlatform(EGLDisplay display) noexcept { setEglDisplay(display); }
};

// Makes the share's context current for the scope, then restores the calling thread's API and
// context (the host's desktop-GL one, typically): GL and GLES dispatch through one current
// context per thread, so it has to be put back explicitly.
struct ScopedCurrent {
    EGLenum api = eglQueryAPI();
    EGLDisplay display = eglGetCurrentDisplay();
    EGLSurface draw = eglGetCurrentSurface(EGL_DRAW);
    EGLSurface read = eglGetCurrentSurface(EGL_READ);
    EGLContext context = eglGetCurrentContext();
    EGLDisplay ownDisplay;
    bool ok;

    ScopedCurrent(EGLDisplay dpy, EGLSurface surface, EGLContext ctx) : ownDisplay(dpy) {
        eglBindAPI(EGL_OPENGL_ES_API);
        ok = eglMakeCurrent(dpy, surface, surface, ctx) == EGL_TRUE;
    }
    ~ScopedCurrent() {
        eglBindAPI(EGL_OPENGL_ES_API);
        eglMakeCurrent(ownDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglBindAPI(api);
        if (context != EGL_NO_CONTEXT) eglMakeCurrent(display, draw, read, context);
    }
};

} // namespace

struct FilaGpuShare {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE; // 1x1 pbuffer: surfaceless contexts are not universal
    HostDisplayPlatform* platform = nullptr;
    PFNEGLCREATEIMAGEKHRPROC createImage = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage = nullptr;
};

struct FilaGpuTexture {
    FilaGpuShare* share = nullptr;
    GLuint glName = 0;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
};

void* FilaGpuShare_currentEglDisplay() { return eglGetCurrentDisplay(); }

FilaGpuShare* FilaGpuShare_create(void* hostEglDisplay) {
    auto* s = new (std::nothrow) FilaGpuShare();
    if (!s || !hostEglDisplay) {
        delete s;
        return nullptr;
    }
    s->display = static_cast<EGLDisplay>(hostEglDisplay);
    s->createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    s->destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));

    EGLint const configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_NONE };
    EGLint const contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLint const pbufferAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    EGLConfig config = nullptr;
    EGLint count = 0;
    EGLenum const api = eglQueryAPI();
    eglBindAPI(EGL_OPENGL_ES_API);
    if (eglChooseConfig(s->display, configAttribs, &config, 1, &count) && count == 1) {
        s->context = eglCreateContext(s->display, config, EGL_NO_CONTEXT, contextAttribs);
        s->surface = eglCreatePbufferSurface(s->display, config, pbufferAttribs);
    }
    eglBindAPI(api);
    if (s->context == EGL_NO_CONTEXT || s->surface == EGL_NO_SURFACE || !s->createImage || !s->destroyImage) {
        FilaGpuShare_destroy(s);
        return nullptr;
    }
    s->platform = new HostDisplayPlatform(s->display);
    return s;
}

void FilaGpuShare_destroy(FilaGpuShare* s) {
    if (!s) return;
    if (s->surface != EGL_NO_SURFACE) eglDestroySurface(s->display, s->surface);
    if (s->context != EGL_NO_CONTEXT) eglDestroyContext(s->display, s->context);
    delete s->platform;
    delete s;
}

void FilaEngineBuilder_gpuShare(FilaEngineBuilder* builder, FilaGpuShare* share) {
    if (!share) return;
    auto& b = *reinterpret_cast<Engine::Builder*>(builder);
    b.backend(Engine::Backend::OPENGL);
    b.platform(share->platform);
    b.sharedContext(share->context);
}

FilaGpuTexture* FilaGpuTexture_create(FilaGpuShare* share, int32_t width, int32_t height) {
    if (!share || width <= 0 || height <= 0) return nullptr;
    auto* t = new (std::nothrow) FilaGpuTexture();
    if (!t) return nullptr;
    t->share = share;

    ScopedCurrent current(share->display, share->surface, share->context);
    if (current.ok) {
        glGenTextures(1, &t->glName);
        glBindTexture(GL_TEXTURE_2D, t->glName);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glBindTexture(GL_TEXTURE_2D, 0);
        EGLint const imageAttribs[] = { EGL_GL_TEXTURE_LEVEL_KHR, 0, EGL_NONE };
        t->image = share->createImage(share->display, share->context, EGL_GL_TEXTURE_2D_KHR,
                reinterpret_cast<EGLClientBuffer>(static_cast<uintptr_t>(t->glName)), imageAttribs);
        // Make the new texture visible to Filament's shared context and to the host.
        glFinish();
    }
    if (t->image == EGL_NO_IMAGE_KHR) {
        FilaGpuTexture_destroy(t);
        return nullptr;
    }
    return t;
}

uint32_t FilaGpuTexture_glName(FilaGpuTexture* t) { return t ? t->glName : 0; }
void* FilaGpuTexture_handle(FilaGpuTexture* t) { return t ? t->image : nullptr; }
bool FilaGpuTexture_lock(FilaGpuTexture* t) { return t != nullptr; }
bool FilaGpuTexture_unlock(FilaGpuTexture* t) { return t != nullptr; }

void FilaGpuTexture_destroy(FilaGpuTexture* t) {
    if (!t) return;
    if (t->image != EGL_NO_IMAGE_KHR) t->share->destroyImage(t->share->display, t->image);
    if (t->glName) {
        ScopedCurrent current(t->share->display, t->share->surface, t->share->context);
        if (current.ok) glDeleteTextures(1, &t->glName);
    }
    delete t;
}

// ── Windows: WGL shared with Filament, textures aliasing D3D11 (WGL_NV_DX_interop2) ──────────
#elif defined(_WIN32)

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <d3d11.h>
#include <dxgi.h>
#include <GL/gl.h>

namespace {

constexpr GLenum kGlTexture2D = 0x0DE1;
constexpr GLenum kWglAccessReadWrite = 0x0001; // WGL_ACCESS_READ_WRITE_NV

using PfnDXOpenDevice = HANDLE(WINAPI*)(void*);
using PfnDXCloseDevice = BOOL(WINAPI*)(HANDLE);
using PfnDXSetResourceShareHandle = BOOL(WINAPI*)(void*, HANDLE);
using PfnDXRegisterObject = HANDLE(WINAPI*)(HANDLE, void*, GLuint, GLenum, GLenum);
using PfnDXUnregisterObject = BOOL(WINAPI*)(HANDLE, HANDLE);
using PfnDXLockObjects = BOOL(WINAPI*)(HANDLE, GLint, HANDLE*);

// Makes the share's context current for the scope, restoring whatever WGL context was current.
// (A host's EGL/ANGLE context lives in ANGLE's own TLS and is unaffected.)
struct ScopedCurrent {
    HDC prevDc = wglGetCurrentDC();
    HGLRC prevRc = wglGetCurrentContext();
    bool ok;
    ScopedCurrent(HDC dc, HGLRC rc) : ok(wglMakeCurrent(dc, rc) == TRUE) {}
    ~ScopedCurrent() { wglMakeCurrent(prevDc, prevRc); }
};

} // namespace

struct FilaGpuShare {
    HWND window = nullptr;
    HDC dc = nullptr;
    HGLRC rc = nullptr;
    ID3D11Device* device = nullptr;
    HANDLE interopDevice = nullptr;
    PfnDXCloseDevice closeDevice = nullptr;
    PfnDXSetResourceShareHandle setShareHandle = nullptr;
    PfnDXRegisterObject registerObject = nullptr;
    PfnDXUnregisterObject unregisterObject = nullptr;
    PfnDXLockObjects lockObjects = nullptr;
    PfnDXLockObjects unlockObjects = nullptr;
};

struct FilaGpuTexture {
    FilaGpuShare* share = nullptr;
    ID3D11Texture2D* texture = nullptr;
    HANDLE sharedHandle = nullptr;
    GLuint glName = 0;
    HANDLE interopObject = nullptr;
};

void* FilaGpuShare_currentEglDisplay() { return nullptr; }

FilaGpuShare* FilaGpuShare_create(void*) {
    auto* s = new (std::nothrow) FilaGpuShare();
    if (!s) return nullptr;

    // A pixel format needs a DC, hence a hidden window; STATIC needs no class registration.
    s->window = CreateWindowExA(0, "STATIC", "filament-gpu-share", WS_POPUP, 0, 0, 1, 1,
            nullptr, nullptr, GetModuleHandleA(nullptr), nullptr);
    s->dc = s->window ? GetDC(s->window) : nullptr;
    // Same pixel format as Filament's PlatformWGL: some drivers only share across equal formats.
    PIXELFORMATDESCRIPTOR pfd = {};
    pfd.nSize = sizeof(pfd);
    pfd.nVersion = 1;
    pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    pfd.iPixelType = PFD_TYPE_RGBA;
    pfd.cColorBits = 32;
    pfd.cDepthBits = 24;
    pfd.iLayerType = PFD_MAIN_PLANE;
    int format = s->dc ? ChoosePixelFormat(s->dc, &pfd) : 0;
    if (!format || !SetPixelFormat(s->dc, format, &pfd) || !(s->rc = wglCreateContext(s->dc))) {
        FilaGpuShare_destroy(s);
        return nullptr;
    }

    ScopedCurrent current(s->dc, s->rc);
    auto openDevice = reinterpret_cast<PfnDXOpenDevice>(wglGetProcAddress("wglDXOpenDeviceNV"));
    s->closeDevice = reinterpret_cast<PfnDXCloseDevice>(wglGetProcAddress("wglDXCloseDeviceNV"));
    s->setShareHandle = reinterpret_cast<PfnDXSetResourceShareHandle>(wglGetProcAddress("wglDXSetResourceShareHandleNV"));
    s->registerObject = reinterpret_cast<PfnDXRegisterObject>(wglGetProcAddress("wglDXRegisterObjectNV"));
    s->unregisterObject = reinterpret_cast<PfnDXUnregisterObject>(wglGetProcAddress("wglDXUnregisterObjectNV"));
    s->lockObjects = reinterpret_cast<PfnDXLockObjects>(wglGetProcAddress("wglDXLockObjectsNV"));
    s->unlockObjects = reinterpret_cast<PfnDXLockObjects>(wglGetProcAddress("wglDXUnlockObjectsNV"));
    bool const hasInterop = current.ok && openDevice && s->closeDevice && s->registerObject &&
            s->unregisterObject && s->lockObjects && s->unlockObjects;

    // Default adapter: the one ANGLE (the consumer) picks too, which legacy handles require.
    if (!hasInterop || FAILED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
            D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0, D3D11_SDK_VERSION, &s->device, nullptr,
            nullptr)) || !(s->interopDevice = openDevice(s->device))) {
        FilaGpuShare_destroy(s);
        return nullptr;
    }
    return s;
}

void FilaGpuShare_destroy(FilaGpuShare* s) {
    if (!s) return;
    if (s->interopDevice) {
        ScopedCurrent current(s->dc, s->rc);
        s->closeDevice(s->interopDevice);
    }
    if (s->device) s->device->Release();
    if (s->rc) wglDeleteContext(s->rc);
    if (s->dc) ReleaseDC(s->window, s->dc);
    if (s->window) DestroyWindow(s->window);
    delete s;
}

void FilaEngineBuilder_gpuShare(FilaEngineBuilder* builder, FilaGpuShare* share) {
    if (!share) return;
    auto& b = *reinterpret_cast<Engine::Builder*>(builder);
    b.backend(Engine::Backend::OPENGL);
    b.sharedContext(share->rc);
}

FilaGpuTexture* FilaGpuTexture_create(FilaGpuShare* share, int32_t width, int32_t height) {
    if (!share || width <= 0 || height <= 0) return nullptr;
    auto* t = new (std::nothrow) FilaGpuTexture();
    if (!t) return nullptr;
    t->share = share;

    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width = UINT(width);
    desc.Height = UINT(height);
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM; // what Nucleus's ANGLE import expects
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    desc.MiscFlags = D3D11_RESOURCE_MISC_SHARED; // legacy handle: the only kind ANGLE imports
    IDXGIResource* resource = nullptr;
    if (FAILED(share->device->CreateTexture2D(&desc, nullptr, &t->texture)) ||
            FAILED(t->texture->QueryInterface(__uuidof(IDXGIResource), reinterpret_cast<void**>(&resource))) ||
            FAILED(resource->GetSharedHandle(&t->sharedHandle))) {
        if (resource) resource->Release();
        FilaGpuTexture_destroy(t);
        return nullptr;
    }
    resource->Release();

    ScopedCurrent current(share->dc, share->rc);
    glGenTextures(1, &t->glName);
    if (share->setShareHandle) share->setShareHandle(t->texture, t->sharedHandle);
    t->interopObject = share->registerObject(share->interopDevice, t->texture, t->glName,
            kGlTexture2D, kWglAccessReadWrite);
    if (!current.ok || !t->interopObject) {
        FilaGpuTexture_destroy(t);
        return nullptr;
    }
    return t;
}

uint32_t FilaGpuTexture_glName(FilaGpuTexture* t) { return t ? t->glName : 0; }
void* FilaGpuTexture_handle(FilaGpuTexture* t) { return t ? t->sharedHandle : nullptr; }

bool FilaGpuTexture_lock(FilaGpuTexture* t) {
    if (!t) return false;
    ScopedCurrent current(t->share->dc, t->share->rc);
    return current.ok && t->share->lockObjects(t->share->interopDevice, 1, &t->interopObject);
}

bool FilaGpuTexture_unlock(FilaGpuTexture* t) {
    if (!t) return false;
    ScopedCurrent current(t->share->dc, t->share->rc);
    return current.ok && t->share->unlockObjects(t->share->interopDevice, 1, &t->interopObject);
}

void FilaGpuTexture_destroy(FilaGpuTexture* t) {
    if (!t) return;
    {
        ScopedCurrent current(t->share->dc, t->share->rc);
        if (t->interopObject) t->share->unregisterObject(t->share->interopDevice, t->interopObject);
        if (t->glName) glDeleteTextures(1, &t->glName);
    }
    if (t->texture) t->texture->Release();
    delete t;
}

// ── Elsewhere (macOS shares Metal textures instead, see FilaMetalTexture below) ──────────────
#else

void* FilaGpuShare_currentEglDisplay() { return nullptr; }
FilaGpuShare* FilaGpuShare_create(void*) { return nullptr; }
void FilaGpuShare_destroy(FilaGpuShare*) {}
void FilaEngineBuilder_gpuShare(FilaEngineBuilder*, FilaGpuShare*) {}
FilaGpuTexture* FilaGpuTexture_create(FilaGpuShare*, int32_t, int32_t) { return nullptr; }
uint32_t FilaGpuTexture_glName(FilaGpuTexture*) { return 0; }
void* FilaGpuTexture_handle(FilaGpuTexture*) { return nullptr; }
bool FilaGpuTexture_lock(FilaGpuTexture*) { return false; }
bool FilaGpuTexture_unlock(FilaGpuTexture*) { return false; }
void FilaGpuTexture_destroy(FilaGpuTexture*) {}

#endif

// ── macOS/iOS: Metal textures on the host's MTLDevice ────────────────────────────────────────
#if defined(__APPLE__)

#include <objc/message.h>
#include <objc/runtime.h>

namespace {
constexpr long kPixelFormatRGBA8Unorm = 70;              // MTLPixelFormatRGBA8Unorm
constexpr long kUsageShaderReadRenderTarget = 0x1 | 0x4; // MTLTextureUsageShaderRead | RenderTarget
constexpr long kStorageModePrivate = 2;                  // MTLStorageModePrivate

// objc_msgSend must be called through each method's exact prototype.
template<typename R, typename... A>
R send(id receiver, const char* selector, A... args) {
    return reinterpret_cast<R (*)(id, SEL, A...)>(objc_msgSend)(receiver, sel_registerName(selector), args...);
}
}

void* FilaMetalTexture_create(void* device, int32_t width, int32_t height) {
    if (!device || width <= 0 || height <= 0) return nullptr;
    id descriptor = send<id>(reinterpret_cast<id>(objc_getClass("MTLTextureDescriptor")),
            "texture2DDescriptorWithPixelFormat:width:height:mipmapped:",
            kPixelFormatRGBA8Unorm, static_cast<unsigned long>(width), static_cast<unsigned long>(height), false);
    send<void>(descriptor, "setUsage:", kUsageShaderReadRenderTarget);
    send<void>(descriptor, "setStorageMode:", kStorageModePrivate);
    return send<id>(static_cast<id>(device), "newTextureWithDescriptor:", descriptor);
}

void FilaMetalTexture_retain(void* texture) {
    if (texture) send<id>(static_cast<id>(texture), "retain");
}

void FilaMetalTexture_release(void* texture) {
    if (texture) send<void>(static_cast<id>(texture), "release");
}

#else

void* FilaMetalTexture_create(void*, int32_t, int32_t) { return nullptr; }
void FilaMetalTexture_retain(void*) {}
void FilaMetalTexture_release(void*) {}

#endif
