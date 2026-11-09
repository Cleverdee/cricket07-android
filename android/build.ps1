<#
Builds the native Android APK (arm64) from the game exe you put in game\:
  1. fetches the build tools into third_party\tools (first run only: Python packages, JDK 21,
     Ghidra 12, Android SDK + NDK r27; about 4 GB)
  2. analyses it with Ghidra and translates it to C (translator\x86toc.py -> out_all\); both are
     cached by the exe's hash, so later builds skip them
  3. builds the APK with Gradle + CMake -> android\output\Cricket07.apk
  4. optionally installs it and copies the game folder to the phone over adb

Options (Build.bat passes them through):
  -Install        install the APK on the phone connected over USB
  -PushGame       also copy game\ to the phone (Android/data/<package>/files/game)
  -Bundle         put the game files inside the APK (about 1.1 GB); the app unpacks them on first launch.
  -Rosters        with -Bundle: use the 2026 rosters (a patched copy of Data\data.gob; your game isn't changed)
  -GameDir <dir>  your game folder (default: game\ at the repository root)
  -OutputDir <dir> where Cricket07.apk goes (default: android\output)
  -AcceptSdkLicenses  answer yes to the Android SDK's licence terms (the builder app asks you first)
  -Retranslate    redo the Ghidra export and the translation even if the exe hasn't changed
  -Opt "-O3"      optimisation for the game code (default -O2)
  -Lto            link-time optimisation (much slower link; measure before keeping it)
  -Debug          a debuggable build with -O1 (faster to compile, for chasing crashes)
#>
param(
    [switch]$Install,
    [switch]$PushGame,
    [switch]$Bundle,
    [switch]$Rosters,
    [string]$GameDir,
    [string]$OutputDir,
    [switch]$AcceptSdkLicenses,
    [switch]$Retranslate,
    [string]$Opt = "-O2",
    [switch]$Lto,
    [switch]$Debug
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is many times slower with the progress bar

$Android = $PSScriptRoot
$Repo = Split-Path $Android -Parent
$Tools = Join-Path $Repo "third_party\tools"
$Package = "com.ashenstudios.cricket07"
$Config = Get-Content (Join-Path $Android "config.json") -Raw | ConvertFrom-Json

$NdkVersion = "27.2.12479018"
$GhidraMajor = "12"
$JdkFeature = "21"
$SdkCmdlineUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"

function Step($msg) { Write-Host ""; Write-Host "== $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host ""; Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }
function Run($exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { Fail "$(Split-Path $exe -Leaf) failed (exit code $LASTEXITCODE)" }
}

function Download($url, $dest) {
    Write-Host "  downloading $url"
    $tmp = "$dest.part"
    Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing
    Move-Item -Force $tmp $dest
}

function Expand-Into($zip, $dest) {
    # zips hold one top-level folder: unpack beside it, then rename that folder to $dest
    $tmp = "$dest.unpack"
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path $zip -DestinationPath $tmp
    $inner = Get-ChildItem $tmp
    if ($inner.Count -eq 1 -and $inner[0].PSIsContainer) { Move-Item $inner[0].FullName $dest } else { Move-Item $tmp $dest }
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Remove-Item -Force $zip
}

if ($Repo -match " ") { Fail "the path '$Repo' has a space in it; the NDK build breaks on that. Move the folder (e.g. to C:\cricket07)." }
New-Item -ItemType Directory -Force $Tools | Out-Null

# ------------------------------------------------------------------ 1. tools

Step "Build tools"

# Python 3 + capstone + pefile, in a venv inside the repo
$Venv = Join-Path $Repo ".venv"
$Py = Join-Path $Venv "Scripts\python.exe"
if (-not (Test-Path $Py)) {
    $sysPy = $null
    foreach ($c in @("py", "python", "python3")) {
        $cmd = Get-Command $c -ErrorAction SilentlyContinue
        if ($cmd) { $sysPy = $cmd.Source; break }
    }
    if (-not $sysPy) { Fail "Python 3 is needed: install it from python.org (tick 'Add to PATH'), then run this again." }
    Write-Host "  creating a Python environment in .venv"
    if ((Split-Path $sysPy -Leaf) -eq "py.exe") { Run $sysPy @("-3", "-m", "venv", $Venv) } else { Run $sysPy @("-m", "venv", $Venv) }
}
# a failed import writes to stderr, which redirecting turns into a terminating error under "Stop"
$ErrorActionPreference = "Continue"
& $Py -I -c "import capstone, pefile" 2>$null
$havePackages = $LASTEXITCODE -eq 0
$ErrorActionPreference = "Stop"
if (-not $havePackages) {
    Write-Host "  installing capstone and pefile"
    Run $Py @("-m", "pip", "install", "--quiet", "--disable-pip-version-check", "capstone", "pefile")
}

# JDK 21 (Ghidra 12 needs it; Gradle runs on it too)
$Jdk = Join-Path $Tools "jdk$JdkFeature"
if (-not (Test-Path (Join-Path $Jdk "bin\java.exe"))) {
    Download "https://api.adoptium.net/v3/binary/latest/$JdkFeature/ga/windows/x64/jdk/hotspot/normal/eclipse" "$Jdk.zip"
    Expand-Into "$Jdk.zip" $Jdk
}
$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$env:PATH"

# Ghidra 12 (newest 12.x release)
$Ghidra = Get-ChildItem $Tools -Directory -Filter "ghidra_$GhidraMajor.*" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $Ghidra) {
    $rels = Invoke-RestMethod "https://api.github.com/repos/NationalSecurityAgency/ghidra/releases?per_page=30" -UseBasicParsing
    $asset = $rels | Where-Object { $_.tag_name -like "Ghidra_$GhidraMajor.*" } | ForEach-Object { $_.assets } |
             Where-Object { $_.name -like "ghidra_$GhidraMajor.*_PUBLIC_*.zip" } | Select-Object -First 1
    if (-not $asset) { Fail "no Ghidra $GhidraMajor release found on GitHub" }
    $name = $asset.name -replace "_\d{8}\.zip$", ""
    Download $asset.browser_download_url (Join-Path $Tools "ghidra.zip")
    Expand-Into (Join-Path $Tools "ghidra.zip") (Join-Path $Tools $name)
    $Ghidra = Get-Item (Join-Path $Tools $name)
}

# Android SDK: ANDROID_HOME if it has everything, else our own copy
$Sdk = $env:ANDROID_HOME
if (-not $Sdk -or -not (Test-Path (Join-Path $Sdk "ndk\$NdkVersion"))) { $Sdk = Join-Path $Tools "android-sdk" }
$SdkManager = Join-Path $Sdk "cmdline-tools\latest\bin\sdkmanager.bat"
$need = @("platform-tools", "platforms;android-35", "build-tools;35.0.0", "ndk;$NdkVersion", "cmake;3.22.1")
$missing = @()
foreach ($p in $need) {
    $dir = Join-Path $Sdk ($p -replace ";", "\")
    if (-not (Test-Path $dir)) { $missing += $p }
}
if ($missing.Count -gt 0) {
    if (-not (Test-Path $SdkManager)) {
        New-Item -ItemType Directory -Force $Sdk | Out-Null
        Download $SdkCmdlineUrl (Join-Path $Tools "cmdline-tools.zip")
        Expand-Into (Join-Path $Tools "cmdline-tools.zip") (Join-Path $Sdk "cmdline-tools\latest")
    }
    Write-Host ""
    if ($AcceptSdkLicenses) {
        (1..30 | ForEach-Object { "y" }) | & $SdkManager "--sdk_root=$Sdk" --licenses
    } else {
        Write-Host "  The Android SDK needs you to accept Google's licence terms. Read them below and answer 'y' to each." -ForegroundColor Yellow
        & $SdkManager "--sdk_root=$Sdk" --licenses
    }
    Write-Host "  installing: $($missing -join ', ')"
    Run $SdkManager (@("--sdk_root=$Sdk") + $missing)
}
$env:ANDROID_HOME = $Sdk
$Adb = Join-Path $Sdk "platform-tools\adb.exe"

# SDL2 source and wgpu-native for aarch64-linux-android
$SdlVersion = "2.32.10"
$WgpuVersion = "v29.0.1.1"
$SdlDir = Join-Path $Repo "third_party\SDL2"
if (-not (Test-Path (Join-Path $SdlDir "android-project"))) {
    $tgz = Join-Path $Tools "SDL2.tar.gz"
    Download "https://github.com/libsdl-org/SDL/releases/download/release-$SdlVersion/SDL2-$SdlVersion.tar.gz" $tgz
    Run (Join-Path $env:WINDIR "System32\tar.exe") @("-xzf", $tgz, "-C", (Join-Path $Repo "third_party"))
    Move-Item (Join-Path $Repo "third_party\SDL2-$SdlVersion") $SdlDir
    Remove-Item -Force $tgz
}
$WgpuDir = Join-Path $Repo "third_party\wgpu"
$WgpuTag = Join-Path $WgpuDir "wgpu-native-meta\wgpu-native-git-tag"
# a copy from an older build is replaced: v29 fixes driver crashes on Mali-G52 phones (Redmi 9)
if (-not (Test-Path (Join-Path $WgpuDir "lib\libwgpu_native.a")) -or -not (Test-Path $WgpuTag) -or
    (Get-Content $WgpuTag -Raw).Trim() -ne $WgpuVersion) {
    $zip = Join-Path $Tools "wgpu.zip"
    Download "https://github.com/gfx-rs/wgpu-native/releases/download/$WgpuVersion/wgpu-android-aarch64-release.zip" $zip
    if (Test-Path $WgpuDir) { Remove-Item -Recurse -Force $WgpuDir }
    Expand-Archive -Path $zip -DestinationPath $WgpuDir
    Remove-Item -Force $zip
}
Write-Host "  tools ready (JDK $JdkFeature, $($Ghidra.Name), NDK $NdkVersion, SDL $SdlVersion, wgpu-native $WgpuVersion)"

# ------------------------------------------------------------------ 2. the exe

Step "Game executable"
$GameDir = if ($GameDir) { [IO.Path]::GetFullPath($GameDir).TrimEnd("\") } else { [IO.Path]::GetFullPath((Join-Path $Repo $Config.gameDir)) }
$Exe = Join-Path $GameDir $Config.exe
if (-not (Test-Path $Exe)) { Fail "no $($Config.exe) in $GameDir. Copy your installed game folder's contents there (see android\README.md)." }

# ------------------------------------------------------------------ 3. Ghidra export + translation

$Hash = (Get-FileHash $Exe -Algorithm SHA256).Hash.Substring(0, 16).ToLower()
$Work = Join-Path $Repo "work\$Hash"
$Funcs = Join-Path $Work "functions.json"
$OutAll = Join-Path $Repo "out_all"
$Stamp = Join-Path $OutAll ".exe-hash"
New-Item -ItemType Directory -Force $Work | Out-Null

if ($Retranslate -or -not (Test-Path $Funcs)) {
    Step "Ghidra analysis (10-30 minutes the first time)"
    $proj = Join-Path $Work "ghidra_project"
    New-Item -ItemType Directory -Force $proj | Out-Null
    $scripts = Join-Path $Repo "ghidra"
    # a copy in a path without spaces: the game folder may be under Program Files
    $ExeCopy = Join-Path $Work $Config.exe
    Copy-Item -Force $Exe $ExeCopy
    # the same as ghidra/export.sh
    Run (Join-Path $Ghidra.FullName "support\analyzeHeadless.bat") @(
        $proj, "recomp", "-import", $ExeCopy, "-overwrite", "-scriptPath", $scripts,
        "-postScript", "NameFromAsserts.java", (Join-Path $Work "assert_names.csv"),
        "-postScript", "RecoverRttiClasses.java", (Join-Path $Work "rtti_classes.csv"),
        "-postScript", "ExportFunctions.java", $Funcs)
    if (-not (Test-Path $Funcs)) { Fail "Ghidra finished without writing $Funcs" }
}

if ($Retranslate -or -not (Test-Path $Stamp) -or (Get-Content $Stamp) -ne $Hash) {
    Step "Translating x86 to C"
    if (Test-Path $OutAll) { Remove-Item -Recurse -Force $OutAll }
    Run $Py @("-I", (Join-Path $Repo "translator\x86toc.py"), $Exe, $Funcs, $OutAll, "--all", "--per-file", "300")
    Set-Content -Path $Stamp -Value $Hash -Encoding ascii
}
$nfiles = (Get-ChildItem $OutAll -Filter *.c).Count
Write-Host "  $nfiles generated C files in out_all\"

# ------------------------------------------------------------------ 4. APK

$BundleDir = Join-Path $Android "bundle"
if ($Bundle) {
    Step "Bundling the game files"
    # hard links into android\bundle\game where the drive allows it, else copies, plus the manifest SetupActivity reads
    if (Test-Path $BundleDir) { Remove-Item -Recurse -Force $BundleDir }
    $skip = @("Support", "Cricket07_uninst.exe", "eauninstall.exe", "EAInstall.dll", "filelist.txt")
    $gob = $null
    if ($Rosters) {
        $gob = Join-Path $Work "rosters\data.gob"
        $newest = (Get-ChildItem (Join-Path $Repo "rosters") -Recurse -File | Measure-Object LastWriteTimeUtc -Maximum).Maximum
        if (-not (Test-Path $gob) -or (Get-Item $gob).LastWriteTimeUtc -lt $newest) {
            Write-Host "  making a copy of data.gob with the 2026 rosters"
            Run $Py @("-m", "pip", "install", "--quiet", "--disable-pip-version-check", "pillow", "numpy")   # team logos and kits
            Run $Py @("-I", (Join-Path $Repo "rosters\apply_rosters.py"), (Join-Path $GameDir "Data\data.gob"), $gob)
        }
    }
    $lines = @()
    foreach ($f in Get-ChildItem $GameDir -Recurse -File) {
        $rel = $f.FullName.Substring($GameDir.Length + 1)
        if ($skip -contains $rel.Split("\")[0]) { continue }
        $src = $f.FullName
        if ($gob -and $rel -ieq "Data\data.gob") { $src = $gob }
        $dst = Join-Path $BundleDir "game\$rel"
        New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
        try { New-Item -ItemType HardLink -Path $dst -Target $src -ErrorAction Stop | Out-Null }
        catch { Copy-Item -LiteralPath $src -Destination $dst }
        $item = Get-Item $src
        $lines += "$($rel.Replace('\', '/'))|$($item.Length)|$($item.LastWriteTimeUtc.Ticks)"
    }
    [IO.File]::WriteAllLines((Join-Path $BundleDir "game_manifest.txt"), $lines)
    Write-Host "  $($lines.Count) files$(if ($gob) { ", Data\data.gob from $gob" })"
    $env:RC_BUNDLE_DIR = $BundleDir
} else {
    Remove-Item Env:RC_BUNDLE_DIR -ErrorAction SilentlyContinue
}

Step "Building the APK (the first build compiles ~1.3M translated instructions: 20-60 minutes)"
Set-Content -Path (Join-Path $Android "local.properties") -Value ("sdk.dir=" + ($Sdk -replace "\\", "/")) -Encoding ascii
$env:RC_NDK_VERSION = $NdkVersion
$env:RC_OPT = if ($Debug) { "-O1" } else { $Opt }
$env:RC_LTO = if ($Lto) { "ON" } else { "OFF" }
$task = if ($Debug) { "assembleDebug" } else { "assembleRelease" }
Push-Location $Android
try { Run (Join-Path $Android "gradlew.bat") @("--no-daemon", $task) } finally { Pop-Location }

$variant = if ($Debug) { "debug" } else { "release" }
$built = Join-Path $Android "app\build\outputs\apk\$variant\app-$variant.apk"
$OutDir = if ($OutputDir) { [IO.Path]::GetFullPath($OutputDir) } else { Join-Path $Android "output" }
New-Item -ItemType Directory -Force $OutDir | Out-Null
$Apk = Join-Path $OutDir "Cricket07.apk"
Copy-Item -Force $built $Apk
Write-Host "  built $Apk ($([math]::Round((Get-Item $Apk).Length / 1MB)) MB)" -ForegroundColor Green

# ------------------------------------------------------------------ 5. phone

$PhoneGame = "/sdcard/Android/data/$Package/files/game"
if ($Install -or $PushGame) {
    Step "Phone"
    $devices = & $Adb devices | Select-String "\tdevice$"
    if (-not $devices) { Fail "no phone found over adb. Plug it in with USB debugging on and accept the prompt on the phone." }
    Run $Adb @("install", "-r", $Apk)
    Write-Host "  installed"
    if ($PushGame) {
        Write-Host "  copying game\ to $PhoneGame (a few minutes)"
        Run $Adb @("shell", "mkdir", "-p", $PhoneGame)
        Run $Adb @("push", "--sync", "$GameDir\.", $PhoneGame)
    }
}

Write-Host ""
Write-Host "Done. The game files go on the phone in:" -ForegroundColor Green
Write-Host "  Android/data/$Package/files/game/   ($($Config.exe) directly inside)"
Write-Host "Copy them with 'Build.bat -PushGame', or with a file manager over USB."
