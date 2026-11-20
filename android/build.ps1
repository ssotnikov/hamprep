#Requires -Version 7.2
[CmdletBinding()]
param(
    [string] $SdkRoot = $env:ANDROID_HOME,
    [string] $JdkRoot = $env:JAVA_HOME,
    [string] $Output,
    [switch] $DebugWebView
)

$ErrorActionPreference = 'Stop'

$manifestText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'AndroidManifest.xml'))
$manifestXml = [xml] $manifestText
$version = $manifestXml.DocumentElement.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
if (-not $Output) {
    if (-not $version -or $version.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0 -or
        $version.TrimEnd(' ', '.') -ne $version) {
        throw 'Android versionName must be a valid release directory name'
    }
    $apkName = if ($DebugWebView) { 'hamprepru-debug.apk' } else { 'hamprepru.apk' }
    $releaseDirectory = Join-Path (Join-Path $PSScriptRoot 'releases') $version
    $Output = Join-Path $releaseDirectory $apkName
}
$destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Output)

function Invoke-BuildTool {
    param([string] $Tool, [string[]] $Arguments)
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$([IO.Path]::GetFileName($Tool)) failed with exit code $LASTEXITCODE"
    }
}

if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $JdkRoot) {
    $JdkRoot = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe).Source)
}
$tools = Join-Path $SdkRoot 'build-tools\36.0.0'
$platform = Join-Path $SdkRoot 'platforms\android-36\android.jar'
if (-not (Test-Path -LiteralPath $platform)) {
    $platform = Join-Path $SdkRoot 'platforms\android-37.0\android.jar'
}
foreach ($required in @($platform, "$tools\aapt2.exe", "$JdkRoot\bin\javac.exe")) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing build dependency: $required" }
}

$work = [IO.Directory]::CreateTempSubdirectory('hamprepru-').FullName
$previousJavaHome = $env:JAVA_HOME
$previousSigningPassword = $env:HAM_EXAM_SIGNING_PASSWORD
try {
    $env:JAVA_HOME = $JdkRoot
    $assets = [IO.Directory]::CreateDirectory((Join-Path $work 'assets')).FullName
    $classes = [IO.Directory]::CreateDirectory((Join-Path $work 'classes')).FullName
    $dex = [IO.Directory]::CreateDirectory((Join-Path $work 'dex')).FullName
    $inputScript = Join-Path $work 'input.js'
    Invoke-BuildTool node.exe @((Join-Path $PSScriptRoot 'prepare-handbook.mjs'),
        (Join-Path $PSScriptRoot '..\html\ham_prepare_ru.html'), $assets, $inputScript, $version)
    Invoke-BuildTool npm.cmd @('exec', '--yes', '--package=esbuild@0.25.12', '--', 'esbuild',
        $inputScript, '--target=chrome69', "--outfile=$assets\handbook.js", '--log-level=warning')

    # The Windows SDK tools can fail on non-ASCII project paths.
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'res'), (Join-Path $PSScriptRoot 'src') -Destination $work -Recurse
    $manifest = Join-Path $work 'AndroidManifest.xml'
    if ($DebugWebView) {
        $manifestText = $manifestText.Replace('<application', '<application android:debuggable="true"')
    }
    [IO.File]::WriteAllText($manifest, $manifestText)
    $resources = Join-Path $work 'resources.zip'
    $unsigned = Join-Path $work 'unsigned.apk'
    $aligned = Join-Path $work 'aligned.apk'
    $signed = Join-Path $work 'signed.apk'
    Invoke-BuildTool "$tools\aapt2.exe" @('compile', '--dir', (Join-Path $work 'res'), '-o', $resources)
    Invoke-BuildTool "$tools\aapt2.exe" @('link', '-I', $platform, '--manifest', $manifest,
        '-A', $assets, '-o', $unsigned, $resources)

    $sources = Get-ChildItem -LiteralPath (Join-Path $work 'src') -Recurse -Filter '*.java' -File
    Invoke-BuildTool "$JdkRoot\bin\javac.exe" (@('--release', '8', '-encoding', 'UTF-8',
        '-classpath', $platform, '-d', $classes) + $sources.FullName)
    $classJar = Join-Path $work 'classes.jar'
    Invoke-BuildTool "$JdkRoot\bin\jar.exe" @('--create', '--file', $classJar, '-C', $classes, '.')
    Invoke-BuildTool "$tools\d8.bat" @('--min-api', '26', '--lib', $platform, '--output', $dex, $classJar)
    Invoke-BuildTool "$JdkRoot\bin\jar.exe" @('--update', '--file', $unsigned, '-C', $dex, 'classes.dex')
    Invoke-BuildTool "$tools\zipalign.exe" @('-f', '4', $unsigned, $aligned)

    $keyDirectory = [IO.Directory]::CreateDirectory((Join-Path $env:USERPROFILE '.android\hamprepru')).FullName
    $keyStore = Join-Path $keyDirectory 'signing.p12'
    $passwordFile = Join-Path $keyDirectory 'signing-password.dpapi'
    if (-not (Test-Path -LiteralPath $passwordFile)) {
        if (Test-Path -LiteralPath $keyStore) { throw "Missing password for $keyStore" }
        $secret = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
        ConvertTo-SecureString $secret -AsPlainText -Force | ConvertFrom-SecureString |
            Set-Content -LiteralPath $passwordFile
    }
    $securePassword = Get-Content -LiteralPath $passwordFile | ConvertTo-SecureString
    $env:HAM_EXAM_SIGNING_PASSWORD = [Net.NetworkCredential]::new('', $securePassword).Password
    if (-not (Test-Path -LiteralPath $keyStore)) {
        Invoke-BuildTool "$JdkRoot\bin\keytool.exe" @('-genkeypair', '-keystore', $keyStore,
            '-storetype', 'PKCS12', '-storepass:env', 'HAM_EXAM_SIGNING_PASSWORD', '-alias', 'hamprepru',
            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Ham Exam')
    }
    Invoke-BuildTool "$tools\apksigner.bat" @('sign', '--ks', $keyStore,
        '--ks-pass', 'env:HAM_EXAM_SIGNING_PASSWORD', '--ks-key-alias', 'hamprepru',
        '--v4-signing-enabled', 'false', '--out', $signed, $aligned)
    Invoke-BuildTool "$tools\apksigner.bat" @('verify', $signed)
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
    [IO.File]::Copy($signed, $destination, $true)
    Get-Item -LiteralPath $destination
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:HAM_EXAM_SIGNING_PASSWORD = $previousSigningPassword
    $tempRoot = (Resolve-Path -LiteralPath ([IO.Path]::GetTempPath())).Path
    $resolvedWork = (Resolve-Path -LiteralPath $work).Path
    if ([IO.Path]::GetDirectoryName($resolvedWork) -eq $tempRoot.TrimEnd('\') -and
        [IO.Path]::GetFileName($resolvedWork).StartsWith('hamprepru-')) {
        [IO.Directory]::Delete($resolvedWork, $true)
    }
}
