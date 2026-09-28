// switcher.exe <cds|sdc> <hz|num/den> <restore|exit|hang|crash> [device]
// Applies a TEMPORARY refresh-rate change, confirms it via QueryDisplayConfig, then ends as told.
//   cds : ChangeDisplaySettingsExW(device, &dm, NULL, CDS_FULLSCREEN, NULL)   (never CDS_UPDATEREGISTRY)
//   sdc : SetDisplayConfig(SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG | SDC_ALLOW_CHANGES) (never SDC_SAVE_TO_DATABASE)
// Only rates the monitor lists at the current resolution are accepted.
#include "rr_common.h"
#include <cmath>

static bool listed(const std::wstring &device, double hz, DWORD width, DWORD height) {
    DEVMODEW dm = {};
    dm.dmSize = sizeof(dm);
    for (DWORD i = 0; EnumDisplaySettingsW(device.c_str(), i, &dm); ++i) {
        if (dm.dmPelsWidth == width && dm.dmPelsHeight == height && std::fabs((double)dm.dmDisplayFrequency - hz) < 1.0) return true;
    }
    return false;
}

static LONG applyCds(const std::wstring &device, DWORD hz) {
    DEVMODEW dm = {};
    dm.dmSize = sizeof(dm);
    if (!EnumDisplaySettingsW(device.c_str(), ENUM_CURRENT_SETTINGS, &dm)) return -1000;
    dm.dmDisplayFrequency = hz;
    dm.dmFields = DM_DISPLAYFREQUENCY;
    return ChangeDisplaySettingsExW(device.c_str(), &dm, nullptr, CDS_FULLSCREEN, nullptr);
}

static LONG applySdc(const std::wstring &device, UINT32 num, UINT32 den) {
    std::vector<DISPLAYCONFIG_PATH_INFO> paths;
    std::vector<DISPLAYCONFIG_MODE_INFO> modes;
    UINT32 i = 0;
    if (!findPath(device, paths, modes, i)) return -1000;
    paths[i].targetInfo.refreshRate.Numerator = num;
    paths[i].targetInfo.refreshRate.Denominator = den;
    paths[i].targetInfo.modeInfoIdx = DISPLAYCONFIG_PATH_MODE_IDX_INVALID;  // let the driver pick the matching target mode
    return SetDisplayConfig((UINT32)paths.size(), paths.data(), (UINT32)modes.size(), modes.data(),
                            SDC_APPLY | SDC_USE_SUPPLIED_DISPLAY_CONFIG | SDC_ALLOW_CHANGES);
}

int wmain(int argc, wchar_t **argv) {
    if (argc < 4) {
        fwprintf(stderr, L"usage: switcher <cds|sdc> <hz|num/den> <restore|exit|hang|crash> [device]\n");
        return 2;
    }
    std::wstring api = argv[1], rate = argv[2], end = argv[3];
    std::wstring device = argc > 4 ? argv[4] : primaryDevice();
    UINT32 num = 0, den = 1;
    if (swscanf_s(rate.c_str(), L"%u/%u", &num, &den) < 1 || den == 0 || num == 0) {
        fwprintf(stderr, L"bad rate %s\n", rate.c_str());
        return 2;
    }
    double hz = (double)num / (double)den;
    if (api != L"cds" && api != L"sdc") return 2;
    if (end != L"restore" && end != L"exit" && end != L"hang" && end != L"crash") return 2;

    DisplayState before = queryDisplay(device);
    wprintf(L"switcher pid=%lu api=%s rate=%s end=%s device=%s\n", GetCurrentProcessId(), api.c_str(), rate.c_str(),
            end.c_str(), device.c_str());
    printState(L"before", before);
    if (before.registryHz != 280) wprintf(L"WARNING: registry mode is %lu Hz, expected 280\n", before.registryHz);
    if (!listed(device, hz, before.width, before.height)) {
        fwprintf(stderr, L"refusing: %.3f Hz is not listed at %lux%lu\n", hz, before.width, before.height);
        return 3;
    }

    double t0 = nowMs();
    LONG result = api == L"cds" ? applyCds(device, (DWORD)std::lround(hz)) : applySdc(device, num, den);
    double t1 = nowMs();
    wprintf(L"%s apply result=%ld in %.0f ms\n", wallClock().c_str(), result, t1 - t0);
    fflush(stdout);

    // Stable = two consecutive reads (100 ms apart) that agree and are within 0.5 Hz of the target.
    DisplayState prev = {};
    double stableAt = -1;
    while (nowMs() - t0 < 6000) {
        DisplayState s = queryDisplay(device);
        if (s.ok && prev.ok && s.num == prev.num && s.den == prev.den && std::fabs(s.hz() - hz) < 0.5) {
            stableAt = nowMs();
            break;
        }
        prev = s;
        Sleep(100);
    }
    DisplayState after = queryDisplay(device);
    printState(L"after ", after);
    if (stableAt > 0) wprintf(L"stable after %.0f ms from call\n", stableAt - t0);
    else wprintf(L"NOT stable at target within 6 s\n");
    fflush(stdout);

    if (end == L"restore") {
        LONG r = api == L"cds"
            ? ChangeDisplaySettingsExW(device.c_str(), nullptr, nullptr, 0, nullptr)
            : SetDisplayConfig(0, nullptr, 0, nullptr, SDC_APPLY | SDC_USE_DATABASE_CURRENT);
        wprintf(L"%s restore result=%ld\n", wallClock().c_str(), r);
        Sleep(1500);
        printState(L"restored", queryDisplay(device));
        return 0;
    }
    if (end == L"exit") {
        wprintf(L"%s exiting without restore\n", wallClock().c_str());
        fflush(stdout);
        return 0;
    }
    if (end == L"crash") {
        wprintf(L"%s crashing (null write)\n", wallClock().c_str());
        fflush(stdout);
        *(volatile int *)nullptr = 0;
        return 1;
    }
    wprintf(L"%s hanging; kill pid %lu (taskkill /F /PID %lu or Task Manager)\n", wallClock().c_str(), GetCurrentProcessId(),
            GetCurrentProcessId());
    fflush(stdout);
    Sleep(INFINITE);
    return 0;
}
