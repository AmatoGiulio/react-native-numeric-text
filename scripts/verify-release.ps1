$ErrorActionPreference = 'Stop'

function Invoke-Native {
  param(
    [Parameter(Mandatory = $true)][string]$Command,
    [Parameter(Mandatory = $true)][string[]]$Arguments
  )

  & $Command @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "$Command $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
  }
}

Write-Host '==> Installing dependencies (immutable)'
Invoke-Native -Command 'yarn' -Arguments @('install', '--immutable')

Write-Host '==> Removing generated lib before source checks'
if (Test-Path 'lib') {
  Remove-Item 'lib' -Recurse -Force
}

Write-Host '==> Typecheck + lint + tests'
Invoke-Native -Command 'yarn' -Arguments @('check')

Write-Host '==> Building package with Bob'
Invoke-Native -Command 'yarn' -Arguments @('prepare')

if (-not (Test-Path 'lib/module/index.js')) {
  throw 'Missing lib/module/index.js after yarn prepare'
}
if (-not (Test-Path 'lib/typescript/src/index.d.ts')) {
  throw 'Missing lib/typescript/src/index.d.ts after yarn prepare'
}

Write-Host '==> Inspecting npm package payload'
Invoke-Native -Command 'npm' -Arguments @('pack', '--dry-run')

Write-Host '==> Release verification passed'
