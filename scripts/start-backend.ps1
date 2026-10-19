. "$PSScriptRoot/env.ps1"
Set-Location $ProjectRoot
& mvn.cmd -pl backend spring-boot:run
exit $LASTEXITCODE
