. "$PSScriptRoot/env.ps1"
Set-Location (Join-Path $ProjectRoot 'frontend')
& pnpm.cmd dev
exit $LASTEXITCODE
