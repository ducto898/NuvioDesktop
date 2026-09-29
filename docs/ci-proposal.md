# CI proposal for the fork (SPEC P8-14) — not installed

Nothing is pushed (owner Q2), so no workflow runs today. If the fork is ever pushed, this is the job to add as
`.github/workflows/fork-verify.yml`. It mirrors `scripts/verify.ps1 -Full` on a clean runner; the hardware checks
(FORK.md §6) stay manual because they need the owner's monitor and GPU.

```yaml
name: fork-verify
on:
  push:
    branches: [feature/refresh-rate-matching]
  pull_request:
jobs:
  verify:
    runs-on: windows-latest
    timeout-minutes: 40
    steps:
      - uses: actions/checkout@v4
        with:
          lfs: true            # libmpv-2.dll (~115 MB) is in Git LFS
          fetch-depth: 0       # verify.ps1 needs the merge-base with upstream/Dev
      - name: Add upstream
        run: |
          git remote add upstream https://github.com/NuvioMedia/NuvioDesktop.git
          git fetch upstream Dev
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: microsoft/setup-msbuild@v2   # MSVC for the native bridge
      - name: local.properties (no keys needed)
        run: New-Item -ItemType File local.properties -Force | Out-Null
      - name: verify.ps1 -Full
        shell: pwsh
        run: pwsh -File scripts/verify.ps1 -Full
      - name: Package (portable app folder)
        shell: pwsh
        run: pwsh -File scripts/package-fork.ps1
      - uses: actions/upload-artifact@v4
        with:
          name: nuvio-rr
          path: ../dist/*.zip
```

What it checks: clean build of the native bridge and the app, the full desktop test suite (only the known upstream
failures in `scripts/known-upstream-test-failures.txt` may fail), the upstream hook budget (every added upstream line
tagged `nuvio-rr fork hook Hn`; code 11, strings 3, Gradle 6), no write to an official Nuvio profile folder, and that
the packaged fork carries `-Dnuvio.fork.name` and the bundled player DLLs.

Open points before enabling it: the WebView2 SDK path the bridge build expects (`-Pnuvio.webview2.dir`, as upstream's
`desktop-release.yml` does), and whether the runner's MSVC version builds the bridge unchanged (the fork was built with
VS 2026 / MSVC 14.51).
