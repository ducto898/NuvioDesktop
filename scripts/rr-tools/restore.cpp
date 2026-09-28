// restore.exe [--sdc] — emergency restore of the registry (saved) display modes, all monitors.
//   default: ChangeDisplaySettingsExW(NULL, NULL, NULL, 0, NULL)
//   --sdc  : SetDisplayConfig(SDC_APPLY | SDC_USE_DATABASE_CURRENT) as well
// Neither call writes the registry; both re-apply what is already saved there.
#include "rr_common.h"

int wmain(int argc, wchar_t **argv) {
    std::wstring device = primaryDevice();
    printState(L"before  ", queryDisplay(device));
    LONG r = ChangeDisplaySettingsExW(nullptr, nullptr, nullptr, 0, nullptr);
    wprintf(L"%s ChangeDisplaySettingsExW(NULL) = %ld\n", wallClock().c_str(), r);
    if (argc > 1 && std::wstring(argv[1]) == L"--sdc") {
        LONG s = SetDisplayConfig(0, nullptr, 0, nullptr, SDC_APPLY | SDC_USE_DATABASE_CURRENT);
        wprintf(L"%s SetDisplayConfig(USE_DATABASE_CURRENT) = %ld\n", wallClock().c_str(), s);
    }
    Sleep(1500);
    printState(L"after   ", queryDisplay(device));
    return r == DISP_CHANGE_SUCCESSFUL ? 0 : 1;
}
