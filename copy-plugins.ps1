# PowerShell script to copy plugin JARs to destination folder
param(
    [string]$Destination = "D:\test parser 2\plugins"
)

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$pluginsRoot = Join-Path $root "swiftcheck-aml-plugins"

if (-not (Test-Path $Destination)) {
    Write-Host "Création du dossier destination : $Destination" -ForegroundColor Cyan
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
}

Write-Host "Recherche des JARs dans $pluginsRoot..." -ForegroundColor Cyan

$jars = Get-ChildItem -Path $pluginsRoot -Filter "*-SNAPSHOT.jar" -Recurse | Where-Object {
    $_.DirectoryName -match "[\/\\]target$" -and -not $_.Name.StartsWith("original-")
}

$count = 0
foreach ($jar in $jars) {
    Copy-Item -Path $jar.FullName -Destination $Destination -Force
    $count++
}

Write-Host "Terminé : $count plugin(s) copié(s) avec succès vers '$Destination'." -ForegroundColor Green

