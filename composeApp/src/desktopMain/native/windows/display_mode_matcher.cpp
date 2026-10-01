// Refresh-rate matching (fork patch, see SPEC.md). Unity-built: #included by player_bridge.cpp
// right after mpvApi(), so it shares that file's includes, mpv declarations and anonymous namespace.
//
// Phase 2 contents: a MEASUREMENT-ONLY sampler. It does nothing unless the process was started
// with NUVIO_RR_MEASURE=1 (scripts/measure.ps1 sets it). Enabled, it writes one log per player
// (mpv's own log + a stats line per second + the Windows mode of the player's monitor).
//
// Threading: onMpvEvent() is called only from a player's mpv event thread (drainMpvEvents), once
// per mpv_wait_event() return, i.e. at least every 0.5 s. All state is thread_local, so it lives
// exactly as long as that thread, and the thread never outlives the mpv handle.
//
// Phase 4 contents: the feature itself (section "Phase 4" below), on per player when Kotlin says so at H2 (Phase 6).
// Phase 5 contents: mpv timing (display-resample at the hook and at runtime), the read-only NVIDIA
// driver-settings read, the health-check counters, fault kinds timing-set and drop-mode.

// player_bridge.cpp includes this file inside its anonymous namespace. System headers and the JNI
// exports need global scope, so that namespace is closed around them and reopened (here and at the end).
}  // namespace
#include <dxgi1_2.h>
#include <cstdarg>
#include <cstring>
#include <map>
#include <future>
#pragma comment(lib, "dxgi.lib")
#include <mmdeviceapi.h>   // Phase 9 E1 fix: passthrough device probe
#include <audioclient.h>
#include <mmreg.h>
#pragma comment(lib, "ole32.lib")
// Phase 8: the fork's folder name (ForkIdentity via JNI); defined at file scope near the end of this file.
std::wstring nuvioRrAppDirName();
namespace {

namespace nuvio_rr {

// mpv declarations player_bridge.cpp does not have (stable libmpv ABI, client.h).
constexpr int kMpvEventLogMessage = 2;
constexpr int kMpvEventStartFile = 6;
constexpr int kMpvEventEndFile = 7;
constexpr int kMpvEventFileLoaded = 8;
struct MpvEventLogMessage {
    const char *prefix;
    const char *level;
    const char *text;
    int log_level;
};
using mpv_request_log_messages_fn = int (*)(mpv_handle *, const char *);
using mpv_event_name_fn = const char *(*)(int);

struct MeasureConfig {
    bool enabled = false;
    std::wstring directory;
    std::string sync;      // NUVIO_RR_MEASURE_SYNC
    int switchHz = 0;      // NUVIO_RR_MEASURE_SWITCH_HZ
    bool ipc = false;      // NUVIO_RR_MEASURE_IPC=1: mpv input-ipc-server on \\.\pipe\nuvio-rr-<pid> (measure.ps1 actions)
    std::vector<std::pair<std::string, std::string>> opts;  // NUVIO_RR_MEASURE_OPTS="k=v;k=v" (Phase 2b spike)
    bool hideOverlay = false;  // NUVIO_RR_MEASURE_HIDE_OVERLAY=1 (Phase 2b spike)
    int maxHz = 0;  // NUVIO_RR_MEASURE_MAX_HZ: modes above it are hidden from decide() (Phase 7, Q30)
};

std::string asciiValue(const std::wstring &value) {
    std::string result;
    for (wchar_t ch : value) result.push_back(ch < 0x80 ? (char)ch : '?');  // mpv option values are ASCII
    return result;
}

std::string trimmed(const std::string &value) {
    size_t first = value.find_first_not_of(" \t");
    if (first == std::string::npos) return std::string();
    size_t last = value.find_last_not_of(" \t");
    return value.substr(first, last - first + 1);
}

std::wstring envValue(const wchar_t *name) {
    wchar_t buffer[4096] = {};
    DWORD length = GetEnvironmentVariableW(name, buffer, (DWORD)(sizeof(buffer) / sizeof(buffer[0])));
    if (length == 0 || length >= sizeof(buffer) / sizeof(buffer[0])) return std::wstring();
    return std::wstring(buffer, buffer + length);
}

// Read once per process. Every other entry point returns before any side effect when disabled.
const MeasureConfig &measureConfig() {
    static MeasureConfig config;
    static std::once_flag once;
    std::call_once(once, []() {
        if (envValue(L"NUVIO_RR_MEASURE") != L"1") return;
        config.enabled = true;
        config.directory = envValue(L"NUVIO_RR_MEASURE_DIR");
        if (config.directory.empty()) {
            std::wstring local = envValue(L"LOCALAPPDATA");
            // Phase 8 (verifier round 3): the fork's own cache, never the official %LOCALAPPDATA%\Nuvio.
            if (!local.empty()) config.directory = local + L"\\" + nuvioRrAppDirName() + L"\\Cache\\nuvio-rr";
        }
        config.sync = asciiValue(envValue(L"NUVIO_RR_MEASURE_SYNC"));
        config.switchHz = _wtoi(envValue(L"NUVIO_RR_MEASURE_SWITCH_HZ").c_str());
        config.ipc = envValue(L"NUVIO_RR_MEASURE_IPC") == L"1";
        std::string opts = asciiValue(envValue(L"NUVIO_RR_MEASURE_OPTS"));
        size_t begin = 0;
        while (begin < opts.size()) {
            size_t end = opts.find(';', begin);
            if (end == std::string::npos) end = opts.size();
            std::string pair = trimmed(opts.substr(begin, end - begin));
            size_t eq = pair.find('=');
            if (eq != std::string::npos && eq > 0) config.opts.emplace_back(trimmed(pair.substr(0, eq)), trimmed(pair.substr(eq + 1)));
            begin = end + 1;
        }
        config.hideOverlay = envValue(L"NUVIO_RR_MEASURE_HIDE_OVERLAY") == L"1";
        config.maxHz = _wtoi(envValue(L"NUVIO_RR_MEASURE_MAX_HZ").c_str());
    });
    return config;
}

double nowSeconds() {
    using namespace std::chrono;
    return duration<double>(steady_clock::now().time_since_epoch()).count();
}

std::string wallClock() {
    SYSTEMTIME st;
    GetLocalTime(&st);
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%02d:%02d:%02d.%03d", st.wHour, st.wMinute, st.wSecond, st.wMilliseconds);
    return buffer;
}

// Windows mode of the monitor hosting `hwnd`: exact rational refresh (QueryDisplayConfig),
// advanced colour (HDR) and bpc, plus the DEVMODE current/registry Hz for comparison.
struct DisplayState {
    bool ok = false;
    std::wstring gdiName;
    UINT32 num = 0, den = 0;
    int hdr = -1, bpc = -1;
    DWORD currentHz = 0, registryHz = 0;
    int width = 0, height = 0;
    bool interlaced = false;
    std::string error;  // the first failing call, for the feature log (P4-7)
};

// GDI device name (\\.\DISPLAYn) of the monitor nearest to `hwnd`; empty if none.
std::wstring monitorName(HWND hwnd) {
    HMONITOR monitor = hwnd ? MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) : nullptr;
    MONITORINFOEXW info = {};
    info.cbSize = sizeof(info);
    if (!monitor || !GetMonitorInfoW(monitor, &info)) return std::wstring();
    return info.szDevice;
}

DisplayState queryDisplayByName(const std::wstring &device) {
    DisplayState state;
    if (device.empty()) {
        state.error = "no monitor for the window";
        return state;
    }
    state.gdiName = device;

    DEVMODEW dm = {};
    dm.dmSize = sizeof(dm);
    if (EnumDisplaySettingsW(device.c_str(), ENUM_CURRENT_SETTINGS, &dm)) {
        state.currentHz = dm.dmDisplayFrequency;
        state.width = (int)dm.dmPelsWidth;
        state.height = (int)dm.dmPelsHeight;
    } else {
        state.error = "EnumDisplaySettingsW(CURRENT) failed err=" + std::to_string(GetLastError());
    }
    DEVMODEW reg = {};
    reg.dmSize = sizeof(reg);
    if (EnumDisplaySettingsW(device.c_str(), ENUM_REGISTRY_SETTINGS, &reg)) state.registryHz = reg.dmDisplayFrequency;

    UINT32 pathCount = 0, modeCount = 0;
    LONG rc = GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, &pathCount, &modeCount);
    if (rc != ERROR_SUCCESS) {
        state.error = "GetDisplayConfigBufferSizes rc=" + std::to_string(rc);
        return state;
    }
    std::vector<DISPLAYCONFIG_PATH_INFO> paths(pathCount);
    std::vector<DISPLAYCONFIG_MODE_INFO> modes(modeCount);
    rc = QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, &pathCount, paths.data(), &modeCount, modes.data(), nullptr);
    if (rc != ERROR_SUCCESS) {
        state.error = "QueryDisplayConfig rc=" + std::to_string(rc);
        return state;
    }
    for (UINT32 i = 0; i < pathCount; ++i) {
        const DISPLAYCONFIG_PATH_INFO &path = paths[i];
        DISPLAYCONFIG_SOURCE_DEVICE_NAME source = {};
        source.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME;
        source.header.size = sizeof(source);
        source.header.adapterId = path.sourceInfo.adapterId;
        source.header.id = path.sourceInfo.id;
        if (DisplayConfigGetDeviceInfo(&source.header) != ERROR_SUCCESS) continue;
        if (_wcsicmp(source.viewGdiDeviceName, device.c_str()) != 0) continue;

        UINT32 modeIndex = path.targetInfo.modeInfoIdx;
        if (modeIndex != DISPLAYCONFIG_PATH_MODE_IDX_INVALID && modeIndex < modeCount &&
            modes[modeIndex].infoType == DISPLAYCONFIG_MODE_INFO_TYPE_TARGET) {
            const auto &signal = modes[modeIndex].targetMode.targetVideoSignalInfo;
            state.num = signal.vSyncFreq.Numerator;
            state.den = signal.vSyncFreq.Denominator;
            state.interlaced = signal.scanLineOrdering != DISPLAYCONFIG_SCANLINE_ORDERING_PROGRESSIVE &&
                signal.scanLineOrdering != DISPLAYCONFIG_SCANLINE_ORDERING_UNSPECIFIED;
        } else {
            state.num = path.targetInfo.refreshRate.Numerator;
            state.den = path.targetInfo.refreshRate.Denominator;
            state.interlaced = path.targetInfo.scanLineOrdering != DISPLAYCONFIG_SCANLINE_ORDERING_PROGRESSIVE &&
                path.targetInfo.scanLineOrdering != DISPLAYCONFIG_SCANLINE_ORDERING_UNSPECIFIED;
        }
        DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO color = {};
        color.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_ADVANCED_COLOR_INFO;
        color.header.size = sizeof(color);
        color.header.adapterId = path.targetInfo.adapterId;
        color.header.id = path.targetInfo.id;
        rc = DisplayConfigGetDeviceInfo(&color.header);
        if (rc == ERROR_SUCCESS) {
            state.hdr = color.advancedColorEnabled ? 1 : 0;
            state.bpc = (int)color.bitsPerColorChannel;
        } else if (state.error.empty()) {
            state.error = "DisplayConfigGetDeviceInfo(ADVANCED_COLOR_INFO) rc=" + std::to_string(rc);
        }
        state.ok = true;
        break;
    }
    if (!state.ok && state.error.empty()) state.error = "no active display path for the device";
    return state;
}

// The feature only acts on a full read: rate, size, HDR and bpc all known (an unknown HDR is not SDR).
bool complete(const DisplayState &d) {
    return d.ok && d.num > 0 && d.den > 0 && d.width > 0 && d.height > 0 && d.hdr >= 0 && d.bpc > 0;
}

DisplayState queryDisplay(HWND hwnd) {
    return queryDisplayByName(monitorName(hwnd));
}

std::string displayText(const DisplayState &d) {
    if (!d.ok) return "display=na";
    char buffer[256];
    double hz = d.den ? (double)d.num / (double)d.den : 0.0;
    std::snprintf(buffer, sizeof(buffer), "display=%ls mode=%dx%d hz=%.3f rational=%u/%u cur_hz=%lu reg_hz=%lu hdr=%d bpc=%d",
        d.gdiName.c_str(), d.width, d.height, hz, d.num, d.den, d.currentHz, d.registryHz, d.hdr, d.bpc);
    return buffer;
}

// Measure-only knob (NUVIO_RR_MEASURE_SWITCH_HZ): switch the player's monitor with CDS_FULLSCREEN,
// never the registry, never restored here (kill test J / display-fps re-detection check).
// Runs on its own detached thread so the mpv event thread never blocks on the mode change.
void measureSwitchAsync(std::wstring device, int hz, std::function<void(const std::string &)> log) {
    std::thread([device, hz, log]() {
        DEVMODEW dm = {};
        dm.dmSize = sizeof(dm);
        if (!EnumDisplaySettingsW(device.c_str(), ENUM_CURRENT_SETTINGS, &dm)) {
            log("switch: EnumDisplaySettingsW failed");
            return;
        }
        dm.dmDisplayFrequency = (DWORD)hz;
        dm.dmFields = DM_DISPLAYFREQUENCY;
        double start = nowSeconds();
        LONG result = ChangeDisplaySettingsExW(device.c_str(), &dm, nullptr, CDS_FULLSCREEN, nullptr);
        char buffer[160];
        std::snprintf(buffer, sizeof(buffer), "switch: ChangeDisplaySettingsExW(%ls, %d Hz, CDS_FULLSCREEN) = %ld in %.0f ms",
            device.c_str(), hz, result, (nowSeconds() - start) * 1000.0);
        log(buffer);
    }).detach();
}

class MeasureSession {
public:
    ~MeasureSession() {
        if (file_) {
            write("end: event thread exit");
            std::fclose(file_);
        }
    }

    void onEvent(mpv_handle *mpv, mpv_event *event, HWND hwnd, bool stopping) {
        if (stopping) {  // player is shutting down: no more mpv or Win32 calls, only one log line
            if (file_ && !stopLogged_) {
                stopLogged_ = true;
                write("stopping: sampler idle");
            }
            return;
        }
        if (!started_) start(mpv, hwnd);
        if (!file_) return;
        if (event && event->event_id != MPV_EVENT_NONE) handleEvent(mpv, event, hwnd);
        if (!loaded_) return;  // P2-3 samples only while a file is loaded (pre-load samples cost up to ~110 ms)
        double now = nowSeconds();
        if (now - lastSample_ >= 0.9) {  // events arrive at least every 0.5 s => sample starts <= 1.4 s apart
            lastSample_ = now;
            sample(mpv, hwnd);
            if (measureConfig().hideOverlay) hideOverlay(hwnd, false);
        }
    }

private:
    FILE *file_ = nullptr;
    bool started_ = false;
    bool switched_ = false;
    bool stopLogged_ = false;
    bool loaded_ = false;
    double lastSample_ = 0.0;
    mpv_event_name_fn eventName_ = nullptr;
    std::shared_ptr<std::mutex> writeMutex_ = std::make_shared<std::mutex>();

    void start(mpv_handle *mpv, HWND hwnd) {
        started_ = true;
        const MeasureConfig &config = measureConfig();
        if (config.directory.empty()) return;
        SHCreateDirectoryExW(nullptr, config.directory.c_str(), nullptr);
        static std::atomic<int> sequence{0};
        SYSTEMTIME st;
        GetLocalTime(&st);
        wchar_t name[128];
        std::swprintf(name, 128, L"\\nuvio-rr-%04d%02d%02d-%02d%02d%02d-%lu-%d.log", st.wYear, st.wMonth, st.wDay,
            st.wHour, st.wMinute, st.wSecond, GetCurrentProcessId(), ++sequence);
        std::wstring path = config.directory + name;
        file_ = _wfsopen(path.c_str(), L"w", _SH_DENYWR);
        if (!file_) return;

        MpvApi &api = mpvApi();
        eventName_ = reinterpret_cast<mpv_event_name_fn>(GetProcAddress(api.library, "mpv_event_name"));
        auto requestLog = reinterpret_cast<mpv_request_log_messages_fn>(GetProcAddress(api.library, "mpv_request_log_messages"));
        int logResult = requestLog ? requestLog(mpv, "v") : -1;
        write("start: pid=" + std::to_string(GetCurrentProcessId()) + " log_request=" + std::to_string(logResult) +
            " sync_knob=" + (config.sync.empty() ? std::string("none") : config.sync) +
            " switch_knob=" + std::to_string(config.switchHz) + " opts_knob=" + std::to_string(config.opts.size()) +
            " hide_overlay_knob=" + (config.hideOverlay ? "1" : "0"));
        write("before: " + displayText(queryDisplay(hwnd)));
        if (config.ipc) {
            std::string pipe = "\\\\.\\pipe\\nuvio-rr-" + std::to_string(GetCurrentProcessId());
            int result = api.setPropertyString(mpv, "input-ipc-server", pipe.c_str());
            write("knob: input-ipc-server=" + pipe + " result=" + std::to_string(result));
        }
        if (!config.sync.empty()) {
            int result = api.setPropertyString(mpv, "video-sync", config.sync.c_str());
            write("knob: video-sync=" + config.sync + " result=" + std::to_string(result));
        }
        // Before the file loads (the event thread starts right after loadfile; the VO is created later).
        // After mpv_initialize, mpv_set_option_string behaves like mpv_set_property_string.
        for (const auto &opt : config.opts) {
            int result = api.setOptionString(mpv, opt.first.c_str(), opt.second.c_str());
            write("opt " + opt.first + "=" + opt.second + " rc=" + std::to_string(result) +
                (result < 0 ? " (" + api.errorText(result) + ", skipped)" : std::string()));
        }
    }

    // Measure-only knob (NUVIO_RR_MEASURE_HIDE_OVERLAY): hide the direct children of the player container
    // that are not mpv's own window (class "mpv"), i.e. the WebView2 controls overlay. Async, because those
    // windows belong to the UI thread. Re-checked every sample in case upstream layout shows it again.
    void hideOverlay(HWND hwnd, bool logAll) {
        struct Ctx { HWND parent; std::vector<std::string> seen; int hidden; } ctx{hwnd, {}, 0};
        EnumChildWindows(hwnd, [](HWND child, LPARAM param) -> BOOL {
            auto *c = reinterpret_cast<Ctx *>(param);
            if (GetParent(child) != c->parent) return TRUE;
            wchar_t cls[128] = {};
            GetClassNameW(child, cls, 128);
            bool visible = IsWindowVisible(child) != FALSE;
            char buffer[200];
            std::snprintf(buffer, sizeof(buffer), "%ls(visible=%d)", cls, visible ? 1 : 0);
            c->seen.push_back(buffer);
            if (wcscmp(cls, L"mpv") != 0 && visible) {
                ShowWindowAsync(child, SW_HIDE);
                ++c->hidden;
            }
            return TRUE;
        }, reinterpret_cast<LPARAM>(&ctx));
        if (!logAll && ctx.hidden == 0) return;
        std::string line = "hide-overlay: children=" + std::to_string(ctx.seen.size()) + " hidden=" + std::to_string(ctx.hidden);
        for (const auto &seen : ctx.seen) line += " " + seen;
        write(line);
    }

    void write(const std::string &line, const std::string &stamp = std::string()) {
        std::lock_guard<std::mutex> lock(*writeMutex_);
        if (!file_) return;
        std::fprintf(file_, "%s %s\n", stamp.empty() ? wallClock().c_str() : stamp.c_str(), line.c_str());
        std::fflush(file_);
    }

    void handleEvent(mpv_handle *mpv, mpv_event *event, HWND hwnd) {
        int id = (int)event->event_id;
        if (id == kMpvEventLogMessage && event->data) {
            auto *msg = static_cast<MpvEventLogMessage *>(event->data);
            std::string text = msg->text ? msg->text : "";
            while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) text.pop_back();
            write(std::string("M [") + (msg->prefix ? msg->prefix : "?") + "] " + (msg->level ? msg->level : "?") + ": " + text);
            return;
        }
        const char *name = eventName_ ? eventName_(id) : nullptr;
        write(std::string("E ") + (name ? name : std::to_string(id).c_str()));
        if (id == kMpvEventFileLoaded) {
            loaded_ = true;
            write("loaded: " + displayText(queryDisplay(hwnd)));
            const MeasureConfig &config = measureConfig();
            if (config.hideOverlay) hideOverlay(hwnd, true);
            if (config.switchHz > 0 && !switched_) {
                switched_ = true;
                DisplayState display = queryDisplay(hwnd);
                if (display.ok) {
                    auto mutex = writeMutex_;
                    FILE *file = file_;
                    // The detached thread only writes while this session's file is open (same mutex).
                    std::shared_ptr<bool> alive = alive_;
                    measureSwitchAsync(display.gdiName, config.switchHz, [mutex, file, alive](const std::string &line) {
                        std::lock_guard<std::mutex> lock(*mutex);
                        if (!*alive) return;
                        std::fprintf(file, "%s %s\n", wallClock().c_str(), line.c_str());
                        std::fflush(file);
                    });
                }
            }
        } else if (id == kMpvEventEndFile || id == MPV_EVENT_SHUTDOWN) {
            loaded_ = false;
            write("after: " + displayText(queryDisplay(hwnd)));
        }
        (void)mpv;
    }

    std::shared_ptr<bool> alive_ = std::make_shared<bool>(true);

    std::string doubleProp(mpv_handle *mpv, const char *name) {
        double value = 0.0;
        if (mpvApi().getProperty(mpv, name, MPV_FORMAT_DOUBLE, &value) < 0) return "na";
        char buffer[32];
        std::snprintf(buffer, sizeof(buffer), "%.6f", value);
        return buffer;
    }

    std::string intProp(mpv_handle *mpv, const char *name) {
        int64_t value = 0;
        if (mpvApi().getProperty(mpv, name, MPV_FORMAT_INT64, &value) < 0) return "na";
        return std::to_string(value);
    }

    std::string flagProp(mpv_handle *mpv, const char *name) {
        int value = 0;
        if (mpvApi().getProperty(mpv, name, MPV_FORMAT_FLAG, &value) < 0) return "na";
        return value ? "yes" : "no";
    }

    std::string stringProp(mpv_handle *mpv, const char *name) {
        char *value = nullptr;
        if (mpvApi().getProperty(mpv, name, MPV_FORMAT_STRING, &value) < 0 || !value) return "na";
        std::string result(value);
        mpvApi().freeValue(value);
        for (char &ch : result) if (ch == ' ') ch = '_';
        return result.empty() ? "na" : result;
    }

    void sample(mpv_handle *mpv, HWND hwnd) {
        std::string stamp = wallClock();  // sample START, so line gaps show the cadence, not the cost
        double started = nowSeconds();
        std::string line = "S";
        line += " time-pos=" + doubleProp(mpv, "time-pos");
        line += " pause=" + flagProp(mpv, "pause");
        line += " container-fps=" + doubleProp(mpv, "container-fps");
        line += " estimated-vf-fps=" + doubleProp(mpv, "estimated-vf-fps");
        line += " display-fps=" + doubleProp(mpv, "display-fps");
        line += " estimated-display-fps=" + doubleProp(mpv, "estimated-display-fps");
        line += " vsync-ratio=" + doubleProp(mpv, "vsync-ratio");
        line += " vsync-jitter=" + doubleProp(mpv, "vsync-jitter");
        line += " frame-drop-count=" + intProp(mpv, "frame-drop-count");
        line += " decoder-frame-drop-count=" + intProp(mpv, "decoder-frame-drop-count");
        line += " mistimed-frame-count=" + intProp(mpv, "mistimed-frame-count");
        line += " vo-delayed-frame-count=" + intProp(mpv, "vo-delayed-frame-count");
        line += " video-sync=" + stringProp(mpv, "video-sync");
        line += " display-sync-active=" + flagProp(mpv, "display-sync-active");
        line += " video-speed-correction=" + doubleProp(mpv, "video-speed-correction");
        line += " audio-speed-correction=" + doubleProp(mpv, "audio-speed-correction");
        line += " interpolation=" + stringProp(mpv, "interpolation");
        line += " display-fps-override=" + stringProp(mpv, "display-fps-override");
        line += " hwdec-current=" + stringProp(mpv, "hwdec-current");
        line += " gamma=" + stringProp(mpv, "video-params/gamma");
        line += " primaries=" + stringProp(mpv, "video-params/primaries");
        line += " " + windowText(hwnd) + " " + displayText(queryDisplay(hwnd));
        char cost[32];
        std::snprintf(cost, sizeof(cost), " cost_ms=%.1f", (nowSeconds() - started) * 1000.0);
        write(line + cost, stamp);
    }

    // Root window size and whether it covers its monitor exactly (app fullscreen is borderless).
    static std::string windowText(HWND hwnd) {
        HWND root = hwnd ? GetAncestor(hwnd, GA_ROOT) : nullptr;
        RECT window = {};
        MONITORINFO info = {};
        info.cbSize = sizeof(info);
        if (!root || !GetWindowRect(root, &window) || !GetMonitorInfoW(MonitorFromWindow(root, MONITOR_DEFAULTTONEAREST), &info)) {
            return "win=na fs=na";
        }
        bool covers = EqualRect(&window, &info.rcMonitor) != FALSE;
        char buffer[64];
        std::snprintf(buffer, sizeof(buffer), "win=%ldx%ld fs=%s", window.right - window.left, window.bottom - window.top,
            covers ? "yes" : "no");
        return buffer;
    }

public:
    void markDead() {
        std::lock_guard<std::mutex> lock(*writeMutex_);
        *alive_ = false;
    }
};

// ================================================================ Phase 4: the feature (SPEC P4)
// Phase 6: on or off per player. onMpvInitialized (H2) asks Kotlin (RefreshRateMatch.nativeFeatureEnabled: the
// NUVIO_RR_ENABLE override, else the "Match display refresh rate" setting); off = nothing below runs for that player.
// Flow: onMpvInitialized (H2) adds mpv's on_preloaded hook -> the hook event reaches onMpvEvent (H4)
// on the mpv event thread, which only starts a worker -> the worker reads fps + the player's monitor
// and calls up into Kotlin (RefreshRateMatch.nativePlaybackStart), which decides and switches through
// the NativeDisplayPort functions below on its own "nuvio-rr" thread -> the worker continues the hook.
// onPlayerShutdown (H5) continues a pending hook itself and cuts the worker off from the mpv handle.

constexpr int kMpvEventHook = 25;
struct MpvEventHook {
    const char *name;
    uint64_t id;
};
using mpv_hook_add_fn = int (*)(mpv_handle *, uint64_t, const char *, int);
using mpv_hook_continue_fn = int (*)(mpv_handle *, uint64_t);

constexpr uint64_t kLogMaxBytes = 1024 * 1024;  // then one rotation to refresh-rate.log.1 (P4-20)
constexpr double kSettleCapSeconds = 4.0;       // P4-8
constexpr DWORD kSettlePollMs = 100;
// NUVIO_RR_FAULT=slow-settle (P4-12): longer than the 2 s window-close wait, so a close early in the settle
// disposes the player (H5) while the settle is still running.
constexpr double kSlowSettleSeconds = 3.0;

// FailureKind.ordinal + 1 (Kotlin NativeCodec): the switch result codes.
enum SwitchCode : int64_t {
    kSwitchOk = 0,
    kDisplayNotFound = 2,
    kSwitchApiError = 3,
    kSettleTimeout = 4,
    kStopRequested = 5,
    kUnexpectedError = 8,
};

struct FeatureConfig {
    std::string fault;     // NUVIO_RR_FAULT, honoured only in measure runs (NUVIO_RR_MEASURE=1, Q16)
    std::wstring logPath;  // run folder in measure runs, else %LOCALAPPDATA%\Nuvio\Cache
};

const FeatureConfig &featureConfig() {
    static FeatureConfig config;
    static std::once_flag once;
    std::call_once(once, []() {
        const MeasureConfig &measure = measureConfig();
        if (measure.enabled) config.fault = asciiValue(envValue(L"NUVIO_RR_FAULT"));
        std::wstring dir = measure.enabled ? envValue(L"NUVIO_RR_MEASURE_DIR") : std::wstring();
        if (dir.empty()) {
            std::wstring local = envValue(L"LOCALAPPDATA");
            // Phase 8 (verifier round 3): the packaged fork logs into %LOCALAPPDATA%\<fork name>\Cache (DesktopStorage
            // puts its cache there too); "Nuvio" when the identity is off (dev runs: LOCALAPPDATA is the dev profile).
            if (!local.empty()) dir = local + L"\\" + nuvioRrAppDirName() + L"\\Cache";
        }
        if (!dir.empty()) {
            SHCreateDirectoryExW(nullptr, dir.c_str(), nullptr);
            config.logPath = dir + L"\\refresh-rate.log";
        }
    });
    return config;
}

bool fault(const char *kind) {
    return featureConfig().fault == kind;
}

std::string strf(const char *format, ...) {
    char buffer[1024];
    va_list args;
    va_start(args, format);
    std::vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    return buffer;
}

// True once H2 turned the feature on for a player; until then H4/H5 return at once (a feature-off process pays nothing).
std::atomic<bool> gFeatureUsed{false};

// The one writer of refresh-rate.log: native lines ("N") and Kotlin lines ("K", via nativeLog). Only called for
// players the feature is on for, or to record why H2 could not ask Kotlin (P6-10), so a feature-off run writes nothing.
void rrLog(const std::string &line) {
    const FeatureConfig &config = featureConfig();
    static std::mutex mutex;
    std::lock_guard<std::mutex> lock(mutex);
    std::string text = wallClock() + " [nuvio-rr] " + line + "\n";
    OutputDebugStringA(text.c_str());
    if (config.logPath.empty()) return;
    WIN32_FILE_ATTRIBUTE_DATA info = {};
    if (GetFileAttributesExW(config.logPath.c_str(), GetFileExInfoStandard, &info) &&
        ((((uint64_t)info.nFileSizeHigh) << 32) | info.nFileSizeLow) > kLogMaxBytes) {
        std::wstring old = config.logPath + L".1";
        MoveFileExW(config.logPath.c_str(), old.c_str(), MOVEFILE_REPLACE_EXISTING);
    }
    FILE *file = _wfsopen(config.logPath.c_str(), L"a", _SH_DENYWR);
    if (!file) return;
    std::fputs(text.c_str(), file);
    std::fclose(file);
}

void nlog(const std::string &line) {
    rrLog("N " + line);
}

std::string stateText(const DisplayState &d) {
    if (!d.ok) return "na";
    return strf("%ls %dx%d@%u/%u %dbpc hdr=%d%s", d.gdiName.c_str(), d.width, d.height, d.num, d.den, d.bpc, d.hdr,
        d.interlaced ? " interlaced" : "");
}

// ---------------------------------------------------------------- players (process-global)
struct PlayerEntry {
    int64_t id = 0;
    const void *owner = nullptr;  // the WindowsMpvWebPlayer
    HWND container = nullptr;
    std::atomic<bool> stopping{false};
    std::mutex mutex;              // guards the three below; held only for short mpv calls
    mpv_handle *mpv = nullptr;     // null once the player shut down
    bool hookPending = false;
    uint64_t hookId = 0;
    // Phase 5 (P5-5/P5-6): our timing change on this player and the values it replaced.
    std::atomic<bool> timingApplied{false};
    std::string savedTiming[3];
    // Health-check counters, cached by the player's own mpv event thread (H4) once per second, so the nuvio-rr
    // thread never calls into mpv while holding [mutex] (verifier Phase 5: shutdown() takes it on the UI thread).
    std::atomic<double> cachedStats[5] = {std::nan(""), std::nan(""), std::nan(""), std::nan(""), 0.0};
    std::atomic<bool> statsReady{false};
    bool badge = false;  // show the rate badge for this player (set once at H2)
};

struct Registry {
    std::mutex mutex;
    std::vector<std::shared_ptr<PlayerEntry>> players;
    int64_t nextId = 1;
};

Registry &registry() {
    static Registry instance;
    return instance;
}

template <typename Match>
std::shared_ptr<PlayerEntry> findPlayer(Match match, bool remove = false) {
    Registry &r = registry();
    std::lock_guard<std::mutex> lock(r.mutex);
    for (auto it = r.players.begin(); it != r.players.end(); ++it) {
        if (!match(**it)) continue;
        std::shared_ptr<PlayerEntry> found = *it;
        if (remove) r.players.erase(it);
        return found;
    }
    return nullptr;
}

bool stopRequested(int64_t playerId) {
    auto entry = findPlayer([playerId](const PlayerEntry &p) { return p.id == playerId; });
    return !entry || entry->stopping.load();
}

void sleepUnlessStopped(double seconds, int64_t playerId) {
    double end = nowSeconds() + seconds;
    while (nowSeconds() < end && !stopRequested(playerId)) Sleep(20);
}

template <typename Fn>
Fn mpvSymbol(const char *name) {
    return reinterpret_cast<Fn>(GetProcAddress(mpvApi().library, name));
}

// Continues the pending hook once; the caller holds entry.mutex.
void continueHookLocked(PlayerEntry &entry, uint64_t hookId, const char *by) {
    if (!entry.hookPending || entry.hookId != hookId || !entry.mpv) return;
    entry.hookPending = false;
    static auto hookContinue = mpvSymbol<mpv_hook_continue_fn>("mpv_hook_continue");
    int rc = hookContinue ? hookContinue(entry.mpv, hookId) : -1;
    nlog(strf("hook p%lld continued by %s rc=%d", (long long)entry.id, by, rc));
}

// ---------------------------------------------------------------- Win32 port (called from the nuvio-rr thread)
// Exact modes from DXGI (research 02); DXGI does not know the link bpc, so each mode carries the
// current bpc and the switch verify (P3-21) checks the real bpc afterwards.
bool enumerateModes(const std::wstring &device, const DisplayState &current, std::vector<int64_t> &out) {
    int bpc = current.bpc;
    ComPtr<IDXGIFactory1> factory;
    HRESULT hr = CreateDXGIFactory1(__uuidof(IDXGIFactory1), reinterpret_cast<void **>(factory.GetAddressOf()));
    if (FAILED(hr)) {
        nlog(strf("modes: CreateDXGIFactory1 failed hr=0x%08lx", (unsigned long)hr));
        return false;
    }
    ComPtr<IDXGIAdapter1> adapter;
    for (UINT a = 0; factory->EnumAdapters1(a, adapter.ReleaseAndGetAddressOf()) != DXGI_ERROR_NOT_FOUND; ++a) {
        ComPtr<IDXGIOutput> output;
        for (UINT o = 0; adapter->EnumOutputs(o, output.ReleaseAndGetAddressOf()) != DXGI_ERROR_NOT_FOUND; ++o) {
            DXGI_OUTPUT_DESC desc = {};
            if (FAILED(output->GetDesc(&desc)) || _wcsicmp(desc.DeviceName, device.c_str()) != 0) continue;
            ComPtr<IDXGIOutput1> output1;
            if (FAILED(output.As(&output1))) {
                nlog("modes: IDXGIOutput1 unavailable");
                return false;
            }
            std::vector<DXGI_MODE_DESC1> modes;
            for (int attempt = 0; attempt < 3; ++attempt) {
                UINT count = 0;
                hr = output1->GetDisplayModeList1(DXGI_FORMAT_R8G8B8A8_UNORM, 0, &count, nullptr);
                if (FAILED(hr)) break;
                modes.resize(count);
                hr = output1->GetDisplayModeList1(DXGI_FORMAT_R8G8B8A8_UNORM, 0, &count, modes.data());
                modes.resize(count);
                if (hr != DXGI_ERROR_MORE_DATA) break;
            }
            if (FAILED(hr)) {
                nlog(strf("modes: GetDisplayModeList1 failed hr=0x%08lx", (unsigned long)hr));
                return false;
            }
            std::string text;
            std::string dropped;
            // Measure-only (Phase 7, Q30): at the 240 Hz desktop default nothing would switch, so a cap hides the modes
            // above it from decide(); the current-state reads and the restore are untouched.
            int maxHz = measureConfig().enabled ? measureConfig().maxHz : 0;
            for (const DXGI_MODE_DESC1 &m : modes) {
                if (maxHz > 0 && m.RefreshRate.Denominator != 0 &&
                    (double)m.RefreshRate.Numerator / m.RefreshRate.Denominator > maxHz + 0.5) {
                    if ((int)m.Width == current.width && (int)m.Height == current.height) {
                        dropped += strf(" %u/%u", m.RefreshRate.Numerator, m.RefreshRate.Denominator);
                    }
                    continue;
                }
                bool interlaced = m.ScanlineOrdering == DXGI_MODE_SCANLINE_ORDER_UPPER_FIELD_FIRST ||
                    m.ScanlineOrdering == DXGI_MODE_SCANLINE_ORDER_LOWER_FIELD_FIRST;
                out.insert(out.end(), {(int64_t)m.Width, (int64_t)m.Height, (int64_t)m.RefreshRate.Numerator,
                    (int64_t)m.RefreshRate.Denominator, (int64_t)bpc, interlaced ? 1 : 0});
                if ((int)m.Width == current.width && (int)m.Height == current.height) {  // log the relevant ones only
                    text += strf(" %ux%u@%u/%u%s", m.Width, m.Height, m.RefreshRate.Numerator, m.RefreshRate.Denominator,
                        interlaced ? "i" : "");
                }
            }
            nlog(strf("modes %ls: %zu (DXGI R8G8B8A8, bpc %d), at %dx%d:", device.c_str(), modes.size(), bpc, current.width,
                current.height) + text);
            if (maxHz > 0) nlog(strf("measure max-hz=%d dropped=", maxHz) + (dropped.empty() ? std::string(" none") : dropped));
            return true;
        }
    }
    nlog(strf("modes: no DXGI output for %ls", device.c_str()));
    return false;
}

bool sameRate(UINT32 num, UINT32 den, int64_t targetNum, int64_t targetDen) {
    if (!num || !den || targetNum <= 0 || targetDen <= 0) return false;
    double a = (double)num / (double)den, b = (double)targetNum / (double)targetDen;
    // Same value as Kotlin's RATE_MATCH_TOLERANCE (Phase 7, F1): DXGI lists 120 Hz as 12000/100, Windows then runs
    // 119998/1000 (16.7 ppm); real neighbours are >= 188 ppm apart (143.973/144).
    return std::fabs(a / b - 1.0) <= 1e-4;
}

bool sameState(const DisplayState &a, const DisplayState &b) {
    return a.ok && b.ok && a.num == b.num && a.den == b.den && a.hdr == b.hdr && a.bpc == b.bpc && a.width == b.width &&
        a.height == b.height && a.interlaced == b.interlaced;
}

std::vector<int64_t> stateValues(const DisplayState &d) {
    return {(int64_t)d.width, (int64_t)d.height, (int64_t)d.num, (int64_t)d.den, (int64_t)d.bpc, d.hdr == 1 ? 1 : 0,
        d.interlaced ? 1 : 0};
}

// CDS_FULLSCREEN switch, then settle (P4-6, P4-8). Result: [code, state x7, elapsed ms].
std::vector<int64_t> switchMode(const std::wstring &device, int width, int height, int64_t num, int64_t den, int64_t playerId) {
    double start = nowSeconds();
    DisplayState last;
    auto result = [&](int64_t code, const char *what) {
        std::vector<int64_t> values = {code};
        std::vector<int64_t> state = stateValues(last);
        values.insert(values.end(), state.begin(), state.end());
        values.push_back((int64_t)((nowSeconds() - start) * 1000.0));
        nlog(strf("switch %ls -> %dx%d@%lld/%lld p%lld: %s code=%lld after %lld ms, last=", device.c_str(), width, height,
            (long long)num, (long long)den, (long long)playerId, what, (long long)code, (long long)values.back()) +
            stateText(last));
        return values;
    };
    if (stopRequested(playerId)) return result(kStopRequested, "player stopping before the switch");
    if (fault("switch-api")) return result(kSwitchApiError, "injected fault switch-api (no API call)");

    DEVMODEW dm = {};
    dm.dmSize = sizeof(dm);
    if (!EnumDisplaySettingsW(device.c_str(), ENUM_CURRENT_SETTINGS, &dm)) {
        nlog(strf("switch: EnumDisplaySettingsW(%ls, CURRENT) failed err=%lu", device.c_str(), GetLastError()));
        return result(kDisplayNotFound, "display not found");
    }
    DWORD hz = (DWORD)std::llround((double)num / (double)den);
    dm.dmDisplayFrequency = hz;
    dm.dmFields = DM_DISPLAYFREQUENCY;
    double callStart = nowSeconds();
    LONG rc = ChangeDisplaySettingsExW(device.c_str(), &dm, nullptr, CDS_FULLSCREEN, nullptr);
    nlog(strf("ChangeDisplaySettingsExW(%ls, %lu Hz, CDS_FULLSCREEN) = %ld in %.0f ms", device.c_str(), hz, rc,
        (nowSeconds() - callStart) * 1000.0));
    if (rc != DISP_CHANGE_SUCCESSFUL) return result(kSwitchApiError, "switch API error");

    double settleStart = nowSeconds();
    if (fault("slow-settle")) {
        nlog("injected fault slow-settle: +3 s");
        sleepUnlessStopped(kSlowSettleSeconds, playerId);
    }
    DisplayState previous;
    for (;;) {
        if (stopRequested(playerId)) return result(kStopRequested, "player stopped during settle");
        if (nowSeconds() - settleStart > kSettleCapSeconds) return result(kSettleTimeout, "settle timeout");
        DisplayState now = queryDisplayByName(device);
        if (complete(now)) {
            last = now;
        } else {
            nlog("settle: incomplete read (" + now.error + ")");
        }
        if (complete(now) && !fault("settle-timeout") && sameRate(now.num, now.den, num, den) && sameState(previous, now)) {
            nlog(strf("settled in %.0f ms (switch call to two equal reads)", (nowSeconds() - callStart) * 1000.0));
            if (fault("verify-mismatch")) {
                nlog("injected fault verify-mismatch: reporting HDR flipped");
                last.hdr = last.hdr == 1 ? 0 : 1;
            }
            return result(kSwitchOk, "ok");
        }
        previous = now;
        Sleep(kSettlePollMs);
    }
}

bool restoreMode(const std::wstring &device) {
    if (fault("restore-failed")) {
        nlog(strf("restore %ls: injected fault restore-failed (no API call)", device.c_str()));
        return false;
    }
    double start = nowSeconds();
    LONG rc = ChangeDisplaySettingsExW(device.c_str(), nullptr, nullptr, 0, nullptr);
    nlog(strf("ChangeDisplaySettingsExW(%ls, NULL, 0) restore = %ld in %.0f ms, now ", device.c_str(), rc,
        (nowSeconds() - start) * 1000.0) + stateText(queryDisplayByName(device)));
    return rc == DISP_CHANGE_SUCCESSFUL;
}

// ---------------------------------------------------------------- JNI upcall
JavaVM *javaVm() {
    static JavaVM *vm = nullptr;
    static std::once_flag once;
    std::call_once(once, []() {
        using GetVms = jint(JNICALL *)(JavaVM **, jsize, jsize *);
        HMODULE jvm = GetModuleHandleW(L"jvm.dll");
        auto getVms = jvm ? reinterpret_cast<GetVms>(GetProcAddress(jvm, "JNI_GetCreatedJavaVMs")) : nullptr;
        jsize count = 0;
        if (!getVms || getVms(&vm, 1, &count) != JNI_OK || count < 1) vm = nullptr;
    });
    return vm;
}

// RefreshRateMatch, found through the system class loader, which is the app's loader in dev runs and in the packaged app.
struct MatchMethods {
    jclass type = nullptr;
    jmethodID start = nullptr;    // nativePlaybackStart
    jmethodID enabled = nullptr;  // nativeFeatureEnabled (Phase 6)
    jmethodID badge = nullptr;    // nativeBadgeEnabled (owner 2026-09-30)
};

const MatchMethods &matchMethods(JNIEnv *env) {
    static MatchMethods methods;
    static std::mutex lookupMutex;
    std::lock_guard<std::mutex> lock(lookupMutex);
    if (!methods.type) {
        jclass local = env->FindClass("com/nuvio/app/features/player/desktop/refreshrate/runtime/RefreshRateMatch");
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (local) {
            jmethodID start = env->GetStaticMethodID(local, "nativePlaybackStart", "(JLjava/lang/String;DDZD)[J");
            if (env->ExceptionCheck()) env->ExceptionClear();
            jmethodID enabled = env->GetStaticMethodID(local, "nativeFeatureEnabled", "()I");
            if (env->ExceptionCheck()) env->ExceptionClear();
            jmethodID badge = env->GetStaticMethodID(local, "nativeBadgeEnabled", "()I");
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (start && enabled && badge) {
                methods.start = start;
                methods.enabled = enabled;
                methods.badge = badge;
                methods.type = static_cast<jclass>(env->NewGlobalRef(local));
            }
            env->DeleteLocalRef(local);
        }
    }
    return methods;
}

// Phase 6 (P6-9, P6-10): is the feature on for the player being created? Kotlin's code: 0 = off, 1 = on by
// NUVIO_RR_ENABLE, 2 = on by the setting, -1 = Kotlin failed. Anything that stops the question being asked is -1 with
// the reason in [why]; the caller treats every value <= 0 as off.
// [badge]: ask nativeBadgeEnabled instead (1 = show the rate badge, 0 = don't; same failure values).
int upcallFeatureEnabled(std::string &why, bool badge = false) {
    JavaVM *vm = javaVm();
    if (!vm) {
        why = "no-jvm";
        return -1;
    }
    JNIEnv *env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) {
            why = "attach-failed";
            return -1;
        }
        attached = true;
    }
    if (!env) {  // GetEnv answered neither OK nor EDETACHED (JNI_EVERSION)
        why = "getenv-failed";
        return -1;
    }
    int code = -1;
    const MatchMethods &match = matchMethods(env);
    jmethodID method = badge ? match.badge : match.enabled;
    if (!badge && match.type && measureConfig().enabled && fault("enable-upcall")) {  // measure runs only: ask for a method that does not exist
        method = env->GetStaticMethodID(match.type, "nativeFeatureEnabledInjectedFault", "()I");
        if (env->ExceptionCheck()) env->ExceptionClear();
        why = "method-not-found (injected fault enable-upcall)";
    }
    if (!match.type) {
        why = "class-not-found";
    } else if (method) {
        jint value = env->CallStaticIntMethod(match.type, method);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            why = "exception";
        } else {
            code = (int)value;
            if (code < 0) why = "kotlin-error";
        }
    } else if (why.empty()) {
        why = "method-not-found";
    }
    if (attached) vm->DetachCurrentThread();
    return code;
}

struct StartProps {
    double containerFps = std::nan("");
    double estimatedFps = std::nan("");
    bool isImage = false;
    std::string text;
};

// At on_preloaded no track is selected yet, so container-fps is usually unavailable: take the
// first video track that is not cover art from track-list (research 03).
StartProps readStartProps(mpv_handle *mpv) {
    MpvApi &api = mpvApi();
    StartProps props;
    double value = 0.0;
    if (api.getProperty(mpv, "container-fps", MPV_FORMAT_DOUBLE, &value) >= 0) props.containerFps = value;
    if (api.getProperty(mpv, "estimated-vf-fps", MPV_FORMAT_DOUBLE, &value) >= 0) props.estimatedFps = value;
    int64_t count = 0;
    api.getProperty(mpv, "track-list/count", MPV_FORMAT_INT64, &count);
    int videoTracks = 0;
    for (int64_t i = 0; i < count && i < 64; ++i) {
        std::string prefix = "track-list/" + std::to_string(i) + "/";
        char *type = nullptr;
        if (api.getProperty(mpv, (prefix + "type").c_str(), MPV_FORMAT_STRING, &type) < 0 || !type) continue;
        bool video = std::strcmp(type, "video") == 0;
        api.freeValue(type);
        if (!video) continue;
        ++videoTracks;
        int albumart = 0, image = 0;
        api.getProperty(mpv, (prefix + "albumart").c_str(), MPV_FORMAT_FLAG, &albumart);
        api.getProperty(mpv, (prefix + "image").c_str(), MPV_FORMAT_FLAG, &image);
        if (albumart) continue;
        props.isImage = image != 0;
        double demuxFps = 0.0;
        if (std::isnan(props.containerFps) &&
            api.getProperty(mpv, (prefix + "demux-fps").c_str(), MPV_FORMAT_DOUBLE, &demuxFps) >= 0) {
            props.containerFps = demuxFps;
        }
        break;
    }
    props.text = strf("fps=%.6f estimate=%.6f image=%d video_tracks=%d tracks=%lld", props.containerFps,
        props.estimatedFps, props.isImage ? 1 : 0, videoTracks, (long long)count);
    return props;
}

// NativeCodec.timing: kind 0 = none/timeout, 1 = upstream, 2 = display-sync.
struct TimingRequest {
    int64_t kind = 0;
    int64_t num = 0;
    int64_t den = 0;
};

std::string upcallStart(int64_t playerId, const std::wstring &display, const StartProps &props, double frameCap,
    TimingRequest &request) {
    JavaVM *vm = javaVm();
    if (!vm) return "no-jvm";
    JNIEnv *env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return "attach-failed";
        attached = true;
    }
    if (!env) return "getenv-failed";
    std::string result;
    const MatchMethods &match = matchMethods(env);
    jclass matchClass = match.type;
    jmethodID startMethod = match.start;
    if (!matchClass || !startMethod) {
        result = "class-not-found";
    } else {
        jstring jdisplay = env->NewString(reinterpret_cast<const jchar *>(display.c_str()), (jsize)display.size());
        auto timing = static_cast<jlongArray>(env->CallStaticObjectMethod(matchClass, startMethod, (jlong)playerId, jdisplay,
            (jdouble)props.containerFps, (jdouble)props.estimatedFps, (jboolean)(props.isImage ? JNI_TRUE : JNI_FALSE),
            (jdouble)frameCap));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            result = "exception";
        } else if (timing && env->GetArrayLength(timing) == 3) {
            jlong t[3] = {};
            env->GetLongArrayRegion(timing, 0, 3, t);
            request = {t[0], t[1], t[2]};
            result = t[0] == 2 ? strf("display-sync(%lld/%lld)", (long long)t[1], (long long)t[2])
                : t[0] == 1 ? std::string("upstream") : std::string("none (timeout or off)");
        } else {
            result = "bad-result";
        }
        if (timing) env->DeleteLocalRef(timing);
        if (jdisplay) env->DeleteLocalRef(jdisplay);
    }
    if (attached) vm->DetachCurrentThread();
    return result;
}

// ---------------------------------------------------------------- Phase 5: NVIDIA driver settings (P5-9, read-only)
// The same calls as scripts/rr-tools/drsprobe.cpp: nvapi_QueryInterface IDs from NVIDIA/nvapi (nvapi_interface.h,
// NvApiDriverSettings.h), no SDK. Never SetSetting/SaveSettings.
constexpr unsigned int kNvFrlFps = 0x10835002;          // FRL_FPS, Max Frame Rate (0 = off)
constexpr unsigned int kNvPreferredPstate = 0x1057EB71;  // PREFERRED_PSTATE, Power management mode (1 = max performance)

#pragma pack(push, 8)
struct NvDrsSetting {  // NVDRS_SETTING_V1, 0x3020 bytes
    unsigned int version;
    wchar_t settingName[2048];
    unsigned int settingId, settingType, settingLocation, isCurrentPredefined, isPredefinedValid;
    union { unsigned int u32; unsigned char raw[4100]; } predefined;
    union { unsigned int u32; unsigned char raw[4100]; } current;
};
struct NvDrsApplication {  // NVDRS_APPLICATION_V1
    unsigned int version, isPredefined;
    wchar_t appName[2048], userFriendlyName[2048], launcher[2048];
};
#pragma pack(pop)

struct DriverSettings {
    double frameCap = std::nan("");  // fps; 0 = off; NaN = unknown (NativeCodec.frameCap)
    std::string text;
};
DriverSettings takeDriverRead(int64_t playerId, double &waitedMs);  // audit #9, defined before H2

struct NvApi {
    using QueryInterface = void *(__cdecl *)(unsigned int);
    using Init = int(__cdecl *)();
    using CreateSession = int(__cdecl *)(void **);
    using Session = int(__cdecl *)(void *);
    using GetBase = int(__cdecl *)(void *, void **);
    using FindApp = int(__cdecl *)(void *, const wchar_t *, void **, void *);
    using GetSetting = int(__cdecl *)(void *, void *, unsigned int, void *);
    bool ok = false;
    std::string error;
    CreateSession create = nullptr;
    Session destroy = nullptr;
    Session load = nullptr;
    GetBase getBase = nullptr;
    FindApp findApp = nullptr;
    GetSetting getSetting = nullptr;
};

NvApi &nvApi() {
    static NvApi api;
    static std::once_flag once;
    std::call_once(once, []() {
        HMODULE lib = LoadLibraryW(L"nvapi64.dll");
        auto qi = lib ? reinterpret_cast<NvApi::QueryInterface>(GetProcAddress(lib, "nvapi_QueryInterface")) : nullptr;
        if (!qi) {
            api.error = lib ? "nvapi_QueryInterface missing" : "nvapi64.dll not found";
            return;
        }
        auto init = reinterpret_cast<NvApi::Init>(qi(0x0150E828));
        api.create = reinterpret_cast<NvApi::CreateSession>(qi(0x0694D52E));
        api.destroy = reinterpret_cast<NvApi::Session>(qi(0xDAD9CFF8));
        api.load = reinterpret_cast<NvApi::Session>(qi(0x375DBD6B));
        api.getBase = reinterpret_cast<NvApi::GetBase>(qi(0xDA8466A0));
        api.findApp = reinterpret_cast<NvApi::FindApp>(qi(0xEEE566B2));
        api.getSetting = reinterpret_cast<NvApi::GetSetting>(qi(0x73BF8338));
        if (!init || !api.create || !api.destroy || !api.load || !api.getBase || !api.findApp || !api.getSetting) {
            api.error = "NVAPI entry missing";
            return;
        }
        int rc = init();
        if (rc != 0) {
            api.error = strf("NvAPI_Initialize rc=%d", rc);
            return;
        }
        api.ok = true;
    });
    return api;
}

// The value that applies to this process: the app profile's own setting if it has one, else the global one.
bool nvSetting(NvApi &api, void *session, void *appProfile, void *base, unsigned int id, unsigned int &value,
    const char *&where) {
    auto setting = std::make_unique<NvDrsSetting>();
    for (int pass = 0; pass < 2; ++pass) {
        void *profile = pass == 0 ? appProfile : base;
        if (!profile) continue;
        std::memset(setting.get(), 0, sizeof(NvDrsSetting));
        setting->version = (unsigned int)sizeof(NvDrsSetting) | (1u << 16);
        if (api.getSetting(session, profile, id, setting.get()) == 0) {
            value = setting->current.u32;
            where = pass == 0 ? "app" : "global";
            return true;
        }
    }
    return false;
}

DriverSettings readDriverSettings() {
    double start = nowSeconds();
    DriverSettings result;
    NvApi &api = nvApi();
    if (!api.ok) {
        result.text = strf("driver frl=unknown power=unknown profile=none (%s) ms=%.0f", api.error.c_str(),
            (nowSeconds() - start) * 1000.0);
        return result;
    }
    void *session = nullptr;
    int rc = api.create(&session);
    if (rc == 0 && (rc = api.load(session)) == 0) {
        void *base = nullptr;
        if (api.getBase(session, &base) != 0) base = nullptr;
        wchar_t path[MAX_PATH] = {};
        GetModuleFileNameW(nullptr, path, MAX_PATH);
        const wchar_t *slash = std::wcsrchr(path, L'\\');
        const wchar_t *exe = slash ? slash + 1 : path;
        void *appProfile = nullptr;
        auto app = std::make_unique<NvDrsApplication>();
        std::memset(app.get(), 0, sizeof(NvDrsApplication));
        app->version = (unsigned int)sizeof(NvDrsApplication) | (1u << 16);
        if (api.findApp(session, exe, &appProfile, app.get()) != 0) appProfile = nullptr;
        unsigned int value = 0;
        const char *from = "default";
        std::string frl = "off", power = "unknown";
        if (nvSetting(api, session, appProfile, base, kNvFrlFps, value, from)) {
            result.frameCap = (double)value;
            if (value != 0) frl = std::to_string(value);
        } else {
            result.frameCap = 0.0;  // set nowhere = the driver default, no limiter
            from = "default";
        }
        std::string frlFrom = from;
        if (nvSetting(api, session, appProfile, base, kNvPreferredPstate, value, from)) {
            power = strf("%u(%s)", value, from);
        }
        result.text = strf("driver frl=%s(%s) power=%s exe=%ls app_profile=%s", frl.c_str(), frlFrom.c_str(),
            power.c_str(), exe, appProfile ? "yes" : "no");
    } else {
        result.text = strf("driver frl=unknown power=unknown (DRS session/load rc=%d)", rc);
    }
    if (session) api.destroy(session);
    result.text += strf(" ms=%.0f", (nowSeconds() - start) * 1000.0);
    return result;
}

// ---------------------------------------------------------------- Phase 5: mpv timing (P5-5, P5-6)
// Saved before our first change on a player and put back exactly on revert. The caller holds entry.mutex
// (H5 takes it too, so no call reaches a destroyed handle).
const char *const kTimingProps[3] = {"video-sync", "interpolation", "display-fps-override"};

bool readStringProperty(mpv_handle *mpv, const char *name, std::string &out) {
    MpvApi &api = mpvApi();
    char *value = nullptr;
    if (api.getProperty(mpv, name, MPV_FORMAT_STRING, &value) < 0 || !value) return false;
    out = value;
    api.freeValue(value);
    return true;
}

int setTimingProperty(PlayerEntry &entry, const char *name, const std::string &value) {
    bool injected = fault("timing-set") && std::strcmp(name, "interpolation") == 0;
    int rc = injected ? -1 : mpvApi().setPropertyString(entry.mpv, name, value.c_str());
    nlog(strf("timing p%lld set %s=%s rc=%d%s", (long long)entry.id, name, value.c_str(), rc,
        injected ? " (injected fault timing-set)" : ""));
    return rc;
}

// Puts back the first [count] saved values, last first.
bool revertTimingLocked(PlayerEntry &entry, int count = 3) {
    if (!entry.mpv) return false;
    bool ok = true;
    for (int i = count - 1; i >= 0; --i) {
        if (setTimingProperty(entry, kTimingProps[i], entry.savedTiming[i]) < 0) ok = false;
    }
    entry.timingApplied = false;
    return ok;
}

// Audit E3 (2026-09-30): an mpv on-screen note of what the feature did, drawn by mpv itself (no upstream line).
// Per player, decided at H2 (owner 2026-09-30): the "Show refresh rate badge" setting, NUVIO_RR_OSD=0/1 overriding it.
// Measure runs keep it off unless NUVIO_RR_OSD=1, so their frame counters stay comparable.
bool rateBadgeFor(std::string &why) {
    if (measureConfig().enabled && envValue(L"NUVIO_RR_OSD") != L"1") return false;
    int code = -1;
    try {
        code = upcallFeatureEnabled(why, true);
    } catch (...) {
        why = "native-exception";
    }
    return code == 1;
}

// Owner 2026-09-30: mpv's default OSD text was "big and ugly". Styled per message with ASS overrides (osd-ass-cc), so
// no global OSD option changes: small Segoe UI, semi-bold value + lighter detail, thin soft outline (no \fad: mpv rebuilds OSD messages, so a fade restarts and never shows).
// Sizes are in mpv's 720-line OSD space (the default OSD font is 55).
enum class RateNote { Synced, NotMatched, SyncOff };

// Owner 2026-09-30: a small badge top-right (films show their content rating top-left), not a sentence.
// Synced: a sync symbol + the rate in whole Hz; not matched / sync off: the rate alone, dimmed. The log keeps the
// exact rate and the reason. ASS overrides per message (osd-ass-cc), so no global OSD option changes; sizes are in
// mpv's 720-line OSD space (default OSD font 55). No \\fad: mpv rebuilds OSD messages, so a fade restarts forever.
void showRateNoteLocked(PlayerEntry &entry, double hz, RateNote kind, const char *why) {
    if (!entry.mpv || !entry.badge || !mpvApi().command) return;
    std::string rate = strf("%.0f", hz);
    std::string style = "${osd-ass-cc/0}{\\an9\\bord1\\shad0\\3c&H000000&\\3a&HC0&\\1c&HFFFFFF&}";
    std::string text = kind == RateNote::Synced
        ? style + "{\\1a&H30&\\fnSegoe UI Symbol\\fs15}\xE2\x86\xBB{\\fnSegoe UI Semibold\\fs14} " + rate
        : style + "{\\1a&H90&\\fnSegoe UI Semibold\\fs14}" + rate;
    const char *args[] = {"expand-properties", "show-text", text.c_str(), "2500", nullptr};  // expands ${osd-ass-cc/0}
    int rc = mpvApi().command(entry.mpv, args);
    nlog(strf("note p%lld %s %.3f Hz (%s) rc=%d", (long long)entry.id,
        kind == RateNote::Synced ? "synced" : kind == RateNote::NotMatched ? "not-matched" : "sync-off", hz, why, rc));
}

std::string hzText(double hz) { return strf("%.2f Hz", hz); }

bool applyDisplaySyncLocked(PlayerEntry &entry, int64_t num, int64_t den) {
    if (!entry.mpv || num <= 0 || den <= 0) return false;
    std::string rate = strf("%.6f", (double)num / (double)den);
    if (entry.timingApplied) return setTimingProperty(entry, "display-fps-override", rate) >= 0;  // re-switch
    for (int i = 0; i < 3; ++i) {
        if (!readStringProperty(entry.mpv, kTimingProps[i], entry.savedTiming[i])) {
            nlog(strf("timing p%lld timing-failed: cannot read %s", (long long)entry.id, kTimingProps[i]));
            return false;
        }
    }
    const std::string values[3] = {"display-resample", "no", rate};
    for (int i = 0; i < 3; ++i) {
        if (setTimingProperty(entry, kTimingProps[i], values[i]) < 0) {
            revertTimingLocked(entry, i);
            nlog(strf("timing p%lld timing-failed at %s: reverted, upstream timing", (long long)entry.id, kTimingProps[i]));
            return false;
        }
    }
    entry.timingApplied = true;
    nlog(strf("timing p%lld display-sync %s (was video-sync=%s interpolation=%s display-fps-override=%s)",
        (long long)entry.id, rate.c_str(), entry.savedTiming[0].c_str(), entry.savedTiming[1].c_str(),
        entry.savedTiming[2].c_str()));
    return true;
}

// NUVIO_RR_FAULT=drop-mode (P5-8): drops our temporary mode 20 s and 50 s after the hook, as a monitor off/on does.
void dropModeFault(std::shared_ptr<PlayerEntry> entry, std::wstring device) {
    double start = nowSeconds();
    for (double at : {20.0, 50.0}) {
        sleepUnlessStopped(at - (nowSeconds() - start), entry->id);
        if (stopRequested(entry->id)) return;
        LONG rc = ChangeDisplaySettingsExW(device.c_str(), nullptr, nullptr, 0, nullptr);
        nlog(strf("injected fault drop-mode at %.0f s: ChangeDisplaySettingsExW(%ls, NULL) = %ld", at, device.c_str(), rc));
    }
}

// Worker for one on_preloaded hook: decide + switch through Kotlin, then apply the timing (P5-5).
void runHook(std::shared_ptr<PlayerEntry> entry, uint64_t hookId) {
    double start = nowSeconds();
    std::string timing = "none";
    TimingRequest request;
    std::wstring display;
    try {
        StartProps props;
        bool alive = false;
        {
            std::lock_guard<std::mutex> lock(entry->mutex);
            if (entry->mpv && entry->hookPending) {
                props = readStartProps(entry->mpv);
                alive = true;
            }
        }
        if (alive && !entry->stopping.load()) {
            display = monitorName(GetAncestor(entry->container, GA_ROOT));
            nlog(strf("hook p%lld on_preloaded %s display=%ls (read in %.0f ms)", (long long)entry->id, props.text.c_str(),
                display.c_str(), (nowSeconds() - start) * 1000.0));
            double driverWaitMs = 0.0;
            DriverSettings driver = takeDriverRead(entry->id, driverWaitMs);
            nlog(strf("hook p%lld ", (long long)entry->id) + driver.text + strf(" (hook waited %.0f ms)", driverWaitMs));
            timing = upcallStart(entry->id, display, props, driver.frameCap, request);
        }
    } catch (...) {
        timing = "unexpected-error";
        request = TimingRequest();
    }
    bool synced = false;
    {
        std::lock_guard<std::mutex> lock(entry->mutex);
        try {
            if (request.kind == 2 && entry->hookPending && !entry->stopping.load()) {
                synced = applyDisplaySyncLocked(*entry, request.num, request.den);
            }
        } catch (...) {
            nlog(strf("timing p%lld timing-failed: unexpected error", (long long)entry->id));
        }
        if (entry->hookPending && !entry->stopping.load()) {
            try {
                if (request.kind == 2 && synced) {
                    showRateNoteLocked(*entry, (double)request.num / (double)request.den, RateNote::Synced, "hook");
                } else if (request.kind == 1 && !display.empty()) {
                    DisplayState now = queryDisplayByName(display);
                    if (complete(now)) showRateNoteLocked(*entry, (double)now.num / (double)now.den, RateNote::NotMatched, "hook");
                }
            } catch (...) {
            }
        }
        nlog(strf("hook p%lld done after %.0f ms timing=%s applied=%s", (long long)entry->id,
            (nowSeconds() - start) * 1000.0, timing.c_str(),
            request.kind == 2 ? (synced ? "display-sync" : "none (failed)") : "none (upstream)"));
        continueHookLocked(*entry, hookId, "worker");
    }
    if (synced && fault("drop-mode") && !display.empty()) {
        try {
            std::thread(dropModeFault, entry, display).detach();
        } catch (...) {
        }
    }
}

// Hook H2: after mpv_initialize, before loadfile, so the first file's hook cannot be missed (P4-9).
// Audit #9 (2026-09-30): the NVAPI driver read takes 150-170 ms. It used to run inside on_preloaded while mpv waited;
// now H2 starts it on its own thread (mpv is still opening the stream then) and the hook collects the result.
// A promise, not std::async: nothing ever waits for a read that nobody collects (player closed before the hook).
// For a local file mpv reaches the hook ~4 ms after H2 (measured), so the early read can't finish in time; then the
// last finished read of this process is used at once (driver settings rarely change between two videos) and the
// running read refreshes it for the next video. Only the first video of a session waits for a read.
std::mutex gDriverReadsMutex;
std::map<int64_t, std::shared_future<DriverSettings>> gDriverReads;
bool gDriverCached = false;
DriverSettings gDriverCache;

void startDriverRead(int64_t playerId) {
    auto promise = std::make_shared<std::promise<DriverSettings>>();
    {
        std::lock_guard<std::mutex> lock(gDriverReadsMutex);
        gDriverReads[playerId] = promise->get_future().share();
    }
    try {
        std::thread([promise]() {
            try {
                DriverSettings fresh = readDriverSettings();
                {
                    std::lock_guard<std::mutex> lock(gDriverReadsMutex);
                    gDriverCache = fresh;
                    gDriverCached = true;
                }
                promise->set_value(fresh);
            } catch (...) {
                DriverSettings unknown;
                unknown.text = "driver frl=unknown power=unknown (read failed)";
                promise->set_value(unknown);
            }
        }).detach();
    } catch (...) {  // no thread: the hook reads it itself
        std::lock_guard<std::mutex> lock(gDriverReadsMutex);
        gDriverReads.erase(playerId);
    }
}

// The early read if there is one (waits for it to finish), else a read now. [waitedMs]: time spent here.
DriverSettings takeDriverRead(int64_t playerId, double &waitedMs) {
    double start = nowSeconds();
    std::shared_future<DriverSettings> pending;
    {
        std::lock_guard<std::mutex> lock(gDriverReadsMutex);
        auto it = gDriverReads.find(playerId);
        if (it != gDriverReads.end()) {
            pending = it->second;
            gDriverReads.erase(it);
        }
    }
    if (pending.valid() && pending.wait_for(std::chrono::seconds(0)) != std::future_status::ready) {
        std::lock_guard<std::mutex> lock(gDriverReadsMutex);
        if (gDriverCached) {
            DriverSettings cached = gDriverCache;
            cached.text += " (cached)";
            waitedMs = (nowSeconds() - start) * 1000.0;
            return cached;  // the pending read finishes on its own thread and refreshes the cache
        }
    }
    DriverSettings result = pending.valid() ? pending.get() : readDriverSettings();
    if (!pending.valid()) {
        std::lock_guard<std::mutex> lock(gDriverReadsMutex);
        gDriverCache = result;
        gDriverCached = true;
    }
    waitedMs = (nowSeconds() - start) * 1000.0;
    return result;
}

void dropDriverRead(int64_t playerId) {
    std::lock_guard<std::mutex> lock(gDriverReadsMutex);
    gDriverReads.erase(playerId);
}

void applyAudioOptions(mpv_handle *mpv);  // Phase 9 E1, defined near the end of nuvio_rr
void applyVideoOptions(mpv_handle *mpv);  // video quality, defined near the end of nuvio_rr
inline void onMpvInitialized(const void *owner, mpv_handle *mpv, HWND container) {
    if (!mpv) return;
    applyAudioOptions(mpv);  // Phase 9 E1: for every player, whether refresh-rate matching is on or not
    applyVideoOptions(mpv);  // video quality: scalers over the bridge's own (same rule)
    std::string why;
    double asked = nowSeconds();
    int code = -1;
    try {
        code = upcallFeatureEnabled(why);
    } catch (...) {
        code = -1;
        why = "native-exception";
    }
    double upcallMs = (nowSeconds() - asked) * 1000.0;
    if (code < 0) nlog(strf("enable upcall failed (%s) in %.1f ms: feature off for this player", why.c_str(), upcallMs));
    if (code <= 0) return;
    gFeatureUsed.store(true);
    auto hookAdd = mpvSymbol<mpv_hook_add_fn>("mpv_hook_add");
    int rc = hookAdd ? hookAdd(mpv, 0, "on_preloaded", 0) : -1;
    std::string badgeWhy;
    auto entry = std::make_shared<PlayerEntry>();
    entry->badge = rateBadgeFor(badgeWhy);
    entry->owner = owner;
    entry->container = container;
    entry->mpv = mpv;
    {
        Registry &r = registry();
        std::lock_guard<std::mutex> lock(r.mutex);
        entry->id = r.nextId++;
        if (rc >= 0) r.players.push_back(entry);
    }
    std::string faultText = featureConfig().fault.empty() ? std::string() : " fault=" + featureConfig().fault;
    std::string badgeText = entry->badge ? " badge=on" : badgeWhy.empty() ? " badge=off" : " badge=off (" + badgeWhy + ")";
    nlog(strf("player p%lld created, enabled=%s (upcall %.1f ms), mpv_hook_add(on_preloaded) rc=%d%s%s", (long long)entry->id,
        code == 1 ? "env" : "setting", upcallMs, rc, badgeText.c_str(), faultText.c_str()));
    if (rc >= 0) startDriverRead(entry->id);
}

// From onMpvEvent (the mpv event thread): hand the hook to a worker and return at once.
void onHookEvent(mpv_handle *mpv, mpv_event *event) {
    auto *hook = static_cast<MpvEventHook *>(event->data);
    if (!hook) return;
    auto entry = findPlayer([mpv](const PlayerEntry &p) { return p.mpv == mpv; });
    if (!entry || !hook->name || std::strcmp(hook->name, "on_preloaded") != 0) {
        static auto hookContinue = mpvSymbol<mpv_hook_continue_fn>("mpv_hook_continue");
        if (hookContinue) hookContinue(mpv, hook->id);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(entry->mutex);
        entry->hookPending = true;
        entry->hookId = hook->id;
    }
    try {
        std::thread(runHook, entry, hook->id).detach();
    } catch (...) {
        std::lock_guard<std::mutex> lock(entry->mutex);
        continueHookLocked(*entry, hook->id, "event thread (no worker)");
    }
}

// Hook H5: first thing in shutdown(), while the mpv handle is still valid (P4-12).
inline void onPlayerShutdown(const void *owner) {
    if (!gFeatureUsed.load()) return;
    auto entry = findPlayer([owner](const PlayerEntry &p) { return p.owner == owner; }, true);
    if (!entry) return;
    entry->stopping.store(true);
    std::lock_guard<std::mutex> lock(entry->mutex);
    continueHookLocked(*entry, entry->hookId, "shutdown");
    entry->mpv = nullptr;
    dropDriverRead(entry->id);
    nlog(strf("player p%lld shutdown", (long long)entry->id));
}

std::wstring playerDisplay(int64_t playerId) {
    auto entry = findPlayer([playerId](const PlayerEntry &p) { return p.id == playerId; });
    if (!entry || entry->stopping.load() || !IsWindow(entry->container)) return std::wstring();
    return monitorName(GetAncestor(entry->container, GA_ROOT));
}

// Hook H4: called by drainMpvEvents after every mpv_wait_event() return (event may be MPV_EVENT_NONE).
// From H4 on the player's mpv event thread: refresh the health-check cache once per second while our timing is applied.
void cacheTimingStats(mpv_handle *mpv) {
    thread_local double last = 0.0;
    double now = nowSeconds();
    if (now - last < 1.0) return;
    last = now;
    auto entry = findPlayer([mpv](const PlayerEntry &p) { return p.mpv == mpv; });
    if (!entry || entry->stopping.load() || !entry->timingApplied.load()) return;
    MpvApi &api = mpvApi();
    double nan = std::nan("");
    auto count = [&](const char *name) {
        int64_t v = 0;
        return api.getProperty(mpv, name, MPV_FORMAT_INT64, &v) >= 0 ? (double)v : nan;
    };
    auto real = [&](const char *name) {
        double v = 0.0;
        return api.getProperty(mpv, name, MPV_FORMAT_DOUBLE, &v) >= 0 ? v : nan;
    };
    int idle = 0;
    api.getProperty(mpv, "core-idle", MPV_FORMAT_FLAG, &idle);
    entry->cachedStats[0].store(count("frame-drop-count"));
    entry->cachedStats[1].store(count("mistimed-frame-count"));
    entry->cachedStats[2].store(real("estimated-display-fps"));
    entry->cachedStats[3].store(real("time-pos"));
    entry->cachedStats[4].store(idle ? 1.0 : 0.0);
    entry->statsReady.store(true);
}

inline void onMpvEvent(mpv_handle *mpv, mpv_event *event, HWND hwnd, bool stopping) {
    bool used = gFeatureUsed.load(std::memory_order_relaxed);
    if (used && mpv && event && (int)event->event_id == kMpvEventHook) onHookEvent(mpv, event);
    if (used && mpv && !stopping) cacheTimingStats(mpv);
    if (!measureConfig().enabled || !mpv) return;
    struct Holder {
        MeasureSession session;
        ~Holder() { session.markDead(); }
    };
    thread_local Holder holder;
    holder.session.onEvent(mpv, event, hwnd, stopping);
}

// ---------------------------------------------------------------- JNI helpers
std::wstring fromJava(JNIEnv *env, jstring value) {
    if (!value) return std::wstring();
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) return std::wstring();
    std::wstring result(reinterpret_cast<const wchar_t *>(chars), (size_t)env->GetStringLength(value));
    env->ReleaseStringChars(value, chars);
    return result;
}

jlongArray toJava(JNIEnv *env, const std::vector<int64_t> &values) {
    jlongArray array = env->NewLongArray((jsize)values.size());
    if (array && !values.empty()) {
        env->SetLongArrayRegion(array, 0, (jsize)values.size(), reinterpret_cast<const jlong *>(values.data()));
    }
    return array;
}

// The watcher queries every second: log a failure once per change, then one recovery line (P5-19).
void logQueryChange(const std::wstring &device, bool ok, const std::string &error) {
    static std::mutex mutex;
    static std::map<std::wstring, std::string> failing;
    std::lock_guard<std::mutex> lock(mutex);
    auto it = failing.find(device);
    if (ok) {
        if (it == failing.end()) return;
        failing.erase(it);
        nlog(strf("query %ls ok again", device.c_str()));
    } else if (it == failing.end() || it->second != error) {
        failing[device] = error;
        nlog(strf("query %ls failed: %s (logged once until it changes)", device.c_str(), error.c_str()));
    }
}

// setTiming from Kotlin (P5-6): kind 1 = upstream (put ours back, if any), 2 = display-sync.
bool setTiming(int64_t playerId, int64_t kind, int64_t num, int64_t den) {
    auto entry = findPlayer([playerId](const PlayerEntry &p) { return p.id == playerId; });
    if (!entry || entry->stopping.load()) {
        nlog(strf("timing p%lld: player gone or stopping, no mpv call", (long long)playerId));
        return false;
    }
    std::lock_guard<std::mutex> lock(entry->mutex);
    if (!entry->mpv) return false;
    if (kind == 2) {
        bool ok = applyDisplaySyncLocked(*entry, num, den);
        if (ok) showRateNoteLocked(*entry, (double)num / (double)den, RateNote::Synced, "re-switch");
        return ok;
    }
    if (kind != 1) return false;
    if (!entry->timingApplied) {
        nlog(strf("timing p%lld upstream: nothing of ours applied", (long long)playerId));
        return true;
    }
    bool ok = revertTimingLocked(*entry);
    if (ok) {
        DisplayState now = IsWindow(entry->container) ? queryDisplayByName(monitorName(GetAncestor(entry->container, GA_ROOT))) : DisplayState();
        if (complete(now)) showRateNoteLocked(*entry, (double)now.num / (double)now.den, RateNote::SyncOff, "fallback");
    }
    nlog(strf("timing p%lld upstream: saved values back (video-sync=%s) ok=%d", (long long)playerId,
        entry->savedTiming[0].c_str(), ok ? 1 : 0));
    return ok;
}

// Health-check counters (P5-11): [drops, mistimed, estimated-display-fps, time-pos, idle 0/1, ours 0/1], NaN = n/a.
// Reads only the cache H4 fills (cacheTimingStats): no mpv call and no lock here.
std::vector<double> timingStats(int64_t playerId) {
    auto entry = findPlayer([playerId](const PlayerEntry &p) { return p.id == playerId; });
    if (!entry || entry->stopping.load()) return {};
    bool ours = entry->timingApplied.load();
    if (ours && !entry->statsReady.load()) {  // no sample yet: counters unknown, "idle" so the window waits
        return {std::nan(""), std::nan(""), std::nan(""), std::nan(""), 1.0, 1.0};
    }
    return {entry->cachedStats[0].load(), entry->cachedStats[1].load(), entry->cachedStats[2].load(),
        entry->cachedStats[3].load(), entry->cachedStats[4].load(), ours ? 1.0 : 0.0};
}

// No C++ exception crosses JNI: it becomes a Java exception, which the Kotlin controller maps to
// unexpected-error (P4-7).
void throwJava(JNIEnv *env, const char *what) {
    nlog(strf("jni: exception %s", what));
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, what);
}

// ---------------------------------------------------------------- Phase 9 E1: audio output options
// At H2 (after mpv_initialize, before loadfile) Kotlin's AudioOutputNative.nativeMpvOptions() gives "name=value" lines
// (audio-channels, audio-spdif, audio-exclusive, audio-device); each is set as a property. Any failure leaves mpv's
// defaults, so playback never depends on it. The video quality options (VideoQualityNative) use the same path.
std::string upcallOptionLines(const char *className) {
    JavaVM *vm = javaVm();
    if (!vm) return {};
    JNIEnv *env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return {};
        attached = true;
    }
    std::string result;
    if (env) {
        jclass type = env->FindClass(className);
        if (env->ExceptionCheck()) { env->ExceptionClear(); type = nullptr; }
        jmethodID method = type ? env->GetStaticMethodID(type, "nativeMpvOptions", "()Ljava/lang/String;") : nullptr;
        if (env->ExceptionCheck()) { env->ExceptionClear(); method = nullptr; }
        auto value = method ? static_cast<jstring>(env->CallStaticObjectMethod(type, method)) : nullptr;
        if (env->ExceptionCheck()) { env->ExceptionClear(); value = nullptr; }
        if (value) {
            const char *chars = env->GetStringUTFChars(value, nullptr);
            if (chars) {
                result = chars;
                env->ReleaseStringUTFChars(value, chars);
            }
            env->DeleteLocalRef(value);
        }
        if (type) env->DeleteLocalRef(type);
    }
    if (attached) vm->DetachCurrentThread();
    return result;
}

void applyOptionLines(mpv_handle *mpv, const char *className, const char *what) {
    std::string lines = upcallOptionLines(className);
    size_t start = 0;
    while (start < lines.size()) {
        size_t end = lines.find('\n', start);
        if (end == std::string::npos) end = lines.size();
        std::string line = lines.substr(start, end - start);
        start = end + 1;
        size_t eq = line.find('=');
        if (eq == std::string::npos || eq == 0) continue;
        std::string name = line.substr(0, eq), value = line.substr(eq + 1);
        int rc = mpvApi().setPropertyString(mpv, name.c_str(), value.c_str());
        nlog(strf("%s option %s=%s rc=%d", what, name.c_str(), value.c_str(), rc));
    }
}

void applyAudioOptions(mpv_handle *mpv) {
    applyOptionLines(mpv, "com/nuvio/app/features/player/desktop/audio/AudioOutputNative", "audio");
}

// Video quality (scale / cscale / dscale, SSimDownscaler shader): set after the bridge's own scalers, so they win.
void applyVideoOptions(mpv_handle *mpv) {
    applyOptionLines(mpv, "com/nuvio/app/features/player/desktop/video/VideoQualityNative", "video");
}

// Phase 9 E1 fix: which IEC 61937 bitstreams the output device takes in exclusive mode, as mpv would open them
// (ao_wasapi: AC3/DTS 48 kHz stereo, E-AC3 192 kHz stereo, TrueHD/DTS-HD 192 kHz 7.1, 16 bit). Bits match Kotlin's
// PassthroughCodec: 1 ac3, 2 eac3, 4 dts, 8 dts-hd, 16 truehd. -1 = the device could not be asked. mpv's own
// fallback for a refused bitstream left network streams stuck on the first frame, so we only ask for what works.
int passthroughSupportMask(const std::wstring &mpvDevice) {
    HRESULT init = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    bool uninit = SUCCEEDED(init);
    int mask = -1;
    IMMDeviceEnumerator *enumerator = nullptr;
    IMMDevice *device = nullptr;
    IAudioClient *client = nullptr;
    if (SUCCEEDED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator),
            reinterpret_cast<void **>(&enumerator)))) {
        HRESULT found;
        if (mpvDevice.empty() || mpvDevice == L"auto") {
            found = enumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, &device);
        } else {
            std::wstring id = mpvDevice.rfind(L"wasapi/", 0) == 0 ? mpvDevice.substr(7) : mpvDevice;
            if (!id.empty() && id[0] == L'{' && id.find(L"}.{") == std::wstring::npos) id = L"{0.0.0.00000000}." + id;
            found = enumerator->GetDevice(id.c_str(), &device);
        }
        if (SUCCEEDED(found) && device &&
                SUCCEEDED(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, reinterpret_cast<void **>(&client)))) {
            struct Probe { int bit; GUID subFormat; DWORD rate; WORD channels; };
            const Probe probes[] = {
                {1, {0x00000092, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}}, 48000, 2},   // DOLBY_DIGITAL
                {2, {0x0000000a, 0x0cea, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}}, 192000, 2},  // DOLBY_DIGITAL_PLUS
                {4, {0x00000008, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}}, 48000, 2},   // DTS
                {8, {0x0000000b, 0x0cea, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}}, 192000, 8},  // DTS_HD
                {16, {0x0000000c, 0x0cea, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}}, 192000, 8}, // DOLBY_MLP
            };
            mask = 0;
            for (const Probe &probe : probes) {
                WAVEFORMATEXTENSIBLE format{};
                format.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
                format.Format.nChannels = probe.channels;
                format.Format.nSamplesPerSec = probe.rate;
                format.Format.wBitsPerSample = 16;
                format.Format.nBlockAlign = (WORD)(probe.channels * 2);
                format.Format.nAvgBytesPerSec = probe.rate * format.Format.nBlockAlign;
                format.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
                format.Samples.wValidBitsPerSample = 16;
                format.dwChannelMask = probe.channels == 8 ? 0x63F : 0x3;  // 7.1 surround : stereo
                format.SubFormat = probe.subFormat;
                if (client->IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE, &format.Format, nullptr) == S_OK) mask |= probe.bit;
            }
        }
    }
    if (client) client->Release();
    if (device) device->Release();
    if (enumerator) enumerator->Release();
    if (uninit) CoUninitialize();
    nlog(strf("audio passthrough probe mask=%d", mask));
    return mask;
}

// mpv's audio-device-list from a short-lived, never-playing mpv instance (the settings page has no player).
std::string audioDeviceListJson() {
    MpvApi &api = mpvApi();
    if (!api.create || !api.initialize || !api.terminateDestroy) return "[]";
    mpv_handle *probe = api.create();
    if (!probe) return "[]";
    api.setOptionString(probe, "config", "no");
    api.setOptionString(probe, "vo", "null");
    api.setOptionString(probe, "idle", "yes");
    std::string json = "[]";
    if (api.initialize(probe) >= 0) {
        char *value = nullptr;
        if (api.getProperty(probe, "audio-device-list", MPV_FORMAT_STRING, &value) >= 0 && value) {
            json = value;
            api.freeValue(value);
        }
    }
    api.terminateDestroy(probe);
    return json;
}

}  // namespace nuvio_rr
}  // namespace

// Phase 8 (H14, Q39): the bridge's WebView2 folder follows the fork's app identity, so the packaged "Nuvio RR" never
// writes the official %LOCALAPPDATA%\Nuvio\WebView2 (D6). One rule for all folders: Kotlin ForkIdentity.appDirName.
// Asked once; if the JVM, the class or the call is unavailable, upstream's "Nuvio" stays. Declared at block scope in
// player_bridge.cpp's webViewUserDataDirectory(); MSVC binds that to the global name, so it is defined at file scope.
std::wstring nuvioRrAppDirName() {
    static std::wstring name;
    static std::once_flag once;
    std::call_once(once, []() {
        name = L"Nuvio";
        JavaVM *vm = nuvio_rr::javaVm();
        if (!vm) return;
        JNIEnv *env = nullptr;
        bool attached = false;
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return;
            attached = true;
        }
        if (!env) return;
        jclass type = env->FindClass("com/nuvio/app/fork/ForkIdentity");
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (type) {
            jmethodID get = env->GetStaticMethodID(type, "getAppDirName", "()Ljava/lang/String;");
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (get) {
                auto value = static_cast<jstring>(env->CallStaticObjectMethod(type, get));
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                } else if (value) {
                    const jchar *chars = env->GetStringChars(value, nullptr);
                    jsize length = env->GetStringLength(value);
                    if (chars && length > 0) name.assign(reinterpret_cast<const wchar_t *>(chars), (size_t)length);
                    if (chars) env->ReleaseStringChars(value, chars);
                }
                if (value) env->DeleteLocalRef(value);
            }
            env->DeleteLocalRef(type);
        }
        if (attached) vm->DetachCurrentThread();
    });
    return name;
}

// ---------------------------------------------------------------- JNI exports (Kotlin NativeDisplayPort)
extern "C" {

JNIEXPORT jlongArray JNICALL Java_com_nuvio_app_features_player_desktop_refreshrate_runtime_NativeDisplayPort_nativeQuery(
    JNIEnv *env, jclass, jstring display) {
    try {
        if (nuvio_rr::fault("display-not-found")) {
            nuvio_rr::nlog("query: injected fault display-not-found");
            return nullptr;
        }
        std::wstring device = nuvio_rr::fromJava(env, display);
        nuvio_rr::DisplayState state = nuvio_rr::queryDisplayByName(device);
        bool ok = nuvio_rr::complete(state);
        nuvio_rr::logQueryChange(device, ok, state.error);
        if (!ok) return nullptr;
        return nuvio_rr::toJava(env, nuvio_rr::stateValues(state));
    } catch (const std::exception &e) {
        nuvio_rr::throwJava(env, e.what());
    } catch (...) {
        nuvio_rr::throwJava(env, "unknown native error in nativeQuery");
    }
    return nullptr;
}

JNIEXPORT jlongArray JNICALL Java_com_nuvio_app_features_player_desktop_refreshrate_runtime_NativeDisplayPort_nativeModes(
    JNIEnv *env, jclass, jstring display) {
    try {
        if (nuvio_rr::fault("enumerate")) {
            nuvio_rr::nlog("modes: injected fault enumerate");
            return nullptr;
        }
        std::wstring device = nuvio_rr::fromJava(env, display);
        nuvio_rr::DisplayState state = nuvio_rr::queryDisplayByName(device);
        std::vector<int64_t> modes;
        if (!nuvio_rr::complete(state)) {
            nuvio_rr::nlog(nuvio_rr::strf("modes %ls: current state unreadable: %s", device.c_str(), state.error.c_str()));
            return nullptr;
        }
        if (!nuvio_rr::enumerateModes(device, state, modes)) return nullptr;
        return nuvio_rr::toJava(env, modes);
    } catch (const std::exception &e) {
        nuvio_rr::throwJava(env, e.what());
    } catch (...) {
        nuvio_rr::throwJava(env, "unknown native error in nativeModes");
    }
    return nullptr;
}

JNIEXPORT jlongArray JNICALL Java_com_nuvio_app_features_player_desktop_refreshrate_runtime_NativeDisplayPort_nativeSwitch(
    JNIEnv *env, jclass, jstring display, jint width, jint height, jlong numerator, jlong denominator, jlong playerId) {
    try {
        std::vector<int64_t> result = nuvio_rr::switchMode(nuvio_rr::fromJava(env, display), width, height, numerator,
            denominator, playerId);
        // After a real switch, so the restore-after-exception path is exercised (P4-19).
        if (nuvio_rr::fault("unexpected")) throw std::runtime_error("injected fault unexpected (after the switch)");
        return nuvio_rr::toJava(env, result);
    } catch (const std::exception &e) {
        nuvio_rr::throwJava(env, e.what());
    } catch (...) {
        nuvio_rr::throwJava(env, "unknown native error in nativeSwitch");
    }
    return nullptr;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_refreshrate_runtime_NativeDisplayPort_nativeRestore(
    JNIEnv *env, jclass, jstring display) {
    try {
        return nuvio_rr::restoreMode(nuvio_rr::fromJava(env, display)) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception &e) {
        nuvio_rr::throwJava(env, e.what());
    } catch (...) {
        nuvio_rr::throwJava(env, "unknown native error in nativeRestore");
    }
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_player_desktop_refreshrate_runtime_NativeDisplayPort_nativePlayerDisplay(
    JNIEnv *env, jclass, jlong playerId) {
    try {
        std::wstring device = nuvio_rr::playerDisplay(playerId);
        if (device.empty()) return nullptr;
        return env->NewString(reinterpret_cast<const jchar *>(device.c_str()), (jsize)device.size());
    } catch (const std::exception &e) {
        nuvio_rr::throwJava(env, e.what());
    } catch (...) {
        nuvio_rr::throwJava(env, "unknown native error in nativePlayerDisplay");
    }
    return nullptr;
}

JNIEXPORT jboolean JNICALL Java_com_nuvio_app_features_player_desktop_refreshrate_runtime_NativeDisplayPort_nativeSetTiming(
    JNIEnv *env, jclass, jlong playerId, jlong kind, jlong numerator, jlong denominator) {
    try {
        return nuvio_rr::setTiming(playerId, kind, numerator, denominator) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception &e) {
        nuvio_rr::throwJava(env, e.what());
    } catch (...) {
        nuvio_rr::throwJava(env, "unknown native error in nativeSetTiming");
    }
    return JNI_FALSE;
}

JNIEXPORT jdoubleArray JNICALL Java_com_nuvio_app_features_player_desktop_refreshrate_runtime_NativeDisplayPort_nativeTimingStats(
    JNIEnv *env, jclass, jlong playerId) {
    try {
        std::vector<double> values = nuvio_rr::timingStats(playerId);
        if (values.empty()) return nullptr;
        jdoubleArray array = env->NewDoubleArray((jsize)values.size());
        if (array) env->SetDoubleArrayRegion(array, 0, (jsize)values.size(), values.data());
        return array;
    } catch (const std::exception &e) {
        nuvio_rr::throwJava(env, e.what());
    } catch (...) {
        nuvio_rr::throwJava(env, "unknown native error in nativeTimingStats");
    }
    return nullptr;
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_player_desktop_refreshrate_runtime_NativeDisplayPort_nativeLog(
    JNIEnv *env, jclass, jstring line) {
    if (!line) return;
    const char *chars = env->GetStringUTFChars(line, nullptr);
    if (!chars) return;
    try {
        nuvio_rr::rrLog(std::string("K ") + chars);
    } catch (...) {
    }
    env->ReleaseStringUTFChars(line, chars);
}

// Phase 9 E1 fix (Kotlin AudioOutputNative)
JNIEXPORT jint JNICALL Java_com_nuvio_app_features_player_desktop_audio_AudioOutputNative_nativePassthroughMask(
    JNIEnv *env, jclass, jstring device) {
    try {
        std::wstring name;
        if (device) {
            const jchar *chars = env->GetStringChars(device, nullptr);
            if (chars) {
                name.assign(reinterpret_cast<const wchar_t *>(chars), (size_t)env->GetStringLength(device));
                env->ReleaseStringChars(device, chars);
            }
        }
        return (jint)nuvio_rr::passthroughSupportMask(name);
    } catch (...) {
        return -1;
    }
}

// Phase 9 E1 (Kotlin AudioOutputNative)
JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_player_desktop_audio_AudioOutputNative_nativeDeviceListJson(
    JNIEnv *env, jclass) {
    try {
        std::string json = nuvio_rr::audioDeviceListJson();
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception &e) {
        nuvio_rr::throwJava(env, e.what());
    } catch (...) {
        nuvio_rr::throwJava(env, "unknown native error in nativeDeviceListJson");
    }
    return nullptr;
}

}  // extern "C"

namespace {  // player_bridge.cpp continues inside its anonymous namespace
