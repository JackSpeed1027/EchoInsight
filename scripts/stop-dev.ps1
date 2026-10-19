param([Parameter(Mandatory=$true)][string]$RecordPath)
. "$PSScriptRoot/env.ps1"
$resolved=(Resolve-Path -LiteralPath $RecordPath).Path
$expectedPrefix=(Join-Path $ProjectRoot 'runtime')+[IO.Path]::DirectorySeparatorChar
if (!$resolved.StartsWith($expectedPrefix,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'dev-processes-*.json') { throw 'Use a process record under this project runtime directory.' }
$record=Get-Content -LiteralPath $resolved -Raw|ConvertFrom-Json
foreach($name in 'backend','frontend') {
    $entry=$record.$name
    $process=Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
    if(!$process){continue}
    if($process.StartTime.ToUniversalTime().ToString('O') -ne ([DateTime]$entry.started).ToUniversalTime().ToString('O')){throw "PID reused for $name; refusing to stop."}
    $command=(Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)").CommandLine
    $expected=if($name -eq 'backend'){Join-Path $ProjectRoot 'backend\target-echoinsight\echoinsight-backend-0.1.0-SNAPSHOT.jar'}else{Join-Path $ProjectRoot 'frontend\node_modules\vite\bin\vite.js'}
    $legacyJar=Join-Path $ProjectRoot 'backend\target\insightflow-backend-0.1.0-SNAPSHOT.jar'
    $normalizedCommand=if($command){$command.Replace('/','\').Replace('\\','\')}else{''}
    $expected=$expected.Replace('/','\').Replace('\\','\')
    $legacyJar=$legacyJar.Replace('/','\').Replace('\\','\')
    $previousJar=(Join-Path $ProjectRoot 'backend\target-echoagent\echoagent-backend-0.1.0-SNAPSHOT.jar').Replace('/','\').Replace('\\','\')
    $matchesLegacy=$name -eq 'backend' -and ($normalizedCommand.Contains($legacyJar) -or $normalizedCommand.Contains($previousJar))
    if(!$command -or (!$normalizedCommand.Contains($expected) -and !$matchesLegacy)){throw "Process does not belong to this project: $name"}
    Stop-Process -Id $entry.pid
    "Stopped $name PID $($entry.pid)."
}
'Database and process records preserved.'
