# Builds the Dictate Android client without Gradle. Clone of the Bucket
# script; the only differences are the package (com.dictate), the output
# name, and the signing key. Same key material as Bucket: it is just a debug
# key, and keeping it identical means one less keystore to track.
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$tools = Join-Path $sdk "build-tools\36.0.0"
$androidJar = Join-Path $sdk "platforms\android-34\android.jar"

$aapt2 = Join-Path $tools "aapt2.exe"
$d8 = Join-Path $tools "d8.bat"
$zipalign = Join-Path $tools "zipalign.exe"
$apksigner = Join-Path $tools "apksigner.bat"

foreach ($p in @($aapt2, $d8, $zipalign, $apksigner, $androidJar)) {
    if (-not (Test-Path $p)) { throw "missing build tool: $p" }
}

$build = Join-Path $here "build"
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $build, "$build\classes", "$build\dex" | Out-Null

$resZip = Join-Path $build "res.zip"
$baseApk = Join-Path $build "base.apk"
$alignedApk = Join-Path $build "aligned.apk"
$outApk = Join-Path $here "dictate.apk"

Write-Host "1/5 compiling resources" -ForegroundColor Cyan
& $aapt2 compile --dir (Join-Path $here "res") -o $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "2/5 linking resources" -ForegroundColor Cyan
& $aapt2 link -o $baseApk -I $androidJar `
    --manifest (Join-Path $here "AndroidManifest.xml") `
    --min-sdk-version 24 --target-sdk-version 34 `
    $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "3/5 compiling java" -ForegroundColor Cyan
$sources = Get-ChildItem (Join-Path $here "java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 11 -nowarn -classpath $androidJar -d "$build\classes" @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "4/5 dexing" -ForegroundColor Cyan
# Pack classes into one jar first: passing every .class path to d8.bat blows
# the batch-file command-line limit once vendored sources grow (ZXing QR).
$classesJar = Join-Path $build "classes.jar"
& jar --create --file $classesJar -C "$build\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& $d8 --lib $androidJar --min-api 24 --output "$build\dex" $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "5/5 packaging and signing" -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, 'Update')
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, "$build\dex\classes.dex", "classes.dex",
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}

& $zipalign -f 4 $baseApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$ks = Join-Path $here "dictate-debug.jks"
if (-not (Test-Path $ks)) {
    Write-Host "  creating signing key" -ForegroundColor DarkGray
    cmd /c "keytool -genkeypair -keystore `"$ks`" -storepass bucketdev -keypass bucketdev -alias bucket -keyalg RSA -keysize 2048 -validity 10000 -dname `"CN=Dictate, O=Local, C=US`" 2>nul"
    if (-not (Test-Path $ks)) { throw "keytool failed to create $ks" }
}

cmd /c "`"$apksigner`" sign --ks `"$ks`" --ks-pass pass:bucketdev --key-pass pass:bucketdev --out `"$outApk`" `"$alignedApk`" 2>&1"
if (-not (Test-Path $outApk)) { throw "apksigner failed to produce $outApk" }

$certs = cmd /c "`"$apksigner`" verify --print-certs `"$outApk`" 2>&1"
$certs | Where-Object { $_ -match 'certificate DN|SHA-256' } | ForEach-Object { Write-Host "  $_" }

$size = [math]::Round((Get-Item $outApk).Length / 1KB, 1)
Write-Host ""
Write-Host "built $outApk ($size KB)" -ForegroundColor Green
Write-Host "install with: adb install -r `"$outApk`"" -ForegroundColor DarkGray