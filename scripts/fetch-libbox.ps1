$ErrorActionPreference = "Stop"

$Version = "v1.14.1-lx.8"
$AarName = "libbox-$($Version.TrimStart('v')).aar"
$ExpectedSha = "beab998c9d0a46826db0d654b5315fa6a25d9e1cbf8cb10c547e757444c26d80"
$Destination = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($PSScriptRoot, "..", "app", "libs"))
$Temp = Join-Path ([System.IO.Path]::GetTempPath()) ("omnivpn-libbox-" + [guid]::NewGuid().ToString("N"))
$Url = "https://github.com/Leadaxe/sing-box-lx/releases/download/$Version/$AarName"
$AarPath = Join-Path $Temp $AarName

New-Item -ItemType Directory -Force -Path $Destination | Out-Null
New-Item -ItemType Directory -Force -Path $Temp | Out-Null

try {
    Write-Host "Fetching $AarName from Leadaxe/sing-box-lx @ $Version"
    Invoke-WebRequest -Uri $Url -OutFile $AarPath

    $ActualSha = (Get-FileHash -Algorithm SHA256 -Path $AarPath).Hash.ToLowerInvariant()
    if ($ActualSha -ne $ExpectedSha) {
        throw "SHA-256 mismatch. Expected $ExpectedSha, got $ActualSha"
    }

    Copy-Item -Force $AarPath (Join-Path $Destination "libbox.aar")
    Set-Content -NoNewline -Path (Join-Path $Destination ".libbox.version") -Value $Version

    Write-Host "Installed $(Join-Path $Destination "libbox.aar")"
}
finally {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $Temp
}
