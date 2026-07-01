<#
DataSmart Govern 镜像签名验证就绪检查脚本。

这个脚本只处理“验证侧”和“准入侧”，不负责生成私钥、保存私钥、上传镜像或替发布系统签名。
这样设计是为了把生产供应链边界拆清楚：
1. CI/CD 或企业镜像仓库负责在镜像构建完成后执行签名，例如 Cosign keyless、KMS 托管密钥或企业制品库内置签名能力；
2. 本仓库负责提供可重复的验证入口，确认 Compose 镜像清单、签名工具、签名策略和文档说明没有缺口；
3. 部署前门禁负责对“已发布到 registry 的镜像引用”执行 cosign verify，而不是对本地未发布镜像做无意义检查。

默认模式不会访问网络，也不会读取 Secret：
- 它读取 `.env.application.example` 中的 `DATASMART_*_IMAGE` 变量，确认镜像范围可枚举；
- 它提醒仍使用 `latest` tag 的镜像需要在生产发布前固定到不可变 tag 或 digest；
- 它检查本机是否安装 `cosign`，未安装时只给 WARN，避免阻断当前本地收敛；
- 它检查生产文档是否已经说明 Cosign、镜像签名和 Secret 边界。

当传入 `-VerifyPublishedImages -Images ...` 时，脚本才会调用 `cosign verify`。
这一步需要镜像已经推送到可访问 registry，并且必须提供 keyless 身份策略或公钥策略：
- keyless 策略：`-CosignIdentityRegexp` 与 `-CosignIssuer`；
- 公钥策略：`-CosignPublicKey`，该文件应是公钥，不应是私钥。

为什么不提供 sign-images.ps1：
- 签名动作往往需要私钥、OIDC 工作负载身份、KMS 权限或企业仓库凭据；
- 把签名动作放在开发机脚本里容易诱导把敏感凭据落入仓库或日志；
- 当前项目处于“生产交付闭环”阶段，更适合先提供可审查、可门禁的验证脚本，把真正签名放到发布流水线。
#>
[CmdletBinding()]
param(
    [switch]$StrictTooling,
    [switch]$VerifyPublishedImages,
    [string[]]$Images = @(),
    [string]$CosignIdentityRegexp = "",
    [string]$CosignIssuer = "",
    [string]$CosignPublicKey = ""
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

    return Get-Content -Raw -Encoding UTF8 -LiteralPath $path
}

function Test-CommandAvailable {
    param([string]$CommandName)

    return $null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)
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

function Test-ImageReferencePolicy {
    param(
        [string]$Name,
        [string]$Image
    )

    if ([string]::IsNullOrWhiteSpace($Image)) {
        Write-CheckResult -Level "FAIL" -Name $Name -Detail "image reference is empty"
        return
    }

    if ($Image -match "\s") {
        Write-CheckResult -Level "FAIL" -Name $Name -Detail "image reference contains whitespace"
        return
    }

    if ($Image -match ":latest$") {
        Write-CheckResult -Level "WARN" -Name $Name -Detail "latest tag is acceptable for local demos, but production signing should use immutable tag or digest"
        return
    }

    if ($Image -match "@sha256:[a-fA-F0-9]{64}$") {
        Write-CheckResult -Level "PASS" -Name $Name -Detail "image is pinned by digest: $Image"
        return
    }

    if ($Image -match ":[^/:]+$") {
        Write-CheckResult -Level "PASS" -Name $Name -Detail "image uses an explicit non-latest tag: $Image"
        return
    }

    Write-CheckResult -Level "WARN" -Name $Name -Detail "image has no explicit tag or digest; production signing should verify immutable references"
}

function Test-CosignVerificationPolicy {
    $hasKeylessPolicy = -not [string]::IsNullOrWhiteSpace($CosignIdentityRegexp) -and
        -not [string]::IsNullOrWhiteSpace($CosignIssuer)
    $hasPublicKeyPolicy = -not [string]::IsNullOrWhiteSpace($CosignPublicKey)

    if ($hasPublicKeyPolicy) {
        if (Test-Path -LiteralPath $CosignPublicKey) {
            Write-CheckResult -Level "PASS" -Name "cosign-policy" -Detail "public-key verification policy is configured"
            return $true
        }

        Write-CheckResult -Level "FAIL" -Name "cosign-policy" -Detail "public key path does not exist"
        return $false
    }

    if ($hasKeylessPolicy) {
        Write-CheckResult -Level "PASS" -Name "cosign-policy" -Detail "keyless identity and issuer policy are configured"
        return $true
    }

    if ($VerifyPublishedImages) {
        Write-CheckResult -Level "WARN" -Name "cosign-policy" -Detail "published image verification needs keyless identity+issuer or public key policy"
    }
    else {
        Write-CheckResult -Level "PASS" -Name "cosign-policy" -Detail "verification policy can be supplied when release images are available"
    }

    return $false
}

function Invoke-CosignVerify {
    param([string]$Image)

    $arguments = @("verify")

    if (-not [string]::IsNullOrWhiteSpace($CosignPublicKey)) {
        $arguments += @("--key", $CosignPublicKey)
    }
    else {
        $arguments += @("--certificate-identity-regexp", $CosignIdentityRegexp)
        $arguments += @("--certificate-oidc-issuer", $CosignIssuer)
    }

    $arguments += $Image

    & cosign @arguments *> $null
    if ($LASTEXITCODE -eq 0) {
        Write-CheckResult -Level "PASS" -Name $Image -Detail "cosign signature verification passed"
    }
    else {
        Write-CheckResult -Level "FAIL" -Name $Image -Detail "cosign signature verification failed"
    }
}

Push-Location $repositoryRoot
try {
    Write-Host "[STEP] inspect image references for signing scope"
    $imageVariables = @(Get-ComposeImageVariables)
    if ($imageVariables.Count -eq 0) {
        Write-CheckResult -Level "FAIL" -Name ".env.application.example" -Detail "no DATASMART_*_IMAGE variables found"
    }
    else {
        Write-CheckResult -Level "PASS" -Name ".env.application.example" -Detail "found $($imageVariables.Count) image variables for signature verification scope"
    }

    foreach ($image in $imageVariables) {
        Test-ImageReferencePolicy -Name $image.Name -Image $image.Value
    }

    Write-Host "[STEP] verify repository signing boundary"
    Test-RequiredText -RelativePath ".gitignore" -ExpectedText ".env.application" -Purpose "local env file may contain registry credentials and must stay out of Git"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "Cosign" -Purpose "production hardening docs must mention Cosign or equivalent image signing"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "CI/CD" -Purpose "production hardening docs must keep signing inside release pipelines"
    Test-RequiredText -RelativePath "docs/production-environment-values.md" -ExpectedText "Harbor/Nexus" -Purpose "environment values docs must connect production image registry and signing"

    Write-Host "[STEP] inspect optional cosign tooling"
    $cosignAvailable = Test-CommandAvailable -CommandName "cosign"
    if ($cosignAvailable) {
        Write-CheckResult -Level "PASS" -Name "cosign" -Detail "Cosign is available for release image signature verification"
    }
    else {
        Write-CheckResult -Level "WARN" -Name "cosign" -Detail "Cosign is not installed; install it in release CI or enterprise deployment gate"
    }

    $policyReady = Test-CosignVerificationPolicy

    if ($VerifyPublishedImages) {
        Write-Host "[STEP] verify published image signatures"

        if (-not $cosignAvailable) {
            Write-CheckResult -Level "FAIL" -Name "cosign" -Detail "cannot verify published images because Cosign is unavailable"
        }
        elseif (-not $policyReady) {
            Write-CheckResult -Level "FAIL" -Name "cosign-policy" -Detail "cannot verify published images without a verification policy"
        }
        elseif ($Images.Count -eq 0) {
            Write-CheckResult -Level "WARN" -Name "published-images" -Detail "no images were supplied; pass -Images with registry image references"
        }
        else {
            foreach ($image in $Images) {
                Invoke-CosignVerify -Image $image
            }
        }
    }

    Write-Host "[SUMMARY] PASS=$passCount, WARN=$warnCount, FAIL=$failCount"

    if ($failCount -gt 0) {
        exit 1
    }

    if ($StrictTooling -and $warnCount -gt 0) {
        Write-Host "[FAIL] strict image signature gate is enabled, warnings are treated as release blockers"
        exit 1
    }

    Write-Host "[PASS] image signature readiness check completed"
}
finally {
    Pop-Location
}
