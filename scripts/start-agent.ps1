# 文件职责：在 IDEA Terminal 运行、查看或恢复本地 LangGraph，不额外占用服务端口。
param(
    [ValidateSet('run','show','resume')][string]$Command = 'run',
    [string]$JobId,
    [ValidateSet('overview','evidence')][string]$Task = 'overview',
    [string]$RunId,
    [string]$Backend = 'http://127.0.0.1:8080'
)
. "$PSScriptRoot/env.ps1"
Set-Location $ProjectRoot
$agentPython = Join-Path $ProjectRoot 'runtime/venvs/echoagent/Scripts/python.exe'
if (!(Test-Path -LiteralPath $agentPython)) { throw 'Run scripts/setup-agent.ps1 first.' }
$env:PYTHONIOENCODING = 'utf-8'
# 兼容旧虚拟环境的可编辑安装，不更改或删除环境与检查点。
$env:PYTHONPATH = (Join-Path $ProjectRoot 'agent/src') + [IO.Path]::PathSeparator + $env:PYTHONPATH

# 1、新运行绑定任务和明确用途；2、已有运行只用原 runId，不接受替换范围。
if ($Command -eq 'run') {
    if (!$JobId) { throw 'A Java job UUID is required.' }
    & $agentPython -m echoinsight_tools run --job $JobId --task $Task --backend $Backend
} else {
    if (!$RunId) { throw 'The existing graph run UUID is required.' }
    & $agentPython -m echoinsight_tools $Command --run $RunId
}
exit $LASTEXITCODE
