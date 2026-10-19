# 文件职责：依次验证后端、前端、演示契约及评测准备，不清理数据库。
. "$PSScriptRoot/env.ps1"
Set-Location $ProjectRoot
& "$PSScriptRoot/check-project-naming.ps1"
& mvn.cmd '-Dechoinsight.model.enabled=false' verify
if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed' }
Push-Location frontend
try {
  & pnpm.cmd build
  if ($LASTEXITCODE -ne 0) { throw 'Frontend verification failed' }
} finally { Pop-Location }
python tools/validate_demo.py
if ($LASTEXITCODE -ne 0) { throw 'Demo validation failed' }

# 4、评测草稿允许为空，正式运行模型评测必须另加 --require-ready。
python -m unittest discover -s tools -p test_validate_eval.py
if ($LASTEXITCODE -ne 0) { throw 'Evaluation validator tests failed' }
python tools/validate_eval.py
if ($LASTEXITCODE -ne 0) { throw 'Evaluation preparation validation failed' }

# LangGraph 为可选实验，不在本主线脚本中自动安装或运行；命令见 docs/agent.md。


# 5、平台采集结果的离线桥接只用合成输入，验证来源和弹幕时间轴边界。
python -m unittest discover -s tools -p test_prepare_bilibili_cli_export.py
if ($LASTEXITCODE -ne 0) { throw 'Bilibili export bridge validation failed' }
