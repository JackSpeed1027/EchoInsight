# 文件职责：建立 EchoInsight 专用 Python 环境，按锁文件安装，不修改全局 Python 包。
param([string]$Python = 'python')
. "$PSScriptRoot/env.ps1"
Set-Location $ProjectRoot
$agentPython = Join-Path $ProjectRoot 'runtime/venvs/echoagent/Scripts/python.exe'

# 1、仅在尚未安装时新建隔离环境，保留已经存在的状态、配置和检查点。
if (!(Test-Path -LiteralPath $agentPython)) {
    & $Python -m venv (Join-Path $ProjectRoot 'runtime/venvs/echoagent')
    if ($LASTEXITCODE -ne 0) { throw 'Python environment setup failed; Python 3.13 is required.' }
}
# 2、固定完整依赖；3、注册本地源码，IDEA Terminal 中改代码即可再次运行。
& $agentPython -m pip install --disable-pip-version-check -r agent/requirements.lock.txt
if ($LASTEXITCODE -ne 0) { throw 'Agent dependencies installation failed.' }
& $agentPython -m pip install --disable-pip-version-check --no-deps --no-build-isolation -e ./agent
if ($LASTEXITCODE -ne 0) { throw 'Local Agent package installation failed.' }
'EchoInsight LangGraph environment is ready. No model keys are needed for the current workflow.'
