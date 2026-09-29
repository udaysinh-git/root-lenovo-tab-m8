// TabletCamRegister: registers / removes the "Tablet Camera" Windows 11 virtual camera.
//
//   TabletCamRegister.exe add      (elevated) register system-wide; persists across reboots
//   TabletCamRegister.exe remove   (elevated) unregister
//
// The media source DLL (CLSID below) must already be registered as an in-proc COM server.
#include <windows.h>
#include <mfapi.h>
#include <mfvirtualcamera.h>
#include <cstdio>
#include <cwchar>

#pragma comment(lib, "mfplat")
#pragma comment(lib, "mfsensorgroup")
#pragma comment(lib, "ole32")

static const wchar_t* kFriendlyName = L"Tablet Camera";
static const wchar_t* kSourceClsid = L"{CEBBFFF7-1284-4D72-81FF-8C216C8984B6}";

int wmain(int argc, wchar_t** argv)
{
    if (argc < 2 || (wcscmp(argv[1], L"add") != 0 && wcscmp(argv[1], L"remove") != 0))
    {
        wprintf(L"usage: TabletCamRegister add|remove\n");
        return 2;
    }

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (SUCCEEDED(hr)) hr = MFStartup(MF_VERSION);
    if (FAILED(hr)) { wprintf(L"init failed 0x%08X\n", hr); return 1; }

    IMFVirtualCamera* vcam = nullptr;
    hr = MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource,
                               MFVirtualCameraLifetime_System,
                               MFVirtualCameraAccess_AllUsers,
                               kFriendlyName, kSourceClsid, nullptr, 0, &vcam);
    if (FAILED(hr)) { wprintf(L"MFCreateVirtualCamera failed 0x%08X (run elevated?)\n", hr); return 1; }

    const bool add = wcscmp(argv[1], L"add") == 0;
    hr = add ? vcam->Start(nullptr) : vcam->Remove();
    if (SUCCEEDED(hr))
    {
        wprintf(L"'%s' %s\n", kFriendlyName, add ? L"registered and started" : L"removed");
    }
    else
    {
        wprintf(L"%s failed 0x%08X\n", add ? L"Start" : L"Remove", static_cast<unsigned>(hr));
    }

    vcam->Release();
    MFShutdown();
    CoUninitialize();
    return SUCCEEDED(hr) ? 0 : 1;
}
