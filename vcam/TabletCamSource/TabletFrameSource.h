//
// TabletFrameSource: pulls the tablet's MJPEG stream (IP Webcam over an adb USB forward)
// and keeps the most recent frame decoded as 32bpp BGRA, ready to copy into camera samples.
//
// The network thread only runs while frames are being requested; after IDLE_STOP_MS without a
// request it disconnects, so an unused camera costs nothing.
//
#pragma once
#ifndef TABLET_FRAME_SOURCE_H
#define TABLET_FRAME_SOURCE_H

#include <mutex>
#include <thread>
#include <atomic>
#include <vector>
#include <string>

class TabletFrameSource
{
public:
    static TabletFrameSource& Instance();

    // Copies the latest frame into pBuf (top-down BGRA/RGB32, given pitch). Returns false if there is
    // no recent frame; the caller then draws a placeholder.
    bool CopyLatest(BYTE* pBuf, DWORD len, LONG pitch, UINT width, UINT height);

private:
    TabletFrameSource() = default;
    ~TabletFrameSource();

    void EnsureRunning();
    void Run();
    bool StreamOnce();                                      // one HTTP session; returns when it ends
    void HandleJpeg(const BYTE* data, size_t size);

    std::wstring m_host = L"127.0.0.1";
    WORD m_port = 8765;
    std::wstring m_path = L"/video";

    std::mutex m_lock;                                      // guards m_frame, m_frameW/H, m_frameTick
    std::vector<BYTE> m_frame;
    UINT m_frameW = 0, m_frameH = 0;
    ULONGLONG m_frameTick = 0;

    UINT m_targetW = 1280, m_targetH = 720;
    DWORD m_rotation = 0;                                   // 0/90/180/270, HKLM\SOFTWARE\TabletCamera\Rotation
    std::atomic<ULONGLONG> m_lastRequestTick{ 0 };
    std::atomic<bool> m_running{ false };
    std::atomic<bool> m_quit{ false };
    std::thread m_thread;
    std::mutex m_startLock;

    static constexpr ULONGLONG STALE_MS = 2000;             // older frames count as "no signal"
    static constexpr ULONGLONG IDLE_STOP_MS = 5000;         // disconnect when nobody asks for frames
};

#endif
