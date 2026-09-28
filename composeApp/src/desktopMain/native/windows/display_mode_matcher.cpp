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
// Phase 4 contents: the feature itself (section "Phase 4" below), off unless NUVIO_RR_ENABLE=1.

// player_bridge.cpp includes this file inside its anonymous namespace. System headers and the JNI
// exports need global scope, so that namespace is closed around them and reopened (here and at the end).
}  // namespace
#include <dxgi1_2.h>
#include <cstdarg>
#include <cstring>
#pragma comment(lib, "dxgi.lib")
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
            if (!local.empty()) config.directory = local + L"\\Nuvio\\Cache\\nuvio-rr";
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
    if (device.empty()) return state;
    state.gdiName = device;

    DEVMODEW dm = {};
    dm.dmSize = sizeof(dm);
    if (EnumDisplaySettingsW(device.c_str(), ENUM_CURRENT_SETTINGS, &dm)) {
        state.currentHz = dm.dmDisplayFrequency;
        state.width = (int)dm.dmPelsWidth;
        state.height = (int)dm.dmPelsHeight;
    }
    DEVMODEW reg = {};
    reg.dmSize = sizeof(reg);
    if (EnumDisplaySettingsW(device.c_str(), ENUM_REGISTRY_SETTINGS, &reg)) state.registryHz = reg.dmDisplayFrequency;

    UINT32 pathCount = 0, modeCount = 0;
    if (GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, &pathCount, &modeCount) != ERROR_SUCCESS) return state;
    std::vector<DISPLAYCONFIG_PATH_INFO> paths(pathCount);
    std::vector<DISPLAYCONFIG_MODE_INFO> modes(modeCount);
    if (QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, &pathCount, paths.data(), &modeCount, modes.data(), nullptr) != ERROR_SUCCESS) {
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
        if (DisplayConfigGetDeviceInfo(&color.header) == ERROR_SUCCESS) {
            state.hdr = color.advancedColorEnabled ? 1 : 0;
            state.bpc = (int)color.bitsPerColorChannel;
        }
        state.ok = true;
        break;
    }
    return state;
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
// Off unless the process starts with NUVIO_RR_ENABLE=1 (dev knob until the Phase 6 setting, Q14).
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
    bool enabled = false;
    std::string fault;     // NUVIO_RR_FAULT, honoured only in measure runs (NUVIO_RR_MEASURE=1, Q16)
    std::wstring logPath;  // run folder in measure runs, else %LOCALAPPDATA%\Nuvio\Cache
};

const FeatureConfig &featureConfig() {
    static FeatureConfig config;
    static std::once_flag once;
    std::call_once(once, []() {
        if (envValue(L"NUVIO_RR_ENABLE") != L"1") return;
        config.enabled = true;
        const MeasureConfig &measure = measureConfig();
        if (measure.enabled) config.fault = asciiValue(envValue(L"NUVIO_RR_FAULT"));
        std::wstring dir = measure.enabled ? envValue(L"NUVIO_RR_MEASURE_DIR") : std::wstring();
        if (dir.empty()) {
            std::wstring local = envValue(L"LOCALAPPDATA");
            if (!local.empty()) dir = local + L"\\Nuvio\\Cache";
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

// The one writer of refresh-rate.log: native lines ("N") and Kotlin lines ("K", via nativeLog).
void rrLog(const std::string &line) {
    const FeatureConfig &config = featureConfig();
    if (!config.enabled) return;
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
            for (const DXGI_MODE_DESC1 &m : modes) {
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
            return true;
        }
    }
    nlog(strf("modes: no DXGI output for %ls", device.c_str()));
    return false;
}

bool sameRate(UINT32 num, UINT32 den, int64_t targetNum, int64_t targetDen) {
    if (!num || !den || targetNum <= 0 || targetDen <= 0) return false;
    double a = (double)num / (double)den, b = (double)targetNum / (double)targetDen;
    return std::fabs(a / b - 1.0) <= 1e-6;
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
        if (now.ok) last = now;
        if (now.ok && !fault("settle-timeout") && sameRate(now.num, now.den, num, den) && sameState(previous, now)) {
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

std::string upcallStart(int64_t playerId, const std::wstring &display, const StartProps &props) {
    JavaVM *vm = javaVm();
    if (!vm) return "no-jvm";
    JNIEnv *env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return "attach-failed";
        attached = true;
    }
    std::string result;
    // Found through the system class loader, which is the app's loader in dev runs and in the packaged app.
    static jclass matchClass = nullptr;
    static jmethodID startMethod = nullptr;
    static std::mutex lookupMutex;
    {
        std::lock_guard<std::mutex> lock(lookupMutex);
        if (!matchClass) {
            jclass local = env->FindClass("com/nuvio/app/features/player/desktop/refreshrate/runtime/RefreshRateMatch");
            if (local) {
                startMethod = env->GetStaticMethodID(local, "nativePlaybackStart", "(JLjava/lang/String;DDZ)[J");
                if (startMethod) matchClass = static_cast<jclass>(env->NewGlobalRef(local));
                env->DeleteLocalRef(local);
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }
    if (!matchClass || !startMethod) {
        result = "class-not-found";
    } else {
        jstring jdisplay = env->NewString(reinterpret_cast<const jchar *>(display.c_str()), (jsize)display.size());
        auto timing = static_cast<jlongArray>(env->CallStaticObjectMethod(matchClass, startMethod, (jlong)playerId, jdisplay,
            (jdouble)props.containerFps, (jdouble)props.estimatedFps, (jboolean)(props.isImage ? JNI_TRUE : JNI_FALSE)));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            result = "exception";
        } else if (timing && env->GetArrayLength(timing) == 3) {
            jlong t[3] = {};
            env->GetLongArrayRegion(timing, 0, 3, t);
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

// Worker for one on_preloaded hook. Phase 4 logs the timing only; Phase 5 applies it (P4-11).
void runHook(std::shared_ptr<PlayerEntry> entry, uint64_t hookId) {
    double start = nowSeconds();
    std::string timing = "none";
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
            std::wstring display = monitorName(GetAncestor(entry->container, GA_ROOT));
            nlog(strf("hook p%lld on_preloaded %s display=%ls (read in %.0f ms)", (long long)entry->id, props.text.c_str(),
                display.c_str(), (nowSeconds() - start) * 1000.0));
            timing = upcallStart(entry->id, display, props);
        }
    } catch (...) {
        timing = "unexpected-error";
    }
    std::lock_guard<std::mutex> lock(entry->mutex);
    nlog(strf("hook p%lld done after %.0f ms timing=%s (logged only in Phase 4)", (long long)entry->id,
        (nowSeconds() - start) * 1000.0, timing.c_str()));
    continueHookLocked(*entry, hookId, "worker");
}

// Hook H2: after mpv_initialize, before loadfile, so the first file's hook cannot be missed (P4-9).
inline void onMpvInitialized(const void *owner, mpv_handle *mpv, HWND container) {
    if (!featureConfig().enabled || !mpv) return;
    auto hookAdd = mpvSymbol<mpv_hook_add_fn>("mpv_hook_add");
    int rc = hookAdd ? hookAdd(mpv, 0, "on_preloaded", 0) : -1;
    auto entry = std::make_shared<PlayerEntry>();
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
    nlog(strf("player p%lld created, mpv_hook_add(on_preloaded) rc=%d%s", (long long)entry->id, rc, faultText.c_str()));
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
    if (!featureConfig().enabled) return;
    auto entry = findPlayer([owner](const PlayerEntry &p) { return p.owner == owner; }, true);
    if (!entry) return;
    entry->stopping.store(true);
    std::lock_guard<std::mutex> lock(entry->mutex);
    continueHookLocked(*entry, entry->hookId, "shutdown");
    entry->mpv = nullptr;
    nlog(strf("player p%lld shutdown", (long long)entry->id));
}

std::wstring playerDisplay(int64_t playerId) {
    auto entry = findPlayer([playerId](const PlayerEntry &p) { return p.id == playerId; });
    if (!entry || entry->stopping.load() || !IsWindow(entry->container)) return std::wstring();
    return monitorName(GetAncestor(entry->container, GA_ROOT));
}

// Hook H4: called by drainMpvEvents after every mpv_wait_event() return (event may be MPV_EVENT_NONE).
inline void onMpvEvent(mpv_handle *mpv, mpv_event *event, HWND hwnd, bool stopping) {
    if (featureConfig().enabled && mpv && event && (int)event->event_id == kMpvEventHook) onHookEvent(mpv, event);
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

// No C++ exception crosses JNI: it becomes a Java exception, which the Kotlin controller maps to
// unexpected-error (P4-7).
void throwJava(JNIEnv *env, const char *what) {
    nlog(strf("jni: exception %s", what));
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, what);
}

}  // namespace nuvio_rr
}  // namespace

// ---------------------------------------------------------------- JNI exports (Kotlin NativeDisplayPort)
extern "C" {

JNIEXPORT jlongArray JNICALL Java_com_nuvio_app_features_player_desktop_refreshrate_runtime_NativeDisplayPort_nativeQuery(
    JNIEnv *env, jclass, jstring display) {
    try {
        if (nuvio_rr::fault("display-not-found")) {
            nuvio_rr::nlog("query: injected fault display-not-found");
            return nullptr;
        }
        nuvio_rr::DisplayState state = nuvio_rr::queryDisplayByName(nuvio_rr::fromJava(env, display));
        return state.ok ? nuvio_rr::toJava(env, nuvio_rr::stateValues(state)) : nullptr;
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
        if (!state.ok || !nuvio_rr::enumerateModes(device, state, modes)) return nullptr;
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

}  // extern "C"

namespace {  // player_bridge.cpp continues inside its anonymous namespace
