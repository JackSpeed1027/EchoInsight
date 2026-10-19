# 文件职责：仅为当前PowerShell会话解析开发工具；优先已有环境，保留本机安装路径作为可用时的后备。
$ErrorActionPreference = 'Stop'
$script:ProjectRoot = Split-Path -Parent $PSScriptRoot

function Add-EchoInsightToolPath([string]$Directory) {
    # 1、只加入真实目录；2、避免重复追加；3、不修改用户/系统的永久PATH。
    if ($Directory -and (Test-Path -LiteralPath $Directory -PathType Container)) {
        if (($env:Path -split ';') -notcontains $Directory) { $env:Path = "$Directory;$env:Path" }
    }
}

# 现有JAVA_HOME优先；新机器也可直接通过PATH中的JDK运行，无需D盘目录。
if ($env:JAVA_HOME -and (Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")) {
    Add-EchoInsightToolPath "$env:JAVA_HOME/bin"
} elseif (Get-Command java.exe -ErrorAction SilentlyContinue) {
    $taskJavaCommand = (Get-Command java.exe).Source
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $taskJavaCommand)
} elseif (Test-Path -LiteralPath 'D:/dev/java/jdk-21.0.12.1/bin/java.exe') {
    $env:JAVA_HOME = 'D:/dev/java/jdk-21.0.12.1'
    Add-EchoInsightToolPath "$env:JAVA_HOME/bin"
}

if ($env:MAVEN_HOME) { Add-EchoInsightToolPath "$env:MAVEN_HOME/bin" }
if (!(Get-Command mvn.cmd -ErrorAction SilentlyContinue)) { Add-EchoInsightToolPath 'D:/dev/maven/apache-maven-3.9.11/bin' }
if ($env:NODE_HOME) { Add-EchoInsightToolPath $env:NODE_HOME }
if (!(Get-Command node.exe -ErrorAction SilentlyContinue)) { Add-EchoInsightToolPath 'D:/dev/node/node-v24.22.0-win-x64' }
