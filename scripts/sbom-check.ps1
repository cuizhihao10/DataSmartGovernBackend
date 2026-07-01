<#
DataSmart Govern SBOM 就绪检查脚本。

SBOM（Software Bill of Materials，软件物料清单）的生产价值是让交付方和客户都能回答：
1. 当前发布包包含哪些 Java/Python 依赖、镜像基础层和运行时组件；
2. 这些组件是否可以被漏洞扫描、许可证审计和供应链准入系统识别；
3. 某个 CVE、基础镜像或依赖库出现风险时，能否快速定位受影响服务。

本脚本处于“生产化收敛”的第一步，默认不下载依赖、不构建镜像、不读取 Secret、不访问生产仓库。
它先检查仓库是否具备生成 SBOM 所需的源头信息：Maven reactor、Python pyproject、Dockerfile、Compose 镜像变量和忽略规则。
如果本机安装了 Syft，可以在后续发布阶段对已构建镜像执行真实 CycloneDX/SPDX SBOM 生成；如果未安装，本脚本只给出 WARN，
不阻断当前本地闭环。进入正式 CI 发布门禁时，可以通过 -StrictTooling 把缺少 Syft 或存在 latest 镜像 tag 视为失败。
#>
[CmdletBinding()]
param(
    [switch]$StrictTooling,
    [switch]$WriteSourceInventory,
    [string]$OutputDirectory = "target/sbom"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$passCount = 0
$warnCount = 0
$failCount = 0

function Write-CheckResult {
    param(
        [ValidateSet("PASS", "WARN", "FAIL")]
        [string]$Level,
        [string]$Name,
        [string]$Detail
    )

    if ($Level -eq "PASS") {
        $script:passCount++
        Write-Host "[PASS] $Name - $Detail"
        return
    }

    if ($Level -eq "WARN") {
        $script:warnCount++
        Write-Host "[WARN] $Name - $Detail"
        return
    }

    $script:failCount++
    Write-Host "[FAIL] $Name - $Detail"
}

function Get-RepositoryContent {
    param([string]$RelativePath)

    $path = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        return $null
    }

    return Get-Content -Raw -LiteralPath $path
}

function Test-RequiredText {
    param(
        [string]$RelativePath,
        [string]$ExpectedText,
        [string]$Purpose
    )

    $content = Get-RepositoryContent -RelativePath $RelativePath
    if ($null -eq $content) {
        Write-CheckResult -Level "FAIL" -Name $RelativePath -Detail "missing file: $Purpose"
        return
    }

    if ($content.Contains($ExpectedText)) {
        Write-CheckResult -Level "PASS" -Name $RelativePath -Detail $Purpose
    }
    else {
        Write-CheckResult -Level "FAIL" -Name $RelativePath -Detail "missing '$ExpectedText': $Purpose"
    }
}

function Get-MavenModules {
    $pom = Get-RepositoryContent -RelativePath "pom.xml"
    if ($null -eq $pom) {
        return @()
    }

    return [regex]::Matches($pom, "<module>([^<]+)</module>") |
        ForEach-Object { $_.Groups[1].Value }
}

function Get-PythonOptionalGroups {
    $pyproject = Get-RepositoryContent -RelativePath "python-ai-runtime/pyproject.toml"
    if ($null -eq $pyproject) {
        return @()
    }

    return [regex]::Matches($pyproject, "^(?<name>[A-Za-z0-9_-]+)\s*=\s*\[", "Multiline") |
        ForEach-Object { $_.Groups["name"].Value }
}

function Get-ComposeImageVariables {
    $envExample = Get-RepositoryContent -RelativePath ".env.application.example"
    if ($null -eq $envExample) {
        return @()
    }

    return [regex]::Matches($envExample, "^(?<name>DATASMART_[A-Z0-9_]+_IMAGE)=(?<value>.+)$", "Multiline") |
        ForEach-Object {
            [pscustomobject]@{
                Name = $_.Groups["name"].Value
                Value = $_.Groups["value"].Value.Trim()
            }
        }
}

function Test-CommandAvailable {
    param([string]$CommandName)

    return $null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)
}

Push-Location $repositoryRoot
try {
    Write-Host "[STEP] verify source manifests for SBOM"
    Test-RequiredText -RelativePath "pom.xml" -ExpectedText "<modules>" -Purpose "Maven reactor declares Java modules that must be represented in Java SBOM"
    Test-RequiredText -RelativePath "pom.xml" -ExpectedText "<java.version>21</java.version>" -Purpose "SBOM metadata can bind the Java runtime baseline to JDK 21"
    Test-RequiredText -RelativePath "python-ai-runtime/pyproject.toml" -ExpectedText "[project.optional-dependencies]" -Purpose "Python Runtime optional dependencies are declared in pyproject"
    Test-RequiredText -RelativePath "python-ai-runtime/pyproject.toml" -ExpectedText "langgraph" -Purpose "AI Runtime SBOM must expose LangGraph dependency surface"

    $javaModules = @(Get-MavenModules)
    foreach ($module in $javaModules) {
        $modulePom = Join-Path $repositoryRoot "$module/pom.xml"
        if (Test-Path -LiteralPath $modulePom) {
            Write-CheckResult -Level "PASS" -Name "$module/pom.xml" -Detail "Java module can be included in Maven dependency SBOM"
        }
        else {
            Write-CheckResult -Level "FAIL" -Name "$module/pom.xml" -Detail "module is declared in root pom but has no module pom"
        }
    }

    Write-Host "[STEP] verify container SBOM sources"
    Test-RequiredText -RelativePath "docker/runtime/java-service.Dockerfile" -ExpectedText "ARG MAVEN_IMAGE" -Purpose "Java image SBOM can identify builder base image"
    Test-RequiredText -RelativePath "docker/runtime/java-service.Dockerfile" -ExpectedText "ARG JAVA_RUNTIME_IMAGE" -Purpose "Java image SBOM can identify runtime base image"
    Test-RequiredText -RelativePath "docker/runtime/python-ai-runtime.Dockerfile" -ExpectedText "ARG PYTHON_IMAGE" -Purpose "Python image SBOM can identify Python base image"
    Test-RequiredText -RelativePath ".dockerignore" -ExpectedText ".m2" -Purpose "local Maven cache must not enter Docker build context or SBOM accidentally"
    Test-RequiredText -RelativePath ".dockerignore" -ExpectedText "__pycache__" -Purpose "Python cache files must not enter Docker build context or SBOM accidentally"
    Test-RequiredText -RelativePath ".gitignore" -ExpectedText ".env.application" -Purpose "local secret env file must not enter source control or SBOM source inventory"

    Write-Host "[STEP] inspect Compose image variables"
    $imageVariables = @(Get-ComposeImageVariables)
    if ($imageVariables.Count -eq 0) {
        Write-CheckResult -Level "FAIL" -Name ".env.application.example" -Detail "no DATASMART_*_IMAGE variables found"
    }
    else {
        Write-CheckResult -Level "PASS" -Name ".env.application.example" -Detail "found $($imageVariables.Count) image variables for image SBOM scope"
    }

    foreach ($image in $imageVariables) {
        if ($image.Value -match ":latest$") {
            Write-CheckResult -Level "WARN" -Name $image.Name -Detail "image uses latest tag; production SBOM should bind a fixed digest or immutable tag"
        }
        else {
            Write-CheckResult -Level "PASS" -Name $image.Name -Detail "image tag is explicit: $($image.Value)"
        }
    }

    Write-Host "[STEP] inspect optional SBOM tooling"
    if (Test-CommandAvailable -CommandName "syft") {
        Write-CheckResult -Level "PASS" -Name "syft" -Detail "Syft is available for image/source SBOM generation"
    }
    else {
        Write-CheckResult -Level "WARN" -Name "syft" -Detail "Syft is not installed; install it in release CI to generate CycloneDX/SPDX SBOM"
    }

    if (Test-CommandAvailable -CommandName "mvn") {
        Write-CheckResult -Level "PASS" -Name "mvn" -Detail "Maven is available for Java dependency graph extraction"
    }
    else {
        Write-CheckResult -Level "WARN" -Name "mvn" -Detail "Maven is not available; Java dependency SBOM cannot be generated on this machine"
    }

    if (Test-CommandAvailable -CommandName "python") {
        Write-CheckResult -Level "PASS" -Name "python" -Detail "Python is available for Python Runtime dependency inspection"
    }
    else {
        Write-CheckResult -Level "WARN" -Name "python" -Detail "Python is not available; Python dependency SBOM cannot be generated on this machine"
    }

    if ($WriteSourceInventory) {
        Write-Host "[STEP] write source inventory"
        $resolvedOutputDirectory = Join-Path $repositoryRoot $OutputDirectory
        New-Item -ItemType Directory -Force -Path $resolvedOutputDirectory | Out-Null

        $inventory = [pscustomobject]@{
            schema = "datasmart.sbom.source-inventory.v1"
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            javaModules = $javaModules
            pythonOptionalDependencyGroups = @(Get-PythonOptionalGroups)
            dockerfiles = @(
                "docker/runtime/java-service.Dockerfile",
                "docker/runtime/python-ai-runtime.Dockerfile"
            )
            composeImageVariables = $imageVariables
            notes = @(
                "This source inventory is not a complete CycloneDX/SPDX SBOM.",
                "Use Syft or enterprise SBOM tooling against built images in release CI.",
                "No secrets, tokens, environment values, prompt data, or business data are included."
            )
        }

        $outputFile = Join-Path $resolvedOutputDirectory "datasmart-source-inventory.json"
        $inventory | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $outputFile
        Write-CheckResult -Level "PASS" -Name $OutputDirectory -Detail "wrote source inventory to $outputFile"
    }

    Write-Host "[SUMMARY] PASS=$passCount, WARN=$warnCount, FAIL=$failCount"

    if ($failCount -gt 0) {
        exit 1
    }

    if ($StrictTooling -and $warnCount -gt 0) {
        Write-Host "[FAIL] strict SBOM tooling gate is enabled, warnings are treated as release blockers"
        exit 1
    }

    Write-Host "[PASS] SBOM readiness check completed"
}
finally {
    Pop-Location
}
