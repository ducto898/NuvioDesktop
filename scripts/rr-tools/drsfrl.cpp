// drsfrl.exe backup <file> | create | delete — owner-approved (2026-09-28, Q9) Phase 2b experiment tool.
//   backup <file> : NvAPI_DRS_SaveSettingsToFile (whole driver-profile database, for recovery)
//   create        : new profile "NuvioRR FRL test" with application java.exe and FRL_FPS (Max Frame Rate) = 0 (off)
//   delete        : delete that profile (only if it has exactly that name), save
// Never touches the global (base) profile or any other profile. IDs from NVIDIA/nvapi (nvapi_interface.h).
#include <windows.h>
#include <cstdio>
#include <cstring>

typedef void *(__cdecl *QI_t)(unsigned int);
typedef int (__cdecl *Fn0)();
typedef int (__cdecl *FnS)(void *);
typedef int (__cdecl *FnSP)(void **);
typedef int (__cdecl *FnFile)(void *, const wchar_t *);
typedef int (__cdecl *FnCreateProfile)(void *, void *, void **);
typedef int (__cdecl *FnCreateApp)(void *, void *, void *);
typedef int (__cdecl *FnSetSetting)(void *, void *, void *);
typedef int (__cdecl *FnFindByName)(void *, const wchar_t *, void **);
typedef int (__cdecl *FnDelete)(void *, void *);

#pragma pack(push, 8)
struct DrsSetting {
    unsigned int version;
    wchar_t settingName[2048];
    unsigned int settingId, settingType, settingLocation, isCurrentPredefined, isPredefinedValid;
    union { unsigned int u32; unsigned char raw[4100]; } predefined;
    union { unsigned int u32; unsigned char raw[4100]; } current;
};
struct DrsApplication {
    unsigned int version, isPredefined;
    wchar_t appName[2048], userFriendlyName[2048], launcher[2048];
};
struct DrsProfile {
    unsigned int version;
    wchar_t profileName[2048];
    unsigned int gpuSupport, isPredefined, numOfApps, numOfSettings;
};
#pragma pack(pop)

static const wchar_t *kProfile = L"NuvioRR FRL test";
static const unsigned int kFrlFps = 0x10835002;

int wmain(int argc, wchar_t **argv) {
    if (argc < 2) { std::printf("usage: drsfrl backup <file> | create | delete\n"); return 2; }
    HMODULE lib = LoadLibraryW(L"nvapi64.dll");
    if (!lib) { std::printf("nvapi64.dll not found\n"); return 1; }
    auto qi = (QI_t)GetProcAddress(lib, "nvapi_QueryInterface");
    auto init = (Fn0)qi(0x0150E828);
    auto createSession = (FnSP)qi(0x0694D52E);
    auto destroySession = (FnS)qi(0xDAD9CFF8);
    auto load = (FnS)qi(0x375DBD6B);
    auto save = (FnS)qi(0xFCBC7E14);
    auto saveToFile = (FnFile)qi(0x2BE25DF8);
    auto createProfile = (FnCreateProfile)qi(0xCC176068);
    auto createApp = (FnCreateApp)qi(0x4347A9DE);
    auto setSetting = (FnSetSetting)qi(0x577DD202);
    auto findByName = (FnFindByName)qi(0x7E4A9A0B);
    auto deleteProfile = (FnDelete)qi(0x17093206);
    if (!init || init() != 0) { std::printf("NvAPI_Initialize failed\n"); return 1; }
    void *s = nullptr;
    if (createSession(&s) != 0 || load(s) != 0) { std::printf("DRS session/load failed\n"); return 1; }
    int rc = 0;
    if (!wcscmp(argv[1], L"backup") && argc >= 3) {
        rc = saveToFile(s, argv[2]);
        std::printf("SaveSettingsToFile(%ls) rc=%d\n", argv[2], rc);
    } else if (!wcscmp(argv[1], L"create")) {
        void *existing = nullptr;
        if (findByName(s, kProfile, &existing) == 0) { std::printf("profile already exists\n"); destroySession(s); return 1; }
        DrsProfile p; std::memset(&p, 0, sizeof(p));
        p.version = (unsigned int)sizeof(DrsProfile) | (1u << 16);
        wcscpy_s(p.profileName, kProfile);
        void *h = nullptr;
        rc = createProfile(s, &p, &h);
        std::printf("CreateProfile rc=%d\n", rc);
        if (rc == 0) {
            DrsApplication a; std::memset(&a, 0, sizeof(a));
            a.version = (unsigned int)sizeof(DrsApplication) | (1u << 16);
            wcscpy_s(a.appName, L"java.exe");
            rc = createApp(s, h, &a);
            std::printf("CreateApplication(java.exe) rc=%d\n", rc);
        }
        if (rc == 0) {
            DrsSetting st; std::memset(&st, 0, sizeof(st));
            st.version = (unsigned int)sizeof(DrsSetting) | (1u << 16);
            st.settingId = kFrlFps; st.settingType = 0; st.current.u32 = 0;
            rc = setSetting(s, h, &st);
            std::printf("SetSetting(FRL_FPS=0) rc=%d\n", rc);
        }
        if (rc == 0) { rc = save(s); std::printf("SaveSettings rc=%d\n", rc); }
    } else if (!wcscmp(argv[1], L"delete")) {
        void *h = nullptr;
        rc = findByName(s, kProfile, &h);
        if (rc != 0) { std::printf("profile not found (rc=%d)\n", rc); destroySession(s); return 1; }
        rc = deleteProfile(s, h);
        std::printf("DeleteProfile rc=%d\n", rc);
        if (rc == 0) { rc = save(s); std::printf("SaveSettings rc=%d\n", rc); }
    } else { std::printf("unknown command\n"); rc = 2; }
    destroySession(s);
    return rc == 0 ? 0 : 1;
}
