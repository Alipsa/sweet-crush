param(
    [Parameter(Mandatory = $true)][string]$BaseDir,
    [Parameter(Mandatory = $true)][string]$TargetDir,
    [Parameter(Mandatory = $true)][string]$ArtifactId,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$MainClass
)

$ErrorActionPreference = "Stop"
$modules = "java.base,java.desktop,java.logging,java.management,java.prefs,jdk.unsupported"

if ($env:OS -ne "Windows_NT") {
    throw "build-windows.ps1 must be run on Windows."
}

$appJar = Join-Path $TargetDir "$ArtifactId-$Version.jar"
$distRoot = Join-Path $TargetDir "dist"
$inputLibDir = Join-Path $distRoot "input\lib"
$arch = $env:PROCESSOR_ARCHITECTURE
if ([string]::IsNullOrWhiteSpace($arch)) {
    $arch = "x64"
}
$bundleName = "$ArtifactId-windows-$arch"
$bundleDir = Join-Path $distRoot $bundleName
$appDir = Join-Path $bundleDir "app"
$appLibDir = Join-Path $appDir "lib"
$runtimeDir = Join-Path $bundleDir "runtime"
$zipFile = Join-Path $distRoot "$bundleName.zip"

function Resolve-Tool([string]$toolName) {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\$toolName.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }
    $cmd = Get-Command $toolName -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }
    throw "Missing required tool: $toolName"
}

$jlink = Resolve-Tool "jlink"

if (-not (Test-Path $appJar)) {
    throw "Application jar not found: $appJar"
}
if (-not (Test-Path $inputLibDir)) {
    throw "Runtime dependency folder not found: $inputLibDir"
}

if (Test-Path $bundleDir) {
    Remove-Item -Recurse -Force $bundleDir
}
if (Test-Path $zipFile) {
    Remove-Item -Force $zipFile
}
New-Item -ItemType Directory -Path $appLibDir -Force | Out-Null

Copy-Item $appJar (Join-Path $appDir "sweet-crush.jar")
$inputJars = Get-ChildItem -Path (Join-Path $inputLibDir "*.jar") -File
if ($inputJars.Count -eq 0) {
    throw "No dependency jars found in $inputLibDir"
}
$inputJars | Copy-Item -Destination $appLibDir
$tracksDir = Join-Path $BaseDir "tracks"
if (Test-Path $tracksDir) {
    Copy-Item -Recurse $tracksDir (Join-Path $bundleDir "tracks")
}

& $jlink `
    --add-modules $modules `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress=2 `
    --output $runtimeDir

$bat = @"
@echo off
setlocal
set "APP_HOME=%~dp0"
"%APP_HOME%runtime\bin\java.exe" -Dsun.java2d.uiScale=2.0 -cp "%APP_HOME%app\sweet-crush.jar;%APP_HOME%app\lib\*" $MainClass %*
"@
Set-Content -Path (Join-Path $bundleDir "sweet-crush.bat") -Value $bat -NoNewline -Encoding Ascii

Compress-Archive -Path $bundleDir -DestinationPath $zipFile -CompressionLevel Optimal
Write-Host "Created Windows distribution: $zipFile"
