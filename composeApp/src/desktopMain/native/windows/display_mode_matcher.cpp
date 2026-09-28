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
};

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
        std::wstring sync = envValue(L"NUVIO_RR_MEASURE_SYNC");
        for (wchar_t ch : sync) config.sync.push_back(ch < 0x80 ? (char)ch : '?');  // mpv option values are ASCII
        config.switchHz = _wtoi(envValue(L"NUVIO_RR_MEASURE_SWITCH_HZ").c_str());
        config.ipc = envValue(L"NUVIO_RR_MEASURE_IPC") == L"1";
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
};

DisplayState queryDisplay(HWND hwnd) {
    DisplayState state;
    HMONITOR monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
    MONITORINFOEXW info = {};
    info.cbSize = sizeof(info);
    if (!monitor || !GetMonitorInfoW(monitor, &info)) return state;
    state.gdiName = info.szDevice;

    DEVMODEW dm = {};
    dm.dmSize = sizeof(dm);
    if (EnumDisplaySettingsW(info.szDevice, ENUM_CURRENT_SETTINGS, &dm)) {
        state.currentHz = dm.dmDisplayFrequency;
        state.width = (int)dm.dmPelsWidth;
        state.height = (int)dm.dmPelsHeight;
    }
    DEVMODEW reg = {};
    reg.dmSize = sizeof(reg);
    if (EnumDisplaySettingsW(info.szDevice, ENUM_REGISTRY_SETTINGS, &reg)) state.registryHz = reg.dmDisplayFrequency;

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
        if (_wcsicmp(source.viewGdiDeviceName, info.szDevice) != 0) continue;

        UINT32 modeIndex = path.targetInfo.modeInfoIdx;
        if (modeIndex != DISPLAYCONFIG_PATH_MODE_IDX_INVALID && modeIndex < modeCount &&
            modes[modeIndex].infoType == DISPLAYCONFIG_MODE_INFO_TYPE_TARGET) {
            const auto &vsync = modes[modeIndex].targetMode.targetVideoSignalInfo.vSyncFreq;
            state.num = vsync.Numerator;
            state.den = vsync.Denominator;
        } else {
            state.num = path.targetInfo.refreshRate.Numerator;
            state.den = path.targetInfo.refreshRate.Denominator;
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
            " switch_knob=" + std::to_string(config.switchHz));
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

// Hook H4: called by drainMpvEvents after every mpv_wait_event() return (event may be MPV_EVENT_NONE).
inline void onMpvEvent(mpv_handle *mpv, mpv_event *event, HWND hwnd, bool stopping) {
    if (!measureConfig().enabled || !mpv) return;
    struct Holder {
        MeasureSession session;
        ~Holder() { session.markDead(); }
    };
    thread_local Holder holder;
    holder.session.onEvent(mpv, event, hwnd, stopping);
}

}  // namespace nuvio_rr
