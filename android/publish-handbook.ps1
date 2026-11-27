#Requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Path,
    [Parameter(Mandatory)] [ValidateRange(1, 2100000000)] [int] $VersionCode,
    [string] $IdentityFile = (Join-Path $env:USERPROFILE '.ssh\radioexam-hamprep-publish')
)

$ErrorActionPreference = 'Stop'
$artifactName = 'hamprep.html'
$file = Get-Item -LiteralPath $Path
if ($file.PSIsContainer -or $file.Name -ne $artifactName -or
    $file.Length -eq 0 -or $file.Length -gt 64MB) {
    throw "Expected a non-empty release $artifactName of at most 64 MiB"
}
$identity = (Get-Item -LiteralPath $IdentityFile).FullName
$hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()

$start = [Diagnostics.ProcessStartInfo]::new((Get-Command ssh.exe).Source)
$start.UseShellExecute = $false
$start.RedirectStandardInput = $true
$start.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
foreach ($arg in @('-T', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
    '-o', 'IdentitiesOnly=yes', '-o', 'ConnectTimeout=10', '-o', 'ServerAliveInterval=15',
    '-o', 'ServerAliveCountMax=2', '-i', $identity, 'hamprep-publish@ssotnikov.pro',
    "publish $VersionCode $hash")) {
    $start.ArgumentList.Add($arg)
}
$inputFile = [IO.File]::OpenRead($file.FullName)
try {
    $ssh = [Diagnostics.Process]::Start($start)
    try {
        try {
            $inputFile.CopyTo($ssh.StandardInput.BaseStream)
        } finally {
            $ssh.StandardInput.Close()
            $ssh.WaitForExit()
        }
        if ($ssh.ExitCode -ne 0) { throw "VPS rejected publication (SSH exit $($ssh.ExitCode))" }
    } finally {
        $ssh.Dispose()
    }
} finally {
    $inputFile.Dispose()
}

$url = 'https://hamprep.ssotnikov.pro/'
$artifactUrl = $url + $artifactName
$download = [IO.Path]::GetTempFileName()
try {
    & curl.exe --fail --silent --show-error --retry 2 --retry-delay 1 --connect-timeout 10 --max-time 90 `
        --header 'Cache-Control: no-cache' --output $download $artifactUrl
    if ($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne $hash) {
        throw 'The public HTTPS response does not match the generated handbook'
    }
} finally {
    [IO.File]::Delete($download)
}
Write-Host "Desktop handbook verified at $url"
