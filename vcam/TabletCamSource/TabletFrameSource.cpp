//
// TabletFrameSource: see header.
//
#include "pch.h"
#include <winhttp.h>
#include <wincodec.h>
#include <algorithm>
#include <cstring>

#pragma comment(lib, "winhttp")
#pragma comment(lib, "windowscodecs")

static const BYTE kSoi[2] = { 0xFF, 0xD8 };   // JPEG start-of-image
static const BYTE kEoi[2] = { 0xFF, 0xD9 };   // JPEG end-of-image

TabletFrameSource& TabletFrameSource::Instance()
{
    static TabletFrameSource instance;
    return instance;
}

TabletFrameSource::~TabletFrameSource()
{
    m_quit = true;
    if (m_thread.joinable())
    {
        m_thread.detach();      // never block DLL unload on a network read
    }
}

bool TabletFrameSource::CopyLatest(BYTE* pBuf, DWORD len, LONG pitch, UINT width, UINT height)
{
    m_targetW = width;
    m_targetH = height;
    m_lastRequestTick = GetTickCount64();
    EnsureRunning();

    std::lock_guard<std::mutex> guard(m_lock);
    if (m_frame.empty() || m_frameW != width || m_frameH != height ||
        GetTickCount64() - m_frameTick > STALE_MS)
    {
        return false;
    }
    if (len < static_cast<DWORD>(std::abs(pitch)) * height)
    {
        return false;
    }

    const UINT rowBytes = width * 4;
    for (UINT r = 0; r < height; r++)
    {
        memcpy(pBuf + r * pitch, m_frame.data() + static_cast<size_t>(r) * rowBytes, rowBytes);
    }
    return true;
}

void TabletFrameSource::EnsureRunning()
{
    if (m_running)
    {
        return;
    }
    std::lock_guard<std::mutex> guard(m_startLock);
    if (m_running)
    {
        return;
    }
    if (m_thread.joinable())
    {
        m_thread.join();        // previous session already ended (m_running is false)
    }
    m_running = true;
    m_thread = std::thread([this] { Run(); });
}

void TabletFrameSource::Run()
{
    (void)CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    while (!m_quit && GetTickCount64() - m_lastRequestTick < IDLE_STOP_MS)
    {
        if (!StreamOnce())
        {
            Sleep(1000);        // tablet unplugged / server not running: retry quietly
        }
    }
    CoUninitialize();
    m_running = false;
}

// Reads one multipart MJPEG HTTP response. Each part carries a JPEG; we locate frames by their
// SOI (FFD8) / EOI (FFD9) markers, which works regardless of the boundary/header format.
bool TabletFrameSource::StreamOnce()
{
    HINTERNET hSession = WinHttpOpen(L"TabletCamera/1.0", WINHTTP_ACCESS_TYPE_NO_PROXY,
                                     WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!hSession) return false;
    WinHttpSetTimeouts(hSession, 2000, 2000, 2000, 3000);

    bool gotFrame = false;
    HINTERNET hConnect = WinHttpConnect(hSession, m_host.c_str(), m_port, 0);
    HINTERNET hRequest = hConnect ? WinHttpOpenRequest(hConnect, L"GET", m_path.c_str(), nullptr,
                                                       WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, 0)
                                  : nullptr;
    if (hRequest &&
        WinHttpSendRequest(hRequest, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0) &&
        WinHttpReceiveResponse(hRequest, nullptr))
    {
        std::vector<BYTE> buf;
        buf.reserve(512 * 1024);
        BYTE chunk[64 * 1024];
        DWORD read = 0;

        while (!m_quit && GetTickCount64() - m_lastRequestTick < IDLE_STOP_MS)
        {
            if (!WinHttpReadData(hRequest, chunk, sizeof(chunk), &read) || read == 0)
            {
                break;
            }
            buf.insert(buf.end(), chunk, chunk + read);

            // Extract every complete JPEG currently in the buffer; keep only the newest.
            size_t consumed = 0;
            const BYTE* newest = nullptr;
            size_t newestLen = 0;
            for (;;)
            {
                auto soi = std::search(buf.begin() + consumed, buf.end(), std::begin(kSoi), std::end(kSoi));
                if (soi == buf.end()) { consumed = buf.size() > 1 ? buf.size() - 1 : 0; break; }
                auto eoi = std::search(soi + 2, buf.end(), std::begin(kEoi), std::end(kEoi));
                if (eoi == buf.end()) { consumed = soi - buf.begin(); break; }
                newest = &*soi;
                newestLen = (eoi - soi) + 2;
                consumed = (eoi - buf.begin()) + 2;
            }
            if (newest)
            {
                std::vector<BYTE> jpeg(newest, newest + newestLen);
                HandleJpeg(jpeg.data(), jpeg.size());
                gotFrame = true;
            }
            buf.erase(buf.begin(), buf.begin() + consumed);
            if (buf.size() > 8 * 1024 * 1024) buf.clear();     // runaway guard
        }
    }

    if (hRequest) WinHttpCloseHandle(hRequest);
    if (hConnect) WinHttpCloseHandle(hConnect);
    WinHttpCloseHandle(hSession);
    return gotFrame;
}

// Decode JPEG -> scale to the camera's size -> 32bpp BGRA, then publish as the latest frame.
void TabletFrameSource::HandleJpeg(const BYTE* data, size_t size)
{
    wil::com_ptr_nothrow<IWICImagingFactory> factory;
    if (FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&factory)))) return;

    wil::com_ptr_nothrow<IWICStream> stream;
    if (FAILED(factory->CreateStream(&stream))) return;
    if (FAILED(stream->InitializeFromMemory(const_cast<BYTE*>(data), static_cast<DWORD>(size)))) return;

    wil::com_ptr_nothrow<IWICBitmapDecoder> decoder;
    if (FAILED(factory->CreateDecoderFromStream(stream.get(), nullptr, WICDecodeMetadataCacheOnDemand, &decoder))) return;
    wil::com_ptr_nothrow<IWICBitmapFrameDecode> frame;
    if (FAILED(decoder->GetFrame(0, &frame))) return;

    const UINT tw = m_targetW, th = m_targetH;
    UINT sw = 0, sh = 0;
    frame->GetSize(&sw, &sh);

    wil::com_ptr_nothrow<IWICBitmapSource> source = frame;
    wil::com_ptr_nothrow<IWICBitmapScaler> scaler;
    if (sw != tw || sh != th)
    {
        if (FAILED(factory->CreateBitmapScaler(&scaler))) return;
        if (FAILED(scaler->Initialize(frame.get(), tw, th, WICBitmapInterpolationModeLinear))) return;
        source = scaler;
    }

    wil::com_ptr_nothrow<IWICFormatConverter> converter;
    if (FAILED(factory->CreateFormatConverter(&converter))) return;
    if (FAILED(converter->Initialize(source.get(), GUID_WICPixelFormat32bppBGRA, WICBitmapDitherTypeNone,
                                     nullptr, 0.0, WICBitmapPaletteTypeCustom))) return;

    std::vector<BYTE> pixels(static_cast<size_t>(tw) * th * 4);
    if (FAILED(converter->CopyPixels(nullptr, tw * 4, static_cast<UINT>(pixels.size()), pixels.data()))) return;

    std::lock_guard<std::mutex> guard(m_lock);
    m_frame.swap(pixels);
    m_frameW = tw;
    m_frameH = th;
    m_frameTick = GetTickCount64();
}
