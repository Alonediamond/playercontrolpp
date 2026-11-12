param(
    [Parameter(Mandatory = $true)][string]$Mc,
    [string]$Parchment = '',
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path

if (-not $Parchment) {
    $Parchment = Get-ChildItem (Join-Path $root 'libs/maven/org/parchmentmc/data/parchment-1.21.11') `
        -Recurse -Filter '*.zip' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Parchment) { throw '找不到 Parchment zip' }

$src = Get-ChildItem (Join-Path $root '.gradle/loom-cache/minecraftMaven') `
    -Recurse -Filter '*-sources.jar' |
    Where-Object { $_.Directory.Name -eq $Mc } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $src) { throw "找不到 $Mc 的 sources jar，请先运行 ./gradlew :$Mc:genSources" }

$outDir = Join-Path $root "versions/$Mc/build"
New-Item -ItemType Directory -Force $outDir | Out-Null
$out = Join-Path $outDir "minecraft-$Mc-sources-parchment.jar"

& $Python (Join-Path $PSScriptRoot 'merge_docs.py') $Parchment $src $out $Mc
