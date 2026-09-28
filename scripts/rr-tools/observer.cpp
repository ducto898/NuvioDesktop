// observer.exe [out.csv] [device] — READ-ONLY. Every 50 ms logs the monitor's exact refresh
// (QueryDisplayConfig), HDR, bpc, DEVMODE current/registry Hz; also every WM_DISPLAYCHANGE seen by
// a hidden top-level window. Ctrl+C to stop. Prints a console line whenever the state changes.
#include "rr_common.h"

static FILE *gCsv = nullptr;
static std::wstring gDevice;
static volatile bool gRunning = true;

static void row(const wchar_t *event, const DisplayState &s) {
    if (!gCsv) return;
    fwprintf(gCsv, L"%s,%.1f,%s,%.3f,%u,%u,%lu,%lu,%d,%d,%lu,%lu\n", wallClock().c_str(), nowMs(), event, s.hz(), s.num, s.den,
             s.currentHz, s.registryHz, s.hdr, s.bpc, s.width, s.height);
    fflush(gCsv);
}

static LRESULT CALLBACK windowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == WM_DISPLAYCHANGE) {
        DisplayState s = queryDisplay(gDevice);
        row(L"WM_DISPLAYCHANGE", s);
        wprintf(L"%s WM_DISPLAYCHANGE %ux%u bpp=%u\n", wallClock().c_str(), LOWORD(lParam), HIWORD(lParam), (unsigned)wParam);
        printState(L"  state", s);
    }
    return DefWindowProcW(hwnd, message, wParam, lParam);
}

static BOOL WINAPI ctrlHandler(DWORD) {
    gRunning = false;
    return TRUE;
}

int wmain(int argc, wchar_t **argv) {
    std::wstring csvPath = argc > 1 ? argv[1] : L"observer.csv";
    gDevice = argc > 2 ? argv[2] : primaryDevice();
    gCsv = _wfsopen(csvPath.c_str(), L"a", _SH_DENYWR);
    if (!gCsv) {
        fwprintf(stderr, L"cannot open %s\n", csvPath.c_str());
        return 2;
    }
    fwprintf(gCsv, L"wall,ms,event,hz,num,den,cur_hz,reg_hz,hdr,bpc,width,height\n");
    SetConsoleCtrlHandler(ctrlHandler, TRUE);

    WNDCLASSW wc = {};
    wc.lpfnWndProc = windowProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.lpszClassName = L"NuvioRrObserver";
    RegisterClassW(&wc);
    // Top-level (not message-only) so it receives the WM_DISPLAYCHANGE broadcast; never shown.
    CreateWindowExW(WS_EX_TOOLWINDOW, wc.lpszClassName, L"", WS_POPUP, 0, 0, 0, 0, nullptr, nullptr, wc.hInstance, nullptr);

    wprintf(L"observer: device=%s csv=%s (Ctrl+C to stop)\n", gDevice.c_str(), csvPath.c_str());
    DisplayState last = queryDisplay(gDevice);
    printState(L"start", last);
    row(L"start", last);
    double nextTick = nowMs();
    while (gRunning) {
        double wait = nextTick - nowMs();
        MsgWaitForMultipleObjects(0, nullptr, FALSE, wait > 0 ? (DWORD)wait : 0, QS_ALLINPUT);
        MSG msg;
        while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        if (nowMs() < nextTick) continue;
        nextTick += 50.0;
        DisplayState s = queryDisplay(gDevice);
        row(L"tick", s);
        if (s.num != last.num || s.den != last.den || s.hdr != last.hdr || s.bpc != last.bpc ||
            s.registryHz != last.registryHz || s.ok != last.ok) {
            printState(L"CHANGE", s);
            last = s;
        }
    }
    printState(L"stop", queryDisplay(gDevice));
    fclose(gCsv);
    return 0;
}
