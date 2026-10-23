<#
.SYNOPSIS
    打两个 Release APK 到仓库根目录。

.DESCRIPTION
    从 app/build.gradle 读版本号，调用 Gradle 构建 original / schedule 两个变体的
    Release 包，再按 "名称_v版本.apk" 复制到根目录。

    APK 不入库（.gitignore 已忽略 *.apk），脚本本身可以提交。

.PARAMETER SkipTests
    跳过单元测试。默认会先跑两个 flavor 的单元测试，失败即中止。

.PARAMETER Clean
    构建前先 clean，避免增量构建残留旧资源。

.EXAMPLE
    .\package-apk.ps1
    .\package-apk.ps1 -SkipTests
    .\package-apk.ps1 -Clean
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $root

# ---------- 读取版本号 ----------
# 版本号只在 app/build.gradle 维护一份，这里解析，避免两处不一致。
$gradleFile = Join-Path $root 'app/build.gradle'
if (-not (Test-Path $gradleFile)) {
    throw "找不到 $gradleFile"
}

$gradleText = Get-Content $gradleFile -Raw
$nameMatch = [regex]::Match($gradleText, 'appVersionName\s*=\s*"([^"]+)"')
$codeMatch = [regex]::Match($gradleText, 'appVersionCode\s*=\s*(\d+)')
if (-not $nameMatch.Success -or -not $codeMatch.Success) {
    throw "无法从 app/build.gradle 解析 appVersionName / appVersionCode"
}
$versionName = $nameMatch.Groups[1].Value
$versionCode = $codeMatch.Groups[1].Value

Write-Host "版本：$versionName (versionCode $versionCode)" -ForegroundColor Cyan

# ---------- 构建 ----------
$gradleArgs = @()
if ($SkipTests) {
    # 只打包时不必跑测试；-x test 对 Kotlin/Java 单元测试都生效。
    $gradleArgs += @('-x', 'test')
}

if ($Clean) {
    Write-Host '清理旧产物…' -ForegroundColor DarkGray
    & .\gradlew.bat clean --offline --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw 'gradle clean 失败' }
}

if (-not $SkipTests) {
    Write-Host '运行单元测试（两个 flavor）…' -ForegroundColor DarkGray
    # javac 的 deprecation 注记是本机 GBK 编码，混在输出里是乱码，过滤掉。
    & .\gradlew.bat :app:testOriginalDebugUnitTest :app:testScheduleDebugUnitTest --offline --console=plain -q @gradleArgs |
        Where-Object { $_ -notmatch '^ע:|Xlint' }
    if ($LASTEXITCODE -ne 0) {
        throw '单元测试未通过，已中止打包'
    }
    $gradleArgs = @()
}

Write-Host '构建 Release APK…' -ForegroundColor DarkGray
& .\gradlew.bat :app:assembleRelease --offline --console=plain -q @gradleArgs |
    Where-Object { $_ -notmatch '^ע:|Xlint' }
if ($LASTEXITCODE -ne 0) { throw 'assembleRelease 失败' }

# ---------- 复制到根目录 ----------
# flavor 目录名 → 根目录文件名前缀。改名后桌面显示的名称来自 manifest 的 appLabel。
$targets = @(
    @{ Flavor = 'original'; File = "清蒸排班_v$versionName.apk" },
    @{ Flavor = 'schedule'; File = "排班_v$versionName.apk" }
)

$produced = @()
foreach ($t in $targets) {
    $src = Join-Path $root "app/build/outputs/apk/$($t.Flavor)/release/app-$($t.Flavor)-release.apk"
    if (-not (Test-Path $src)) {
        throw "未找到构建产物：$src"
    }
    $dest = Join-Path $root $t.File
    Copy-Item $src $dest -Force
    $sizeMb = [math]::Round((Get-Item $dest).Length / 1MB, 1)
    $produced += [pscustomobject]@{ 文件 = $t.File; 大小 = "$sizeMb MB" }
}

Write-Host ''
Write-Host '打包完成：' -ForegroundColor Green
foreach ($p in $produced) {
    Write-Host ("  {0}  ({1})" -f $p.文件, $p.大小)
}