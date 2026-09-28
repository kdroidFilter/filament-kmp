#include "../c/Interop.h"
#include "EngineBuilderWrapper.h"

#include <new>

using namespace filament;

// ── Linux / EGL ──────────────────────────────────────────────────────────────────────────────
#if defined(__linux__) && defined(FILA_EGL_PLATFORM)

#include <backend/platforms/PlatformEGLHeadless.h>

using filament::backend::PlatformEGLHeadless;

#include <EGL/egl.h>
#include <GL/gl.h>

namespace {

// PlatformEGLHeadless on the host's display instead of EGL_DEFAULT_DISPLAY: a context can only
// share with one created on the same EGLDisplay. The display is already initialized by its owner.
class HostDisplayPlatform final : public PlatformEGLHeadless {
public:
    explicit HostDisplayPlatform(EGLDisplay display) noexcept { setEglDisplay(display); }
};

using PfnGenTextures = void (*)(GLsizei, GLuint*);
using PfnDeleteTextures = void (*)(GLsizei, const GLuint*);
using PfnBindTexture = void (*)(GLenum, GLuint);
using PfnTexImage2D = void (*)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void*);
using PfnTexParameteri = void (*)(GLenum, GLenum, GLint);
using PfnFlush = void (*)();

template <typename T> T gl(const char* name) { return reinterpret_cast<T>(eglGetProcAddress(name)); }

} // namespace

bool FilaInterop_hasEglPlatform(void) { return true; }

void FilaEngineBuilder_eglDisplay(FilaEngineBuilder* builder, void* eglDisplay) {
    // ponytail: the platform must outlive the engine and Filament never frees it; one small
    // object per engine leaks. Track it per engine if engines get created in a loop.
    auto* platform = new HostDisplayPlatform(static_cast<EGLDisplay>(eglDisplay));
    auto& b = reinterpret_cast<FilaEngineBuilderWrapper*>(builder)->builder;
    b.backend(Engine::Backend::OPENGL);
    b.platform(platform);
}

uint32_t FilaGl_createTexture(int32_t width, int32_t height) {
    GLuint name = 0;
    gl<PfnGenTextures>("glGenTextures")(1, &name);
    if (name == 0) return 0;
    gl<PfnBindTexture>("glBindTexture")(GL_TEXTURE_2D, name);
    auto texParameteri = gl<PfnTexParameteri>("glTexParameteri");
    texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
    gl<PfnTexImage2D>("glTexImage2D")(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA,
            GL_UNSIGNED_BYTE, nullptr);
    gl<PfnBindTexture>("glBindTexture")(GL_TEXTURE_2D, 0);
    return name;
}

void FilaGl_deleteTexture(uint32_t name) {
    GLuint n = name;
    gl<PfnDeleteTextures>("glDeleteTextures")(1, &n);
}

void FilaGl_flush(void) { gl<PfnFlush>("glFlush")(); }

#else

bool FilaInterop_hasEglPlatform(void) { return false; }
void FilaEngineBuilder_eglDisplay(FilaEngineBuilder*, void*) {}
uint32_t FilaGl_createTexture(int32_t, int32_t) { return 0; }
void FilaGl_deleteTexture(uint32_t) {}
void FilaGl_flush(void) {}

#endif

// ── Windows / WGL_NV_DX_interop2 ─────────────────────────────────────────────────────────────
#if defined(_WIN32)

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

struct FilaDxShare {
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

struct FilaDxTexture {
    FilaDxShare* share = nullptr;
    ID3D11Texture2D* texture = nullptr;
    HANDLE sharedHandle = nullptr;
    GLuint glName = 0;
    HANDLE interopObject = nullptr;
};

FilaDxShare* FilaDxShare_create(void) {
    auto* s = new (std::nothrow) FilaDxShare();
    if (!s) return nullptr;

    // A pixel format needs a DC, hence a hidden window; STATIC needs no class registration.
    s->window = CreateWindowExA(0, "STATIC", "filament-dx-share", WS_POPUP, 0, 0, 1, 1,
            nullptr, nullptr, GetModuleHandleA(nullptr), nullptr);
    s->dc = s->window ? GetDC(s->window) : nullptr;
    PIXELFORMATDESCRIPTOR pfd = { sizeof(pfd), 1, PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL,
            PFD_TYPE_RGBA, 32 };
    int format = s->dc ? ChoosePixelFormat(s->dc, &pfd) : 0;
    if (!format || !SetPixelFormat(s->dc, format, &pfd) || !(s->rc = wglCreateContext(s->dc))) {
        FilaDxShare_destroy(s);
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
        FilaDxShare_destroy(s);
        return nullptr;
    }
    return s;
}

void* FilaDxShare_glContext(FilaDxShare* share) { return share ? share->rc : nullptr; }

void FilaDxShare_destroy(FilaDxShare* s) {
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

FilaDxTexture* FilaDxTexture_create(FilaDxShare* share, int32_t width, int32_t height) {
    if (!share || width <= 0 || height <= 0) return nullptr;
    auto* t = new (std::nothrow) FilaDxTexture();
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
        FilaDxTexture_destroy(t);
        return nullptr;
    }
    resource->Release();

    ScopedCurrent current(share->dc, share->rc);
    glGenTextures(1, &t->glName);
    if (share->setShareHandle) share->setShareHandle(t->texture, t->sharedHandle);
    t->interopObject = share->registerObject(share->interopDevice, t->texture, t->glName,
            kGlTexture2D, kWglAccessReadWrite);
    if (!current.ok || !t->interopObject) {
        FilaDxTexture_destroy(t);
        return nullptr;
    }
    return t;
}

uint32_t FilaDxTexture_glName(FilaDxTexture* t) { return t ? t->glName : 0; }
void* FilaDxTexture_sharedHandle(FilaDxTexture* t) { return t ? t->sharedHandle : nullptr; }

bool FilaDxTexture_lock(FilaDxTexture* t) {
    if (!t) return false;
    ScopedCurrent current(t->share->dc, t->share->rc);
    return current.ok && t->share->lockObjects(t->share->interopDevice, 1, &t->interopObject);
}

bool FilaDxTexture_unlock(FilaDxTexture* t) {
    if (!t) return false;
    ScopedCurrent current(t->share->dc, t->share->rc);
    return current.ok && t->share->unlockObjects(t->share->interopDevice, 1, &t->interopObject);
}

void FilaDxTexture_destroy(FilaDxTexture* t) {
    if (!t) return;
    {
        ScopedCurrent current(t->share->dc, t->share->rc);
        if (t->interopObject) t->share->unregisterObject(t->share->interopDevice, t->interopObject);
        if (t->glName) glDeleteTextures(1, &t->glName);
    }
    if (t->texture) t->texture->Release();
    delete t;
}

#else

FilaDxShare* FilaDxShare_create(void) { return nullptr; }
void* FilaDxShare_glContext(FilaDxShare*) { return nullptr; }
void FilaDxShare_destroy(FilaDxShare*) {}
FilaDxTexture* FilaDxTexture_create(FilaDxShare*, int32_t, int32_t) { return nullptr; }
uint32_t FilaDxTexture_glName(FilaDxTexture*) { return 0; }
void* FilaDxTexture_sharedHandle(FilaDxTexture*) { return nullptr; }
bool FilaDxTexture_lock(FilaDxTexture*) { return false; }
bool FilaDxTexture_unlock(FilaDxTexture*) { return false; }
void FilaDxTexture_destroy(FilaDxTexture*) {}

#endif
