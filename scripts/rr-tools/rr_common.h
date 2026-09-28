// Shared helpers for the refresh-rate kill-test tools (fork tooling, SPEC P2-12). Read-only except
// where a tool says otherwise. Nothing here writes the display registry.
#pragma once
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <cstdio>
#include <cwchar>
#include <string>
#include <vector>

inline std::wstring wallClock() {
    FILETIME ft;
    GetSystemTimePreciseAsFileTime(&ft);
    FILETIME local;
    FileTimeToLocalFileTime(&ft, &local);
    SYSTEMTIME st;
    FileTimeToSystemTime(&local, &st);
    wchar_t buffer[32];
    swprintf(buffer, 32, L"%02d:%02d:%02d.%03d", st.wHour, st.wMinute, st.wSecond, st.wMilliseconds);
    return buffer;
}

inline double nowMs() {
    static LARGE_INTEGER frequency = [] { LARGE_INTEGER f; QueryPerformanceFrequency(&f); return f; }();
    LARGE_INTEGER counter;
    QueryPerformanceCounter(&counter);
    return (double)counter.QuadPart * 1000.0 / (double)frequency.QuadPart;
}

// Primary monitor's GDI name (\\.\DISPLAYn) unless one is given.
inline std::wstring primaryDevice() {
    POINT origin = {0, 0};
    HMONITOR monitor = MonitorFromPoint(origin, MONITOR_DEFAULTTOPRIMARY);
    MONITORINFOEXW info = {};
    info.cbSize = sizeof(info);
    if (monitor && GetMonitorInfoW(monitor, &info)) return info.szDevice;
    return L"\\\\.\\DISPLAY1";
}

struct DisplayState {
    bool ok = false;
    UINT32 num = 0, den = 0;          // QueryDisplayConfig target vSyncFreq (exact)
    int hdr = -1, bpc = -1;           // advanced colour enabled, bits per colour channel
    DWORD currentHz = 0, registryHz = 0;
    DWORD width = 0, height = 0;
    double hz() const { return den ? (double)num / (double)den : 0.0; }
};

// Finds the active QDC path whose source is `device`. Returns false if none.
inline bool findPath(const std::wstring &device, std::vector<DISPLAYCONFIG_PATH_INFO> &paths,
                     std::vector<DISPLAYCONFIG_MODE_INFO> &modes, UINT32 &index) {
    UINT32 pathCount = 0, modeCount = 0;
    if (GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, &pathCount, &modeCount) != ERROR_SUCCESS) return false;
    paths.resize(pathCount);
    modes.resize(modeCount);
    if (QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, &pathCount, paths.data(), &modeCount, modes.data(), nullptr) != ERROR_SUCCESS) {
        return false;
    }
    paths.resize(pathCount);
    modes.resize(modeCount);
    for (UINT32 i = 0; i < pathCount; ++i) {
        DISPLAYCONFIG_SOURCE_DEVICE_NAME source = {};
        source.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME;
        source.header.size = sizeof(source);
        source.header.adapterId = paths[i].sourceInfo.adapterId;
        source.header.id = paths[i].sourceInfo.id;
        if (DisplayConfigGetDeviceInfo(&source.header) != ERROR_SUCCESS) continue;
        if (_wcsicmp(source.viewGdiDeviceName, device.c_str()) == 0) {
            index = i;
            return true;
        }
    }
    return false;
}

inline DisplayState queryDisplay(const std::wstring &device) {
    DisplayState state;
    DEVMODEW dm = {};
    dm.dmSize = sizeof(dm);
    if (EnumDisplaySettingsW(device.c_str(), ENUM_CURRENT_SETTINGS, &dm)) {
        state.currentHz = dm.dmDisplayFrequency;
        state.width = dm.dmPelsWidth;
        state.height = dm.dmPelsHeight;
    }
    DEVMODEW reg = {};
    reg.dmSize = sizeof(reg);
    if (EnumDisplaySettingsW(device.c_str(), ENUM_REGISTRY_SETTINGS, &reg)) state.registryHz = reg.dmDisplayFrequency;

    std::vector<DISPLAYCONFIG_PATH_INFO> paths;
    std::vector<DISPLAYCONFIG_MODE_INFO> modes;
    UINT32 i = 0;
    if (!findPath(device, paths, modes, i)) return state;
    UINT32 modeIndex = paths[i].targetInfo.modeInfoIdx;
    if (modeIndex != DISPLAYCONFIG_PATH_MODE_IDX_INVALID && modeIndex < modes.size() &&
        modes[modeIndex].infoType == DISPLAYCONFIG_MODE_INFO_TYPE_TARGET) {
        state.num = modes[modeIndex].targetMode.targetVideoSignalInfo.vSyncFreq.Numerator;
        state.den = modes[modeIndex].targetMode.targetVideoSignalInfo.vSyncFreq.Denominator;
    } else {
        state.num = paths[i].targetInfo.refreshRate.Numerator;
        state.den = paths[i].targetInfo.refreshRate.Denominator;
    }
    DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO color = {};
    color.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_ADVANCED_COLOR_INFO;
    color.header.size = sizeof(color);
    color.header.adapterId = paths[i].targetInfo.adapterId;
    color.header.id = paths[i].targetInfo.id;
    if (DisplayConfigGetDeviceInfo(&color.header) == ERROR_SUCCESS) {
        state.hdr = color.advancedColorEnabled ? 1 : 0;
        state.bpc = (int)color.bitsPerColorChannel;
    }
    state.ok = true;
    return state;
}

inline void printState(const wchar_t *label, const DisplayState &s) {
    wprintf(L"%s %s hz=%.3f (%u/%u) cur_hz=%lu reg_hz=%lu hdr=%d bpc=%d %lux%lu\n", wallClock().c_str(), label, s.hz(),
            s.num, s.den, s.currentHz, s.registryHz, s.hdr, s.bpc, s.width, s.height);
    fflush(stdout);
}
