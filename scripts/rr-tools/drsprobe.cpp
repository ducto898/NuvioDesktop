// drsprobe.exe [app.exe ...] — READ-ONLY NVIDIA driver-profile (DRS) probe. Prints frame-rate-limiter and
// related settings from the global (base) profile and from the profile of each named application.
// Calls only NvAPI_Initialize, DRS_CreateSession/LoadSettings/GetBaseProfile/FindApplicationByName/GetSetting,
// DestroySession, Unload. Never SetSetting/SaveSettings. IDs from NVIDIA/nvapi (nvapi_interface.h,
// NvApiDriverSettings.h). Phase 2b: find the 200 fps cap inside Present.
#include <windows.h>
#include <cstdio>
#include <cstring>

typedef void *(__cdecl *QueryInterface_t)(unsigned int);
typedef int (__cdecl *Init_t)();
typedef int (__cdecl *Unload_t)();
typedef int (__cdecl *CreateSession_t)(void **);
typedef int (__cdecl *Session_t)(void *);
typedef int (__cdecl *GetBase_t)(void *, void **);
typedef int (__cdecl *FindApp_t)(void *, const wchar_t *, void **, void *);
typedef int (__cdecl *GetSetting_t)(void *, void *, unsigned int, void *);
typedef int (__cdecl *GetProfileInfo_t)(void *, void *, void *);

#pragma pack(push, 8)
struct DrsSetting {           // NVDRS_SETTING_V1, 0x3020 bytes
    unsigned int version;
    wchar_t settingName[2048];
    unsigned int settingId, settingType, settingLocation, isCurrentPredefined, isPredefinedValid;
    union { unsigned int u32; unsigned char raw[4100]; } predefined;
    union { unsigned int u32; unsigned char raw[4100]; } current;
};
struct DrsApplication {       // NVDRS_APPLICATION_V1
    unsigned int version, isPredefined;
    wchar_t appName[2048], userFriendlyName[2048], launcher[2048];
};
struct DrsProfile {           // NVDRS_PROFILE_V1
    unsigned int version;
    wchar_t profileName[2048];
    unsigned int gpuSupport, isPredefined, numOfApps, numOfSettings;
};
#pragma pack(pop)

struct Known { unsigned int id; const char *name; };
static const Known kSettings[] = {
    {0x10835002, "FRL_FPS (Max Frame Rate)"},
    {0x10834FEE, "PS_FRAMERATE_LIMITER (legacy FRL)"},
    {0x10835005, "FRL_FPS_BATTERY? (0x10835005)"},
    {0x10835000, "BATTERY_BOOST_APP_FPS"},
    {0x1094F157, "VRR_APP_OVERRIDE"},
    {0x1194F158, "VRR_MODE"},
    {0x1094F1F7, "VRRREQUESTSTATE"},
    {0x00A879CF, "VSYNCMODE"},
    {0x107D639D, "PRERENDERLIMIT (max pre-rendered frames)"},
    {0x10E41DF3, "LOW_LATENCY / ULTRA_LOW_LATENCY? (0x10E41DF3)"},
    {0x0005F543, "LOW_LATENCY_MODE? (0x0005F543)"},
    {0x1057EB71, "PREFERRED_PSTATE (power management mode)"},
    {0x11E91A61, "VSYNC_BEHAVIOR_FLAGS? (0x11E91A61)"},
    {0x20C1221E, "OGL_TRIPLE_BUFFER / vsync tear control? (0x20C1221E)"},
};

static void dump(void *session, void *profile, GetSetting_t getSetting, const char *label) {
    std::printf("[%s]\n", label);
    for (const Known &k : kSettings) {
        DrsSetting s;
        std::memset(&s, 0, sizeof(s));
        s.version = (unsigned int)sizeof(DrsSetting) | (1u << 16);
        int rc = getSetting(session, profile, k.id, &s);
        if (rc == 0) {
            std::printf("  0x%08X %-44s = %u (0x%X)  location=%u predefined=%u\n", k.id, k.name, s.current.u32, s.current.u32,
                s.settingLocation, s.isCurrentPredefined);
        } else {
            std::printf("  0x%08X %-44s : not set (rc=%d)\n", k.id, k.name, rc);
        }
    }
}

int wmain(int argc, wchar_t **argv) {
    HMODULE lib = LoadLibraryW(L"nvapi64.dll");
    if (!lib) { std::printf("nvapi64.dll not found\n"); return 1; }
    auto qi = (QueryInterface_t)GetProcAddress(lib, "nvapi_QueryInterface");
    if (!qi) { std::printf("nvapi_QueryInterface missing\n"); return 1; }
    auto init = (Init_t)qi(0x0150E828);
    auto unload = (Unload_t)qi(0xD22BDD7E);
    auto create = (CreateSession_t)qi(0x0694D52E);
    auto destroy = (Session_t)qi(0xDAD9CFF8);
    auto load = (Session_t)qi(0x375DBD6B);
    auto getBase = (GetBase_t)qi(0xDA8466A0);
    auto findApp = (FindApp_t)qi(0xEEE566B2);
    auto getSetting = (GetSetting_t)qi(0x73BF8338);
    auto getProfileInfo = (GetProfileInfo_t)qi(0x61CD6FD6);
    if (!init || !create || !destroy || !load || !getBase || !findApp || !getSetting) { std::printf("NVAPI entry missing\n"); return 1; }
    if (init() != 0) { std::printf("NvAPI_Initialize failed\n"); return 1; }
    void *session = nullptr;
    if (create(&session) != 0 || load(session) != 0) { std::printf("DRS session/load failed\n"); return 1; }
    void *base = nullptr;
    if (getBase(session, &base) == 0) dump(session, base, getSetting, "global (base) profile");
    for (int i = 1; i < argc; ++i) {
        void *profile = nullptr;
        DrsApplication app;
        std::memset(&app, 0, sizeof(app));
        app.version = (unsigned int)sizeof(DrsApplication) | (1u << 16);
        int rc = findApp(session, argv[i], &profile, &app);
        if (rc != 0) { std::printf("[%ls] no application profile (rc=%d)\n", argv[i], rc); continue; }
        char label[600] = {};
        if (getProfileInfo) {
            DrsProfile info;
            std::memset(&info, 0, sizeof(info));
            info.version = (unsigned int)sizeof(DrsProfile) | (1u << 16);
            if (getProfileInfo(session, profile, &info) == 0) {
                std::snprintf(label, sizeof(label), "%ls -> profile \"%ls\" (apps %u, settings %u)", argv[i], info.profileName, info.numOfApps, info.numOfSettings);
            }
        }
        if (!label[0]) std::snprintf(label, sizeof(label), "%ls", argv[i]);
        dump(session, profile, getSetting, label);
    }
    destroy(session);
    if (unload) unload();
    return 0;
}
