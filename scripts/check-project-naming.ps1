# 文件职责：核对当前源码、构建与 IDEA 入口的一致性；只读检查，不清空 IDE 缓存或历史。
$ErrorActionPreference = 'Stop'
$namingRoot = Split-Path -Parent $PSScriptRoot
$expectedMain = 'io.echoinsight.EchoInsightApplication'
$expectedModule = 'echoinsight-backend'

# 1、检查正式源码与测试源码的包声明和目录，不把历史配置兼容字符串当成旧包。
foreach ($sourceSet in 'main','test') {
    $sourceRoot = Join-Path $namingRoot "backend/src/$sourceSet/java"
    foreach ($source in Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -Recurse) {
        $body = Get-Content -LiteralPath $source.FullName -Raw
        $package = [regex]::Match($body, '(?m)^package[ ]+([^;]+);').Groups[1].Value
        if ($package -ne 'io.echoinsight' -and !$package.StartsWith('io.echoinsight.')) {
            throw "Unexpected Java package: $($source.FullName)"
        }
        $relative = [IO.Path]::GetRelativePath($sourceRoot, $source.DirectoryName).Replace('\','.')
        if ($relative -ne $package) { throw "Package path mismatch: $($source.FullName)" }
        if ($body -match '(?:EchoAgentApplication|InsightFlowApplication)') {
            throw "Retired application class: $($source.FullName)"
        }
    }
}
$entry = Join-Path $namingRoot 'backend/src/main/java/io/echoinsight/EchoInsightApplication.java'
if (!(Test-Path -LiteralPath $entry)) { throw 'EchoInsight application source is missing.' }

# 2、构建入口与共享运行配置必须指向同一个正式类，不依赖旧名称的桥接类。
[xml]$rootPom = Get-Content -LiteralPath (Join-Path $namingRoot 'pom.xml') -Raw
[xml]$backendPom = Get-Content -LiteralPath (Join-Path $namingRoot 'backend/pom.xml') -Raw
if ($rootPom.project.groupId -ne 'io.echoinsight' -or $rootPom.project.artifactId -ne 'echoinsight') {
    throw 'Root Maven coordinates are not EchoInsight.'
}
if ($backendPom.project.artifactId -ne $expectedModule) { throw 'Backend Maven name mismatch.' }
$bootPlugin = @($backendPom.project.build.plugins.plugin) | Where-Object artifactId -eq 'spring-boot-maven-plugin'
if ($bootPlugin.configuration.mainClass -ne $expectedMain) { throw 'Maven main class mismatch.' }
[xml]$shared = Get-Content -LiteralPath (Join-Path $namingRoot '.run/EchoInsight Backend.run.xml') -Raw
$mainOption = $shared.component.configuration.option | Where-Object name -eq 'MAIN_CLASS_NAME'
if ($mainOption.value -ne $expectedMain -or $shared.component.configuration.module.name -ne $expectedModule) {
    throw 'Shared IDEA run configuration mismatch.'
}
$frontend = Get-Content -LiteralPath (Join-Path $namingRoot 'frontend/package.json') -Raw | ConvertFrom-Json
if ($frontend.name -ne 'echoinsight-frontend') { throw 'Frontend package name mismatch.' }

# 3、IDEA 本机文件可不存在；存在时核对显示名、编译模块及已保存启动入口。
$ideaName = Join-Path $namingRoot '.idea/.name'
if ((Test-Path -LiteralPath $ideaName) -and (Get-Content -LiteralPath $ideaName -Raw).Trim() -ne 'EchoInsight') {
    throw 'Local IDEA project display name mismatch.'
}
foreach ($relative in '.idea/compiler.xml','.idea/workspace.xml') {
    $local = Join-Path $namingRoot $relative
    if (!(Test-Path -LiteralPath $local)) { continue }
    [xml]$localXml = Get-Content -LiteralPath $local -Raw
    $nodes = if ($relative -like '*workspace.xml') {
        $localXml.SelectNodes("//component[@name='RunManager']")
    } else { $localXml.SelectNodes('//component') }
    foreach ($node in $nodes) {
        if ($node.OuterXml -match 'io.echoagent|io.insightflow|echoagent-backend|insightflow-backend|EchoAgentApplication') {
            throw "Retired IDEA startup/compiler reference: $relative"
        }
    }
}
'EchoInsight naming verified: Java packages, main class, Maven, frontend and IDEA.'
