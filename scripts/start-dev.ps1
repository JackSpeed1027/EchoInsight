. "$PSScriptRoot/env.ps1"
Set-Location $ProjectRoot
foreach ($port in 8080,5173) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $port is occupied. No process was terminated."
    }
}
$jar=Join-Path $ProjectRoot 'backend\target-echoinsight\echoinsight-backend-0.1.0-SNAPSHOT.jar'
$vite=Join-Path $ProjectRoot 'frontend\node_modules\vite\bin\vite.js'
if (!(Test-Path -LiteralPath $jar) -or !(Test-Path -LiteralPath $vite)) { throw 'Run Maven package and pnpm install first.' }
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$backend=Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList @('-jar',$jar) -WorkingDirectory $ProjectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$ProjectRoot\runtime\backend-$stamp.out.log" -RedirectStandardError "$ProjectRoot\runtime\backend-$stamp.err.log"
$frontend=Start-Process -FilePath (Get-Command node.exe -ErrorAction Stop).Source -ArgumentList @($vite,'--host','127.0.0.1','--port','5173','--strictPort') -WorkingDirectory "$ProjectRoot\frontend" -WindowStyle Hidden -PassThru -RedirectStandardOutput "$ProjectRoot\runtime\frontend-$stamp.out.log" -RedirectStandardError "$ProjectRoot\runtime\frontend-$stamp.err.log"
$record=@{backend=@{pid=$backend.Id;started=$backend.StartTime.ToUniversalTime().ToString('O')};frontend=@{pid=$frontend.Id;started=$frontend.StartTime.ToUniversalTime().ToString('O')}}
$recordPath="$ProjectRoot\runtime\dev-processes-$stamp.json"
$record|ConvertTo-Json -Depth 3|Set-Content -LiteralPath $recordPath -Encoding utf8
"Started backend PID $($backend.Id), frontend PID $($frontend.Id)."
"Process record: $recordPath"
'Open http://127.0.0.1:5173 after backend startup. MySQL remains managed by your existing setup.'
