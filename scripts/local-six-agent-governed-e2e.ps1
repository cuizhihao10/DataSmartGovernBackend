<#
.SYNOPSIS
    DataSmart Govern 六专业 Agent 的真实本地黑盒 E2E 验收脚本。

.DESCRIPTION
    本脚本只通过本地 Keycloak、Gateway 和公开 Agent API 验证六个专业 Agent 的真实运行链路，
    不导入 Python 模块、不替换真实模型、不伪造 specialist fact，也不直接访问业务数据库。

    六个角色为：
    - KNOWLEDGE_AGENT：RAG/知识与历史案例证据；
    - DATASOURCE_AGENT：数据源名称消歧、权限范围内发现和元数据入口；
    - DATA_SYNC_AGENT：同步任务规划、对象/字段映射和生命周期 ToolPlan；
    - PRECHECK_AGENT：基于 Java 控制面事实执行确定性预检查；
    - RECOVERY_AGENT：读取失败事实并生成受审批约束的恢复方案；
    - MONITOR_AGENT：只读观察任务和执行状态。

    安全默认值：
    - 不带 -Execute 时只打印计划，不请求 Keycloak，不调用 Agent API，不创建任务；
    - 带 -Execute 时才会通过 project-owner 获取短期 access token；token、密码、API key 和响应正文永不输出；
    - Recovery 场景要求可审计诊断证据，但由模型决定是否继续调用 RAG；高风险动作只验证进入审批/Java handoff；
    - 只解析低敏角色、状态、稳定 ID、计数和建议，不输出 prompt、模型原文、SQL、工具参数或连接信息。

    典型用法：
    - 只查看当前验收计划：
      .\scripts\local-six-agent-governed-e2e.ps1
    - 成功场景，流式调用 Gateway：
      $env:DATASMART_KEYCLOAK_LOCAL_USER_PASSWORD = '<本地 project-owner 密码>'
      .\scripts\local-six-agent-governed-e2e.ps1 -Execute -ConfirmAndExecute `
        -SourceDatasourceName 'FlashSync MySQL 源' `
        -TargetDatasourceName 'FlashSync PostgreSQL 目标' `
        -SourceObjectName 'datasmart_e2e_platform_orders' `
        -TargetSchemaName 'datasmart_e2e' -TargetObjectName 'orders_platform_clean' `
        -Objective '将 MySQL 的 datasmart_e2e_platform_orders 全量同步到 PostgreSQL datasmart_e2e schema 的 orders_platform_clean，并完成预检查后执行'
    - 恢复场景，验证诊断证据 -> 模型按需检索 -> 受治理恢复建议：
      .\scripts\local-six-agent-governed-e2e.ps1 -Execute -Scenario Recovery `
        -SourceDatasourceName 'FlashSync MySQL 源' `
        -TargetDatasourceName 'FlashSync PostgreSQL 目标' `
        -TaskId 701 -ExecutionId 9001 -FailureCode 'DIRTY_DATA' `
        -FailureReference '本地失败执行的受控引用' `
        -Objective '排查这次同步失败，结合历史案例提出恢复方案，并在用户审批前停止'
    - 只调用同步 JSON 接口而不读取 NDJSON：
      .\scripts\local-six-agent-governed-e2e.ps1 -Execute -UsePlanEndpoint ...

    注意：
    - SourceDatasourceName/TargetDatasourceName 必须是当前 project-owner 在 projectId 范围内可见的数据源名称；
    - objective 应该包含真实的表映射、同步模式和必要配置，脚本不会替模型补造表名或字段；
    - -Skip* 选项只用于分阶段排障，不应作为最终验收的替代品；
    - 本脚本默认只验证 Agent 计划和控制面反馈，不代替 data-sync 的数据库数据量、日志和业务结果验收。
#>

[CmdletBinding()]
param(
    # 只有显式传入 -Execute 才会访问 Keycloak 和 Agent API；默认行为是安全的 PlanOnly。
    [switch]$Execute,

    # Success 场景只有显式传入该开关才会批准当前生命周期 Run；Recovery 场景始终禁止自动批准修复动作。
    [switch]$ConfirmAndExecute,

    # 只有首次 Success 确认可以建立有界 AUTOPILOT 授权；后续恢复继续由服务端按该快照自动决策。
    [switch]$EnableAutopilot,

    # 无人值守恢复的循环和总时长预算。后端仍会再次执行更严格的范围与上限校验。
    [ValidateRange(1, 10)]
    [int]$AutopilotMaxRecoveryCycles = 3,
    [ValidateRange(5, 1440)]
    [int]$AutopilotMaxTotalDurationMinutes = 120,

    # 允许调用方显式写出只读模式；与默认行为等价，但不能和 -Execute 同时使用。
    [switch]$PlanOnly,

    # 成功场景验证同步规划和后置复核；恢复场景验证诊断证据、模型检索决策和恢复治理门禁。
    [ValidateSet('Success', 'Recovery')]
    [string]$Scenario = 'Success',

    # 默认使用真实 NDJSON 流式接口；该开关用于排查网关或流式传输问题。
    [switch]$UsePlanEndpoint,

    # 以下开关只用于阶段性排查。最终验收不建议跳过任何一项。
    [switch]$SkipRoleAssertion,
    [switch]$SkipBridgeAssertion,
    [switch]$SkipPostBridgeAssertion,
    [switch]$SkipDurableFactAssertion,
    [switch]$RequireAllSixRolesExecuted,

    # 只构造脚本内的低敏响应夹具来回归 Specialist 状态聚合；不会读取凭据、访问网络或创建任务。
    # 它存在的目的是让“首轮失败、后置同角色成功”的显示语义可以脱离真实服务稳定复现和验证。
    [switch]$RunSpecialistStatusAggregationRegressionTest,

    # 只构造公开 AgentRequest 并断言异名对象映射进入结构化基线；不会读取凭据、访问网络或创建任务。
    [switch]$RunRequestContractRegressionTest,

    # 只构造确认回执、低敏 Specialist fact、扁平 recovery 状态和执行账本夹具；用于防止 E2E 再次等待
    # 不存在的嵌套内部对象。该回归不读取凭据、不访问 Gateway，也不会创建或重试真实任务。
    [switch]$RunAutopilotPublicContractRegressionTest,

    # 统一产品入口。脚本不绕过 Gateway 访问 Python Runtime。
    [string]$GatewayBaseUrl = 'http://localhost:8080',
    [string]$KeycloakBaseUrl = 'http://localhost:18080',
    [string]$KeycloakRealm = 'datasmart',
    [string]$KeycloakClientId = 'datasmart-gateway',
    [string]$KeycloakUsername = 'project-owner',

    # 密码只从参数或环境变量读取，永远不写入输出；推荐只使用环境变量，避免命令历史记录保存密码。
    [string]$KeycloakPassword = '',

    # 当前租户/项目/用户范围。Gateway 会以 JWT 和 permission-admin 结果重新建立可信上下文。
    [long]$TenantId = 10,
    [long]$ProjectId = 101,
    [string]$ActorId = '1001',

    # 数据源名称用于 DATASOURCE_AGENT 消歧；可选 ID 只用于减少歧义，不能替代 Gateway 权限校验。
    [string]$SourceDatasourceName = '',
    [string]$TargetDatasourceName = '',
    [string]$SourceDatasourceId = '',
    [string]$TargetDatasourceId = '',
    [string]$SourceConnectorType = 'MYSQL',
    [string]$TargetConnectorType = 'POSTGRESQL',

    # 异名表不能依赖模型从自然语言猜测映射。非空时，脚本把这组用户审核值写入结构化 objectMappings。
    # MySQL 的 database 属于 JDBC catalog，通常不应填写 SourceSchemaName；PostgreSQL 等连接器才使用 schema。
    [string]$SourceSchemaName = '',
    [string]$SourceObjectName = '',
    [string]$TargetSchemaName = '',
    [string]$TargetObjectName = '',

    # INSERT 用于验证空目标表准入；UPDATE 对应产品中的 merge/upsert，适合目标表已有主键数据的验收。
    [ValidateSet('INSERT', 'UPDATE')]
    [string]$WriteStrategy = 'INSERT',

    # objective 是唯一的自然语言业务目标；不要在其中放密码、token 或连接串。
    [string]$Objective = '',

    # Recovery 场景可传已有任务/执行定位；脚本只把它作为受控业务上下文传给 Agent，不自行执行。
    [string]$TaskId = '',
    [string]$ExecutionId = '',
    [string]$FailureCode = '',
    [string]$FailureReference = '',

    # Agent 规划是长耗时操作，超时只影响本脚本等待，不会中止服务端已经提交的控制面事实。
    [ValidateRange(10, 1800)]
    [int]$TimeoutSeconds = 180,

    # 确认提交后轮询真实 data-sync execution 的最长等待时间，与模型规划超时相互独立。
    [ValidateRange(10, 3600)]
    [int]$ExecutionTimeoutSeconds = 300,

    # 大于 0 时额外断言对象账本与总记录数；0 表示只要求存在对象且成功写入正数记录。
    [ValidateRange(0, 10000)]
    [int]$ExpectedObjectCount = 0,
    [ValidateRange(0, 9223372036854775807)]
    [long]$ExpectedRecordCount = 0,
    [string]$RequestId = ''
)

$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 不保证在首次引用 HttpClient 时自动加载 System.Net.Http。
# 本脚本使用 ResponseHeadersRead 消费 NDJSON 流，如果缺少该程序集会在访问 Keycloak 之前直接失败，
# 造成“Agent 链路未执行却被误认为服务故障”。显式加载只初始化 .NET HTTP 类型，不访问网络、不读取凭据，
# 并且在 PowerShell 7 中也是幂等操作。
Add-Type -AssemblyName System.Net.Http

$script:ExpectedRoles = @(
    'KNOWLEDGE_AGENT',
    'DATASOURCE_AGENT',
    'DATA_SYNC_AGENT',
    'PRECHECK_AGENT',
    'RECOVERY_AGENT',
    'MONITOR_AGENT'
)
$script:SuccessPlanningExecutedRoles = @(
    'DATASOURCE_AGENT',
    'DATA_SYNC_AGENT'
)
$script:SuccessPostConfirmationExecutedRoles = @(
    'DATASOURCE_AGENT',
    'DATA_SYNC_AGENT',
    'PRECHECK_AGENT',
    'MONITOR_AGENT'
)
$script:RecoveryExecutedRoles = @(
    'RECOVERY_AGENT',
    'MONITOR_AGENT'
)
$script:Checks = New-Object System.Collections.Generic.List[object]
$script:FailureCount = 0
$script:TerminalFailure = $false
$script:RunStamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmssfff')

if ([string]::IsNullOrWhiteSpace($RequestId)) {
    $RequestId = "local-six-agent-$($script:RunStamp)"
}

function Add-Check {
    <#
    .SYNOPSIS
        记录一条低敏验收结果并立即显示给操作者。

    .DESCRIPTION
        所有检查都通过本函数输出，避免调用方为了排障而直接打印 HTTP 响应。Detail 只应该包含稳定状态、
        数量、角色名、业务错误摘要和建议，不允许传入 token、密码、SQL、工具参数或模型原文。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][ValidateSet('PASS', 'WARN', 'FAIL')][string]$Status,
        [Parameter(Mandatory = $true)][string]$Detail
    )

    $safeDetail = Get-LowSensitiveMessage -Text $Detail -Fallback '检查未通过，详细原因已收敛为低敏摘要；请查看 traceId 对应的服务端审计日志。'
    $script:Checks.Add([pscustomobject]@{
        Name = $Name
        Status = $Status
        Detail = $safeDetail
    }) | Out-Null

    if ($Status -eq 'FAIL') {
        $script:FailureCount++
    }

    $color = switch ($Status) {
        'PASS' { 'Green' }
        'WARN' { 'Yellow' }
        default { 'Red' }
    }
    Write-Host "[$Status] $Name - $safeDetail" -ForegroundColor $color
}

function Stop-E2E {
    <#
    .SYNOPSIS
        以可读错误结束当前验收阶段。

    .DESCRIPTION
        该函数先登记 FAIL，再抛出只含人话的异常。上层 catch 只会打印这条脱敏信息，绝不打印异常对象中的
        HTTP 原文、响应正文或堆栈，从而保证验收脚本的失败输出本身不会成为敏感信息泄漏通道。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Detail
    )

    $safeDetail = Get-LowSensitiveMessage -Text $Detail -Fallback '验收阶段失败；请查看 traceId 对应的服务端审计日志。'
    Add-Check -Name $Name -Status 'FAIL' -Detail $safeDetail
    throw (New-SafeE2EException -Detail $safeDetail)
}

function Protect-LogText {
    <#
    .SYNOPSIS
        对即将进入终端的文本做最后一层低敏脱敏。

    .DESCRIPTION
        该函数不是安全边界，真正的安全边界是本脚本不打印响应正文和原始异常；它只是防御性地替换常见的
        Bearer、sk-、password、token、secret 和 API key 片段，并限制长度，防止代理错误页或第三方错误消息
        把凭据带到终端。模型输出、SQL 和工具参数不会因为该函数存在而被允许打印。
    #>
    param([AllowNull()][object]$Text)

    if ($null -eq $Text) {
        return ''
    }
    $safe = [string]$Text
    $safe = $safe.Replace("`r", ' ').Replace("`n", ' ').Trim()
    $safe = $safe -replace '(?i)bearer\s+[^\s,;]+', 'Bearer [已脱敏]'
    $safe = $safe -replace '(?i)sk-[a-z0-9_-]{8,}', 'sk-[已脱敏]'
    $safe = $safe -replace '(?i)(password|passwd|token|secret|api[_-]?key)\s*[:=]\s*[^,\s}]+', '$1=[已脱敏]'
    if ($safe.Length -gt 600) {
        $safe = $safe.Substring(0, 600) + '...'
    }
    return $safe
}

function Get-SafeStatusToken {
    <#
    .SYNOPSIS
        把服务端返回的状态、事件类型或错误码收敛为可安全显示的短标识。

    .DESCRIPTION
        流式事件的 type、severity 和 error code 通常是固定枚举，但脚本不能假定远端永远正确。
        本函数只允许字母开头、长度受限的标识，拒绝把任意正文、SQL、模型输出或工具参数当作状态打印。
        调用方应为无法通过白名单的值提供人话兜底文本。
    #>
    param(
        [AllowNull()][object]$Text,
        [Parameter(Mandatory = $true)][string]$Fallback
    )

    $safe = Protect-LogText -Text $Text
    if ([string]::IsNullOrWhiteSpace($safe) -or $safe -notmatch '^[A-Za-z][A-Za-z0-9_.:-]{0,79}$') {
        return $Fallback
    }
    return $safe
}

function Get-LowSensitiveMessage {
    <#
    .SYNOPSIS
        从服务端错误字段中提取可以给操作者看的低敏人话。

    .DESCRIPTION
        错误响应中的 message/detail 并不天然等于安全摘要，第三方驱动甚至可能把 SQL、连接串、工具参数、
        prompt 或模型原文拼进异常。这里采用“白名单式使用”的最后一道防线：先做凭据脱敏，再拒绝包含高风险
        关键词的正文，改用调用方提供的固定兜底说明。这样验收失败仍然可读，但不会因为排障而回显响应正文。
    #>
    param(
        [AllowNull()][object]$Text,
        [Parameter(Mandatory = $true)][string]$Fallback
    )

    $safe = Protect-LogText -Text $Text
    if ([string]::IsNullOrWhiteSpace($safe)) {
        return $Fallback
    }
    # 成功路径必须传入固定的业务摘要，不能复用服务端原文来规避本规则。保留完整关键词集，使任何可能
    # 指向载荷内容的错误消息继续回退到调用方提供的低敏说明。
    $unsafePattern = '(?i)\b(select|insert|update|delete|truncate|alter|create\s+table|drop\s+table|merge|sql|jdbc|dsn|prompt|model|completion|raw[_ -]?(prompt|output)|stack|traceback)\b|连接串|查询语句|工具参数|调用参数|模型原文|提示词|响应正文|堆栈'
    if ($safe -match $unsafePattern) {
        return $Fallback
    }
    return $safe
}

function ConvertFrom-JsonSafe {
    <#
    .SYNOPSIS
        在 Windows PowerShell 5.1 和 PowerShell 7 之间兼容解析 JSON。

    .DESCRIPTION
        PowerShell 7 的 ConvertFrom-Json 支持 -Depth，而 Windows PowerShell 5.1 没有这个参数。
        直接在脚本中无条件写 ConvertFrom-Json -Depth 会导致本地 5.1 执行时失败，因此这里先检查当前
        cmdlet 是否提供该参数：新版使用更深的解析深度，旧版使用原生解析器。函数只返回对象，不打印 JSON。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Json,
        [ValidateRange(1, 200)][int]$Depth = 100
    )

    $jsonCommand = Get-Command -Name 'ConvertFrom-Json' -CommandType Cmdlet -ErrorAction Stop
    if ($jsonCommand.Parameters.ContainsKey('Depth')) {
        return ($Json | ConvertFrom-Json -Depth $Depth)
    }
    return ($Json | ConvertFrom-Json)
}

function New-SafeE2EException {
    <#
    .SYNOPSIS
        创建带内部标记的低敏验收异常。

    .DESCRIPTION
        网络调用的 catch 不能通过匹配异常文本来判断“这是脚本自己生成的错误”，因为普通 .NET 异常也可能
        包含冒号或业务词，从而被误重抛并泄漏原文。本函数把已经脱敏的人话放入异常，并在 Data 中写入只供
        本脚本识别的标记；上层 catch 只重抛带标记的异常，其他异常统一转换为固定建议。
    #>
    param([Parameter(Mandatory = $true)][string]$Detail)

    $exception = New-Object -TypeName System.InvalidOperationException -ArgumentList (Protect-LogText -Text $Detail)
    $exception.Data['DataSmartSafeE2E'] = $true
    return $exception
}

function Test-SafeE2EException {
    <#
    .SYNOPSIS
        判断异常是否由本脚本的低敏错误边界创建。

    .DESCRIPTION
        该判断只读取异常 Data 中的固定布尔标记，不读取异常 Message。它用于网络请求 catch，确保服务端
        原始响应、HTTP 堆栈和驱动错误不会因为“为了保留人话”而被重新抛到最终输出。
    #>
    param([AllowNull()][object]$Exception)

    return ($null -ne $Exception -and $null -ne $Exception.Data -and $Exception.Data.Contains('DataSmartSafeE2E'))
}

function Get-FieldValue {
    <#
    .SYNOPSIS
        从 JSON 对象中按候选名称读取一个字段。

    .DESCRIPTION
        PowerShell 5.1 的 ConvertFrom-Json 会返回 PSCustomObject，PowerShell 7 也可能返回字典或数组。
        该函数统一处理这两种形态，并且只读取调用方明确指定的字段，不做任意对象序列化，避免误把模型正文
        或工具参数带入后续日志。
    #>
    param(
        [AllowNull()][object]$Object,
        [Parameter(Mandatory = $true)][string[]]$Names
    )

    if ($null -eq $Object) {
        return $null
    }
    foreach ($name in $Names) {
        if ($Object -is [System.Collections.IDictionary]) {
            foreach ($key in $Object.Keys) {
                if ([string]$key -ieq $name) {
                    return $Object[$key]
                }
            }
        }
        $property = $Object.PSObject.Properties | Where-Object { $_.Name -ieq $name } | Select-Object -First 1
        if ($null -ne $property) {
            return $property.Value
        }
    }
    return $null
}

function Get-Items {
    <#
    .SYNOPSIS
        把单值、数组和空值统一转换成 PowerShell 数组。

    .DESCRIPTION
        API 摘要中的 tuple/list 在不同 JSON 解析器下可能表现为单值或 ICollection。统一转换后，角色、桥接和
        durable fact 的断言可以使用同一套循环，而不会为了兼容类型去递归打印完整响应。
    #>
    param([AllowNull()][object]$Value)

    if ($null -eq $Value) {
        return @()
    }
    if ($Value -is [string] -or $Value -is [System.Collections.IDictionary]) {
        return @($Value)
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        return @($Value)
    }
    return @($Value)
}

function Format-SafeFieldErrors {
    <#
    .SYNOPSIS
        把服务端返回的字段级校验问题转换成可读、低敏的一行摘要。

    .DESCRIPTION
        FastAPI 的校验错误通常位于顶层 ``detail.fieldErrors``，而不是旧版脚本假设的
        ``error.suggestions``。如果直接把数组插入字符串，PowerShell 只会输出
        ``System.Object[]``，用户既看不到缺少了什么，也无法判断下一步应该修复请求体还是修复服务。
        这里仅读取约定的 field/message 两个字段，并沿用 Get-LowSensitiveMessage 的脱敏边界；
        因此可以显示“哪个字段有问题”，但不会把原始请求、模型输出、SQL 或连接信息回显到终端。
    #>
    param([AllowNull()][object]$Items)

    $summaries = @(
        Get-Items $Items |
            ForEach-Object {
                $field = Get-FieldValue -Object $_ -Names @('field', 'path', 'name')
                $message = Get-FieldValue -Object $_ -Names @('message', 'detail', 'reason')
                $safeField = Protect-LogText -Text $field
                $safeMessage = Get-LowSensitiveMessage -Text $message -Fallback '字段值不符合 Agent 接口要求。'
                if ([string]::IsNullOrWhiteSpace($safeField)) {
                    "请求字段：$safeMessage"
                } else {
                    "$safeField：$safeMessage"
                }
            } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Select-Object -First 10
    )
    return ($summaries -join '；')
}

function Format-SafeHttpDetail {
    <#
    .SYNOPSIS
        组合 HTTP 业务错误的主消息与字段级修复提示。

    .DESCRIPTION
        调用方不应该直接把一个 PowerShell 对象插入字符串，因为嵌套对象会退化为
        ``@{...}``，数组会退化为 ``System.Object[]``。这个函数把错误对象拆成三层：
        稳定错误消息、具体字段问题、最多三条建议。这样黑盒验收脚本的输出与前端错误弹窗
        使用同一套“先说清问题，再告诉用户怎么做”的语义，同时仍然不输出原始响应正文。
    #>
    param([Parameter(Mandatory = $true)][object]$Detail)

    $parts = New-Object System.Collections.Generic.List[string]
    $message = [string](Get-FieldValue -Object $Detail -Names @('Message', 'message'))
    if (-not [string]::IsNullOrWhiteSpace($message)) {
        $parts.Add($message.Trim()) | Out-Null
    }
    $fieldSummary = Format-SafeFieldErrors -Items (Get-FieldValue -Object $Detail -Names @('FieldErrors', 'fieldErrors'))
    if (-not [string]::IsNullOrWhiteSpace($fieldSummary)) {
        $parts.Add("具体问题：$fieldSummary") | Out-Null
    }
    return ($parts -join '；')
}

function Test-PositiveIdentifier {
    <#
    .SYNOPSIS
        判断 taskId/executionId 是否像 Java 控制面返回的正整数 ID。

    .DESCRIPTION
        这里只接受正十进制整数，拒绝布尔值、UUID、模型自造文本、负数和浮点数。这样后置 PRECHECK/MONITOR
        只能由真实控制面定位事实触发，不能被 objective 或模型摘要中的类似字符串伪造。
    #>
    param([AllowNull()][object]$Value)

    if ($null -eq $Value -or $Value -is [bool]) {
        return $false
    }
    $text = ([string]$Value).Trim()
    return $text -match '^[1-9][0-9]{0,18}$'
}

function Assert-BasicInputs {
    <#
    .SYNOPSIS
        在网络请求前校验脚本参数。

    .DESCRIPTION
        参数校验的目的不是替代服务端权限和业务校验，而是尽早发现“没有项目、没有数据源名称、恢复没有
        失败定位”等脚本使用错误。错误消息只引用字段名和值的业务含义，不输出任何 Secret。
    #>
    if ($Execute -and $PlanOnly) {
        Stop-E2E -Name '脚本模式' -Detail '不能同时指定 -Execute 和 -PlanOnly；默认不执行，若要调用 Agent 请只使用 -Execute。'
    }
    if ($ConfirmAndExecute -and -not $Execute) {
        Stop-E2E -Name '显式确认模式' -Detail '-ConfirmAndExecute 只能和 -Execute 一起使用；只读计划模式不会批准任何 Agent Run。'
    }
    if ($ConfirmAndExecute -and $Scenario -eq 'Recovery') {
        Stop-E2E -Name '恢复审批边界' -Detail 'Recovery 场景禁止使用 -ConfirmAndExecute；高风险变更仍必须停留在产品治理审批边界内。'
    }
    if ($EnableAutopilot -and (-not $Execute -or -not $ConfirmAndExecute -or $Scenario -ne 'Success')) {
        Stop-E2E -Name 'Autopilot 首次授权边界' -Detail '-EnableAutopilot 只能用于 Success 场景的 -Execute -ConfirmAndExecute；它不能在恢复请求中补授或扩大权限。'
    }
    if ($TenantId -le 0 -or $ProjectId -le 0 -or [string]::IsNullOrWhiteSpace($ActorId)) {
        Stop-E2E -Name '租户项目上下文' -Detail 'TenantId、ProjectId 和 ActorId 必须是非空的合法上下文。'
    }
    if ($Execute) {
        if ([string]::IsNullOrWhiteSpace($SourceDatasourceName) -or [string]::IsNullOrWhiteSpace($TargetDatasourceName)) {
            Stop-E2E -Name '数据源参数' -Detail '执行真实验收前必须分别提供 -SourceDatasourceName 和 -TargetDatasourceName；MySQL/PostgreSQL 类型不能代替数据源名称。'
        }
    }
    foreach ($connector in @($SourceConnectorType, $TargetConnectorType)) {
        if ([string]::IsNullOrWhiteSpace($connector) -or $connector -notmatch '^[A-Za-z][A-Za-z0-9_-]{0,31}$') {
            Stop-E2E -Name '连接器参数' -Detail 'SourceConnectorType/TargetConnectorType 必须是安全的连接器类型标识。'
        }
    }
    $objectSelectors = @($SourceSchemaName, $SourceObjectName, $TargetSchemaName, $TargetObjectName)
    $hasObjectSelector = @($objectSelectors | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count -gt 0
    if ($hasObjectSelector -and (
            [string]::IsNullOrWhiteSpace($SourceObjectName) -or
            [string]::IsNullOrWhiteSpace($TargetObjectName)
        )) {
        Stop-E2E -Name '对象映射参数' -Detail '提供结构化对象映射时必须同时填写 SourceObjectName 和 TargetObjectName；异名表不能只填写一端。'
    }
    foreach ($selector in $objectSelectors) {
        if (-not [string]::IsNullOrWhiteSpace($selector) -and $selector -notmatch '^[A-Za-z_][A-Za-z0-9_$]{0,127}$') {
            Stop-E2E -Name '对象映射参数' -Detail 'Schema/Object 名称必须是安全数据库标识符；脚本不接受 SQL 片段、引号或限定表达式。'
        }
    }
    if ($Scenario -eq 'Recovery' -and [string]::IsNullOrWhiteSpace($TaskId) -and [string]::IsNullOrWhiteSpace($ExecutionId)) {
        Stop-E2E -Name '恢复定位参数' -Detail 'Recovery 场景至少需要 -TaskId 或 -ExecutionId，Agent 才能基于真实失败执行进行诊断。'
    }
    foreach ($identifier in @(
            [pscustomobject]@{ Name = 'TaskId'; Value = $TaskId },
            [pscustomobject]@{ Name = 'ExecutionId'; Value = $ExecutionId }
        )) {
        if (-not [string]::IsNullOrWhiteSpace([string]$identifier.Value) -and -not (Test-PositiveIdentifier $identifier.Value)) {
            Stop-E2E -Name '任务执行定位参数' -Detail "$($identifier.Name) 必须是正整数；脚本不会把 UUID、负数或模型文本当作真实控制面定位。"
        }
    }
    if ([string]::IsNullOrWhiteSpace($Objective)) {
        if ($Scenario -eq 'Recovery') {
            $script:EffectiveObjective = '请基于当前失败的同步任务和执行日志排查问题，优先检索历史案例，提出恢复方案并在用户审批前停止。'
        } else {
            $script:EffectiveObjective = '请使用当前项目中已授权的源端和目标端数据源完成一次全量数据同步，核对对象映射和字段映射，通过预检查后执行。'
        }
    } else {
        $script:EffectiveObjective = $Objective.Trim()
    }
    if ([string]::IsNullOrWhiteSpace($RequestId) -or $RequestId -notmatch '^[A-Za-z0-9._:-]{8,120}$') {
        Stop-E2E -Name '请求关联 ID' -Detail 'RequestId 必须是 8 到 120 位安全字符，用于关联流式帧和审计事实。'
    }
    $scriptMode = if ($ConfirmAndExecute) {
        'EXECUTE_WITH_EXPLICIT_SUCCESS_CONFIRMATION'
    } elseif ($Execute) {
        'EXECUTE_WITHOUT_CONFIRMATION'
    } else {
        'PLAN_ONLY'
    }
    Add-Check -Name '本地参数' -Status 'PASS' -Detail "场景=$Scenario，项目范围有效，脚本模式=$scriptMode。"
}

function New-AgentRequestBody {
    <#
    .SYNOPSIS
        构造公开 AgentRequest JSON。

    .DESCRIPTION
        请求体只放租户/项目/操作者上下文、自然语言目标和低敏数据源选择提示。数据源密码、JDBC URL、token
        和工具参数不在本脚本中构造。source/target 采用独立对象，明确告诉 DATASOURCE_AGENT 两个方向不能
        把最后一个候选同时填入两端；dataSyncRequest 只提供任务模式和名称提示，真实表字段仍必须由 Agent/Java
        元数据校验确认。
    #>
    $source = [ordered]@{
        datasourceName = $SourceDatasourceName
        connectorType = $SourceConnectorType.ToUpperInvariant()
    }
    $target = [ordered]@{
        datasourceName = $TargetDatasourceName
        connectorType = $TargetConnectorType.ToUpperInvariant()
    }
    if (-not [string]::IsNullOrWhiteSpace($SourceDatasourceId)) {
        $source.datasourceId = $SourceDatasourceId.Trim()
    }
    if (-not [string]::IsNullOrWhiteSpace($TargetDatasourceId)) {
        $target.datasourceId = $TargetDatasourceId.Trim()
    }

    $taskName = "local-six-agent-$($script:RunStamp)"
    $syncRequest = [ordered]@{
        taskName = $taskName
        sourceDatasourceName = $SourceDatasourceName
        targetDatasourceName = $TargetDatasourceName
        sourceConnectorType = $SourceConnectorType.ToUpperInvariant()
        targetConnectorType = $TargetConnectorType.ToUpperInvariant()
        syncMode = 'FULL'
        writeMode = $WriteStrategy.ToUpperInvariant()
        writeStrategy = $WriteStrategy.ToUpperInvariant()
    }
    if (-not [string]::IsNullOrWhiteSpace($SourceDatasourceId)) {
        $syncRequest.sourceDatasourceId = $SourceDatasourceId.Trim()
    }
    if (-not [string]::IsNullOrWhiteSpace($TargetDatasourceId)) {
        $syncRequest.targetDatasourceId = $TargetDatasourceId.Trim()
    }
    $objectMappings = @()
    if (-not [string]::IsNullOrWhiteSpace($SourceObjectName) -and
        -not [string]::IsNullOrWhiteSpace($TargetObjectName)) {
        $objectMappings = @(
            [ordered]@{
                objectKey = 'local-six-agent-object-1'
                sourceSchemaName = $SourceSchemaName.Trim()
                sourceObjectName = $SourceObjectName.Trim()
                targetSchemaName = $TargetSchemaName.Trim()
                targetObjectName = $TargetObjectName.Trim()
                whereCondition = ''
            }
        )
        # dataSyncRequest 是 DATA_SYNC_AGENT 的用户审核基线；模型只能补充字段映射，不能删除或改写对象定位。
        $syncRequest.objectMappings = $objectMappings
    }

    $variables = [ordered]@{
        sourceDatasourceName = $SourceDatasourceName
        sourceConnectorType = $SourceConnectorType.ToUpperInvariant()
        targetDatasourceName = $TargetDatasourceName
        targetConnectorType = $TargetConnectorType.ToUpperInvariant()
        requestedDirections = @('SOURCE', 'TARGET')
        syncMode = 'FULL'
        writeMode = $WriteStrategy.ToUpperInvariant()
        taskName = $taskName
        source = $source
        target = $target
        dataSyncRequest = $syncRequest
    }
    if ($objectMappings.Count -gt 0) {
        # 顶层副本供主 Agent 的 ToolPlan 参数提取使用；两处内容相同且都只是低敏对象定位，不包含 SQL 或凭据。
        $variables.objectMappings = $objectMappings
    }
    if (-not [string]::IsNullOrWhiteSpace($SourceDatasourceId)) {
        $variables.sourceDatasourceId = $SourceDatasourceId.Trim()
    }
    if (-not [string]::IsNullOrWhiteSpace($TargetDatasourceId)) {
        $variables.targetDatasourceId = $TargetDatasourceId.Trim()
    }
    if (-not [string]::IsNullOrWhiteSpace($TaskId)) {
        $variables.taskId = $TaskId.Trim()
    }
    if (-not [string]::IsNullOrWhiteSpace($ExecutionId)) {
        $variables.executionId = $ExecutionId.Trim()
    }

    if ($Scenario -eq 'Recovery') {
        $failureContext = [ordered]@{
            taskId = if ([string]::IsNullOrWhiteSpace($TaskId)) { $null } else { $TaskId.Trim() }
            executionId = if ([string]::IsNullOrWhiteSpace($ExecutionId)) { $null } else { $ExecutionId.Trim() }
            failureCode = if ([string]::IsNullOrWhiteSpace($FailureCode)) { $null } else { $FailureCode.Trim() }
            failureReference = if ([string]::IsNullOrWhiteSpace($FailureReference)) { $null } else { $FailureReference.Trim() }
        }
        $variables.failureContext = $failureContext
        $variables.recoveryContext = $failureContext
        $variables.monitoringRequest = $failureContext
        if (-not [string]::IsNullOrWhiteSpace($FailureCode)) {
            $variables.failureCode = $FailureCode.Trim()
        }
        if (-not [string]::IsNullOrWhiteSpace($FailureReference)) {
            $variables.failureReference = $FailureReference.Trim()
        }
    }

    return [ordered]@{
        tenant_id = [string]$TenantId
        project_id = [string]$ProjectId
        actor_id = [string]$ActorId
        objective = $script:EffectiveObjective
        variables = $variables
        preferred_workload = 'agent_reasoning'
        locale = 'zh-CN'
        request_id = $RequestId
    }
}

function Show-PlanOnlySummary {
    <#
    .SYNOPSIS
        显示只读模式将要执行的验收范围。

    .DESCRIPTION
        该函数故意不显示完整 objective 或请求 JSON，因为自然语言中可能包含业务 SQL、字段值或其他敏感内容。
        它只显示场景、项目、数据源名称和验证阶段，帮助操作者在真正执行前检查参数是否正确。
    #>
    Write-Host ''
    Write-Host 'DataSmart 六专业 Agent 本地验收计划' -ForegroundColor Cyan
    Write-Host "模式：PLAN_ONLY（未访问 Keycloak，未调用 Agent API，未创建任务）"
    Write-Host "场景：$Scenario"
    Write-Host "租户/项目：$TenantId / $ProjectId"
    Write-Host "源端数据源名称：$SourceDatasourceName"
    Write-Host "目标端数据源名称：$TargetDatasourceName"
    Write-Host '验证：六角色名册、专业参与结果、DATA_SYNC/RECOVERY bridge、可信 task/execution、后置 PRECHECK/MONITOR、durable facts。'
    if ($Scenario -eq 'Recovery') {
        Write-Host '恢复安全边界：只验证进入审批/Java handoff，不自动批准，不执行改表、清理、重试或重放。' -ForegroundColor Yellow
    } elseif ($ConfirmAndExecute) {
        Write-Host '成功执行边界：将显式批准同步生命周期 Run，并等待真实 execution、对象账本和运行日志完成验收。' -ForegroundColor Yellow
    } else {
        Write-Host '成功执行边界：未传 -ConfirmAndExecute，只验证审批前计划，不会创建并启动同步任务。' -ForegroundColor Yellow
    }
    Write-Host '如需真正调用，请显式追加 -Execute，并通过环境变量 DATASMART_KEYCLOAK_LOCAL_USER_PASSWORD 提供密码。' -ForegroundColor Yellow
}

function New-HttpClient {
    <#
    .SYNOPSIS
        创建一次性、带超时的 HttpClient。

    .DESCRIPTION
        脚本不复用静态客户端，避免把上一次请求的 Authorization 或响应状态带到下一次请求。调用方负责在
        finally 中 Dispose；任何异常都由上层转换为低敏业务错误。
    #>
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [System.TimeSpan]::FromSeconds($TimeoutSeconds)
    return $client
}

function New-HttpRequestMessage {
    <#
    .SYNOPSIS
        创建带认证和 JSON 内容的 HTTP 请求。

    .DESCRIPTION
        Authorization 只存在于内存中的 HttpRequestMessage，不通过 Write-Host、Verbose、异常消息或自定义
        日志输出。请求体由调用方传入已构造的 JSON，响应读取后也不会回显。
    #>
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [AllowNull()][string]$JsonBody,
        [string]$Accept = 'application/json'
    )

    $httpMethod = if ($Method -eq 'GET') { [System.Net.Http.HttpMethod]::Get } else { [System.Net.Http.HttpMethod]::Post }
    $message = [System.Net.Http.HttpRequestMessage]::new($httpMethod, $Uri)
    $message.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $AccessToken)
    $message.Headers.Accept.Clear()
    $message.Headers.Accept.ParseAdd($Accept)
    $message.Headers.Add('X-DataSmart-Trace-Id', $RequestId)
    $message.Headers.Add('X-DataSmart-Source-Service', 'local-six-agent-e2e')
    <#
        Agent 的 project_id 不能只依赖 JSON body：Gateway 对已识别的 gateway 请求会主动删除请求体中的
        project_id，再从经过权限判定的项目上下文 Header 重建可信字段。这是防止客户端自报项目越权的必要边界，
        也是前端 API client 的真实行为。黑盒脚本必须模拟同一个“当前项目选择器”Header，否则请求到 Python
        时自然会缺少 project_id，无法验证后续六专业 Agent 链路。
    #>
    if ($ProjectId -gt 0) {
        $message.Headers.Add('X-DataSmart-Project-Id', [string]$ProjectId)
    }
    if ($Method -eq 'POST') {
        $message.Content = [System.Net.Http.StringContent]::new(
            $JsonBody,
            [System.Text.Encoding]::UTF8,
            'application/json'
        )
    }
    return $message
}

function Get-SafeHttpErrorDetail {
    <#
    .SYNOPSIS
        从 HTTP 错误响应中提取人话错误码、原因和建议。

    .DESCRIPTION
        只读取 error.code/error.message/error.suggestions 等白名单字段，绝不输出原始 body。对于非 JSON 错误页，
        使用状态码和固定建议；对于服务端已提供的业务详情，再做一次凭据脱敏和长度限制。
    #>
    param(
        [Parameter(Mandatory = $true)][int]$StatusCode,
        [AllowNull()][string]$Body,
        [string]$Operation = 'Agent API'
    )

    $parsed = $null
    if (-not [string]::IsNullOrWhiteSpace($Body)) {
        try {
        $parsed = ConvertFrom-JsonSafe -Json $Body -Depth 50
        } catch {
            $parsed = $null
        }
    }
    # 兼容两种服务端错误外壳：旧控制面可能返回 error，FastAPI HTTPException
    # 通常返回 detail。两者内部都可能包含 code、message、fieldErrors 和 suggestions。
    # 之前只读取 error，导致 detail 对象被强制转成字符串，最终显示成“@{...}”或
    # “System.Object[]”，把真正的缺项完全隐藏了。
    $errorNode = Get-FieldValue -Object $parsed -Names @('error', 'errors')
    if ($null -eq $errorNode) {
        $detailNode = Get-FieldValue -Object $parsed -Names @('detail')
        if ($detailNode -isnot [string]) {
            $errorNode = $detailNode
        }
    }
    $code = Get-FieldValue -Object $errorNode -Names @('code', 'errorCode')
    if ([string]::IsNullOrWhiteSpace([string]$code)) {
        $code = Get-FieldValue -Object $parsed -Names @('code', 'errorCode')
    }
    $code = Get-SafeStatusToken -Text $code -Fallback "HTTP_$StatusCode"
    $message = Get-FieldValue -Object $errorNode -Names @('message', 'detail', 'reason')
    if ([string]::IsNullOrWhiteSpace([string]$message)) {
        $message = Get-FieldValue -Object $parsed -Names @('message', 'detail', 'reason')
    }
    $fallbackMessage = switch ($StatusCode) {
            401 { '认证已失效或 Gateway 未接受当前 Keycloak token。' }
            403 { '当前 project-owner 没有访问该项目或 Agent 入口的权限。' }
            404 { '本地服务未提供该 Agent 入口，或路由/容器版本不一致。' }
            409 { '当前 Agent 会话或控制面状态冲突，可能已有未完成运行。' }
            default { '本地 Agent API 未能完成请求。' }
    }
    $message = Get-LowSensitiveMessage -Text $message -Fallback $fallbackMessage
    $suggestionValues = Get-Items (Get-FieldValue -Object $errorNode -Names @('suggestions', 'advice'))
    $suggestions = @(
        $suggestionValues |
            Where-Object { $_ -is [string] } |
            ForEach-Object {
                Get-LowSensitiveMessage -Text $_ -Fallback '查看服务端低敏审计日志中的 traceId 后再重试。'
            } |
            Select-Object -First 3
    )
    if ($suggestions.Count -eq 0) {
        $suggestions = @('确认 Keycloak、Gateway、agent-runtime、permission-admin 和 Python AI Runtime 使用同一份本地配置。', '查看服务端低敏审计日志中的 traceId 后再重试。')
    }
    $fieldErrors = Get-FieldValue -Object $errorNode -Names @('fieldErrors', 'field_errors')
    return [pscustomobject]@{
        Code = Protect-LogText $code
        Message = Protect-LogText $message
        FieldErrors = @($fieldErrors)
        Suggestions = $suggestions
        Operation = $Operation
    }
}

function Get-KeycloakAccessToken {
    <#
    .SYNOPSIS
        使用本地 Keycloak 的 project-owner password grant 获取短期 access token。

    .DESCRIPTION
        这是本地 E2E 的认证入口，不是生产登录方案。密码优先从环境变量读取，token 只在内存中传给 Gateway，
        从不写入终端、文件、异常或检查记录。Token Endpoint 的错误只转换成稳定错误码和建议。
    #>
    $password = $KeycloakPassword
    if ([string]::IsNullOrWhiteSpace($password)) {
        $password = $env:DATASMART_KEYCLOAK_LOCAL_USER_PASSWORD
    }
    if ([string]::IsNullOrWhiteSpace($password)) {
        Stop-E2E -Name 'Keycloak 认证参数' -Detail '未提供 project-owner 密码；请通过 DATASMART_KEYCLOAK_LOCAL_USER_PASSWORD 环境变量提供，不要把密码写入日志。'
    }

    $tokenUri = "$( $KeycloakBaseUrl.TrimEnd('/') )/realms/$([uri]::EscapeDataString($KeycloakRealm))/protocol/openid-connect/token"
    $form = [System.Collections.Generic.Dictionary[string, string]]::new()
    $form['grant_type'] = 'password'
    $form['client_id'] = $KeycloakClientId
    $form['username'] = $KeycloakUsername
    $form['password'] = $password
    $client = New-HttpClient
    $content = [System.Net.Http.FormUrlEncodedContent]::new($form)
    try {
        $response = $client.PostAsync($tokenUri, $content).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            $detail = Get-SafeHttpErrorDetail -StatusCode ([int]$response.StatusCode) -Body $body -Operation 'Keycloak token'
            Stop-E2E -Name 'Keycloak token' -Detail "无法获取 project-owner token：$(Format-SafeHttpDetail -Detail $detail) 建议：$($detail.Suggestions -join '；')"
        }
        $tokenResponse = ConvertFrom-JsonSafe -Json $body -Depth 20
        $accessToken = Get-FieldValue -Object $tokenResponse -Names @('access_token')
        if ([string]::IsNullOrWhiteSpace([string]$accessToken)) {
            Stop-E2E -Name 'Keycloak token' -Detail 'Keycloak 返回成功状态，但没有 access_token；请检查 realm、client 和本地用户配置。'
        }
        Add-Check -Name 'Keycloak token' -Status 'PASS' -Detail 'project-owner token 获取成功，凭据未输出。'
        return [string]$accessToken
    } catch {
        if (Test-SafeE2EException -Exception $_.Exception) {
            throw
        }
        Stop-E2E -Name 'Keycloak token' -Detail 'Keycloak token 请求失败；请确认本地 Keycloak 已启动、realm 已导入且 project-owner 可登录。'
    } finally {
        $content.Dispose()
        $client.Dispose()
    }
}

function Write-StreamFrameSummary {
    <#
    .SYNOPSIS
        输出一条 NDJSON 流式帧的低敏进度摘要。

    .DESCRIPTION
        该函数模拟前端所需的“实时进度”体验，但只展示 accepted/progress/heartbeat/result/done 的状态和
        RuntimeEvent 的类型、序号、严重级别等固定字段。它不会把事件 attributes、模型消息、SQL 或工具参数
        序列化到终端。
    #>
    param([Parameter(Mandatory = $true)][object]$Frame)

    $frameType = [string](Get-FieldValue -Object $Frame -Names @('type'))
    switch ($frameType) {
        'accepted' {
            Write-Host '[STREAM] Agent 请求已接收。' -ForegroundColor DarkCyan
        }
        'progress' {
            $event = Get-FieldValue -Object $Frame -Names @('event')
            $eventType = Get-SafeStatusToken -Text (Get-FieldValue -Object $event -Names @('eventType', 'type', 'name')) -Fallback 'LOW_SENSITIVE_RUNTIME_STEP'
            $sequence = Get-FieldValue -Object $event -Names @('sequence', 'seq')
            $severityValue = Get-FieldValue -Object $event -Names @('severity', 'status')
            $severity = if ($null -ne $severityValue) {
                Get-SafeStatusToken -Text $severityValue -Fallback 'UNKNOWN'
            } else {
                ''
            }
            $safeSequence = if ($null -ne $sequence -and ([string]$sequence) -match '^[0-9]{1,18}$') {
                [string]$sequence
            } else {
                ''
            }
            $suffix = if (-not [string]::IsNullOrWhiteSpace($safeSequence)) { " sequence=$safeSequence" } else { '' }
            if (-not [string]::IsNullOrWhiteSpace($severity)) { $suffix += " status=$severity" }
            Write-Host "[STREAM] $eventType$suffix" -ForegroundColor DarkCyan
        }
        'heartbeat' {
            $elapsed = Get-FieldValue -Object $Frame -Names @('elapsedMs')
            $safeElapsed = if ($null -ne $elapsed -and ([string]$elapsed) -match '^[0-9]{1,12}$') { [string]$elapsed } else { '未知时长' }
            Write-Host "[STREAM] Agent 仍在处理，已等待 ${safeElapsed}ms。" -ForegroundColor DarkGray
        }
        'result' {
            Write-Host '[STREAM] Agent 已返回最终低敏计划摘要，开始断言闭环。' -ForegroundColor DarkCyan
        }
        'error' {
            $errorNode = Get-FieldValue -Object $Frame -Names @('error')
            $code = Get-SafeStatusToken -Text (Get-FieldValue -Object $errorNode -Names @('code', 'errorCode')) -Fallback 'AGENT_STREAM_ERROR'
            $message = Get-LowSensitiveMessage -Text (Get-FieldValue -Object $errorNode -Names @('message', 'detail')) -Fallback 'Agent 流返回了业务错误。'
            Write-Host "[STREAM][ERROR] $code：$message" -ForegroundColor Yellow
        }
        'done' {
            Write-Host '[STREAM] Agent 流已结束。' -ForegroundColor DarkGray
        }
        default {
            Write-Host '[STREAM] 收到未知但已忽略的低敏帧。' -ForegroundColor DarkGray
        }
    }
}

function Invoke-AgentPlanStream {
    <#
    .SYNOPSIS
        通过 Gateway 调用 /api/agent/plans/stream 并逐行解析 NDJSON。

    .DESCRIPTION
        ResponseHeadersRead 让脚本在模型和专业 Agent 运行期间及时看到 accepted、progress 和 heartbeat，避免
        用户误以为服务没有工作。函数只保存最终 data 和低敏帧类型；遇到 HTTP 错误或缺失最终 result 时，
        使用白名单错误字段生成可执行建议，不返回原始响应。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][string]$JsonBody
    )

    $uri = "$( $GatewayBaseUrl.TrimEnd('/') )/api/agent/plans/stream"
    $client = New-HttpClient
    $request = New-HttpRequestMessage -Method 'POST' -Uri $uri -AccessToken $AccessToken -JsonBody $JsonBody -Accept 'application/x-ndjson'
    $frames = New-Object System.Collections.Generic.List[object]
    $reader = $null
    try {
        $response = $client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $detail = Get-SafeHttpErrorDetail -StatusCode ([int]$response.StatusCode) -Body $body -Operation 'Gateway Agent stream'
            Stop-E2E -Name 'Agent 流式接口' -Detail "Gateway 拒绝 Agent 请求：$(Format-SafeHttpDetail -Detail $detail) 建议：$($detail.Suggestions -join '；')"
        }
        $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $reader = [System.IO.StreamReader]::new($stream)
        $resultData = $null
        $errorFrame = $null
        while ($true) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            try {
                $frame = ConvertFrom-JsonSafe -Json $line -Depth 100
            } catch {
                Add-Check -Name 'Agent 流式帧' -Status 'WARN' -Detail '收到一行无法解析的 NDJSON，已忽略其正文并继续等待最终结果。'
                continue
            }
            $frames.Add($frame) | Out-Null
            Write-StreamFrameSummary -Frame $frame
            $frameType = [string](Get-FieldValue -Object $frame -Names @('type'))
            if ($frameType -eq 'result') {
                $resultData = Get-FieldValue -Object $frame -Names @('data', 'result')
            } elseif ($frameType -eq 'error') {
                $errorFrame = $frame
            }
        }
        if ($null -eq $resultData) {
            if ($null -ne $errorFrame) {
                $errorNode = Get-FieldValue -Object $errorFrame -Names @('error')
                $code = Get-SafeStatusToken -Text (Get-FieldValue -Object $errorNode -Names @('code', 'errorCode')) -Fallback 'AGENT_STREAM_ERROR'
                $message = Get-LowSensitiveMessage -Text (Get-FieldValue -Object $errorNode -Names @('message', 'detail')) -Fallback 'Agent 流未返回最终计划。'
                Stop-E2E -Name 'Agent 流式处理' -Detail "$code：$message 建议：检查最后一个流式步骤对应的服务日志，并确认控制面没有处于等待审批或参数补全状态。"
            }
            Stop-E2E -Name 'Agent 流式处理' -Detail 'Agent 流正常建立但没有返回最终计划；可能是服务提前断开、模型超时或容器版本不一致。建议检查服务健康状态和 traceId 后重试。'
        }
        return [pscustomobject]@{
            Result = $resultData
            # Windows PowerShell 5.1 直接用 @(...) 包装 Generic.List[object] 时可能抛出
            # “参数类型不匹配”。先调用 ToArray 可以保留每个已解析帧，并让调用方在最终结果帧之后
            # 继续执行合同断言。
            Frames = $frames.ToArray()
        }
    } catch {
        if (Test-SafeE2EException -Exception $_.Exception) {
            throw
        }
        Stop-E2E -Name 'Agent 流式处理' -Detail '读取 Agent 流失败；请确认 Gateway 与 Python AI Runtime 正常，且请求超时没有超过本地服务允许范围。'
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        $request.Dispose()
        $client.Dispose()
    }
}

function Invoke-AgentPlanJson {
    <#
    .SYNOPSIS
        通过 Gateway 调用非流式 /api/agent/plans。

    .DESCRIPTION
        该函数用于排查 NDJSON、代理缓冲或客户端兼容性问题。它与流式入口使用完全相同的认证和 JSON body，
        但只在响应完整后解析。错误处理和低敏边界与流式函数保持一致。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][string]$JsonBody
    )

    $uri = "$( $GatewayBaseUrl.TrimEnd('/') )/api/agent/plans"
    $client = New-HttpClient
    $request = New-HttpRequestMessage -Method 'POST' -Uri $uri -AccessToken $AccessToken -JsonBody $JsonBody
    try {
        $response = $client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            $detail = Get-SafeHttpErrorDetail -StatusCode ([int]$response.StatusCode) -Body $body -Operation 'Gateway Agent plan'
            Stop-E2E -Name 'Agent JSON 接口' -Detail "Gateway 拒绝 Agent 请求：$(Format-SafeHttpDetail -Detail $detail) 建议：$($detail.Suggestions -join '；')"
        }
        try {
            return (ConvertFrom-JsonSafe -Json $body -Depth 100)
        } catch {
            Stop-E2E -Name 'Agent JSON 接口' -Detail 'Gateway 返回成功状态但不是合法 JSON；请确认 Gateway 路由没有把 Agent 请求转发到错误服务。'
        }
    } catch {
        if (Test-SafeE2EException -Exception $_.Exception) {
            throw
        }
        Stop-E2E -Name 'Agent JSON 接口' -Detail '读取 Agent JSON 响应失败；请检查 Gateway、Python AI Runtime 和本地网络。'
    } finally {
        $request.Dispose()
        $client.Dispose()
    }
}

function Invoke-AgentPlan {
    <#
    .SYNOPSIS
        按当前模式调用 Agent 计划入口。

    .DESCRIPTION
        该函数统一把 PowerShell 请求体序列化一次，避免流式和非流式路径使用不同字段。它不在输出中显示 JSON，
        也不把 response 直接打印给用户；后续断言函数只消费白名单字段。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][object]$RequestBody
    )

    $jsonBody = $RequestBody | ConvertTo-Json -Depth 100 -Compress
    if ($UsePlanEndpoint) {
        Add-Check -Name 'Agent 请求入口' -Status 'PASS' -Detail '使用 /api/agent/plans 非流式入口。'
        return (Invoke-AgentPlanJson -AccessToken $AccessToken -JsonBody $jsonBody)
    }
    Add-Check -Name 'Agent 请求入口' -Status 'PASS' -Detail '使用 /api/agent/plans/stream 流式入口。'
    $streamResult = Invoke-AgentPlanStream -AccessToken $AccessToken -JsonBody $jsonBody
    return $streamResult.Result
}

function Get-GatewayResponseData {
    <#
    .SYNOPSIS
        解包 Gateway/Java 服务统一响应，只返回 data 中的受控业务结果。

    .DESCRIPTION
        Agent 确认接口和 data-sync 查询接口都使用 code/message/data 外壳。E2E 只读取 code 与 data，
        不把完整响应或 message 原样写入终端；这样既能发现 HTTP 200 中的业务失败，也不会因为验收日志
        泄漏工具参数、SQL、连接信息或模型内容。没有统一外壳的 Python 低敏响应会原样作为已解析对象返回。
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Response,
        [Parameter(Mandatory = $true)][string]$Operation
    )

    $code = Get-FieldValue -Object $Response -Names @('code')
    if ($null -ne $code) {
        $normalizedCode = ([string]$code).Trim().ToUpperInvariant()
        if (@('0', '200', 'SUCCESS', 'OK') -notcontains $normalizedCode) {
            $safeMessage = Get-LowSensitiveMessage `
                -Text (Get-FieldValue -Object $Response -Names @('message')) `
                -Fallback "$Operation 返回了业务失败；请根据 traceId 查看服务端低敏日志。"
            Stop-E2E -Name $Operation -Detail "业务状态=$normalizedCode；$safeMessage"
        }
        return (Get-FieldValue -Object $Response -Names @('data'))
    }
    return $Response
}

function Test-TransientGatewayReadFailure {
    <#
    .SYNOPSIS
        判断 Gateway 失败是否属于可以安全重试的只读瞬态故障。

    .DESCRIPTION
        E2E 最终验收会连续查询 execution、对象账本、Recovery 和 durable facts。Gateway 或权限中心短暂重启时，
        这些 GET 可能返回 502/503/504；生产 fail-closed 权限链还会把“权限中心暂时不可用”返回为 403。GET 没有
        数据副作用，因此可以在很小的预算内重试。POST 即使收到相同状态也必须立即失败，普通 403 同样不能被
        当成瞬态故障，以免脚本掩盖真实越权或重复确认、重试、隔离等写操作。

        函数只检查状态码和固定低敏故障标记，不记录响应正文，也不把服务端 message 带入成功摘要。
    #>
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][int]$StatusCode,
        [AllowNull()][string]$ResponseBody
    )

    if ($Method -ne 'GET') {
        return $false
    }
    if ($StatusCode -in @(502, 503, 504)) {
        return $true
    }
    return $StatusCode -eq 403 -and
        -not [string]::IsNullOrWhiteSpace($ResponseBody) -and
        $ResponseBody.Contains('权限中心暂时不可用')
}

function Invoke-GatewayJson {
    <#
    .SYNOPSIS
        通过当前用户 token 调用一个 Gateway JSON API。

    .DESCRIPTION
        该函数复用 Agent 规划相同的认证、项目 Header、超时和低敏错误边界，供显式确认与 data-sync
        只读验收使用。它不允许调用方附加任意 Header，也不会打印请求体或响应体，避免 E2E 因方便而绕过
        Gateway 的用户身份透传、项目授权和审计链路。
    #>
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [AllowNull()][object]$Body,
        [Parameter(Mandatory = $true)][string]$Operation
    )

    $uri = "$( $GatewayBaseUrl.TrimEnd('/') )$Path"
    $jsonBody = if ($Method -eq 'POST') {
        if ($null -eq $Body) { '{}' } else { $Body | ConvertTo-Json -Depth 100 -Compress }
    } else {
        $null
    }
    $client = New-HttpClient
    $maxAttempts = if ($Method -eq 'GET') { 3 } else { 1 }
    try {
        for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
            # HttpRequestMessage 发送后不能再次使用，因此每轮都创建新请求；响应也必须及时释放，避免长轮询
            # 在 Windows PowerShell 5.1 中耗尽连接池。AccessToken 和请求正文不会进入重试日志。
            $request = New-HttpRequestMessage -Method $Method -Uri $uri -AccessToken $AccessToken -JsonBody $jsonBody
            $response = $null
            try {
                try {
                    $response = $client.SendAsync(
                        $request,
                        [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
                    ).GetAwaiter().GetResult()
                } catch {
                    if ($Method -eq 'GET' -and $attempt -lt $maxAttempts) {
                        Start-Sleep -Milliseconds (250 * $attempt)
                        continue
                    }
                    throw
                }

                $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                if (-not $response.IsSuccessStatusCode) {
                    $statusCode = [int]$response.StatusCode
                    if ($attempt -lt $maxAttempts -and
                        (Test-TransientGatewayReadFailure -Method $Method -StatusCode $statusCode -ResponseBody $responseBody)) {
                        Start-Sleep -Milliseconds (250 * $attempt)
                        continue
                    }
                    $detail = Get-SafeHttpErrorDetail -StatusCode $statusCode -Body $responseBody -Operation $Operation
                    Stop-E2E -Name $Operation -Detail "HTTP 调用失败：$(Format-SafeHttpDetail -Detail $detail) 建议：$($detail.Suggestions -join '；')"
                }
                if ([string]::IsNullOrWhiteSpace($responseBody)) {
                    return $null
                }
                try {
                    $parsed = ConvertFrom-JsonSafe -Json $responseBody -Depth 100
                } catch {
                    Stop-E2E -Name $Operation -Detail '接口返回成功状态但不是合法 JSON；请检查 Gateway 路由和服务版本。'
                }
                return (Get-GatewayResponseData -Response $parsed -Operation $Operation)
            } finally {
                if ($null -ne $response) {
                    $response.Dispose()
                }
                $request.Dispose()
            }
        }
        Stop-E2E -Name $Operation -Detail 'Gateway 只读查询已耗尽有界重试预算；请检查服务健康状态和 traceId。'
    } catch {
        if (Test-SafeE2EException -Exception $_.Exception) {
            throw
        }
        Stop-E2E -Name $Operation -Detail 'Gateway 调用失败；请检查服务健康状态、当前项目权限和 traceId。'
    } finally {
        $client.Dispose()
    }
}

function Get-LifecycleRunReference {
    <#
    .SYNOPSIS
        选择真正包含同步生命周期工具的最新 Durable Run。

    .DESCRIPTION
        首轮 controlPlaneIngestion 常常只包含数据源目录或元数据工具；真正的草稿、预检查、发布和执行位于
        Durable loop 的后续 Run。这里倒序检查每个 turn 的低敏 submittedToolNames，只接受同一 Run 中明确包含
        sync.task.draft.save、sync.task.precheck、sync.task.publish、sync.task.run 四步的候选。

        不能验证完整生命周期时必须返回空值，而不是回退到“最后一个有 ID 的 turn”或 ingestion。这样确认接口
        不会误批准元数据查询或后置复核 Run；调用方会给出固定、低敏且可行动的错误说明。
    #>
    param([Parameter(Mandatory = $true)][object]$Response)

    $requiredToolCodes = @(
        'sync.task.draft.save',
        'sync.task.precheck',
        'sync.task.publish',
        'sync.task.run'
    )
    $durableLoop = Get-FieldValue -Object $Response -Names @('agentDurableModelToolLoop', 'durableLoop')
    $turns = @(Get-Items (Get-FieldValue -Object $durableLoop -Names @('turns')))
    for ($index = $turns.Count - 1; $index -ge 0; $index--) {
        $sessionId = [string](Get-FieldValue -Object $turns[$index] -Names @('sessionId', 'session_id'))
        $runId = [string](Get-FieldValue -Object $turns[$index] -Names @('runId', 'run_id'))
        if ([string]::IsNullOrWhiteSpace($sessionId) -or [string]::IsNullOrWhiteSpace($runId)) {
            continue
        }

        # Durable 摘要通常公开 submittedToolNames；这里同时兼容旧对象结构，但绝不读取 arguments 或
        # 序列化完整计划，因为确认候选选择只需要稳定的工具代码。
        $submittedToolCodes = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
        foreach ($tool in (Get-Items (Get-FieldValue -Object $turns[$index] -Names @('submittedToolNames', 'submitted_tool_names', 'toolPlans', 'tool_plans')))) {
            $toolCode = if ($tool -is [string]) {
                $tool
            } else {
                [string](Get-FieldValue -Object $tool -Names @('toolCode', 'tool_code', 'toolName', 'tool_name'))
            }
            if (-not [string]::IsNullOrWhiteSpace($toolCode)) {
                $null = $submittedToolCodes.Add($toolCode.Trim())
            }
        }
        $hasCompleteLifecycle = $true
        foreach ($requiredToolCode in $requiredToolCodes) {
            if (-not $submittedToolCodes.Contains($requiredToolCode)) {
                $hasCompleteLifecycle = $false
                break
            }
        }
        if ($hasCompleteLifecycle) {
            return [pscustomobject]@{ SessionId = $sessionId.Trim(); RunId = $runId.Trim(); Source = 'DURABLE_LIFECYCLE_TOOLPLAN' }
        }
    }
    return $null
}

function Invoke-ConfirmedAgentRun {
    <#
    .SYNOPSIS
        对 Success 场景当前生命周期 Run 做一次显式用户确认。

    .DESCRIPTION
        确认不是脚本默认行为，只有调用方同时传入 -Execute -ConfirmAndExecute 才会进入本函数。请求仍由
        Gateway 以 project-owner 的真实身份授权，Java 再核对 session 发起人、租户、项目和审批状态；
        Recovery 场景在参数校验阶段即被拒绝，不能借此函数自动批准改表、清理或重试。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][object]$Reference
    )

    $sessionId = [uri]::EscapeDataString([string]$Reference.SessionId)
    $runId = [uri]::EscapeDataString([string]$Reference.RunId)
    # idempotencyKey 对同一个 E2E RequestId 保持稳定。若服务端已经完成确认但 HTTP 响应丢失，调用方可用
    # 同一 key 重放并读取原结果；任何修改后的确认事实必须由服务端摘要校验拒绝，不能重复产生副作用。
    $confirmationBody = [ordered]@{
        confirmed = $true
        comment = '六专业 Agent 本地 E2E：用户显式确认 Success 场景同步生命周期计划。'
        idempotencyKey = "$RequestId-confirm"
    }
    if ($EnableAutopilot) {
        # expiresAt 始终使用带 Z/offset 的 UTC ISO-8601。Java 和 data-sync 必须按同一瞬时时间比较，
        # 不能把它降为本地墙钟 LocalDateTime，否则 Asia/Shanghai 容器会把授权提前八小时判定过期。
        $confirmationBody.autopilotPolicy = [ordered]@{
            executionMode = 'AUTOPILOT'
            maxRecoveryCycles = $AutopilotMaxRecoveryCycles
            maxTotalDurationMinutes = $AutopilotMaxTotalDurationMinutes
            maxAutomaticRiskLevel = 'LOW'
            # 目录中的八个动作都已有受治理执行器；它们只是用户在首次确认时授予的上限，不代表一定执行。
            # 后续每轮仍由模型基于结构化诊断选择一个动作，再由 Agent Runtime 和 data-sync 使用当前事实、
            # 双主体、项目权限、风险、指纹、幂等回执与循环预算重新校验。凭据、DDL、删除、覆盖和扩域不在目录内。
            allowedRecoveryActions = @(
                'RETRY_EXECUTION',
                'APPLY_QUARANTINE',
                'ROLLBACK_EXECUTION_POLICY',
                'TUNE_EXECUTION_POLICY',
                'REFRESH_METADATA',
                'RESUME_FROM_CHECKPOINT',
                'REPLAY_FAILED_SHARDS',
                'REPAIR_FIELD_MAPPING'
            )
            requireApprovalFor = @(
                'CHANGE_SCHEMA',
                'CHANGE_CREDENTIAL',
                'DELETE_DATA',
                'OVERWRITE_TARGET',
                'EXPAND_DATA_SCOPE'
            )
            expiresAt = [DateTimeOffset]::UtcNow.AddMinutes($AutopilotMaxTotalDurationMinutes).ToString('o')
        }
    }
    $result = Invoke-GatewayJson `
        -Method 'POST' `
        -Path "/api/agent/sessions/$sessionId/runs/$runId/confirm-and-execute" `
        -AccessToken $AccessToken `
        -Body $confirmationBody `
        -Operation 'Agent Run 显式确认'
    Add-Check -Name 'Agent Run 显式确认' -Status 'PASS' -Detail "已批准来源=$($Reference.Source) 的同步生命周期 Run；运行标识与服务端内容均未回显。"
    return $result
}

function Test-SafeAutopilotPublicIdentifier {
    <#
    .SYNOPSIS
        判断一个公开 Autopilot 标识是否是短、稳定且可低敏显示的定位符。

    .DESCRIPTION
        policy、session、run 和 evidence reference 都是不同公开 API 返回的定位字段。E2E 不输出其完整值，
        但需要拒绝空白、大段文本或异常对象，避免把意外响应正文当成授权或事实证据。这里不承担授权判断，
        授权仍由确认接口和服务端持久化快照负责。
    #>
    param([AllowNull()][object]$Value)

    $text = if ($null -eq $Value) { '' } else { ([string]$Value).Trim() }
    return $text -match '^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$'
}

function Get-StrictAutopilotSnapshotInteger {
    <#
    .SYNOPSIS
        从确认响应的公开授权盒读取一个有边界的整数。

    .DESCRIPTION
        PowerShell 会把空值和若干非数值类型隐式转换为 0。对于首次授权的循环数和总时长，这种宽松转换会把
        损坏响应误判为有效的低风险限制。因此本函数只接受十进制正整数，并由调用方传入 DTO/策略定义允许的
        最小和最大值。
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Snapshot,
        [Parameter(Mandatory = $true)][string[]]$Names,
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][int]$Minimum,
        [Parameter(Mandatory = $true)][int]$Maximum
    )

    $raw = Get-FieldValue -Object $Snapshot -Names $Names
    $text = if ($null -eq $raw) { '' } else { ([string]$raw).Trim() }
    if ($text -notmatch '^[1-9][0-9]{0,3}$') {
        Stop-E2E -Name 'Autopilot 首次授权盒' -Detail "$Label 缺失或不是受限正整数。"
    }
    $value = [int]$text
    if ($value -lt $Minimum -or $value -gt $Maximum) {
        Stop-E2E -Name 'Autopilot 首次授权盒' -Detail "$Label=$value 超出公开授权合同范围 $Minimum..$Maximum。"
    }
    return $value
}

function Assert-ConfirmedAutopilotSnapshot {
    <#
    .SYNOPSIS
        仅从首次 confirm-and-execute 响应验证 AUTOPILOT 授权盒。

    .DESCRIPTION
        AgentRunConfirmedExecutionResponse.autopilotSnapshot 是浏览器和 E2E 能看到的唯一公开授权证据。它绑定
        本次确认的根 session/run、循环和总时长预算、低风险上限以及八个当前可兑现的受治理自动动作。后续
        autopilot-recovery GET 故意不重复公开这些授权字段；因此这里在确认成功后立即验证，并把已验证快照
        传给后续恢复轮询作边界比对，而不是从恢复状态 API 猜测或重建授权。

        此函数只读取 DTO 已公开的低敏字段。它不读取持久化 authorization JSON、策略摘要、委托、工具参数、
        prompt 或任何内部回执，也不发送新的授权或恢复请求。
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Confirmation,
        [Parameter(Mandatory = $true)][object]$Reference
    )

    $snapshot = Get-FieldValue -Object $Confirmation -Names @('autopilotSnapshot', 'autopilot_snapshot')
    if ($null -eq $snapshot) {
        Stop-E2E -Name 'Autopilot 首次授权盒' -Detail '首次确认已请求 AUTOPILOT，但 confirm-and-execute 响应没有返回 autopilotSnapshot。'
    }

    $policyId = Get-FieldValue -Object $snapshot -Names @('policyId', 'policy_id')
    $policyVersion = Get-FieldValue -Object $snapshot -Names @('policyVersion', 'policy_version')
    if (-not (Test-SafeAutopilotPublicIdentifier $policyId) -or
        -not (Test-SafeAutopilotPublicIdentifier $policyVersion)) {
        Stop-E2E -Name 'Autopilot 首次授权盒' -Detail '确认响应中的 policyId 或 policyVersion 缺失，无法证明服务端已建立可审计授权盒。'
    }

    $executionMode = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $snapshot -Names @('executionMode', 'execution_mode')
    ) -Fallback 'UNKNOWN'
    $state = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $snapshot -Names @('state')
    ) -Fallback 'UNKNOWN'
    if ($executionMode -ne 'AUTOPILOT' -or $state -ne 'ACTIVE') {
        Stop-E2E -Name 'Autopilot 首次授权盒' -Detail "确认响应中的 executionMode/state 不可用于无人值守低风险恢复：mode=$executionMode、state=$state。"
    }

    $rootSessionId = [string](Get-FieldValue -Object $snapshot -Names @('rootSessionId', 'root_session_id'))
    $rootRunId = [string](Get-FieldValue -Object $snapshot -Names @('rootRunId', 'root_run_id'))
    if (-not (Test-SafeAutopilotPublicIdentifier $rootSessionId) -or
        -not (Test-SafeAutopilotPublicIdentifier $rootRunId) -or
        $rootSessionId.Trim() -cne ([string]$Reference.SessionId).Trim() -or
        $rootRunId.Trim() -cne ([string]$Reference.RunId).Trim()) {
        Stop-E2E -Name 'Autopilot 首次授权盒' -Detail 'autopilotSnapshot 的根 session/run 与本次显式确认的生命周期 Run 不一致。'
    }

    $maxCycles = Get-StrictAutopilotSnapshotInteger `
        -Snapshot $snapshot `
        -Names @('maxRecoveryCycles', 'max_recovery_cycles') `
        -Label 'maxRecoveryCycles' `
        -Minimum 1 `
        -Maximum 10
    $maxDurationMinutes = Get-StrictAutopilotSnapshotInteger `
        -Snapshot $snapshot `
        -Names @('maxTotalDurationMinutes', 'max_total_duration_minutes') `
        -Label 'maxTotalDurationMinutes' `
        -Minimum 5 `
        -Maximum 1440
    if ($maxCycles -ne $AutopilotMaxRecoveryCycles -or $maxDurationMinutes -ne $AutopilotMaxTotalDurationMinutes) {
        Stop-E2E -Name 'Autopilot 首次授权盒' -Detail "服务端返回的循环/时长预算与本次确认不一致：cycles=$maxCycles、minutes=$maxDurationMinutes。"
    }

    $riskLevel = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $snapshot -Names @('maxAutomaticRiskLevel', 'max_automatic_risk_level')
    ) -Fallback 'UNKNOWN'
    if ($riskLevel -ne 'LOW') {
        Stop-E2E -Name 'Autopilot 首次授权盒' -Detail "公开授权盒的自动风险上限不是 LOW：risk=$riskLevel。"
    }

    $allowedActions = [System.Collections.Generic.List[string]]::new()
    foreach ($rawAction in (Get-Items (Get-FieldValue -Object $snapshot -Names @('allowedRecoveryActions', 'allowed_recovery_actions')))) {
        $action = Get-SafeStatusToken -Text $rawAction -Fallback 'UNKNOWN'
        if ($action -eq 'UNKNOWN') {
            Stop-E2E -Name 'Autopilot 首次授权盒' -Detail 'allowedRecoveryActions 包含缺失或非公开代码。'
        }
        $allowedActions.Add($action) | Out-Null
    }
    $requiredAllowedActions = @(
        'RETRY_EXECUTION',
        'APPLY_QUARANTINE',
        'ROLLBACK_EXECUTION_POLICY',
        'TUNE_EXECUTION_POLICY',
        'REFRESH_METADATA',
        'RESUME_FROM_CHECKPOINT',
        'REPLAY_FAILED_SHARDS',
        'REPAIR_FIELD_MAPPING'
    )
    if ($allowedActions.Count -ne $requiredAllowedActions.Count -or
        @($requiredAllowedActions | Where-Object { -not $allowedActions.Contains($_) }).Count -gt 0) {
        Stop-E2E -Name 'Autopilot 首次授权盒' -Detail '公开授权盒没有严格保留当前服务端可兑现的八项受治理低风险动作目录。'
    }

    $approvalActions = [System.Collections.Generic.List[string]]::new()
    foreach ($rawAction in (Get-Items (Get-FieldValue -Object $snapshot -Names @('requireApprovalFor', 'require_approval_for')))) {
        $action = Get-SafeStatusToken -Text $rawAction -Fallback 'UNKNOWN'
        if ($action -eq 'UNKNOWN') {
            Stop-E2E -Name 'Autopilot 首次授权盒' -Detail 'requireApprovalFor 包含缺失或非公开代码。'
        }
        $approvalActions.Add($action) | Out-Null
    }
    $requiredApprovalActions = @('CHANGE_SCHEMA', 'CHANGE_CREDENTIAL', 'DELETE_DATA', 'OVERWRITE_TARGET', 'EXPAND_DATA_SCOPE')
    if ($approvalActions.Count -ne $requiredApprovalActions.Count -or
        @($requiredApprovalActions | Where-Object { -not $approvalActions.Contains($_) }).Count -gt 0) {
        Stop-E2E -Name 'Autopilot 首次授权盒' -Detail '公开授权盒没有完整保留本次确认要求人工审批的高风险动作集合。'
    }

    $issuedAtText = [string](Get-FieldValue -Object $snapshot -Names @('issuedAt', 'issued_at'))
    $expiresAtText = [string](Get-FieldValue -Object $snapshot -Names @('expiresAt', 'expires_at'))
    [DateTimeOffset]$issuedAt = [DateTimeOffset]::MinValue
    [DateTimeOffset]$expiresAt = [DateTimeOffset]::MinValue
    if ([string]::IsNullOrWhiteSpace($issuedAtText) -or [string]::IsNullOrWhiteSpace($expiresAtText) -or
        -not [DateTimeOffset]::TryParse($issuedAtText, [ref]$issuedAt) -or
        -not [DateTimeOffset]::TryParse($expiresAtText, [ref]$expiresAt) -or
        $expiresAt -le $issuedAt -or $expiresAt -le [DateTimeOffset]::UtcNow) {
        Stop-E2E -Name 'Autopilot 首次授权盒' -Detail '公开授权盒的 issuedAt/expiresAt 缺失、无效或已经失效。'
    }

    Add-Check -Name 'Autopilot 首次授权盒' -Status 'PASS' -Detail "确认响应已绑定根 Run，并固定 LOW 风险、$maxCycles 轮和 $maxDurationMinutes 分钟的恢复边界。"
    return [pscustomobject]@{
        PolicyId = ([string]$policyId).Trim()
        PolicyVersion = ([string]$policyVersion).Trim()
        RootSessionId = $rootSessionId.Trim()
        RootRunId = $rootRunId.Trim()
        MaxRecoveryCycles = $maxCycles
        MaxTotalDurationMinutes = $maxDurationMinutes
        AllowedRecoveryActions = @($allowedActions)
        RequireApprovalFor = @($approvalActions)
        ExpiresAt = $expiresAt
    }
}

function Get-TrustedPositiveField {
    <#
    .SYNOPSIS
        从 Java 确认接口的结构化 output 中读取一个正整数资源 ID。

    .DESCRIPTION
        本函数只遍历已经通过当前用户确认、由 Java ToolAdapter 返回的 output，并且只匹配调用方给出的
        taskId/executionId 白名单字段；它不读取模型正文、objective、planArguments 或错误消息。递归深度受限，
        因而不会因为异常响应形成无限遍历，也不会把任意看起来像数字的文本当成控制面事实。
    #>
    param(
        [AllowNull()][object]$Value,
        [Parameter(Mandatory = $true)][string[]]$Names,
        [int]$Depth = 0
    )

    if ($null -eq $Value -or $Depth -gt 8 -or $Value -is [string] -or $Value -is [ValueType]) {
        return $null
    }
    foreach ($name in $Names) {
        $candidate = Get-FieldValue -Object $Value -Names @($name)
        if (Test-PositiveIdentifier $candidate) {
            return [string]$candidate
        }
    }
    if ($Value -is [System.Collections.IDictionary]) {
        foreach ($nested in $Value.Values) {
            $found = Get-TrustedPositiveField -Value $nested -Names $Names -Depth ($Depth + 1)
            if ($null -ne $found) { return $found }
        }
        return $null
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        foreach ($nested in $Value) {
            $found = Get-TrustedPositiveField -Value $nested -Names $Names -Depth ($Depth + 1)
            if ($null -ne $found) { return $found }
        }
        return $null
    }
    foreach ($property in @($Value.PSObject.Properties)) {
        $found = Get-TrustedPositiveField -Value $property.Value -Names $Names -Depth ($Depth + 1)
        if ($null -ne $found) { return $found }
    }
    return $null
}

function Assert-ConfirmedLifecycle {
    <#
    .SYNOPSIS
        验证确认批次真的完成草稿、预检查、发布和启动四个生命周期步骤。

    .DESCRIPTION
        HTTP 200 或 runState=SUCCEEDED 本身不足以证明任务已提交。该断言逐项读取 Java audit 的 toolCode/state，
        要求 sync.task.draft.save、sync.task.precheck、sync.task.publish、sync.task.run 全部 SUCCEEDED，并从各自
        output 提取可信 taskId/executionId。任一工具缺失、失败或没有真实资源 ID 都会立即终止验收。
    #>
    param([Parameter(Mandatory = $true)][object]$Confirmation)

    $results = @(Get-Items (Get-FieldValue -Object $Confirmation -Names @('toolResults')))
    $requiredTools = @(
        'sync.task.draft.save',
        'sync.task.precheck',
        'sync.task.publish',
        'sync.task.run'
    )
    $resultByTool = @{}
    foreach ($result in $results) {
        $audit = Get-FieldValue -Object $result -Names @('audit')
        $toolCode = [string](Get-FieldValue -Object $audit -Names @('toolCode'))
        if (-not [string]::IsNullOrWhiteSpace($toolCode)) {
            $resultByTool[$toolCode] = $result
        }
    }
    foreach ($toolCode in $requiredTools) {
        if (-not $resultByTool.ContainsKey($toolCode)) {
            Stop-E2E -Name '同步生命周期工具' -Detail "确认批次缺少 $toolCode；任务没有完整走过草稿、预检查、发布和启动链路。"
        }
        $audit = Get-FieldValue -Object $resultByTool[$toolCode] -Names @('audit')
        $state = Get-SafeStatusToken -Text (Get-FieldValue -Object $audit -Names @('state')) -Fallback 'UNKNOWN'
        if ($state -ne 'SUCCEEDED') {
            $message = Get-LowSensitiveMessage -Text (Get-FieldValue -Object $audit -Names @('message')) -Fallback '工具执行失败；请查看该 Run 的低敏审计详情。'
            Stop-E2E -Name '同步生命周期工具' -Detail "$toolCode 状态=$state；$message"
        }
    }

    $taskId = $null
    $executionId = $null
    foreach ($result in $results) {
        $output = Get-FieldValue -Object $result -Names @('output')
        if ($null -eq $taskId) {
            $taskId = Get-TrustedPositiveField -Value $output -Names @('taskId', 'syncTaskId')
        }
        if ($null -eq $executionId) {
            $executionId = Get-TrustedPositiveField -Value $output -Names @('executionId')
        }
    }
    if (-not (Test-PositiveIdentifier $taskId) -or -not (Test-PositiveIdentifier $executionId)) {
        Stop-E2E -Name '真实任务/执行定位' -Detail '四个生命周期工具均返回成功，但确认回执没有可信 taskId/executionId；请检查 Java ToolAdapter 输出合同。'
    }
    Add-Check -Name '同步生命周期工具' -Status 'PASS' -Detail '草稿保存、确定性预检查、发布和启动四个工具均由 Java 成功执行。'
    Add-Check -Name '真实任务/执行定位' -Status 'PASS' -Detail "已从 Java 确认回执定位 taskId=$taskId、executionId=$executionId。"
    return [pscustomobject]@{ TaskId = [long]$taskId; ExecutionId = [long]$executionId }
}

function Get-PageRecords {
    <# 将 MyBatis-Plus 分页对象统一转换成数组；非分页数组也可直接使用。 #>
    param([AllowNull()][object]$Value)

    if ($null -eq $Value) { return @() }
    $records = Get-FieldValue -Object $Value -Names @('records', 'items', 'results')
    if ($null -ne $records) { return @(Get-Items $records) }
    return @(Get-Items $Value)
}

function Get-SyncExecutionById {
    <#
    .SYNOPSIS
        从公开 execution 历史定位一个由 Java 确认回执给出的执行。

    .DESCRIPTION
        data-sync 没有单 execution 明细 GET 时，任务级执行历史就是浏览器和 E2E 共同使用的公开查询合同。此
        helper 只按确认回执或 recovery view 已给出的正整数 ID 过滤分页结果，不扫描日志正文、不访问数据库，
        也不会把未授权 execution 误当成本次 worker 的状态。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][long]$TaskId,
        [Parameter(Mandatory = $true)][long]$ExecutionId,
        [string]$Operation = '查询同步 execution'
    )

    $page = Invoke-GatewayJson `
        -Method 'GET' `
        -Path "/api/sync/sync-tasks/$TaskId/executions?current=1&size=100" `
        -AccessToken $AccessToken `
        -Body $null `
        -Operation $Operation
    return (Get-PageRecords $page | Where-Object {
            [string](Get-FieldValue -Object $_ -Names @('id', 'executionId')) -eq [string]$ExecutionId
        } | Select-Object -First 1)
}

function Wait-SyncExecutionTerminal {
    <#
    .SYNOPSIS
        等待一个公开 execution 到达终态，但不预先把 FAILED 当作 E2E 失败。

    .DESCRIPTION
        普通 Success 验收会在下一步要求 SUCCEEDED；启用 AUTOPILOT 时则必须先允许首次 execution 以 FAILED
        或 PARTIALLY_SUCCEEDED 停止，才能由服务端触发受治理恢复。将“等待终态”和“断言成功”拆开可避免
        脚本在恢复尚未启动时过早失败，同时仍只依赖公开 execution API。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][long]$TaskId,
        [Parameter(Mandatory = $true)][long]$ExecutionId,
        [ValidateRange(1, 3600)][int]$TimeoutSeconds = $ExecutionTimeoutSeconds,
        [string]$Operation = '等待同步 execution 终态'
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $terminalStates = @('SUCCEEDED', 'FAILED', 'PARTIALLY_SUCCEEDED', 'CANCELLED', 'MANUALLY_TERMINATED', 'SKIPPED')
    $execution = $null
    $state = 'NOT_VISIBLE_YET'
    do {
        $execution = Get-SyncExecutionById `
            -AccessToken $AccessToken `
            -TaskId $TaskId `
            -ExecutionId $ExecutionId `
            -Operation '查询同步 execution'
        $state = if ($null -eq $execution) {
            'NOT_VISIBLE_YET'
        } else {
            Get-SafeStatusToken -Text (Get-FieldValue -Object $execution -Names @('executionState', 'state')) -Fallback 'UNKNOWN'
        }
        if ($terminalStates -contains $state) {
            return [pscustomobject]@{ Execution = $execution; State = $state; ExecutionId = $ExecutionId }
        }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)

    if ($null -eq $execution) {
        Stop-E2E -Name $Operation -Detail '确认接口返回了 executionId，但在超时前始终无法从任务执行历史查询到它。'
    }
    Stop-E2E -Name $Operation -Detail "execution 在超时前没有进入公开终态；最后状态=$state。"
}

function Get-SyncExecutionObjectLedger {
    <#
    .SYNOPSIS
        查询一个 execution 的公开对象级账本。

    .DESCRIPTION
        failed-object retry 会把失败对象重置为 PENDING，并使父 execution 重回 QUEUED。这个列表是验证该控制面
        事实和后续 worker 终态的公开依据；它不要求也不会读取 recovery GET 中不存在的 failedObjectRetry
        嵌套对象。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][long]$TaskId,
        [Parameter(Mandatory = $true)][long]$ExecutionId,
        [string]$Operation = '查询同步对象账本'
    )

    $objectsPage = Invoke-GatewayJson `
        -Method 'GET' `
        -Path "/api/sync/sync-tasks/$TaskId/executions/$ExecutionId/objects?current=1&size=100" `
        -AccessToken $AccessToken `
        -Body $null `
        -Operation $Operation
    return @(Get-PageRecords $objectsPage)
}

function Assert-SyncExecutionSucceeded {
    <#
    .SYNOPSIS
        用 execution、对象账本和日志三个公开 API 证明 worker 已成功完成。

    .DESCRIPTION
        recovery status 的 executionState/executionFinishedAt 只是 current execution 的低敏投影，不能替代
        此处的 worker 事实。该函数因此重新通过普通 execution 和 object execution API 查询 currentExecutionId，
        再检查账本和运行日志；无论首次执行直接成功，还是失败对象被 Autopilot 重新排队后成功，证据口径一致。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][long]$TaskId,
        [Parameter(Mandatory = $true)][long]$ExecutionId,
        [Parameter(Mandatory = $true)][object]$Execution
    )

    $state = Get-SafeStatusToken -Text (Get-FieldValue -Object $Execution -Names @('executionState', 'state')) -Fallback 'UNKNOWN'
    if ($state -ne 'SUCCEEDED') {
        $errorMessage = Get-LowSensitiveMessage `
            -Text (Get-FieldValue -Object $Execution -Names @('errorMessage', 'failureReason')) `
            -Fallback '执行未成功；请在任务运行详情查看失败阶段、日志和 Agent 恢复入口。'
        Stop-E2E -Name '真实同步执行' -Detail "execution 状态=$state；$errorMessage"
    }

    $recordsRead = [long](Get-FieldValue -Object $Execution -Names @('recordsRead'))
    $recordsWritten = [long](Get-FieldValue -Object $Execution -Names @('recordsWritten'))
    $failedRecords = [long](Get-FieldValue -Object $Execution -Names @('failedRecordCount'))
    if ($failedRecords -ne 0 -or $recordsRead -le 0 -or $recordsWritten -le 0) {
        Stop-E2E -Name '同步记录计数' -Detail "execution 已成功但计数不合理：read=$recordsRead、written=$recordsWritten、failed=$failedRecords。"
    }
    if ($ExpectedRecordCount -gt 0 -and ($recordsRead -ne $ExpectedRecordCount -or $recordsWritten -ne $ExpectedRecordCount)) {
        Stop-E2E -Name '同步记录计数' -Detail "期望 read/write=$ExpectedRecordCount，实际 read=$recordsRead、written=$recordsWritten。"
    }
    Add-Check -Name '真实同步执行' -Status 'PASS' -Detail 'worker 已把本次 execution 推进到 SUCCEEDED。'
    Add-Check -Name '同步记录计数' -Status 'PASS' -Detail "read=$recordsRead、written=$recordsWritten、failed=$failedRecords。"

    $objects = @(Get-SyncExecutionObjectLedger -AccessToken $AccessToken -TaskId $TaskId -ExecutionId $ExecutionId)
    if ($objects.Count -le 0) {
        Stop-E2E -Name '同步对象账本' -Detail 'execution 已成功但没有对象级账本，无法证明每条源表到目标表映射的执行结果。'
    }
    if ($ExpectedObjectCount -gt 0 -and $objects.Count -ne $ExpectedObjectCount) {
        Stop-E2E -Name '同步对象账本' -Detail "期望对象数=$ExpectedObjectCount，实际对象数=$($objects.Count)。"
    }
    $failedObjects = @($objects | Where-Object {
            (Get-SafeStatusToken -Text (Get-FieldValue -Object $_ -Names @('objectState', 'state')) -Fallback 'UNKNOWN') -ne 'SUCCEEDED'
        })
    if ($failedObjects.Count -gt 0) {
        Stop-E2E -Name '同步对象账本' -Detail "存在 $($failedObjects.Count) 个对象未成功；请在任务详情按对象查看日志。"
    }
    Add-Check -Name '同步对象账本' -Status 'PASS' -Detail "对象数=$($objects.Count)，全部 SUCCEEDED。"

    $logsPage = Invoke-GatewayJson `
        -Method 'GET' `
        -Path "/api/sync/sync-tasks/$TaskId/executions/$ExecutionId/logs?current=1&size=100" `
        -AccessToken $AccessToken `
        -Body $null `
        -Operation '查询同步运行日志'
    $logs = @(Get-PageRecords $logsPage)
    if ($logs.Count -le 0) {
        Stop-E2E -Name '同步运行日志' -Detail 'execution 已成功但没有持久化运行日志，无法审计预检查、调度、同步开始和完成阶段。'
    }
    Add-Check -Name '同步运行日志' -Status 'PASS' -Detail "已查询到 $($logs.Count) 条持久化阶段日志，正文未输出。"
    return [pscustomobject]@{
        ExecutionId = $ExecutionId
        State = $state
        RecordsRead = $recordsRead
        RecordsWritten = $recordsWritten
        FailedRecordCount = $failedRecords
        ObjectCount = $objects.Count
        LogCount = $logs.Count
    }
}

function Wait-SyncExecutionResult {
    <#
    .SYNOPSIS
        等待本次真实 data-sync execution 成功并验证其公开 worker 证据。

    .DESCRIPTION
        这是普通 Success 场景与 Autopilot 恢复后共同复用的成功断言。启用 Autopilot 的调用方可以先使用
        Wait-SyncExecutionTerminal 观察首次失败，再只在 recovery case=RECOVERED 时回到本函数验收最终 worker。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][long]$TaskId,
        [Parameter(Mandatory = $true)][long]$ExecutionId
    )

    $terminal = Wait-SyncExecutionTerminal -AccessToken $AccessToken -TaskId $TaskId -ExecutionId $ExecutionId
    return (Assert-SyncExecutionSucceeded `
            -AccessToken $AccessToken `
            -TaskId $TaskId `
            -ExecutionId $ExecutionId `
            -Execution $terminal.Execution)
}

function Get-AutopilotRecoverySnapshot {
    <#
    .SYNOPSIS
        读取一个真实同步 execution 的公开 Autopilot recovery 快照。

    .DESCRIPTION
        该函数是 Autopilot E2E 的唯一恢复状态数据源。它只经由 Gateway 调用
        GET /api/sync/sync-tasks/{taskId}/executions/{executionId}/autopilot-recovery，因而 data-sync
        会按当前 project-owner 的 JWT、项目范围和 execution 归属重新授权。函数不访问 Kafka、数据库、
        Prometheus、Docker 或内部 controller，也不会把 JSON 正文输出到终端。

        返回值只用于后续白名单字段断言。HTTP 404、授权拒绝、空响应或非 JSON 已由 Invoke-GatewayJson
        统一收敛为失败，不能被解释为“Autopilot 尚未运行”。这样不会把部署遗漏、路由缺失或权限问题误报为
        正常的异步等待。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][long]$TaskId,
        [Parameter(Mandatory = $true)][long]$ExecutionId
    )

    return (Invoke-GatewayJson `
        -Method 'GET' `
        -Path "/api/sync/sync-tasks/$TaskId/executions/$ExecutionId/autopilot-recovery" `
        -AccessToken $AccessToken `
        -Body $null `
        -Operation '查询 Autopilot recovery 快照')
}

function Get-AutopilotRecoverySnapshotSection {
    <#
    .SYNOPSIS
        从公开快照读取一个受命名约束的低敏 section。

    .DESCRIPTION
        不同服务版本可能把快照根对象包装为 autopilotRecovery、recovery 或 data。本函数只接受调用方列出的
        section 名称，并拒绝空对象，避免递归搜寻时意外消费模型原文、工具参数或任意诊断正文。对于关键 section
        缺失，调用者必须明确失败，不能以空值推断某一步已完成。
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Snapshot,
        [Parameter(Mandatory = $true)][string[]]$Names
    )

    $root = Get-FieldValue -Object $Snapshot -Names @('autopilotRecovery', 'autopilot_recovery', 'recovery')
    if ($null -eq $root) {
        $root = $Snapshot
    }
    return (Get-FieldValue -Object $root -Names $Names)
}

function Get-AutopilotRecoveryStatus {
    <#
    .SYNOPSIS
        读取并规范化公开快照中的 case 状态。

    .DESCRIPTION
        状态只接受有限的控制面枚举。AUTO_APPROVED 只是策略决策，RECOVERY_STARTED 只是已交给受治理
        worker，二者都不能作为恢复成功。此函数故意不把未知字符串当成“处理中”，使 API 合同漂移在 E2E
        中立即可见。
    #>
    param([Parameter(Mandatory = $true)][object]$Snapshot)

    # SyncAutopilotRecoveryStatusView 是扁平公开视图。不能根据源码知识或内部 DTO 虚构嵌套 recovery-case
    # 对象；E2E 必须严格消费 Gateway 实际返回的合同。
    return (Get-SafeStatusToken -Text (
            Get-FieldValue -Object $Snapshot -Names @('caseState')
        ) -Fallback 'UNKNOWN')
}

function Get-AutopilotRecoveryCase {
    <#
    .SYNOPSIS
        获取公开快照中的持久化 recovery case 摘要。

    .DESCRIPTION
        case 是正常恢复循环、授权和成功终态断言的锚点。若接口仍未创建 case，函数返回空；轮询器可以在有限
        等待内重试。例外是 consumerResultStatus=ATTENTION_REQUIRED：服务端可在创建 case 前持久化一个有界
        停止事实，因此该状态不应被误判为“等待中”或要求虚构 case。
    #>
    param([Parameter(Mandatory = $true)][object]$Snapshot)

    # 公开视图在根节点提供 caseId 及其状态、循环等标量字段。返回根节点既能复用已有有界数值帮助方法，
    # 也不会假装 API 返回了并未公开的嵌套回执。
    $caseId = Get-FieldValue -Object $Snapshot -Names @('caseId')
    if ($null -eq $caseId -or [string]::IsNullOrWhiteSpace(([string]$caseId).Trim())) {
        return $null
    }
    return $Snapshot
}

function Get-AutopilotAttentionRequiredStatus {
    <#
    .SYNOPSIS
        从扁平公开状态投影识别 Autopilot 的有界停止结果。

    .DESCRIPTION
        ATTENTION_REQUIRED 可以来自持久化 recovery case，也可以来自尚未创建 case 的 consumer callback。二者
        都代表服务端已经停止自动路径，而不是 worker 成功。此 helper 只读取 caseState、consumerResultStatus
        与有限的 cycle/maxCycles 字段，不读取原因正文、内部回执或模型输出。
    #>
    param([Parameter(Mandatory = $true)][object]$Snapshot)

    $caseState = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $Snapshot -Names @('caseState')
    ) -Fallback 'UNKNOWN'
    $consumerStatus = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $Snapshot -Names @('consumerResultStatus')
    ) -Fallback 'UNKNOWN'
    return ($caseState -eq 'ATTENTION_REQUIRED' -or $consumerStatus -eq 'ATTENTION_REQUIRED')
}

function Get-AutopilotRecoveryCycleValue {
    <#
    .SYNOPSIS
        从 recovery case 中读取一个受限的非负循环计数。

    .DESCRIPTION
        maxCycles 是首次显式确认时的授权边界；当前 cycle 必须是整数且不能超过该边界。函数不允许空值、
        小数、负数或模型文本进入循环判断，避免因 PowerShell 的静默数值转换把无效响应误判为第 0 轮。
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Case,
        [Parameter(Mandatory = $true)][string[]]$Names,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $raw = Get-FieldValue -Object $Case -Names $Names
    $text = if ($null -eq $raw) { '' } else { ([string]$raw).Trim() }
    if ($text -notmatch '^[0-9]{1,2}$') {
        Stop-E2E -Name 'Autopilot recovery 循环合同' -Detail "$Label 缺失或不是受限非负整数。"
    }
    return [int]$text
}

function Get-AutopilotRecoveryItems {
    <#
    .SYNOPSIS
        读取公开快照的一个低敏列表 section。

    .DESCRIPTION
        该 helper 只展开固定字段名对应的数组，并统一单项/数组的 PowerShell 表示。它不对 snapshot 做递归
        扫描，因此不会因为服务端新增任何大字段而把原始日志、模型输出、SQL 或样本数据带进 E2E 断言。
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Snapshot,
        [Parameter(Mandatory = $true)][string[]]$Names
    )

    return @(Get-Items (Get-AutopilotRecoverySnapshotSection -Snapshot $Snapshot -Names $Names))
}

function Test-AutopilotReceiptIdentifier {
    <#
    .SYNOPSIS
        判断公开回执是否携带稳定的非敏感标识。

    .DESCRIPTION
        E2E 不显示 receipt ID、digest 或 action fingerprint，但必须确认它们存在，才能证明观测到的是一次
        可审计的服务端回执而非模型建议。允许大小写字母、数字、冒号、横线、下划线和点，长度上限防止异常
        响应把大段正文伪装成 ID。
    #>
    param([AllowNull()][object]$Value)

    $text = if ($null -eq $Value) { '' } else { ([string]$Value).Trim() }
    # 回执 ID 可能是 UUID、数据库数字 ID 或带摘要前缀的 token。字段名已经来自公开合同白名单，因此应
    # 接受较短的数字 ID，不能仅因某个部署使用紧凑标识就拒绝合法回执。
    return $text -match '^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$'
}

function Assert-AutopilotAuthorizationSnapshot {
    <#
    .SYNOPSIS
        验证公开快照可观察的首次授权边界。

    .DESCRIPTION
        recovery status 只公开 riskLevel、cycle 和 maxCycles，不重复公开 policy ID、receipt 或 action allowlist。
        首次 confirm-and-execute 已验证过授权盒；这里把状态中的循环和风险字段与该已验证快照逐轮比对，防止
        后续投影把恢复带出第一次确认的边界。
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Snapshot,
        [Parameter(Mandatory = $true)][object]$AuthorizationSnapshot
    )

    $maxCyclesRaw = Get-FieldValue -Object $Snapshot -Names @('maxCycles')
    $maxCyclesText = if ($null -eq $maxCyclesRaw) { '' } else { ([string]$maxCyclesRaw).Trim() }
    $riskLevel = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $Snapshot -Names @('riskLevel')
    ) -Fallback 'UNKNOWN'
    if ($maxCyclesText -notmatch '^[1-9][0-9]?$') {
        Stop-E2E -Name 'Autopilot 授权边界' -Detail "公开 recovery 快照缺少受限 maxCycles：maxCycles=$maxCyclesText。"
    }
    $snapshotMaxCycles = [int]$maxCyclesText
    if ($snapshotMaxCycles -ne [int]$AuthorizationSnapshot.MaxRecoveryCycles -or
        $snapshotMaxCycles -gt $AutopilotMaxRecoveryCycles -or $snapshotMaxCycles -gt 10 -or
        $riskLevel -ne 'LOW') {
        Stop-E2E -Name 'Autopilot 授权边界' -Detail "公开 recovery 快照未保持首次低风险授权范围：maxCycles=$snapshotMaxCycles、risk=$riskLevel。"
    }
    Add-Check -Name 'Autopilot 授权边界' -Status 'PASS' -Detail "公开快照保持低风险边界（maxCycles=$snapshotMaxCycles，risk=$riskLevel）。"
    return $snapshotMaxCycles
}

function Get-AutopilotCurrentExecutionId {
    <#
    .SYNOPSIS
        从扁平 Autopilot 公开状态读取当前 execution 标识。

    .DESCRIPTION
        recovery URL 始终以 root execution 定位，但 failed-object retry 或未来 replay 可以推进
        currentExecutionId。RECOVERED 后必须用这个值重新查询普通 execution、对象账本和日志，不能把 root
        URL 的投影误当作最终 worker 证据。
    #>
    param([Parameter(Mandatory = $true)][object]$Snapshot)

    $value = Get-FieldValue -Object $Snapshot -Names @('currentExecutionId')
    if (-not (Test-PositiveIdentifier $value)) {
        Stop-E2E -Name 'Autopilot current execution' -Detail '公开 recovery 状态缺少可信 currentExecutionId，无法验证恢复后的普通 worker 事实。'
    }
    return [long]$value
}

function Test-AutopilotRecoveryEligibleInitialState {
    <#
    .SYNOPSIS
        判断首次同步终态是否能进入已授权的自治恢复路径。

    .DESCRIPTION
        当前 data-sync 只为 FAILED 和 PARTIALLY_SUCCEEDED 的真实失败建立 Autopilot 触发。SUCCEEDED 必须直接
        按普通成功路径验收；取消、手工终止和跳过不能被脚本伪装为可自动恢复的失败。
    #>
    param([Parameter(Mandatory = $true)][string]$State)

    return $State -in @('FAILED', 'PARTIALLY_SUCCEEDED')
}

function Assert-AutopilotModelEvidence {
    <#
    .SYNOPSIS
        断言真实 Autopilot 快照中的模型 SEARCH/SKIP 决策及条件化 RAG 证据。

    .DESCRIPTION
        SyncAutopilotRecoveryStatusView 公开四个扁平 retrieval 字段，而不是嵌套内部证据对象。SEARCH 必须
        有正数 evidenceCount 和 sha256: 摘要；SKIP 必须显式报告零 count 且 digest 为空。这样 E2E 能验证同一
        recovery 的条件化检索语义，同时不读取 RAG 文本、引用正文或内部审计结构。
    #>
    param([Parameter(Mandatory = $true)][object]$Snapshot)

    $decision = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $Snapshot -Names @('retrievalDecision')
    ) -Fallback 'UNKNOWN'
    $strategy = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $Snapshot -Names @('retrievalStrategy')
    ) -Fallback 'UNKNOWN'
    $countRaw = Get-FieldValue -Object $Snapshot -Names @('retrievalEvidenceCount')
    $countText = if ($null -eq $countRaw) { '' } else { ([string]$countRaw).Trim() }
    $digest = [string](Get-FieldValue -Object $Snapshot -Names @('retrievalEvidenceDigest'))
    $digest = $digest.Trim()
    if ($strategy -eq 'UNKNOWN' -or $countText -notmatch '^[0-9]{1,4}$') {
        Stop-E2E -Name 'Autopilot 模型决策' -Detail '公开 recovery 状态缺少合法 retrievalStrategy 或 retrievalEvidenceCount。'
    }
    $count = [int]$countText
    if ($decision -eq 'SEARCH') {
        if ($count -le 0 -or $digest -notmatch '^sha256:') {
            Stop-E2E -Name 'Autopilot 模型决策' -Detail '模型选择 SEARCH，但扁平公开状态没有正数 retrievalEvidenceCount 或 sha256: retrievalEvidenceDigest。'
        }
        Add-Check -Name 'Autopilot 模型决策' -Status 'PASS' -Detail "模型在真实恢复循环中选择 $strategy 检索，并公开 $count 条摘要化证据。"
    } elseif ($decision -eq 'SKIP') {
        if ($count -ne 0 -or -not [string]::IsNullOrWhiteSpace($digest)) {
            Stop-E2E -Name 'Autopilot 模型决策' -Detail '模型选择 SKIP，但扁平公开状态没有保持 retrievalEvidenceCount=0 且 retrievalEvidenceDigest 为空。'
        }
        Add-Check -Name 'Autopilot 模型决策' -Status 'PASS' -Detail "模型在真实诊断证据充分时选择 $strategy 跳过检索，未机械要求 RAG。"
    } else {
        Stop-E2E -Name 'Autopilot 模型/RAG 运行证据' -Detail '公开 autopilot-recovery GET 的 retrievalDecision 不是 SEARCH 或 SKIP，无法证明条件化检索语义。'
    }
    return $decision
}

function Assert-AutopilotPreviewAndQuarantineReceipts {
    <#
    .SYNOPSIS
        验证公开视图中的可选 quarantine 应用结果。

    .DESCRIPTION
        当 recoveryAction=APPLY_QUARANTINE 时，SyncAutopilotRecoveryStatusView 必须报告 operation=APPLIED、
        receipt=COMPLETED 以及正数 selected/affected count。该公开视图不含 preview 或 authorization receipt，
        因而终态调用者会将缺少 preview/authorization linkage 作为不可证明的 E2E 缺口，而不是把它假定为
        已发生。没有选择隔离时，此函数不要求隔离副作用。
    #>
    param([Parameter(Mandatory = $true)][object]$Snapshot)

    $recoveryAction = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $Snapshot -Names @('recoveryAction')
    ) -Fallback 'UNKNOWN'
    if ($recoveryAction -ne 'APPLY_QUARANTINE') {
        return $false
    }
    $operationState = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $Snapshot -Names @('quarantineOperationState')
    ) -Fallback 'UNKNOWN'
    $receiptState = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $Snapshot -Names @('quarantineReceiptState')
    ) -Fallback 'UNKNOWN'
    $selectedRaw = Get-FieldValue -Object $Snapshot -Names @('quarantineSelectedCount')
    $selectedText = if ($null -eq $selectedRaw) { '' } else { ([string]$selectedRaw).Trim() }
    $affectedRaw = Get-FieldValue -Object $Snapshot -Names @('quarantineAffectedCount')
    $affectedText = if ($null -eq $affectedRaw) { '' } else { ([string]$affectedRaw).Trim() }
    if ($operationState -ne 'APPLIED' -or $receiptState -ne 'COMPLETED' -or
        $selectedText -notmatch '^[1-9][0-9]*$' -or $affectedText -notmatch '^[1-9][0-9]*$') {
        Stop-E2E -Name 'Autopilot quarantine 回执' -Detail "公开 quarantine 状态不完整：operation=$operationState、receipt=$receiptState、selected=$selectedText、affected=$affectedText。"
    }
    Add-Check -Name 'Autopilot quarantine 回执' -Status 'PASS' -Detail "公开视图证明可选 quarantine 已应用（selected=$selectedText，affected=$affectedText）。"
    return $true
}

function Assert-AutopilotFailedObjectRetry {
    <#
    .SYNOPSIS
        验证失败对象重试已由服务端排队并交给 worker。

    .DESCRIPTION
        一条 AUTO_APPROVED 决策、Kafka 投递标记或指标增加均不足以证明恢复已执行。公开 status view 以
        consumerResultStatus=RECOVERY_STARTED 和 reason=AUTOPILOT_FAILED_OBJECTS_REQUEUED 表示失败对象重试
        已进入受治理消费者路径。最终 RECOVERED 时还会由 executionState 的 worker 终态断言要求成功。
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Snapshot,
        [Parameter(Mandatory = $true)][string]$CaseState
    )

    $status = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $Snapshot -Names @('consumerResultStatus')
    ) -Fallback 'UNKNOWN'
    $reasonCode = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $Snapshot -Names @('consumerResultReasonCode')
    ) -Fallback 'UNKNOWN'
    if ($status -eq 'RECOVERY_STARTED' -and $reasonCode -eq 'AUTOPILOT_FAILED_OBJECTS_REQUEUED') {
        Add-Check -Name 'Autopilot failed-object retry' -Status 'PASS' -Detail '公开消费者结果证明失败对象重试已排队。'
        return $true
    }
    if ($CaseState -in @('RECOVERY_STARTED', 'RECOVERED')) {
        Stop-E2E -Name 'Autopilot failed-object retry' -Detail "公开消费者结果不能证明失败对象已重试排队：status=$status、reason=$reasonCode。"
    }
    return $false
}

function Assert-AutopilotWorkerTerminal {
    <#
    .SYNOPSIS
        验证已重试 execution 的 worker 终态与恢复 case 终态一致。

    .DESCRIPTION
        RECOVERED 不能仅由 case 状态声称。公开 status view 的 executionState=SUCCEEDED 是 worker 已完成的
        运行事实，且只能使用 executionFinishedAt 证明完成时间。ATTENTION_REQUIRED 是有界自动停止，不要求
        WorkerTerminal=true，调用者不得将它叙述为 worker 成功。
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Snapshot,
        [Parameter(Mandatory = $true)][string]$CaseState
    )

    $workerState = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $Snapshot -Names @('executionState')
    ) -Fallback 'UNKNOWN'
    if ($CaseState -eq 'RECOVERED') {
        $executionFinishedAt = Get-FieldValue -Object $Snapshot -Names @('executionFinishedAt')
        $executionFinishedAtText = if ($null -eq $executionFinishedAt) { '' } else { ([string]$executionFinishedAt).Trim() }
        if ($workerState -ne 'SUCCEEDED' -or [string]::IsNullOrWhiteSpace($executionFinishedAtText)) {
            Stop-E2E -Name 'Autopilot worker terminal' -Detail "case 已 RECOVERED，但 worker terminal=$workerState，不是成功终态。"
        }
        Add-Check -Name 'Autopilot worker terminal' -Status 'PASS' -Detail '公开 executionState 和 executionFinishedAt 证明恢复 worker 已成功终态。'
        return $true
    }
    if ($CaseState -eq 'ATTENTION_REQUIRED') {
        return $false
    }
    return $false
}

function Assert-AutopilotRecoverySnapshot {
    <#
    .SYNOPSIS
        对一次公开 Autopilot recovery 快照执行所有运行时不变量检查。

    .DESCRIPTION
        该函数将授权、模型检索、可选 preview/quarantine、失败对象重试、worker 和循环状态串成同一份公开
        快照的证据链。它不创建任务、不补造 execution、不对恢复 API 发送 POST，也不读取源代码/指标作为
        替代证据。RECOVERED 必须含可复核 worker 事实；ATTENTION_REQUIRED 只验证有界停止，绝不虚称 worker
        已成功。返回的摘要仅保存低敏状态和计数，供轮询器决定是否已经达到这两个可接受结果。
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Snapshot,
        [AllowNull()][object]$AuthorizationSnapshot
    )

    $case = Get-AutopilotRecoveryCase -Snapshot $Snapshot
    $caseState = Get-AutopilotRecoveryStatus -Snapshot $Snapshot
    if ($null -eq $case) {
        if (Get-AutopilotAttentionRequiredStatus -Snapshot $Snapshot) {
            # 只有 callback 的有界停止仍属于公开恢复结果。接受停止前必须校验扁平 SEARCH/SKIP 投影，
            # 防止这种异常结构绕过检索合同。
            $decision = Assert-AutopilotModelEvidence -Snapshot $Snapshot
            $cycle = Get-AutopilotRecoveryCycleValue -Case $Snapshot -Names @('cycle') -Label 'cycle'
            $publicMaxCyclesRaw = Get-FieldValue -Object $Snapshot -Names @('maxCycles')
            $publicMaxCyclesText = if ($null -eq $publicMaxCyclesRaw) { '' } else { ([string]$publicMaxCyclesRaw).Trim() }
            $maxCycles = if ([string]::IsNullOrWhiteSpace($publicMaxCyclesText)) {
                if ($null -eq $AuthorizationSnapshot) {
                    Stop-E2E -Name 'Autopilot ATTENTION_REQUIRED 边界' -Detail '无 recovery case 的公开停止结果没有 maxCycles，且本地没有首次授权快照可验证其边界。'
                }
                [int]$AuthorizationSnapshot.MaxRecoveryCycles
            } else {
                Get-AutopilotRecoveryCycleValue -Case $Snapshot -Names @('maxCycles') -Label 'maxCycles'
            }
            $reasonCode = Get-SafeStatusToken -Text (
                Get-FieldValue -Object $Snapshot -Names @('consumerResultReasonCode')
            ) -Fallback 'UNKNOWN'
            if ($reasonCode -eq 'UNKNOWN' -or $maxCycles -le 0 -or $maxCycles -gt $AutopilotMaxRecoveryCycles -or
                ($null -ne $AuthorizationSnapshot -and $maxCycles -ne [int]$AuthorizationSnapshot.MaxRecoveryCycles) -or
                $cycle -gt $maxCycles) {
                Stop-E2E -Name 'Autopilot ATTENTION_REQUIRED 边界' -Detail "无 recovery case 的公开停止结果越过首次授权边界：cycle=$cycle、maxCycles=$maxCycles。"
            }
            Add-Check -Name 'Autopilot ATTENTION_REQUIRED 边界' -Status 'PASS' -Detail "公开 consumer callback 在 cycle=$cycle/$maxCycles 停止自动路径，未声称 worker 成功。"
            return [pscustomobject]@{
                HasCase = $false
                State = 'ATTENTION_REQUIRED'
                Cycle = $cycle
                MaxCycles = $maxCycles
                RetrievalDecision = $decision
                QuarantineApplied = $false
                RetryQueued = $false
                WorkerTerminal = $false
                IsTerminal = $true
            }
        }
        return [pscustomobject]@{
            HasCase = $false
            State = $caseState
            Cycle = $null
            MaxCycles = $null
            IsTerminal = $false
        }
    }
    if ($caseState -notin @(
            'AUTO_APPROVED',
            'RECOVERY_STARTED',
            'RECOVERED',
            'ATTENTION_REQUIRED',
            'WAITING_APPROVAL',
            'MANUALLY_APPROVED',
            'REJECTED',
            'CANCELLED'
        )) {
        Stop-E2E -Name 'Autopilot recovery 状态' -Detail "公开快照返回未知 recovery case 状态=$caseState。"
    }
    $authorizedMaxCycles = if ($null -eq $AuthorizationSnapshot) {
        $maxCyclesRaw = Get-FieldValue -Object $Snapshot -Names @('maxCycles')
        $maxCyclesText = if ($null -eq $maxCyclesRaw) { '' } else { ([string]$maxCyclesRaw).Trim() }
        if ($maxCyclesText -notmatch '^[1-9][0-9]?$') {
            Stop-E2E -Name 'Autopilot 授权边界' -Detail '公开 recovery 快照缺少受限 maxCycles。'
        }
        [int]$maxCyclesText
    } else {
        Assert-AutopilotAuthorizationSnapshot -Snapshot $Snapshot -AuthorizationSnapshot $AuthorizationSnapshot
    }
    $cycle = Get-AutopilotRecoveryCycleValue -Case $case -Names @('cycle', 'recoveryCycle', 'recovery_cycle') -Label 'cycle'
    $caseMaxCycles = Get-AutopilotRecoveryCycleValue -Case $case -Names @('maxCycles', 'max_cycles', 'maxRecoveryCycles', 'max_recovery_cycles') -Label 'case.maxCycles'
    if ($caseMaxCycles -ne $authorizedMaxCycles -or $caseMaxCycles -gt $AutopilotMaxRecoveryCycles -or $cycle -gt $caseMaxCycles) {
        Stop-E2E -Name 'Autopilot recovery 循环合同' -Detail "公开 case 循环越过首次授权边界：cycle=$cycle、caseMaxCycles=$caseMaxCycles、authorizationMaxCycles=$authorizedMaxCycles。"
    }
    if ($caseState -in @('REJECTED', 'CANCELLED', 'WAITING_APPROVAL', 'MANUALLY_APPROVED')) {
        Stop-E2E -Name 'Autopilot recovery 状态' -Detail "首次 AUTOPILOT 授权后的恢复进入非自动终点=$caseState；本 E2E 只接受 RECOVERED 或有界 ATTENTION_REQUIRED。"
    }

    $decision = Assert-AutopilotModelEvidence -Snapshot $Snapshot
    $quarantineApplied = if ($caseState -eq 'ATTENTION_REQUIRED') { $false } else { Assert-AutopilotPreviewAndQuarantineReceipts -Snapshot $Snapshot }
    $retryQueued = if ($caseState -eq 'ATTENTION_REQUIRED') { $false } else { Assert-AutopilotFailedObjectRetry -Snapshot $Snapshot -CaseState $caseState }
    $workerTerminal = if ($caseState -eq 'ATTENTION_REQUIRED') { $false } else { Assert-AutopilotWorkerTerminal -Snapshot $Snapshot -CaseState $caseState }
    return [pscustomobject]@{
        HasCase = $true
        State = $caseState
        Cycle = $cycle
        MaxCycles = $caseMaxCycles
        RetrievalDecision = $decision
        QuarantineApplied = $quarantineApplied
        RetryQueued = $retryQueued
        WorkerTerminal = $workerTerminal
        IsTerminal = ($caseState -in @('RECOVERED', 'ATTENTION_REQUIRED'))
    }
}

function Wait-AutopilotRecoveryResult {
    <#
    .SYNOPSIS
        轮询公开 recovery GET，直至真实恢复完成或安全停在 ATTENTION_REQUIRED。

    .DESCRIPTION
        首次 execution 失败不是 E2E 的最终结论。启用 AUTOPILOT 后，本函数以执行超时和首次授权的
        maxCycles 为双边界，持续读取同一 task/execution 的公开 recovery 快照。只有看到完整运行时证据链
        后才接受 RECOVERED；若恢复不可行，则必须在不超过 maxCycles 的前提下到达 ATTENTION_REQUIRED。
        到期、case 未出现、状态停滞、授权越界、回执缺失或任何未知状态都失败，绝不借助源码、Docker 输出、
        Kafka 偏移或 metrics 冒充恢复成功。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][long]$TaskId,
        [Parameter(Mandatory = $true)][long]$ExecutionId,
        [Parameter(Mandatory = $true)][object]$AuthorizationSnapshot
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($ExecutionTimeoutSeconds)
    $lastSummary = $null
    $seenRecoveryOutcome = $false
    do {
        $snapshot = Get-AutopilotRecoverySnapshot -AccessToken $AccessToken -TaskId $TaskId -ExecutionId $ExecutionId
        $summary = Assert-AutopilotRecoverySnapshot -Snapshot $snapshot -AuthorizationSnapshot $AuthorizationSnapshot
        $lastSummary = $summary
        if (-not $summary.HasCase -and -not $summary.IsTerminal) {
            Start-Sleep -Seconds 2
            continue
        }
        $seenRecoveryOutcome = $true
        if ($summary.State -eq 'RECOVERED') {
            if (-not $summary.RetryQueued) {
                Stop-E2E -Name 'Autopilot RECOVERED 运行证据' -Detail 'case 已 RECOVERED，但公开消费者结果不能证明 failed-object retry 已排队。'
            }
            $currentExecutionId = Get-AutopilotCurrentExecutionId -Snapshot $snapshot
            Add-Check -Name 'Autopilot 最终恢复' -Status 'PASS' -Detail "真实恢复循环在 cycle=$($summary.Cycle)/$($summary.MaxCycles) 到达 RECOVERED。"
            $summary | Add-Member -MemberType NoteProperty -Name CurrentExecutionId -Value $currentExecutionId
            return $summary
        }
        if ($summary.State -eq 'ATTENTION_REQUIRED') {
            if ($summary.Cycle -gt $summary.MaxCycles) {
                Stop-E2E -Name 'Autopilot ATTENTION_REQUIRED 边界' -Detail "不可恢复路径没有在 maxCycles 内停止：cycle=$($summary.Cycle)、maxCycles=$($summary.MaxCycles)。"
            }
            Add-Check -Name 'Autopilot 最终恢复' -Status 'PASS' -Detail "不可恢复路径在 cycle=$($summary.Cycle)/$($summary.MaxCycles) 停于 ATTENTION_REQUIRED，未无限重试且未声称 worker 成功。"
            return $summary
        }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)

    if (-not $seenRecoveryOutcome) {
        Stop-E2E -Name 'Autopilot recovery 轮询' -Detail '初始 execution 未成功后，在超时前始终没有出现公开 recovery case 或持久化 ATTENTION_REQUIRED 停止结果；无法证明自治恢复已经收敛。'
    }
    $lastState = if ($null -eq $lastSummary) { 'UNKNOWN' } else { $lastSummary.State }
    Stop-E2E -Name 'Autopilot recovery 轮询' -Detail "公开 recovery 快照在超时前未达到 RECOVERED 或 ATTENTION_REQUIRED；最后状态=$lastState。"
}

function Invoke-AutopilotSuccessRecoveryFlow {
    <#
    .SYNOPSIS
        执行已授权 Success 场景的首次终态与自治恢复分流。

    .DESCRIPTION
        该函数把真实 E2E 的关键时序固定在一个位置：先等待 root execution 终态；首次成功按普通 worker
        证据验收；只有 FAILED/PARTIALLY_SUCCEEDED 才等待 recovery；RECOVERED 后再用 currentExecutionId 验证
        普通 execution、对象账本和日志；ATTENTION_REQUIRED 只返回有界停止摘要。四个 ScriptBlock 让离线回归
        能复用这条生产分流而不访问 Gateway，正常入口则传入实际公开 API helper。
    #>
    param(
        [Parameter(Mandatory = $true)][long]$TaskId,
        [Parameter(Mandatory = $true)][long]$ExecutionId,
        [Parameter(Mandatory = $true)][object]$AuthorizationSnapshot,
        [Parameter(Mandatory = $true)][scriptblock]$WaitTerminal,
        [Parameter(Mandatory = $true)][scriptblock]$AssertSucceeded,
        [Parameter(Mandatory = $true)][scriptblock]$WaitRecovery
    )

    $initialTerminal = & $WaitTerminal $ExecutionId '等待首次同步 execution 终态'
    if ($null -eq $initialTerminal) {
        Stop-E2E -Name 'Autopilot 首次 execution' -Detail '首次 execution 终态查询没有返回公开结果。'
    }
    if ($initialTerminal.State -eq 'SUCCEEDED') {
        $summary = & $AssertSucceeded $ExecutionId $initialTerminal.Execution
        Add-Check -Name 'Autopilot 恢复分流' -Status 'PASS' -Detail '首次 execution 已成功，未进入恢复路径。'
        return [pscustomobject]@{
            Outcome = 'INITIAL_SUCCEEDED'
            ExecutionSummary = $summary
            RecoverySummary = $null
        }
    }
    if (-not (Test-AutopilotRecoveryEligibleInitialState -State $initialTerminal.State)) {
        Stop-E2E -Name 'Autopilot 首次 execution' -Detail "首次 execution 进入不支持自治恢复的终态=$($initialTerminal.State)。"
    }

    Add-Check -Name 'Autopilot 恢复分流' -Status 'PASS' -Detail "首次 execution 状态=$($initialTerminal.State)，开始轮询受治理恢复结果。"
    $recoverySummary = & $WaitRecovery $AuthorizationSnapshot
    if ($null -eq $recoverySummary) {
        Stop-E2E -Name 'Autopilot recovery 轮询' -Detail '自治恢复轮询没有返回公开收敛摘要。'
    }
    if ($recoverySummary.State -eq 'RECOVERED') {
        if (-not (Test-PositiveIdentifier $recoverySummary.CurrentExecutionId)) {
            Stop-E2E -Name 'Autopilot current execution' -Detail 'RECOVERED 没有返回可信 currentExecutionId，无法验证最终 worker 事实。'
        }
        $currentExecutionId = [long]$recoverySummary.CurrentExecutionId
        $currentTerminal = & $WaitTerminal $currentExecutionId '等待 Autopilot current execution 终态'
        if ($null -eq $currentTerminal) {
            Stop-E2E -Name 'Autopilot current execution' -Detail 'RECOVERED 后的 current execution 终态查询没有返回公开结果。'
        }
        $summary = & $AssertSucceeded $currentExecutionId $currentTerminal.Execution
        return [pscustomobject]@{
            Outcome = 'RECOVERED'
            ExecutionSummary = $summary
            RecoverySummary = $recoverySummary
        }
    }
    if ($recoverySummary.State -eq 'ATTENTION_REQUIRED') {
        # 此处有意不调用 AssertSucceeded：自治流程有界停止不等于 worker 执行成功。
        Add-Check -Name 'Autopilot 有界停止' -Status 'PASS' -Detail '恢复已停在 ATTENTION_REQUIRED；未将此状态计为 worker 成功。'
        return [pscustomobject]@{
            Outcome = 'ATTENTION_REQUIRED'
            ExecutionSummary = $null
            RecoverySummary = $recoverySummary
        }
    }
    Stop-E2E -Name 'Autopilot recovery 轮询' -Detail "自治恢复返回了不支持的收敛状态=$($recoverySummary.State)。"
}

function Add-RolesFromItems {
    <#
    .SYNOPSIS
        从低敏列表中提取已知 Agent 角色名。

    .DESCRIPTION
        只读取 agentRole/role 或直接的角色字符串，并且只保留六个白名单角色。这样即使响应中有其他文本，
        也不会因为通用递归而把模型输出或错误正文当作角色证据。
    #>
    param(
        # HashSet 在开始收集角色前必然为空。PowerShell 会把“空集合”误判成没有为 Mandatory 参数
        # 传值，因此必须显式允许空集合；否则真实 Agent 已返回结果，验证器却会在读取第一条证据前退出。
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][System.Collections.Generic.HashSet[string]]$Set,
        [AllowNull()][AllowEmptyCollection()][object]$Items
    )

    foreach ($item in (Get-Items $Items)) {
        # skippedRoles 在 JSON 中通常是 {"ROLE":"原因"} 对象，而不是角色数组。
        # 这里只读取对象键；值可能是模型或工具说明，绝不能为了找角色把值打印出来。
        if ($item -is [System.Collections.IDictionary]) {
            foreach ($key in $item.Keys) {
                if ($script:ExpectedRoles -contains ([string]$key)) {
                    $Set.Add([string]$key) | Out-Null
                }
            }
        }
        # Windows PowerShell 5.1 的 ConvertFrom-Json 会把 JSON 对象解析为 PSCustomObject，
        # 因此还要检查其属性名，才能兼容同一个 skippedRoles 合同的另一种运行时表示。
        foreach ($property in @($item.PSObject.Properties)) {
            if ($script:ExpectedRoles -contains $property.Name) {
                $Set.Add($property.Name) | Out-Null
            }
        }
        $candidate = if ($item -is [string]) { [string]$item } else { [string](Get-FieldValue -Object $item -Names @('agentRole', 'role')) }
        if ($script:ExpectedRoles -contains $candidate) {
            $Set.Add($candidate) | Out-Null
        }
    }
}

function Test-SpecialistCompletionStatus {
    <#
    .SYNOPSIS
        判断一个 Specialist turn 状态是否可以作为最终成功证据。

    .DESCRIPTION
        服务端可能使用 COMPLETED 或 SUCCEEDED 表达已完成。这里把这两个稳定枚举集中处理，避免不同断言各自
        写一份状态白名单后出现“角色参与通过但调度诊断仍报失败”的矛盾。WAITING、PARTIALLY_FAILED、FAILED 和
        无法识别的状态都不会被当成成功；它们只有在更晚的同角色后置结果成功时才会被恢复。
    #>
    param([Parameter(Mandatory = $true)][string]$Status)

    return @('COMPLETED', 'SUCCEEDED') -contains $Status
}

function Get-SpecialistResultTimeline {
    <#
    .SYNOPSIS
        按首轮和后置复核的真实时间顺序提取 Specialist 低敏结果。

    .DESCRIPTION
        一个 Recovery 请求可能先在 specialistAgentExecution 中得到瞬态 FAILED，再在
        specialistVerificationExecution 中对同一角色完成补偿性复核。若只读取首轮批次状态，最终验收会留下
        已经恢复的 PARTIALLY_FAILED 告警；若只看“任何一次成功”，又会掩盖后来再次失败的真实问题。

        本函数因此只读取角色、状态、错误码和固定阶段名，并显式把 INITIAL 放在 POST_VERIFICATION 之前。调用方
        可以安全地按数组顺序选择每个角色的最后一条结果，而不会访问模型摘要、RAG 正文、工具参数或业务资源。
    #>
    param([Parameter(Mandatory = $true)][object]$Response)

    $timeline = [System.Collections.Generic.List[object]]::new()
    $sequence = 0
    $phases = @(
        [pscustomobject]@{
            Name = 'INITIAL'
            Container = Get-FieldValue -Object $Response -Names @('specialistAgentExecution')
        },
        [pscustomobject]@{
            Name = 'POST_VERIFICATION'
            Container = Get-FieldValue -Object $Response -Names @('specialistVerificationExecution')
        }
    )

    foreach ($phase in $phases) {
        foreach ($item in (Get-Items (Get-FieldValue -Object $phase.Container -Names @('results')))) {
            $role = Get-SafeStatusToken -Text (Get-FieldValue -Object $item -Names @('agentRole', 'role')) -Fallback 'UNKNOWN_ROLE'
            if ($script:ExpectedRoles -notcontains $role) {
                continue
            }
            $sequence++
            $timeline.Add([pscustomobject]@{
                Role = $role
                Status = Get-SafeStatusToken -Text (Get-FieldValue -Object $item -Names @('status')) -Fallback 'UNKNOWN'
                ErrorCode = Get-SafeStatusToken -Text (Get-FieldValue -Object $item -Names @('errorCode', 'error_code')) -Fallback 'NONE'
                Phase = $phase.Name
                Sequence = $sequence
            }) | Out-Null
        }
    }

    return @($timeline.ToArray())
}

function Get-SpecialistFinalStatusEvidence {
    <#
    .SYNOPSIS
        将同一角色的多阶段结果归并为可用于最终验收的最终状态。

    .DESCRIPTION
        归并规则是“同一角色的更晚阶段覆盖更早阶段”：INITIAL 的 FAILED 被 POST_VERIFICATION 的 COMPLETED
        覆盖时，该角色不再产生 WARN；反过来，若后置结果仍 FAILED、WAITING 或未知，则最终失败必须保留。
        这比批次级 status 更准确，因为一个批次可以包含多个角色及不同时间的重试结果。

        除最终状态外，函数还保留已恢复转换和未恢复结果的低敏字符串，供人类排障时区分“短暂抖动已恢复”与
        “当前仍有问题”。失败批次没有可归因的非成功角色结果时，函数明确标记为证据不足并保持 WARN，避免把
        服务端不完整响应，或未知角色失败，误判为当前角色均已成功。
    #>
    param([Parameter(Mandatory = $true)][object]$Response)

    $timeline = @(Get-SpecialistResultTimeline -Response $Response)
    $latestByRole = @{}
    $recoveredByRole = @{}

    foreach ($record in $timeline) {
        $previous = $null
        if ($latestByRole.ContainsKey($record.Role)) {
            $previous = $latestByRole[$record.Role]
        }
        if ($null -ne $previous) {
            $previousSucceeded = Test-SpecialistCompletionStatus -Status $previous.Status
            $currentSucceeded = Test-SpecialistCompletionStatus -Status $record.Status
            if (-not $previousSucceeded -and $currentSucceeded) {
                # 只有同一角色先出现非成功结果、随后成功，后一次成功才属于真实恢复。
                $recoveredByRole[$record.Role] = "$($record.Role)=$($previous.Status)/$($previous.ErrorCode)=>$($record.Status)"
            } elseif (-not $currentSucceeded) {
                # 后续失败会覆盖更早的恢复；只有角色最终状态才能决定是否清除警告。
                $recoveredByRole.Remove($record.Role) | Out-Null
            }
        }
        $latestByRole[$record.Role] = $record
    }

    $completed = [System.Collections.Generic.List[string]]::new()
    $unresolved = [System.Collections.Generic.List[string]]::new()
    $finalStates = [System.Collections.Generic.List[string]]::new()
    foreach ($role in @($latestByRole.Keys | Sort-Object)) {
        $record = $latestByRole[$role]
        $state = "$role=$($record.Status)/$($record.ErrorCode)"
        $finalStates.Add($state) | Out-Null
        if (Test-SpecialistCompletionStatus -Status $record.Status) {
            $completed.Add($role) | Out-Null
        } else {
            $unresolved.Add($state) | Out-Null
        }
    }

    $specialist = Get-FieldValue -Object $Response -Names @('specialistAgentExecution')
    $verification = Get-FieldValue -Object $Response -Names @('specialistVerificationExecution')
    $initialBatchStatus = Get-SafeStatusToken -Text (Get-FieldValue -Object $specialist -Names @('status')) -Fallback 'NOT_RECORDED'
    $verificationBatchStatus = Get-SafeStatusToken -Text (Get-FieldValue -Object $verification -Names @('status')) -Fallback 'NOT_RECORDED'
    # 只有响应在准确阶段指出至少一个已知失败角色时，失败批次才可视为可恢复；否则未知角色失败或不完整
    # 载荷会被无关角色的成功结果掩盖。
    $failedBatchWithoutRoleResult = $false
    foreach ($phaseBatch in @(
        [pscustomobject]@{ Name = 'INITIAL'; Status = $initialBatchStatus },
        [pscustomobject]@{ Name = 'POST_VERIFICATION'; Status = $verificationBatchStatus }
    )) {
        if (@('FAILED', 'PARTIALLY_FAILED') -notcontains $phaseBatch.Status) {
            continue
        }
        $phaseHasNonCompletedRole = @(
            $timeline | Where-Object {
                $_.Phase -eq $phaseBatch.Name -and -not (Test-SpecialistCompletionStatus -Status $_.Status)
            }
        ).Count -gt 0
        if (-not $phaseHasNonCompletedRole) {
            $failedBatchWithoutRoleResult = $true
            break
        }
    }
    $finalBatchStatus = if ($unresolved.Count -gt 0) {
        'PARTIALLY_FAILED'
    } elseif ($failedBatchWithoutRoleResult) {
        'EVIDENCE_INCOMPLETE'
    } elseif ($latestByRole.Count -gt 0) {
        'COMPLETED'
    } else {
        'NOT_RECORDED'
    }

    return [pscustomobject]@{
        Registered = @($latestByRole.Keys | Sort-Object)
        Completed = @($completed | Sort-Object)
        Unresolved = @($unresolved | Sort-Object -Unique)
        Recovered = @($recoveredByRole.Values | Sort-Object -Unique)
        FinalStates = @($finalStates | Sort-Object)
        InitialBatchStatus = $initialBatchStatus
        VerificationBatchStatus = $verificationBatchStatus
        FinalBatchStatus = $finalBatchStatus
        FailedBatchWithoutRoleResult = $failedBatchWithoutRoleResult
        RequiresWarning = ($unresolved.Count -gt 0 -or $failedBatchWithoutRoleResult)
    }
}

function Get-RoleEvidence {
    <#
    .SYNOPSIS
        从 Agent 低敏响应中构建“注册角色”和“最终成功角色”集合。

    .DESCRIPTION
        注册证据来自 execution session、协作执行计划、调度名册以及 skippedRoles；最终执行证据由
        Get-SpecialistFinalStatusEvidence 统一处理首轮和后置复核，避免初始瞬态失败在后置成功后仍污染最终
        验收。函数不访问 plan.arguments、不读取模型消息，也不输出原始响应。
    #>
    param([Parameter(Mandatory = $true)][object]$Response)

    $registered = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $finalStatus = Get-SpecialistFinalStatusEvidence -Response $Response

    $session = Get-FieldValue -Object $Response -Names @('agentExecutionSession')
    Add-RolesFromItems -Set $registered -Items (Get-FieldValue -Object $session -Names @('activeRoles'))
    Add-RolesFromItems -Set $registered -Items (Get-FieldValue -Object $session -Names @('workItems'))
    $roster = Get-FieldValue -Object $session -Names @('rosterCoverage')
    foreach ($property in @($roster.PSObject.Properties)) {
        if ($property.Name -match '(?i)role|agent') {
            Add-RolesFromItems -Set $registered -Items $property.Value
        }
    }

    $executionPlan = Get-FieldValue -Object $Response -Names @('agentCollaborationExecutionPlan')
    Add-RolesFromItems -Set $registered -Items (Get-FieldValue -Object $executionPlan -Names @('workItems'))
    $scheduling = Get-FieldValue -Object $Response -Names @('intelligentGatewayGovernance')
    $scheduling = Get-FieldValue -Object $scheduling -Names @('agentSessionScheduling')
    Add-RolesFromItems -Set $registered -Items (Get-FieldValue -Object $scheduling -Names @('participatingAgents'))
    Add-RolesFromItems -Set $registered -Items (Get-FieldValue -Object $scheduling -Names @('standbyAgents', 'deferredAgents'))

    Add-RolesFromItems -Set $registered -Items $finalStatus.Registered
    $specialist = Get-FieldValue -Object $Response -Names @('specialistAgentExecution')
    Add-RolesFromItems -Set $registered -Items (Get-FieldValue -Object $specialist -Names @('skippedRoles'))

    return [pscustomobject]@{
        Registered = @($registered | Sort-Object)
        Executed = $finalStatus.Completed
        NonCompleted = $finalStatus.Unresolved
        Recovered = $finalStatus.Recovered
        FinalStates = $finalStatus.FinalStates
        InitialBatchStatus = $finalStatus.InitialBatchStatus
        VerificationBatchStatus = $finalStatus.VerificationBatchStatus
        FinalBatchStatus = $finalStatus.FinalBatchStatus
        FailedBatchWithoutRoleResult = $finalStatus.FailedBatchWithoutRoleResult
        RequiresWarning = $finalStatus.RequiresWarning
    }
}

function Get-RequiredRolesForStage {
    <#
    .SYNOPSIS
        返回当前场景和验收阶段必须成功完成的专业 Agent 角色集合。

    .DESCRIPTION
        六 Agent 闭环并不是要求每个 HTTP 响应都运行全部角色。Success 的首次规划响应尚未创建任务，
        因而只需要证明数据源消歧和同步规划完成；PRECHECK 与 MONITOR 依赖真实 taskId/executionId，
        必须等用户显式确认提交后才被纳入强制成功集合。这个函数把该时序集中在一个地方，避免注册断言和
        durable fact 断言各自维护一套角色规则而再次发生前后不一致。

        Recovery 的目标是基于可审计诊断证据产生受治理恢复方案，所以无论传入哪个阶段都要求恢复和监控角色。
        知识角色是否执行由 Recovery 模型的 SEARCH/SKIP 决策决定，并由专门证据断言验证。
        -RequireAllSixRolesExecuted 是显式诊断覆盖项，优先级最高，用于人工验证所有角色同时可用，
        不能作为日常 Success/Recovery 验收的默认要求。
    #>
    param(
        [ValidateSet('Planning', 'PostConfirmation')]
        [string]$Stage = 'Planning'
    )

    if ($RequireAllSixRolesExecuted) {
        return $script:ExpectedRoles
    }
    if ($Scenario -eq 'Recovery') {
        return $script:RecoveryExecutedRoles
    }
    if ($Stage -eq 'PostConfirmation') {
        return $script:SuccessPostConfirmationExecutedRoles
    }
    return $script:SuccessPlanningExecutedRoles
}

function Test-RecoveryGovernedWaiting {
    <#
    .SYNOPSIS
        Recognize a real Recovery specialist wait without treating it as completed execution.

    .DESCRIPTION
        Recovery is allowed to finish a read-only diagnostic turn in WAITING_FOR_INPUT while it
        waits for grounded evidence, monitoring facts, user approval, or a Java ToolPlan handoff.
        This helper only permits the role-participation check to continue; Assert-RagAndRecoveryEvidence
        and Assert-BridgeEvidence remain the authoritative gates for evidence and approval semantics.
    #>
    param([Parameter(Mandatory = $true)][object]$Response)

    $result = Get-SpecialistResultByRole -Response $Response -Role 'RECOVERY_AGENT'
    if ($null -eq $result) {
        return $false
    }
    $status = Get-SafeStatusToken -Text (Get-FieldValue -Object $result -Names @('status')) -Fallback 'UNKNOWN'
    if ($status -ne 'WAITING_FOR_INPUT') {
        return $false
    }
    $output = Get-FieldValue -Object $result -Names @('structuredOutput')
    if (Test-TrueFlag (Get-FieldValue -Object $output -Names @('executed'))) {
        return $false
    }
    $requiredFields = @(Get-Items (Get-FieldValue -Object $result -Names @('requiredInputFields', 'required_input_fields')))
    if ($requiredFields.Count -gt 0) {
        return $true
    }
    $nextStep = [string](Get-FieldValue -Object $output -Names @('nextStep', 'next_step'))
    return -not [string]::IsNullOrWhiteSpace($nextStep)
}

function Assert-RoleEvidence {
    <#
    .SYNOPSIS
        验证当前验收阶段所需的专业 Agent 是否已经注册并成功参与。

    .DESCRIPTION
        初始 Agent 响应发生在用户确认之前，因此 Success 的 Planning 阶段只能要求 DATASOURCE_AGENT 与
        DATA_SYNC_AGENT 成功完成。PRECHECK_AGENT 和 MONITOR_AGENT 需要真实 taskId/executionId，不能在
        尚未创建任务时被当作失败。它们只在显式确认后的 durable facts 阶段成为 Success 的强制门禁。

        Recovery 场景始终验证 RECOVERY_AGENT、MONITOR_AGENT 的受治理参与；KNOWLEDGE_AGENT 只有在
        RECOVERY_AGENT 明确选择 SEARCH 时才成为必需角色。-RequireAllSixRolesExecuted 仅用于人工构造的
        全角色诊断场景，默认关闭，避免为了测试而机械触发 RAG 或高风险恢复分析。
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Response,

        # Planning 用于首次响应；PostConfirmation 只应由已确认提交后的事实验收使用。
        [ValidateSet('Planning', 'PostConfirmation')]
        [string]$Stage = 'Planning'
    )

    if ($SkipRoleAssertion) {
        Add-Check -Name '六角色断言' -Status 'WARN' -Detail '已通过 -SkipRoleAssertion 跳过。'
        return (Get-RoleEvidence -Response $Response)
    }
    $evidence = Get-RoleEvidence -Response $Response
    $requiredRegistered = @(Get-RequiredRolesForStage -Stage $Stage)
    $missingRegistered = @($requiredRegistered | Where-Object { $evidence.Registered -notcontains $_ })
    if ($missingRegistered.Count -gt 0) {
        Stop-E2E -Name '六角色注册' -Detail "响应中缺少角色注册证据：$($missingRegistered -join '、')。建议检查 agent-runtime 的 specialist roster、Python Runtime 的角色开关和本次项目授权。"
    }
    $requiredExecuted = @(Get-RequiredRolesForStage -Stage $Stage)
    $recoveryWaitingAllowed = $Scenario -eq 'Recovery' -and (Test-RecoveryGovernedWaiting -Response $Response)
    $missingExecuted = @(
        $requiredExecuted | Where-Object {
            $evidence.Executed -notcontains $_ -and
            -not ($recoveryWaitingAllowed -and $_ -eq 'RECOVERY_AGENT')
        }
    )
    if ($missingExecuted.Count -gt 0) {
        $nonCompletedText = if ($evidence.NonCompleted.Count -gt 0) { $evidence.NonCompleted -join '、' } else { '无结果' }
        $dataSyncGovernanceNote = ''
        if ($missingExecuted -contains 'DATA_SYNC_AGENT') {
            $dataSyncResult = Get-SpecialistResultByRole -Response $Response -Role 'DATA_SYNC_AGENT'
            $dataSyncError = Get-SafeStatusToken -Text (
                Get-FieldValue -Object $dataSyncResult -Names @('errorCode', 'error_code')
            ) -Fallback 'NONE'
            if ($dataSyncError -eq 'DATA_SYNC_SPECIALIST_SIDE_EFFECT_REJECTED') {
                $dataSyncOutput = Get-FieldValue -Object $dataSyncResult -Names @('structuredOutput')
                # 失败详情只能显示代码内固定的控制键名，不能把模型值、JSON 路径、工具参数或配置正文
                # 拼进终端。即使未来 Python 返回了额外字段，这个白名单也会让它们在 E2E 边界被丢弃。
                $safeControlFieldNames = @(
                    'action', 'actions', 'execute', 'executed', 'execution', 'executionid',
                    'publish', 'published', 'publishresult', 'persist', 'persisted', 'run',
                    'runresult', 'save', 'saved', 'sideeffect', 'sideeffects', 'taskid',
                    'toolcall', 'toolcalls', 'depthlimit'
                )
                $activeFields = @(
                    Get-Items (
                        Get-FieldValue -Object $dataSyncOutput -Names @(
                            'activeConfigurationControlFields',
                            'active_configuration_control_fields'
                        )
                    ) | ForEach-Object {
                        ([string]$_).Trim().ToLowerInvariant()
                    } | Where-Object {
                        $_ -in $safeControlFieldNames
                    } | Select-Object -Unique
                )
                $fieldCount = [int](Get-FieldValue -Object $dataSyncOutput -Names @(
                        'quarantinedConfigurationFieldCount',
                        'quarantined_configuration_field_count'
                    ))
                $activeFieldText = if ($activeFields.Count -gt 0) {
                    $activeFields -join ','
                } else {
                    '未投影'
                }
                $dataSyncGovernanceNote = "；DATA_SYNC 安全门命中控制键=[$activeFieldText]，控制字段数=$fieldCount"
            }
        }
        Stop-E2E -Name '专业 Agent 参与' -Detail "当前 $Stage 阶段缺少成功完成结果：$($missingExecuted -join '、')；未完成状态=$nonCompletedText$dataSyncGovernanceNote。PARTIALLY_FAILED 不属于成功完成。成功场景中的恢复 Agent 只有在失败上下文中才应执行；若本次是恢复验收，请提供真实 TaskId/ExecutionId 和失败信息。"
    }
    $waitingNote = if ($recoveryWaitingAllowed) {
        'RECOVERY_AGENT 已完成只读阶段并进入受治理等待态，后续 grounded/bridge 门禁仍必须通过。'
    } else {
        '所有必需角色均以完成状态回传。'
    }
    Add-Check -Name '场景角色参与' -Status 'PASS' -Detail "当前 $Stage 阶段角色参与符合场景边界；实际成功角色=$($evidence.Executed -join '、')；$waitingNote"
    return $evidence
}

function Add-SpecialistSchedulingDiagnostics {
    <#
    .SYNOPSIS
        记录六 Agent 黑盒响应中的低敏调度状态，帮助定位“角色已注册但没有执行”的原因。

    .DESCRIPTION
        本函数刻意只读取 runner/status/role/count/reason 等稳定控制字段，不读取用户目标、模型回复、
        ToolPlan 参数、SQL、数据源连接信息或 RAG 正文。诊断会同时展示执行会话工作项、turn attempt、
        Specialist 批次状态和 skippedRoles，使一次真实 E2E 失败能够区分“没有候选 turn”“候选状态不可执行”
        “缺少工作项”“缺少工具白名单”或“checkpoint 未落盘”，而不需要打印整份响应破坏低敏边界。

        初始批次状态只描述首轮尝试，不能单独决定最终 WARN。函数会使用同角色后置复核结果计算 finalBatch：
        初始 FAILED/PARTIALLY_FAILED 而后置 COMPLETED 时以 PASS 展示“已恢复”；后置仍未完成，或失败批次
        没有任何角色级结果时，才保留 WARN。计划工具只展示稳定 tool code 和准备度决策；参数、原因正文和
        业务资源值一律不读取。
    #>
    param([Parameter(Mandatory = $true)][object]$Response)

    $session = Get-FieldValue -Object $Response -Names @('agentExecutionSession')
    $workItemStates = @(
        foreach ($item in (Get-Items (Get-FieldValue -Object $session -Names @('workItems')))) {
            $role = Get-SafeStatusToken -Text (Get-FieldValue -Object $item -Names @('agentRole', 'role')) -Fallback 'UNKNOWN_ROLE'
            $status = Get-SafeStatusToken -Text (Get-FieldValue -Object $item -Names @('sessionStatus', 'status')) -Fallback 'UNKNOWN'
            "$role=$status"
        }
    )

    $runner = Get-FieldValue -Object $Response -Names @('agentTurnRunner')
    $runnerStatus = Get-SafeStatusToken -Text (Get-FieldValue -Object $runner -Names @('runnerStatus', 'runStatus')) -Fallback 'UNKNOWN'
    $attemptStates = @(
        foreach ($attempt in (Get-Items (Get-FieldValue -Object $runner -Names @('turnAttempts')))) {
            $role = Get-SafeStatusToken -Text (Get-FieldValue -Object $attempt -Names @('agentRole', 'role')) -Fallback 'UNKNOWN_ROLE'
            $status = Get-SafeStatusToken -Text (Get-FieldValue -Object $attempt -Names @('turnStatus', 'status')) -Fallback 'UNKNOWN'
            "$role=$status"
        }
    )

    $specialist = Get-FieldValue -Object $Response -Names @('specialistAgentExecution')
    $executedCount = [int](Get-FieldValue -Object $specialist -Names @('executedCount'))
    # 角色断言共用同一份聚合结果，避免已恢复角色在一个检查中为 PASS、在另一个检查中却为 WARN。
    $roleEvidence = Get-RoleEvidence -Response $Response
    $skippedStates = @()
    $skipped = Get-FieldValue -Object $specialist -Names @('skippedRoles')
    if ($null -ne $skipped) {
        foreach ($property in @($skipped.PSObject.Properties)) {
            $role = Get-SafeStatusToken -Text $property.Name -Fallback 'UNKNOWN_ROLE'
            $reason = Get-SafeStatusToken -Text $property.Value -Fallback 'UNKNOWN_REASON'
            $skippedStates += "$role=$reason"
        }
    }

    $checkpointState = if ($null -ne (Get-FieldValue -Object $Response -Names @('agentTurnRunnerCheckpoint'))) {
        'RECORDED'
    } else {
        'MISSING'
    }
    $gatewayGovernance = Get-FieldValue -Object $Response -Names @('intelligentGatewayGovernance')
    $plannedToolNames = @(
        foreach ($toolName in (Get-Items (Get-FieldValue -Object $gatewayGovernance -Names @('plannedToolNames')))) {
            Get-SafeStatusToken -Text $toolName -Fallback 'UNKNOWN_TOOL'
        }
    )
    $readiness = Get-FieldValue -Object $Response -Names @('toolExecutionReadiness')
    $readinessStates = @(
        foreach ($item in (Get-Items (Get-FieldValue -Object $readiness -Names @('items')))) {
            $toolName = Get-SafeStatusToken -Text (Get-FieldValue -Object $item -Names @('toolName', 'toolCode')) -Fallback 'UNKNOWN_TOOL'
            $decision = Get-SafeStatusToken -Text (Get-FieldValue -Object $item -Names @('decision', 'status')) -Fallback 'UNKNOWN'
            "$toolName=$decision"
        }
    )
    $workItemText = if ($workItemStates.Count -gt 0) { $workItemStates -join '，' } else { '无' }
    $attemptText = if ($attemptStates.Count -gt 0) { $attemptStates -join '，' } else { '无' }
    $resultText = if ($roleEvidence.FinalStates.Count -gt 0) { $roleEvidence.FinalStates -join '，' } else { '无' }
    $recoveredText = if ($roleEvidence.Recovered.Count -gt 0) { $roleEvidence.Recovered -join '，' } else { '无' }
    $unresolvedText = if ($roleEvidence.NonCompleted.Count -gt 0) { $roleEvidence.NonCompleted -join '，' } else { '无' }
    $skippedText = if ($skippedStates.Count -gt 0) { $skippedStates -join '，' } else { '无' }
    $plannedToolText = if ($plannedToolNames.Count -gt 0) { $plannedToolNames -join '，' } else { '无' }
    $readinessText = if ($readinessStates.Count -gt 0) { $readinessStates -join '，' } else { '无' }
    $evidenceText = if ($roleEvidence.FailedBatchWithoutRoleResult) { '缺少失败批次的角色级结果' } else { '完整' }
    $diagnosticStatus = if ($roleEvidence.RequiresWarning) { 'WARN' } else { 'PASS' }
    Add-Check -Name 'Specialist 调度诊断' -Status $diagnosticStatus -Detail "runner=$runnerStatus；checkpoint=$checkpointState；initialBatch=$($roleEvidence.InitialBatchStatus)；verificationBatch=$($roleEvidence.VerificationBatchStatus)；finalBatch=$($roleEvidence.FinalBatchStatus)；initialExecuted=$executedCount；plannedTools=[$plannedToolText]；readiness=[$readinessText]；workItems=[$workItemText]；attempts=[$attemptText]；finalResults=[$resultText]；recovered=[$recoveredText]；unresolved=[$unresolvedText]；evidence=$evidenceText；skipped=[$skippedText]。"
}

function Invoke-SpecialistStatusAggregationRegressionTest {
    <#
    .SYNOPSIS
        在不调用任何服务的前提下验证 Specialist 最终状态归并规则。

    .DESCRIPTION
        真实 Recovery E2E 依赖 Keycloak、Gateway、Python Runtime 和 Java 控制面，不能把“网络暂态已经被后置复核
        修复”稳定复现为普通单元测试。本函数因此只构造低敏 status/errorCode 夹具，覆盖三个关键边界：
        1. 首轮 FAILED、后置同角色 COMPLETED 必须变为 PASS；
        2. 首轮 COMPLETED、后置同角色 FAILED 必须以更晚失败为准并保留 WARN；
        3. 失败批次没有可归因的失败角色时不得误报 PASS；
        4. 失败批次只包含完成角色的矛盾响应也不得误报 PASS；
        5. PASS 安全摘要不会被错误拦截，真实敏感载荷仍会回退；
        6. 生命周期确认只能选择含四个同步步骤的同一 Durable Run；
        7. Recovery 可基于权威诊断证据 SKIP RAG，只有选择 SEARCH 时才要求 KNOWLEDGE_AGENT。

        夹具不包含用户目标、数据源、SQL、模型回复、工具参数或凭据。该函数仅由
        -RunSpecialistStatusAggregationRegressionTest 调用，避免把回归行为混入正常 PlanOnly 或真实 E2E 流程。
    #>
    param()

    # PASS 文案是在本地生成的安全摘要，不是服务端原文。它可以说明“不回显服务端内容”，但不能因此被低敏过滤器
    # 误认为危险信息；相反，真正带 SQL 的错误消息仍必须落到固定兜底文案。
    $successSummary = 'Agent 计划已接收，服务端内容未回显。'
    if ((Get-LowSensitiveMessage -Text $successSummary -Fallback '不应使用此兜底') -ne $successSummary) {
        throw '回归失败：安全成功摘要被低敏过滤器错误拦截。'
    }
    $unsafeFallback = '检查未通过，详细原因已收敛为低敏摘要。'
    if ((Get-LowSensitiveMessage -Text '数据库执行 select * from customer' -Fallback $unsafeFallback) -ne $unsafeFallback) {
        throw '回归失败：包含 SQL 的服务端错误没有被低敏过滤器拦截。'
    }

    # 最新的后置复核 Run 不能抢占真正的同步生命周期 Run；只有同一 turn 包含四个受控步骤才可进入确认。
    $lifecycleFixture = [pscustomobject]@{
        agentDurableModelToolLoop = [pscustomobject]@{
            turns = @(
                [pscustomobject]@{
                    sessionId = 'session-catalog'
                    runId = 'run-catalog'
                    submittedToolNames = @('datasource.catalog.read', 'datasource.metadata.read')
                },
                [pscustomobject]@{
                    sessionId = 'session-lifecycle'
                    runId = 'run-lifecycle'
                    submittedToolNames = @('sync.task.draft.save', 'sync.task.precheck', 'sync.task.publish', 'sync.task.run')
                },
                [pscustomobject]@{
                    sessionId = 'session-verification'
                    runId = 'run-verification'
                    submittedToolNames = @('sync.task.execution.read', 'sync.task.precheck')
                }
            )
        }
    }
    $lifecycleReference = Get-LifecycleRunReference -Response $lifecycleFixture
    if ($null -eq $lifecycleReference -or
        $lifecycleReference.SessionId -ne 'session-lifecycle' -or
        $lifecycleReference.RunId -ne 'run-lifecycle' -or
        $lifecycleReference.Source -ne 'DURABLE_LIFECYCLE_TOOLPLAN') {
        throw '回归失败：生命周期 Run 选择没有避开最后一个非生命周期 Durable turn。'
    }
    $noLifecycleFixture = [pscustomobject]@{
        agentDurableModelToolLoop = [pscustomobject]@{
            turns = @(
                [pscustomobject]@{
                    sessionId = 'session-incomplete'
                    runId = 'run-incomplete'
                    submittedToolNames = @('sync.task.draft.save', 'sync.task.precheck')
                }
            )
        }
        controlPlaneIngestion = [pscustomobject]@{
            sessionId = 'session-ingestion'
            runId = 'run-ingestion'
        }
    }
    if ($null -ne (Get-LifecycleRunReference -Response $noLifecycleFixture)) {
        throw '回归失败：缺少完整同步生命周期时错误回退到了不确定的 Run。'
    }

    $recoveredResponse = [pscustomobject]@{
        specialistAgentExecution = [pscustomobject]@{
            status = 'PARTIALLY_FAILED'
            results = @(
                [pscustomobject]@{ agentRole = 'KNOWLEDGE_AGENT'; status = 'COMPLETED'; errorCode = 'NONE' },
                [pscustomobject]@{ agentRole = 'RECOVERY_AGENT'; status = 'COMPLETED'; errorCode = 'NONE' },
                [pscustomobject]@{ agentRole = 'MONITOR_AGENT'; status = 'FAILED'; errorCode = 'TRANSIENT_TIMEOUT' }
            )
        }
        specialistVerificationExecution = [pscustomobject]@{
            status = 'COMPLETED'
            results = @(
                [pscustomobject]@{ agentRole = 'MONITOR_AGENT'; status = 'COMPLETED'; errorCode = 'NONE' }
            )
        }
    }
    $recoveredEvidence = Get-RoleEvidence -Response $recoveredResponse
    if ($recoveredEvidence.RequiresWarning -or
        $recoveredEvidence.FinalBatchStatus -ne 'COMPLETED' -or
        $recoveredEvidence.NonCompleted.Count -ne 0 -or
        $recoveredEvidence.Recovered -notcontains 'MONITOR_AGENT=FAILED/TRANSIENT_TIMEOUT=>COMPLETED') {
        throw '回归失败：同角色后置成功没有覆盖首轮瞬态失败，最终验收仍可能错误报告 PARTIALLY_FAILED。'
    }
    # 除纯聚合结果外还要覆盖可见诊断路径。即使 Get-RoleEvidence 本身仍正确，这里的回归也会影响用户，
    # 因为 E2E 摘要通过 Add-Check 计算警告数量。
    Add-SpecialistSchedulingDiagnostics -Response $recoveredResponse
    $recoveredDiagnostic = $script:Checks[$script:Checks.Count - 1]
    if ($recoveredDiagnostic.Status -ne 'PASS') {
        throw '回归失败：已恢复的 Specialist 结果仍在调度诊断中显示 WARN。'
    }

    $regressedResponse = [pscustomobject]@{
        specialistAgentExecution = [pscustomobject]@{
            status = 'COMPLETED'
            results = @(
                [pscustomobject]@{ agentRole = 'MONITOR_AGENT'; status = 'COMPLETED'; errorCode = 'NONE' }
            )
        }
        specialistVerificationExecution = [pscustomobject]@{
            status = 'PARTIALLY_FAILED'
            results = @(
                [pscustomobject]@{ agentRole = 'MONITOR_AGENT'; status = 'FAILED'; errorCode = 'POST_VERIFICATION_TIMEOUT' }
            )
        }
    }
    $regressedEvidence = Get-RoleEvidence -Response $regressedResponse
    if (-not $regressedEvidence.RequiresWarning -or
        $regressedEvidence.FinalBatchStatus -ne 'PARTIALLY_FAILED' -or
        $regressedEvidence.NonCompleted -notcontains 'MONITOR_AGENT=FAILED/POST_VERIFICATION_TIMEOUT') {
        throw '回归失败：后置复核的真实失败没有保留为最终告警。'
    }
    Add-SpecialistSchedulingDiagnostics -Response $regressedResponse
    $regressedDiagnostic = $script:Checks[$script:Checks.Count - 1]
    if ($regressedDiagnostic.Status -ne 'WARN') {
        throw '回归失败：后置复核的真实失败没有在调度诊断中保留 WARN。'
    }

    $incompleteResponse = [pscustomobject]@{
        specialistAgentExecution = [pscustomobject]@{
            status = 'PARTIALLY_FAILED'
            results = @()
        }
        specialistVerificationExecution = $null
    }
    $incompleteEvidence = Get-RoleEvidence -Response $incompleteResponse
    if (-not $incompleteEvidence.RequiresWarning -or
        -not $incompleteEvidence.FailedBatchWithoutRoleResult -or
        $incompleteEvidence.FinalBatchStatus -ne 'EVIDENCE_INCOMPLETE') {
        throw '回归失败：失败批次缺少角色级结果时被错误视为已恢复。'
    }
    Add-SpecialistSchedulingDiagnostics -Response $incompleteResponse
    $incompleteDiagnostic = $script:Checks[$script:Checks.Count - 1]
    if ($incompleteDiagnostic.Status -ne 'WARN') {
        throw '回归失败：失败批次缺少角色级结果时没有保留 WARN。'
    }

    $inconsistentResponse = [pscustomobject]@{
        specialistAgentExecution = [pscustomobject]@{
            status = 'PARTIALLY_FAILED'
            results = @(
                [pscustomobject]@{ agentRole = 'MONITOR_AGENT'; status = 'COMPLETED'; errorCode = 'NONE' }
            )
        }
        specialistVerificationExecution = $null
    }
    $inconsistentEvidence = Get-RoleEvidence -Response $inconsistentResponse
    if (-not $inconsistentEvidence.RequiresWarning -or
        -not $inconsistentEvidence.FailedBatchWithoutRoleResult -or
        $inconsistentEvidence.FinalBatchStatus -ne 'EVIDENCE_INCOMPLETE') {
        throw '回归失败：批次失败但没有可归因失败角色的矛盾响应被错误视为成功。'
    }
    Add-SpecialistSchedulingDiagnostics -Response $inconsistentResponse
    $inconsistentDiagnostic = $script:Checks[$script:Checks.Count - 1]
    if ($inconsistentDiagnostic.Status -ne 'WARN') {
        throw '回归失败：矛盾的失败批次响应没有在调度诊断中保留 WARN。'
    }

    $recoveryWaitingResponse = [pscustomobject]@{
        specialistAgentExecution = [pscustomobject]@{
            status = 'WAITING_FOR_INPUT'
            results = @(
                [pscustomobject]@{ agentRole = 'KNOWLEDGE_AGENT'; status = 'COMPLETED'; errorCode = 'NONE' }
                [pscustomobject]@{
                    agentRole = 'RECOVERY_AGENT'
                    status = 'WAITING_FOR_INPUT'
                    errorCode = 'NONE'
                    requiredInputFields = @('userApproval')
                    structuredOutput = [pscustomobject]@{ executed = $false; requiresApproval = $true; javaToolPlanPending = $true }
                }
                [pscustomobject]@{ agentRole = 'MONITOR_AGENT'; status = 'COMPLETED'; errorCode = 'NONE' }
            )
        }
    }
    if (-not (Test-RecoveryGovernedWaiting -Response $recoveryWaitingResponse)) {
        throw '回归失败：Recovery 等待用户审批的低敏状态没有被识别为受治理等待。'
    }
    $recoveryWaitingEvidence = Get-RoleEvidence -Response $recoveryWaitingResponse
    if ($recoveryWaitingEvidence.Executed -contains 'RECOVERY_AGENT') {
        throw '回归失败：Recovery WAITING_FOR_INPUT 被错误计入已完成执行角色。'
    }

    # 同一份低敏 evidence audit 分别覆盖模型 SKIP 和 SEARCH。夹具只使用摘要、固定引用和时间，
    # 不包含日志正文、知识片段或修复参数，因此可以安全地在本地退出码回归中运行。
    $regressionRetrievedAt = '2026-08-11T13:00:00Z'
    $regressionQueryDigest = 'sha256:' + ('1' * 64)
    $regressionEvidenceDigest = 'sha256:' + ('2' * 64)
    $evidenceAuditFixture = [pscustomobject]@{
        queryDigest = $regressionQueryDigest
        evidenceDigest = $regressionEvidenceDigest
        retrievedAt = $regressionRetrievedAt
        evidenceCount = 1
        sourceTypes = @('EXECUTION_LOG', 'STRUCTURED_API')
        evidenceRecords = @(
            [pscustomobject]@{
                evidenceId = 'diagnostic-evidence-fixture'
                sourceType = 'EXECUTION_LOG'
                sourceRef = 'execution-log://fixture'
                retrievedAt = $regressionRetrievedAt
                queryDigest = $regressionQueryDigest
            }
        )
    }
    $recoveryOutputFixture = [pscustomobject]@{
        diagnosticEvidenceGate = [pscustomobject]@{ satisfied = $true; ragRequired = $false }
        evidenceAudit = $evidenceAuditFixture
        retrievalDecision = 'SKIP'
        retrievalStrategy = 'STRUCTURED_DIAGNOSTIC'
        executed = $false
        readOnly = $true
        planAvailable = $true
        repairActions = @([pscustomobject]@{ actionType = 'RETRY_EXECUTION' })
    }
    $skipRetrievalResponse = [pscustomobject]@{
        specialistAgentExecution = [pscustomobject]@{
            results = @(
                [pscustomobject]@{
                    agentRole = 'RECOVERY_AGENT'
                    status = 'COMPLETED'
                    structuredOutput = $recoveryOutputFixture
                },
                [pscustomobject]@{ agentRole = 'MONITOR_AGENT'; status = 'COMPLETED' }
            )
        }
    }
    $originalScenario = $script:Scenario
    try {
        $script:Scenario = 'Recovery'
        Assert-RagAndRecoveryEvidence -Response $skipRetrievalResponse

        $searchOutputFixture = $recoveryOutputFixture.PSObject.Copy()
        $searchOutputFixture.retrievalDecision = 'SEARCH'
        $searchOutputFixture.retrievalStrategy = 'RAG'
        $searchRetrievalResponse = [pscustomobject]@{
            specialistAgentExecution = [pscustomobject]@{
                results = @(
                    [pscustomobject]@{
                        agentRole = 'KNOWLEDGE_AGENT'
                        status = 'COMPLETED'
                        evidenceReferences = @('rag-evidence-fixture')
                        structuredOutput = [pscustomobject]@{
                            grounded = $true
                            citations = @([pscustomobject]@{ citationId = 'citation-fixture' })
                        }
                    },
                    [pscustomobject]@{
                        agentRole = 'RECOVERY_AGENT'
                        status = 'COMPLETED'
                        structuredOutput = $searchOutputFixture
                    },
                    [pscustomobject]@{ agentRole = 'MONITOR_AGENT'; status = 'COMPLETED' }
                )
            }
        }
        Assert-RagAndRecoveryEvidence -Response $searchRetrievalResponse
    } finally {
        $script:Scenario = $originalScenario
    }

    Write-Host '[PASS] Specialist 脚本回归：状态聚合、生命周期 Run 与 Recovery 自主检索决策均符合预期。' -ForegroundColor Green
}

function Invoke-AutopilotPublicContractRegressionTest {
    <#
    .SYNOPSIS
        离线回归 Autopilot recovery 的公开扁平状态合同和自治恢复分流。

    .DESCRIPTION
        这组夹具刻意只使用 Gateway 的 SyncAutopilotRecoveryStatusView 可公开字段。它不模拟 Kafka、worker、
        数据库、RAG 正文或内部 recovery case，因此可以在没有容器、凭据和网络的 CI 中稳定执行。测试覆盖：
        - RECOVERED 使用扁平 SEARCH 证据、executionFinishedAt 和 currentExecutionId；
        - ATTENTION_REQUIRED 是有界停止，不把它包装成 worker 成功；
        - callback 先于 case 落库时，仍以首次授权快照约束循环上限；
        - 只有 FAILED/PARTIALLY_SUCCEEDED 才进入自治恢复分支。

        负例调用真实断言后会撤销其测试期 FAIL 记录。这样既能确认错误合同确实被拒绝，又不会把一个预期的
        夹具拒绝伪装成此离线回归本身失败。
    #>
    param()

    # 最终验收查询允许吸收短暂的只读依赖抖动，但不能把普通 403 或任何 POST 写请求变成透明重放。
    if (-not (Test-TransientGatewayReadFailure -Method 'GET' -StatusCode 503 -ResponseBody '') -or
        -not (Test-TransientGatewayReadFailure -Method 'GET' -StatusCode 403 -ResponseBody '{"message":"权限中心暂时不可用，网关已拒绝本次访问"}') -or
        (Test-TransientGatewayReadFailure -Method 'GET' -StatusCode 403 -ResponseBody '{"message":"当前用户没有项目访问权限"}') -or
        (Test-TransientGatewayReadFailure -Method 'POST' -StatusCode 503 -ResponseBody '')) {
        throw 'Autopilot 公开合同回归失败：Gateway 只读瞬态重试边界不符合预期。'
    }

    function Assert-ExpectedAutopilotEvidenceRejection {
        param(
            [Parameter(Mandatory = $true)][string]$Name,
            [Parameter(Mandatory = $true)][object]$Snapshot
        )

        $checkCount = $script:Checks.Count
        $failureCount = $script:FailureCount
        $rejected = $false
        try {
            # Stop-E2E 会把夹具预期的失败写入 Information 流。这里只抑制该预期本地信号，
            # 让独立回归的输出仍然只有明确的 PASS/FAIL 结果。
            $null = Assert-AutopilotModelEvidence -Snapshot $Snapshot 6>$null
        } catch {
            if (-not (Test-SafeE2EException -Exception $_.Exception)) {
                throw
            }
            $rejected = $true
        } finally {
            while ($script:Checks.Count -gt $checkCount) {
                $script:Checks.RemoveAt($script:Checks.Count - 1)
            }
            $script:FailureCount = $failureCount
        }
        if (-not $rejected) {
            throw "Autopilot 公开合同回归失败：$Name 没有被真实断言拒绝。"
        }
    }

    # 授权快照是 Recovery GET 可以合法比对的唯一外部事实。它不含 Secret 或恢复载荷，
    # 用于证明只有 callback 的有界停止不能扩大循环次数。
    $authorizationSnapshot = [pscustomobject]@{
        MaxRecoveryCycles = 3
        MaxTotalDurationMinutes = 120
    }

    # 夹具不包含旧的嵌套 evidence 对象；合法 SEARCH 响应完全由四个扁平字段描述。
    # 摘要有意使用非十六进制后缀：公开合同只要求 sha256: 前缀，不应暴露内部摘要序列化长度。
    $recoveredSearchSnapshot = [pscustomobject]@{
        caseId = 'autopilot-recovered-fixture'
        caseState = 'RECOVERED'
        cycle = 1
        maxCycles = 3
        riskLevel = 'LOW'
        retrievalDecision = 'SEARCH'
        retrievalStrategy = 'HISTORY_CASE_RAG'
        retrievalEvidenceCount = 2
        retrievalEvidenceDigest = 'sha256:flat-public-contract-digest'
        consumerResultStatus = 'RECOVERY_STARTED'
        consumerResultReasonCode = 'AUTOPILOT_FAILED_OBJECTS_REQUEUED'
        recoveryAction = 'RETRY_EXECUTION'
        executionState = 'SUCCEEDED'
        executionFinishedAt = '2026-08-12T01:02:03Z'
        currentExecutionId = 902
    }
    $recoveredSummary = Assert-AutopilotRecoverySnapshot `
        -Snapshot $recoveredSearchSnapshot `
        -AuthorizationSnapshot $authorizationSnapshot
    $currentExecutionId = Get-AutopilotCurrentExecutionId -Snapshot $recoveredSearchSnapshot
    if ($recoveredSummary.State -ne 'RECOVERED' -or -not $recoveredSummary.WorkerTerminal -or
        -not $recoveredSummary.RetryQueued -or $recoveredSummary.RetrievalDecision -ne 'SEARCH' -or
        $currentExecutionId -ne 902) {
        throw 'Autopilot 公开合同回归失败：RECOVERED 没有保留 SEARCH、worker 终态或 currentExecutionId 证据。'
    }

    # 已持久化的 ATTENTION_REQUIRED case 带有 SKIP 证据，但有意不包含成功 worker 终态。
    # 断言必须接受该有界停止并返回 WorkerTerminal=false，不能要求虚构成功事实。
    $attentionSkipSnapshot = [pscustomobject]@{
        caseId = 'autopilot-attention-fixture'
        caseState = 'ATTENTION_REQUIRED'
        cycle = 2
        maxCycles = 3
        riskLevel = 'LOW'
        retrievalDecision = 'SKIP'
        retrievalStrategy = 'STRUCTURED_DIAGNOSTIC'
        retrievalEvidenceCount = 0
        retrievalEvidenceDigest = ''
        executionState = 'FAILED'
    }
    $attentionSummary = Assert-AutopilotRecoverySnapshot `
        -Snapshot $attentionSkipSnapshot `
        -AuthorizationSnapshot $authorizationSnapshot
    if ($attentionSummary.State -ne 'ATTENTION_REQUIRED' -or -not $attentionSummary.IsTerminal -or
        $attentionSummary.WorkerTerminal -or $attentionSummary.RetryQueued -or
        $attentionSummary.RetrievalDecision -ne 'SKIP') {
        throw 'Autopilot 公开合同回归失败：ATTENTION_REQUIRED 被错误地当成 worker 成功或未保留 SKIP 合同。'
    }

    # consumer 可以在 recovery case 创建前持久化有界停止。这里有意省略 maxCycles，
    # 用于证明该分支复用已验证的首次确认快照，而不是强求 WorkerTerminal。
    $callbackAttentionSnapshot = [pscustomobject]@{
        consumerResultStatus = 'ATTENTION_REQUIRED'
        consumerResultReasonCode = 'AUTOPILOT_CYCLE_LIMIT_REACHED'
        cycle = 3
        retrievalDecision = 'SKIP'
        retrievalStrategy = 'STRUCTURED_DIAGNOSTIC'
        retrievalEvidenceCount = 0
        retrievalEvidenceDigest = ''
        executionState = 'FAILED'
    }
    $callbackAttentionSummary = Assert-AutopilotRecoverySnapshot `
        -Snapshot $callbackAttentionSnapshot `
        -AuthorizationSnapshot $authorizationSnapshot
    if ($callbackAttentionSummary.HasCase -or $callbackAttentionSummary.State -ne 'ATTENTION_REQUIRED' -or
        $callbackAttentionSummary.Cycle -ne 3 -or $callbackAttentionSummary.MaxCycles -ne 3 -or
        $callbackAttentionSummary.WorkerTerminal -or $callbackAttentionSummary.RetrievalDecision -ne 'SKIP') {
        throw 'Autopilot 公开合同回归失败：无 case 的 ATTENTION_REQUIRED 没有按首次授权边界停止。'
    }

    # 正向夹具证明可接受的公开形状；负向夹具防止未来放宽 SEARCH/SKIP 的计数与摘要不变量，
    # 同时不让预期的 [FAIL] 噪声混入独立回归输出。
    $invalidSearchSnapshot = $recoveredSearchSnapshot.PSObject.Copy()
    $invalidSearchSnapshot.retrievalEvidenceCount = 0
    $invalidSearchSnapshot.retrievalEvidenceDigest = ''
    Assert-ExpectedAutopilotEvidenceRejection -Name 'SEARCH without evidence' -Snapshot $invalidSearchSnapshot

    $invalidSkipSnapshot = $attentionSkipSnapshot.PSObject.Copy()
    $invalidSkipSnapshot.retrievalEvidenceDigest = 'sha256:not-empty-for-skip'
    Assert-ExpectedAutopilotEvidenceRejection -Name 'SKIP with evidence digest' -Snapshot $invalidSkipSnapshot

    $initialStates = @(
        [pscustomobject]@{ State = 'SUCCEEDED'; Eligible = $false },
        [pscustomobject]@{ State = 'FAILED'; Eligible = $true },
        [pscustomobject]@{ State = 'PARTIALLY_SUCCEEDED'; Eligible = $true },
        [pscustomobject]@{ State = 'CANCELLED'; Eligible = $false }
    )
    foreach ($initialState in $initialStates) {
        if ((Test-AutopilotRecoveryEligibleInitialState -State $initialState.State) -ne $initialState.Eligible) {
            throw "Autopilot 公开合同回归失败：首次 execution 状态=$($initialState.State) 的恢复分流不符合合同。"
        }
    }

    # 使用本地探针复用生产流程。探针只替代公开 API 调用，其轨迹用于证明顺序和目标 execution ID，
    # 不会伪造 worker 成功，也不会访问任何服务。
    $initialSuccessTrace = [System.Collections.Generic.List[string]]::new()
    $initialSuccessFlow = Invoke-AutopilotSuccessRecoveryFlow `
        -TaskId 701 `
        -ExecutionId 901 `
        -AuthorizationSnapshot $authorizationSnapshot `
        -WaitTerminal {
            param([long]$candidateExecutionId, [string]$operation)
            $initialSuccessTrace.Add("wait:$candidateExecutionId") | Out-Null
            return [pscustomobject]@{
                State = 'SUCCEEDED'
                Execution = [pscustomobject]@{ executionState = 'SUCCEEDED' }
            }
        } `
        -AssertSucceeded {
            param([long]$candidateExecutionId, [object]$candidateExecution)
            $initialSuccessTrace.Add("assert:$candidateExecutionId") | Out-Null
            return [pscustomobject]@{ ExecutionId = $candidateExecutionId; State = 'SUCCEEDED' }
        } `
        -WaitRecovery {
            param([object]$authorization)
            throw 'Autopilot 公开合同回归失败：首次成功错误地进入 recovery 轮询。'
        }
    if ($initialSuccessFlow.Outcome -ne 'INITIAL_SUCCEEDED' -or
        (@($initialSuccessTrace) -join ',') -ne 'wait:901,assert:901') {
        throw 'Autopilot 公开合同回归失败：首次成功没有走普通 execution 验收路径。'
    }

    $recoveredTrace = [System.Collections.Generic.List[string]]::new()
    $recoveredFlow = Invoke-AutopilotSuccessRecoveryFlow `
        -TaskId 701 `
        -ExecutionId 901 `
        -AuthorizationSnapshot $authorizationSnapshot `
        -WaitTerminal {
            param([long]$candidateExecutionId, [string]$operation)
            $recoveredTrace.Add("wait:$candidateExecutionId") | Out-Null
            $state = if ($candidateExecutionId -eq 901) { 'FAILED' } else { 'SUCCEEDED' }
            return [pscustomobject]@{
                State = $state
                Execution = [pscustomobject]@{ executionState = $state }
            }
        } `
        -AssertSucceeded {
            param([long]$candidateExecutionId, [object]$candidateExecution)
            $recoveredTrace.Add("assert:$candidateExecutionId") | Out-Null
            return [pscustomobject]@{ ExecutionId = $candidateExecutionId; State = 'SUCCEEDED' }
        } `
        -WaitRecovery {
            param([object]$authorization)
            $recoveredTrace.Add('recovery:901') | Out-Null
            return [pscustomobject]@{
                State = 'RECOVERED'
                CurrentExecutionId = 902
                Cycle = 1
                MaxCycles = 3
                WorkerTerminal = $true
            }
        }
    if ($recoveredFlow.Outcome -ne 'RECOVERED' -or $recoveredFlow.ExecutionSummary.ExecutionId -ne 902 -or
        (@($recoveredTrace) -join ',') -ne 'wait:901,recovery:901,wait:902,assert:902') {
        throw 'Autopilot 公开合同回归失败：RECOVERED 没有按 currentExecutionId 重新验收普通 worker 事实。'
    }

    $attentionTrace = [System.Collections.Generic.List[string]]::new()
    $attentionFlow = Invoke-AutopilotSuccessRecoveryFlow `
        -TaskId 701 `
        -ExecutionId 901 `
        -AuthorizationSnapshot $authorizationSnapshot `
        -WaitTerminal {
            param([long]$candidateExecutionId, [string]$operation)
            $attentionTrace.Add("wait:$candidateExecutionId") | Out-Null
            return [pscustomobject]@{
                State = 'PARTIALLY_SUCCEEDED'
                Execution = [pscustomobject]@{ executionState = 'PARTIALLY_SUCCEEDED' }
            }
        } `
        -AssertSucceeded {
            param([long]$candidateExecutionId, [object]$candidateExecution)
            throw 'Autopilot 公开合同回归失败：ATTENTION_REQUIRED 错误地调用了 worker 成功验收。'
        } `
        -WaitRecovery {
            param([object]$authorization)
            $attentionTrace.Add('recovery:901') | Out-Null
            return [pscustomobject]@{
                State = 'ATTENTION_REQUIRED'
                Cycle = 3
                MaxCycles = 3
                WorkerTerminal = $false
            }
        }
    if ($attentionFlow.Outcome -ne 'ATTENTION_REQUIRED' -or $null -ne $attentionFlow.ExecutionSummary -or
        (@($attentionTrace) -join ',') -ne 'wait:901,recovery:901') {
        throw 'Autopilot 公开合同回归失败：ATTENTION_REQUIRED 没有停在有界恢复结果，或被误作 worker 成功。'
    }

    $modelEvidenceSource = (Get-Command -Name Assert-AutopilotModelEvidence -CommandType Function -ErrorAction Stop).ScriptBlock.ToString()
    $legacyNestedEvidenceField = 'rag' + 'Evidence'
    if ($modelEvidenceSource -match [regex]::Escape($legacyNestedEvidenceField) -or
        $modelEvidenceSource -notmatch 'retrievalDecision' -or
        $modelEvidenceSource -notmatch 'retrievalStrategy' -or
        $modelEvidenceSource -notmatch 'retrievalEvidenceCount' -or
        $modelEvidenceSource -notmatch 'retrievalEvidenceDigest') {
        throw 'Autopilot 公开合同回归失败：模型检索断言不再只消费四个扁平公开字段。'
    }

    Write-Host '[PASS] Autopilot 公开合同回归：扁平检索、executionFinishedAt、currentExecutionId、有界停止和首次失败分流均符合预期。' -ForegroundColor Green
}

function Get-SpecialistResultByRole {
    <#
    .SYNOPSIS
        从 specialistAgentExecution 中取出指定角色的低敏结果。

    .DESCRIPTION
        该辅助函数只在结果数组中按 agentRole 精确定位，不会遍历或序列化结构化业务正文。它把“角色已注册”
        与“该角色返回了什么受控状态”分开，供 Recovery 的 RAG 证据和审批门禁断言使用。
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Response,
        [Parameter(Mandatory = $true)][string]$Role
    )

    $specialist = Get-FieldValue -Object $Response -Names @('specialistAgentExecution')
    foreach ($result in (Get-Items (Get-FieldValue -Object $specialist -Names @('results')))) {
        $roleValue = [string](Get-FieldValue -Object $result -Names @('agentRole', 'role'))
        if ($roleValue -eq $Role) {
            return $result
        }
    }
    return $null
}

function Test-TrueFlag {
    <#
    .SYNOPSIS
        兼容 JSON 布尔值和旧版本客户端可能产生的布尔字符串。

    .DESCRIPTION
        PowerShell 把非空字符串（包括字符串“false”）转换为 true，直接使用 [bool] 转换会造成严重的审批
        误判。本函数只把原生 true、1、yes 或 true 文本认定为真，其他值全部按 false 处理。
    #>
    param([AllowNull()][object]$Value)

    if ($Value -is [bool]) {
        return [bool]$Value
    }
    return ([string]$Value).Trim() -match '^(?i:true|yes|1)$'
}

function Assert-RagAndRecoveryEvidence {
    <#
    .SYNOPSIS
        验证 Recovery 的诊断证据门、模型检索决策和受治理动作边界。

    .DESCRIPTION
        Recovery 并不机械执行 RAG。RECOVERY_AGENT 必须先提供可复算的诊断 evidence gate，其中包含来源类型、
        查询摘要、证据摘要、时间和 evidence ID。模型在这些事实足够时可以返回 SKIP，直接使用执行日志、
        结构化 API 或监控事实；只有返回 SEARCH 时，本函数才要求 KNOWLEDGE_AGENT 完成 grounded 检索。

        无论是否检索，Python 都只能生成只读草案或 Java ToolPlan 建议，不能执行恢复副作用。函数只检查低敏
        元数据和计数，不输出 evidence ID、sourceRef、引用标题/正文、修复参数、SQL 或模型原文。
    #>
    param([Parameter(Mandatory = $true)][object]$Response)

    $knowledgeResult = Get-SpecialistResultByRole -Response $Response -Role 'KNOWLEDGE_AGENT'
    if ($Scenario -ne 'Recovery') {
        if ($null -eq $knowledgeResult) {
            Add-Check -Name 'RAG 专业结果' -Status 'PASS' -Detail 'Success 场景未提出知识检索需求，RAG 按需待命。'
            return
        }
        $knowledgeStatus = Get-SafeStatusToken -Text (Get-FieldValue -Object $knowledgeResult -Names @('status')) -Fallback 'UNKNOWN'
        if ($knowledgeStatus -ne 'COMPLETED') {
            Stop-E2E -Name 'RAG 专业结果' -Detail "KNOWLEDGE_AGENT 状态=$knowledgeStatus，知识专业 turn 没有完成。"
        }
        $knowledgeOutput = Get-FieldValue -Object $knowledgeResult -Names @('structuredOutput')
        $grounded = Test-TrueFlag (Get-FieldValue -Object $knowledgeOutput -Names @('grounded'))
        $citations = @(Get-Items (Get-FieldValue -Object $knowledgeOutput -Names @('citations')))
        if ($grounded -and $citations.Count -gt 0) {
            Add-Check -Name 'RAG 专业结果' -Status 'PASS' -Detail "KNOWLEDGE_AGENT 已完成按需检索（引用数=$($citations.Count)）。"
        } else {
            Add-Check -Name 'RAG 专业结果' -Status 'WARN' -Detail 'KNOWLEDGE_AGENT 已执行，但本次 Success 需求没有 grounded 引用；不阻断清晰同步任务。'
        }
        return
    }

    $recoveryResult = Get-SpecialistResultByRole -Response $Response -Role 'RECOVERY_AGENT'
    if ($null -eq $recoveryResult) {
        Stop-E2E -Name '恢复诊断证据' -Detail '没有 RECOVERY_AGENT 结构化结果，无法验证诊断证据门和检索决策。'
    }
    $recoveryOutput = Get-FieldValue -Object $recoveryResult -Names @('structuredOutput')
    $evidenceGate = Get-FieldValue -Object $recoveryOutput -Names @('diagnosticEvidenceGate', 'diagnostic_evidence_gate')
    $evidenceAudit = Get-FieldValue -Object $recoveryOutput -Names @('evidenceAudit', 'evidence_audit')
    if ($null -eq $evidenceGate -or -not (Test-TrueFlag (Get-FieldValue -Object $evidenceGate -Names @('satisfied')))) {
        Stop-E2E -Name '恢复诊断证据' -Detail 'RECOVERY_AGENT 没有通过可审计诊断 evidence gate，不能进入模型修复决策。'
    }
    if (Test-TrueFlag (Get-FieldValue -Object $evidenceGate -Names @('ragRequired', 'rag_required'))) {
        Stop-E2E -Name '恢复诊断证据' -Detail '诊断 evidence gate 错误地把 RAG 设为强制条件；检索应由模型根据现有证据自主决定。'
    }
    if ($null -eq $evidenceAudit) {
        Stop-E2E -Name '恢复诊断证据' -Detail 'RECOVERY_AGENT 缺少 evidenceAudit，Java 无法复算来源、时间和证据摘要。'
    }

    $queryDigest = [string](Get-FieldValue -Object $evidenceAudit -Names @('queryDigest', 'query_digest'))
    $evidenceDigest = [string](Get-FieldValue -Object $evidenceAudit -Names @('evidenceDigest', 'evidence_digest'))
    $retrievedAt = [string](Get-FieldValue -Object $evidenceAudit -Names @('retrievedAt', 'retrieved_at'))
    $evidenceCount = [int](Get-FieldValue -Object $evidenceAudit -Names @('evidenceCount', 'evidence_count'))
    $sourceTypes = @(Get-Items (Get-FieldValue -Object $evidenceAudit -Names @('sourceTypes', 'source_types'))) | ForEach-Object {
        ([string]$_).Trim().ToUpperInvariant()
    }
    $evidenceRecords = @(Get-Items (Get-FieldValue -Object $evidenceAudit -Names @('evidenceRecords', 'evidence_records')))
    if ($queryDigest -notmatch '^sha256:[0-9a-fA-F]{64}$' -or $evidenceDigest -notmatch '^sha256:[0-9a-fA-F]{64}$') {
        Stop-E2E -Name '恢复诊断证据' -Detail 'evidenceAudit 的 queryDigest/evidenceDigest 不是可复算 SHA-256 合同。'
    }
    $parsedRetrievedAt = [DateTimeOffset]::MinValue
    if ([string]::IsNullOrWhiteSpace($retrievedAt) -or
        -not [DateTimeOffset]::TryParse($retrievedAt, [ref]$parsedRetrievedAt)) {
        Stop-E2E -Name '恢复诊断证据' -Detail 'evidenceAudit 缺少带时区的合法 retrievedAt。'
    }
    if ($evidenceCount -le 0 -or $evidenceRecords.Count -ne $evidenceCount -or $sourceTypes.Count -le 0) {
        Stop-E2E -Name '恢复诊断证据' -Detail 'evidenceAudit 的证据计数、记录或来源类型不完整。'
    }
    foreach ($record in $evidenceRecords) {
        $evidenceId = [string](Get-FieldValue -Object $record -Names @('evidenceId', 'evidence_id'))
        $sourceType = [string](Get-FieldValue -Object $record -Names @('sourceType', 'source_type'))
        $sourceRef = [string](Get-FieldValue -Object $record -Names @('sourceRef', 'source_ref'))
        $recordQueryDigest = [string](Get-FieldValue -Object $record -Names @('queryDigest', 'query_digest'))
        $recordRetrievedAt = [string](Get-FieldValue -Object $record -Names @('retrievedAt', 'retrieved_at'))
        $parsedRecordTime = [DateTimeOffset]::MinValue
        if ([string]::IsNullOrWhiteSpace($evidenceId) -or [string]::IsNullOrWhiteSpace($sourceType) -or
            [string]::IsNullOrWhiteSpace($sourceRef) -or $recordQueryDigest -ne $queryDigest -or
            -not [DateTimeOffset]::TryParse($recordRetrievedAt, [ref]$parsedRecordTime)) {
            Stop-E2E -Name '恢复诊断证据' -Detail '至少一条 evidenceRecord 缺少 ID、来源、时间、引用或一致的 queryDigest。'
        }
    }
    $authoritativeSources = @($sourceTypes | Where-Object { $_ -in @('EXECUTION_LOG', 'STRUCTURED_API', 'MONITORING_API') })
    if ($authoritativeSources.Count -le 0) {
        Stop-E2E -Name '恢复诊断证据' -Detail '诊断证据没有执行日志、结构化 API 或监控 API 等权威来源。'
    }
    Add-Check -Name '恢复诊断证据' -Status 'PASS' -Detail "诊断 evidence gate 已通过（证据数=$evidenceCount，来源类型数=$($sourceTypes.Count)），摘要和时间合同完整。"

    $retrievalDecision = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $recoveryOutput -Names @('retrievalDecision', 'retrieval_decision', 'ragDecision', 'rag_decision')
    ) -Fallback 'UNKNOWN'
    $retrievalStrategy = Get-SafeStatusToken -Text (
        Get-FieldValue -Object $recoveryOutput -Names @('retrievalStrategy', 'retrieval_strategy')
    ) -Fallback 'UNKNOWN'
    if ($retrievalDecision -eq 'SEARCH') {
        if ($null -eq $knowledgeResult) {
            Stop-E2E -Name '模型检索决策' -Detail 'RECOVERY_AGENT 已选择 SEARCH，但响应中没有 KNOWLEDGE_AGENT 结果。'
        }
        $knowledgeStatus = Get-SafeStatusToken -Text (Get-FieldValue -Object $knowledgeResult -Names @('status')) -Fallback 'UNKNOWN'
        $knowledgeOutput = Get-FieldValue -Object $knowledgeResult -Names @('structuredOutput')
        $grounded = Test-TrueFlag (Get-FieldValue -Object $knowledgeOutput -Names @('grounded'))
        $citations = @(Get-Items (Get-FieldValue -Object $knowledgeOutput -Names @('citations')))
        $evidenceReferences = @(Get-Items (Get-FieldValue -Object $knowledgeResult -Names @('evidenceReferences')))
        if ($knowledgeStatus -ne 'COMPLETED' -or -not $grounded -or
            $citations.Count -le 0 -or $evidenceReferences.Count -le 0) {
            Stop-E2E -Name '模型检索决策' -Detail '模型选择 SEARCH 后，KNOWLEDGE_AGENT 没有返回完整 grounded 引用证据。'
        }
        Add-Check -Name '模型检索决策' -Status 'PASS' -Detail "模型选择 $retrievalStrategy 检索并获得 grounded 证据（引用数=$($citations.Count)）。"
    } elseif ($retrievalDecision -eq 'SKIP') {
        Add-Check -Name '模型检索决策' -Status 'PASS' -Detail "模型基于现有权威诊断证据自主跳过 RAG（策略=$retrievalStrategy），未机械调用知识检索。"
    } else {
        Stop-E2E -Name '模型检索决策' -Detail "RECOVERY_AGENT 返回未知检索决策=$retrievalDecision；只允许 SEARCH 或 SKIP。"
    }

    $requiresApproval = Test-TrueFlag (Get-FieldValue -Object $recoveryOutput -Names @('requiresApproval'))
    $javaToolPlanPending = Test-TrueFlag (Get-FieldValue -Object $recoveryOutput -Names @('javaToolPlanPending'))
    $executed = Test-TrueFlag (Get-FieldValue -Object $recoveryOutput -Names @('executed'))
    $planAvailable = Test-TrueFlag (Get-FieldValue -Object $recoveryOutput -Names @('planAvailable'))
    $readOnly = Test-TrueFlag (Get-FieldValue -Object $recoveryOutput -Names @('readOnly'))
    $repairActionCount = @(Get-Items (Get-FieldValue -Object $recoveryOutput -Names @('repairActions'))).Count
    if ($executed) {
        Stop-E2E -Name '恢复治理边界' -Detail 'Recovery specialist 声称已经在 Python 中执行恢复动作；副作用必须进入 Java 治理链。'
    }
    if ($requiresApproval) {
        if (-not $javaToolPlanPending) {
            Stop-E2E -Name '恢复治理边界' -Detail 'Recovery 提出高风险动作但没有等待 Java ToolPlan，不能建立审批事实。'
        }
        Add-Check -Name '恢复治理边界' -Status 'PASS' -Detail "Recovery 已生成 $repairActionCount 个待审批动作并停在 Java 控制面。"
    } elseif ($planAvailable -or $readOnly -or $repairActionCount -gt 0) {
        Add-Check -Name '恢复治理边界' -Status 'PASS' -Detail "Recovery 已生成 $repairActionCount 个低风险诊断/预览建议，仍由 Java bridge 校验后续动作。"
    } else {
        Stop-E2E -Name '恢复治理边界' -Detail 'Recovery 没有形成可交接动作或恢复草案。'
    }
}

function Get-BridgeItems {
    <#
    .SYNOPSIS
        读取 specialistToolPlanBridges 的低敏摘要列表。

    .DESCRIPTION
        桥接摘要只允许访问 specialistRole、status、acceptedToolPlanCount、recoveryHandoff 和 payloadPolicy。
        本函数不读取 accepted ToolPlan 的 arguments，也不打印工具参数。
    #>
    param([Parameter(Mandatory = $true)][object]$Response)
    return @(Get-Items (Get-FieldValue -Object $Response -Names @('specialistToolPlanBridges')))
}

function Get-ControlPlaneFeedbackItems {
    <#
    .SYNOPSIS
        读取当前响应最终控制面快照中的低敏工具反馈。

    .DESCRIPTION
        Recovery 可能先执行一次只读 diagnosis bootstrap，再用正式 auditId/runId 二次桥接 preview 或
        高风险修复动作。仅看到 ACCEPTED bridge 还不能证明低风险 preview 真正到达 Java worker，因而
        本函数只读取公开的 toolName、status、auditId 与 runId，用于核对最终工具回执；不会读取 result、
        SQL、连接参数、模型原文或工具 arguments。
    #>
    param([Parameter(Mandatory = $true)][object]$Response)

    $feedback = Get-FieldValue -Object $Response -Names @('controlPlaneFeedback')
    return @(Get-Items (Get-FieldValue -Object $feedback -Names @('items')))
}

function Get-BridgeIssueSummary {
    <#
    .SYNOPSIS
        把 bridge 返回的低敏 issue code/message 压缩成可直接排障的摘要。

    .DESCRIPTION
        bridge 已经把参数值、SQL 和连接信息排除在公开摘要之外。这里继续只读取 issue 的
        稳定编码和人话消息，避免 E2E 在失败时只留下一个无法行动的 REJECTED 状态。
    #>
    param([AllowNull()][object]$Bridge)

    $items = @(
        foreach ($issue in (Get-Items (Get-FieldValue -Object $Bridge -Names @('issues')))) {
            $code = Get-SafeStatusToken -Text (Get-FieldValue -Object $issue -Names @('code')) -Fallback 'UNKNOWN_ISSUE'
            $message = Get-LowSensitiveMessage -Text (Get-FieldValue -Object $issue -Names @('message')) -Fallback '桥接校验未通过，请查看 traceId 对应的服务端日志。'
            "$code：$message"
        }
    )
    if ($items.Count -eq 0) { return '未返回具体 issue；请检查 bridge 服务端审计日志。' }
    return ($items | Select-Object -First 3) -join '；'
}

function Assert-BridgeEvidence {
    <#
    .SYNOPSIS
        验证 DATA_SYNC/RECOVERY 是否经过受治理 ToolPlan bridge。

    .DESCRIPTION
        Success 要求 DATA_SYNC bridge 已接受至少一个受治理 ToolPlan；Recovery 要求 RECOVERY bridge 明确进入
        审批或 Java handoff 等待态，并且 directExecution=false、approvalFactAccepted 不得为 true。这样脚本
        能证明“模型建议进入治理链路”，同时证明高风险恢复没有被自动批准。
    #>
    param([Parameter(Mandatory = $true)][object]$Response)

    $bridges = Get-BridgeItems -Response $Response
    if ($SkipBridgeAssertion) {
        Add-Check -Name '专业桥接' -Status 'WARN' -Detail '已通过 -SkipBridgeAssertion 跳过。'
        return $bridges
    }
    if ($bridges.Count -eq 0) {
        Stop-E2E -Name '专业桥接' -Detail '响应没有 specialistToolPlanBridges；专业 Agent 结果尚未进入受治理 Java ToolPlan bridge。建议检查 specialist agent、bridge 开关和 durable runner 配置。'
    }
    $syncBridge = $bridges | Where-Object { [string](Get-FieldValue $_ @('specialistRole')) -eq 'DATA_SYNC_AGENT' } | Select-Object -First 1
    $recoveryBridges = @($bridges | Where-Object { [string](Get-FieldValue $_ @('specialistRole')) -eq 'RECOVERY_AGENT' })
    # 两阶段 Recovery 的第一条 bridge 可能只是 sync.execution.diagnose bootstrap，并不带恢复 handoff。
    # 验收必须优先查看最后一条真正带 handoff 的动作 bridge；若当前仍停在诊断证据等待态，才退回最后一条
    # Recovery bridge，以便输出其明确 issue，而不是错误读取第一条 bootstrap 并报告 UNKNOWN。
    $recoveryBridge = $recoveryBridges |
        Where-Object { $null -ne (Get-FieldValue -Object $_ -Names @('recoveryHandoff')) } |
        Select-Object -Last 1
    if ($null -eq $recoveryBridge) {
        $recoveryBridge = $recoveryBridges | Select-Object -Last 1
    }

    if ($Scenario -eq 'Success') {
        if ($null -eq $syncBridge) {
            Stop-E2E -Name 'DATA_SYNC bridge' -Detail '没有 DATA_SYNC_AGENT bridge；同步规划没有进入 Java ToolPlan 生命周期。建议检查真实数据源消歧、对象映射和目标元数据是否完整。'
        }
        $status = Get-SafeStatusToken -Text (Get-FieldValue -Object $syncBridge -Names @('status')) -Fallback 'UNKNOWN'
        $count = [int](Get-FieldValue -Object $syncBridge -Names @('acceptedToolPlanCount'))
        if ($status -ne 'ACCEPTED' -or $count -le 0) {
            Stop-E2E -Name 'DATA_SYNC bridge' -Detail "DATA_SYNC bridge 状态=$status、受理计划数=$count；原因：$(Get-BridgeIssueSummary -Bridge $syncBridge)"
        }
        Add-Check -Name 'DATA_SYNC bridge' -Status 'PASS' -Detail "DATA_SYNC bridge 已接受受治理生命周期计划，计划数量=$count。"
    } else {
        if ($null -eq $recoveryBridge) {
            Stop-E2E -Name 'RECOVERY bridge' -Detail '没有 RECOVERY_AGENT bridge；恢复方案没有进入审批/Java handoff。建议提供真实失败执行定位，并确认 RAG 证据和诊断结果已返回。'
        }
        $status = Get-SafeStatusToken -Text (Get-FieldValue -Object $recoveryBridge -Names @('status')) -Fallback 'UNKNOWN'
        $handoff = Get-FieldValue -Object $recoveryBridge -Names @('recoveryHandoff')
        $directExecution = [bool](Get-FieldValue -Object $handoff -Names @('directExecution'))
        $approvalAccepted = [bool](Get-FieldValue -Object $handoff -Names @('approvalFactAccepted'))
        $approvalStatus = Get-SafeStatusToken -Text (Get-FieldValue -Object $handoff -Names @('approvalStatus')) -Fallback 'UNKNOWN'
        $acceptedToolNames = @(
            Get-Items (Get-FieldValue -Object $recoveryBridge -Names @('acceptedToolNames')) |
                ForEach-Object { [string]$_ }
        )
        $lowRiskPreviewTools = @(
            'sync.dirty-record.quarantine.preview',
            'datasource.schema.repair.preview'
        )
        $acceptedPreviewTools = @($acceptedToolNames | Where-Object { $lowRiskPreviewTools -contains $_ } | Select-Object -Unique)
        $waitingStates = @(
            'WAITING_FOR_APPROVAL',
            'WAITING_FOR_JAVA_HANDOFF',
            'JAVA_TOOLPLAN_HANDOFF_PENDING',
            'JAVA_TOOLPLAN_APPROVAL_OUTBOX_PENDING',
            'APPROVAL_PENDING',
            'PENDING_APPROVAL',
            'PENDING',
            'WAITING'
        )
        if ($directExecution -or $approvalAccepted) {
            Stop-E2E -Name '恢复安全门禁' -Detail 'Recovery handoff 报告了直接执行或已接受审批事实；本验收脚本要求高风险恢复在用户批准前停止，请立即检查审批事实和 worker outbox。'
        }
        if ($acceptedPreviewTools.Count -gt 0) {
            if ($status -ne 'ACCEPTED') {
                Stop-E2E -Name 'RECOVERY preview bridge' -Detail "低风险恢复预览未被 bridge 接受，状态=$status；原因：$(Get-BridgeIssueSummary -Bridge $recoveryBridge)"
            }
            $feedbackItems = Get-ControlPlaneFeedbackItems -Response $Response
            foreach ($previewTool in $acceptedPreviewTools) {
                $receipt = $feedbackItems |
                    Where-Object {
                        [string](Get-FieldValue -Object $_ -Names @('toolName')) -eq $previewTool -and
                        (Get-SafeStatusToken -Text (Get-FieldValue -Object $_ -Names @('status')) -Fallback 'UNKNOWN') -eq 'SUCCEEDED'
                    } |
                    Select-Object -Last 1
                $auditId = [string](Get-FieldValue -Object $receipt -Names @('auditId'))
                $runId = [string](Get-FieldValue -Object $receipt -Names @('runId'))
                if ($null -eq $receipt -or [string]::IsNullOrWhiteSpace($auditId) -or [string]::IsNullOrWhiteSpace($runId)) {
                    Stop-E2E -Name 'RECOVERY preview 回执' -Detail "恢复预览工具 $previewTool 没有 SUCCEEDED 且绑定 auditId/runId 的 Java 回执；不能把 bridge 摘要视为已执行。"
                }
            }
            Add-Check -Name 'RECOVERY preview 回执' -Status 'PASS' -Detail "低风险恢复预览已通过 Java 控制面执行并返回正式审计回执，工具数=$($acceptedPreviewTools.Count)。"
            return $bridges
        }
        if ($waitingStates -notcontains $status -and $waitingStates -notcontains $approvalStatus) {
            $issueSummary = Get-BridgeIssueSummary -Bridge $recoveryBridge
            Stop-E2E -Name 'RECOVERY bridge' -Detail "恢复 bridge 状态=$status、审批状态=$approvalStatus，未进入预期的审批/Java handoff 等待态。原因：$issueSummary"
        }
        Add-Check -Name 'RECOVERY bridge' -Status 'PASS' -Detail "恢复方案已进入受治理等待态（bridge=$status，approval=$approvalStatus），脚本未自动批准。"
    }
    return $bridges
}

function Assert-PostBridgeVerification {
    <#
    .SYNOPSIS
        验证真实 taskId/executionId 生成后是否再次运行 PRECHECK_AGENT 和 MONITOR_AGENT。

    .DESCRIPTION
        只有 Success 场景要求新建同步任务的 postBridgeVerification 为 EXECUTED，并且 taskId/executionId 都必须
        是正整数。角色必须同时包含 PRECHECK_AGENT 和 MONITOR_AGENT；否则只能说明 Python 生成了摘要，不能说明
        Java 控制面真实资源已经被后置复核。Recovery 场景在审批前不要求产生新 execution。
    #>
    param([Parameter(Mandatory = $true)][object]$Response)

    $post = Get-FieldValue -Object $Response -Names @('postBridgeVerification')
    if ($SkipPostBridgeAssertion) {
        Add-Check -Name '后置 PRECHECK/MONITOR' -Status 'WARN' -Detail '已通过 -SkipPostBridgeAssertion 跳过。'
        return $post
    }
    if ($Scenario -eq 'Recovery') {
        if ($null -eq $post) {
            Add-Check -Name '后置 PRECHECK/MONITOR' -Status 'WARN' -Detail 'Recovery 在高风险动作审批前未产生新执行，未要求 postBridgeVerification；审批前不会自动创建重试执行。'
        } else {
            $status = [string](Get-FieldValue -Object $post -Names @('status'))
            Add-Check -Name '后置 PRECHECK/MONITOR' -Status 'PASS' -Detail "Recovery 返回后置复核摘要，状态=$status；恢复执行仍由审批门禁控制。"
        }
        return $post
    }
    if ($null -eq $post) {
        Stop-E2E -Name '后置 PRECHECK/MONITOR' -Detail '响应没有 postBridgeVerification；控制面没有用真实资源事实重新运行预检查和监控。建议检查真实 Java feedback、durable runner 和 post-bridge finalization 配置。'
    }
    $status = Get-SafeStatusToken -Text (Get-FieldValue -Object $post -Names @('status')) -Fallback 'UNKNOWN'
    $taskId = Get-FieldValue -Object $post -Names @('taskId')
    $executionId = Get-FieldValue -Object $post -Names @('executionId')
    $roles = @(Get-Items (Get-FieldValue -Object $post -Names @('executedRoles')))
    if ($status -ne 'EXECUTED' -or -not (Test-PositiveIdentifier $taskId) -or -not (Test-PositiveIdentifier $executionId)) {
        Stop-E2E -Name '真实任务/执行定位' -Detail "后置复核没有同时返回可信的正整数 taskId 和 executionId（状态=$status）。建议检查 Java 成功反馈是否包含 audit/run/outputRef 以及 data-sync 任务是否真正发布并启动。"
    }
    $missingRoles = @('PRECHECK_AGENT', 'MONITOR_AGENT') | Where-Object { $roles -notcontains $_ }
    if ($missingRoles.Count -gt 0) {
        Stop-E2E -Name '后置 PRECHECK/MONITOR' -Detail "后置复核缺少角色：$($missingRoles -join '、')。有真实 task/execution ID 但没有再次执行只读预检查或监控，说明闭环仍不完整。"
    }
    Add-Check -Name '真实任务/执行定位' -Status 'PASS' -Detail "Java 控制面返回了可信 taskId=$taskId、executionId=$executionId。"
    Add-Check -Name '后置 PRECHECK/MONITOR' -Status 'PASS' -Detail '真实资源产生后已再次执行 PRECHECK_AGENT 和 MONITOR_AGENT。'
    return $post
}

function Get-DurableFactSessionIds {
    <#
    .SYNOPSIS
        从低敏响应中获取 specialist fact 查询所需的全部 sessionId。

    .DESCRIPTION
        规划阶段专业事实使用 Python LangGraph 执行会话 ``multi-agent-session-*``，而用户确认后产生的
        PRECHECK/MONITOR 事实必须绑定真实 Java ``ags_*`` 会话。两个 ID 描述同一次用户请求的不同可信
        生命周期边界，不能互相替代；本函数按服务端响应收集并去重，绝不从 objective 或模型文本猜测。
    #>
    param([Parameter(Mandatory = $true)][object]$Response)

    $sessionIds = [System.Collections.Generic.List[string]]::new()
    $executionSession = Get-FieldValue -Object $Response -Names @('agentExecutionSession')
    $executionSessionId = [string](Get-FieldValue -Object $executionSession -Names @('sessionId'))
    if (-not [string]::IsNullOrWhiteSpace($executionSessionId)) {
        $sessionIds.Add($executionSessionId.Trim())
    }
    $ingestion = Get-FieldValue -Object $Response -Names @('controlPlaneIngestion')
    $controlPlaneSessionId = [string](Get-FieldValue -Object $ingestion -Names @('sessionId'))
    if (-not [string]::IsNullOrWhiteSpace($controlPlaneSessionId) -and
        -not $sessionIds.Contains($controlPlaneSessionId.Trim())) {
        $sessionIds.Add($controlPlaneSessionId.Trim())
    }
    return @($sessionIds)
}

function Invoke-DurableFactQuery {
    <#
    .SYNOPSIS
        通过 Gateway 查询当前项目可见的 specialist durable facts。

    .DESCRIPTION
        该查询是只读的，使用同一个 project-owner token，并让 Gateway/agent-runtime 重新做对象归属校验。
        查询结果只在内存中解析角色和计数，不把事实正文或模型交互内容打印出来。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][string]$SessionId
    )

    $escapedSessionId = [uri]::EscapeDataString($SessionId)
    $uri = "$( $GatewayBaseUrl.TrimEnd('/') )/api/agent/specialist-turn-facts/sessions/$escapedSessionId"
    $client = New-HttpClient
    $request = New-HttpRequestMessage -Method 'GET' -Uri $uri -AccessToken $AccessToken
    try {
        $response = $client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            $detail = Get-SafeHttpErrorDetail -StatusCode ([int]$response.StatusCode) -Body $body -Operation 'specialist durable facts'
            Stop-E2E -Name 'specialist durable facts' -Detail "查询 durable facts 失败：$(Format-SafeHttpDetail -Detail $detail) 建议：$($detail.Suggestions -join '；')"
        }
        try {
            return (ConvertFrom-JsonSafe -Json $body -Depth 100)
        } catch {
            Stop-E2E -Name 'specialist durable facts' -Detail 'durable facts 接口返回成功状态但不是合法 JSON；请检查 agent-runtime 版本和 Gateway 路由。'
        }
    } catch {
        if (Test-SafeE2EException -Exception $_.Exception) {
            throw
        }
        Stop-E2E -Name 'specialist durable facts' -Detail '读取 durable facts 失败；请确认 agent-runtime 已完成数据库迁移且当前 project-owner 具有只读查看权限。'
    } finally {
        $request.Dispose()
        $client.Dispose()
    }
}

function Assert-DurableFacts {
    <#
    .SYNOPSIS
        验证六专业 Agent 的 durable fact 已落库并可按会话回放。

    .DESCRIPTION
        durable fact 是“真实 specialist turn 已发生”的审计证据，不等同于模型说自己调用过工具。脚本只检查
        count、agentRole 和可选状态；不打印 fact 的 prompt、模型输出、工具参数或业务正文。

        Planning 阶段只验证 DATASOURCE_AGENT 与 DATA_SYNC_AGENT 的成功事实，因为用户尚未确认时系统不得
        创建任务，也就不能要求基于真实资源执行的 PRECHECK_AGENT/MONITOR_AGENT。PostConfirmation 阶段
        才要求四个 Success 角色全部出现 COMPLETED 或 SUCCEEDED 事实。任何 FAILED、WAITING_FOR_INPUT 或
        PARTIALLY_FAILED 都不会加入成功集合，从而不能被误判为完整闭环。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][object]$Response,

        # 调用方根据是否已经执行显式确认决定阶段，避免用响应中不可信的文本猜测任务是否已创建。
        [ValidateSet('Planning', 'PostConfirmation')]
        [string]$Stage = 'Planning'
    )

    if ($SkipDurableFactAssertion) {
        Add-Check -Name 'specialist durable facts' -Status 'WARN' -Detail '已通过 -SkipDurableFactAssertion 跳过。'
        return
    }
    $sessionIds = @(Get-DurableFactSessionIds -Response $Response)
    if ($sessionIds.Count -le 0) {
        Stop-E2E -Name 'specialist durable facts' -Detail '响应没有服务端 sessionId，无法查询专业 Agent durable facts；这通常表示执行会话或控制面接入没有建立。'
    }
    $facts = @(
        foreach ($sessionId in $sessionIds) {
            # 每个会话都通过 Gateway 查询，让 Java 重新执行租户、项目和对象授权。内存中只合并返回的
            # 低敏事实视图，不打印任何事实正文。
            $factResponse = Invoke-DurableFactQuery -AccessToken $AccessToken -SessionId $sessionId
            $data = Get-FieldValue -Object $factResponse -Names @('data', 'items', 'facts')
            if ($null -eq $data) { $data = $factResponse }
            if ($data -is [System.Collections.IDictionary] -or $data.PSObject.Properties.Count -gt 0) {
                $nested = Get-FieldValue -Object $data -Names @('facts', 'items', 'results')
                if ($null -ne $nested) { $data = $nested }
            }
            Get-Items $data
        }
    )
    if ($facts.Count -le 0) {
        Stop-E2E -Name 'specialist durable facts' -Detail 'durable facts 查询成功但没有返回任何事实；请检查 result_sink、agent-runtime 数据库迁移和请求项目范围。'
    }
    $factRoles = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $waitingRoles = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($fact in $facts) {
        $role = [string](Get-FieldValue -Object $fact -Names @('agentRole', 'role'))
        $status = Get-SafeStatusToken -Text (Get-FieldValue -Object $fact -Names @('status')) -Fallback 'UNKNOWN'
        if ($script:ExpectedRoles -contains $role -and @('COMPLETED', 'SUCCEEDED') -contains $status) {
            $factRoles.Add($role) | Out-Null
        } elseif ($Scenario -eq 'Recovery' -and $role -eq 'RECOVERY_AGENT' -and $status -eq 'WAITING_FOR_INPUT') {
            # 响应级 RAG 与 bridge 断言在本查询之前运行，用于证明 Recovery 等待原因。durable fact 只需
            # 证明受治理 turn 已记录，不能把审批或证据等待重新标记为 COMPLETED。
            $waitingRoles.Add($role) | Out-Null
        }
    }
    # 角色集通过同一函数选择，确保首次规划的响应和提交后的 durable fact 不会错误使用同一套门禁。
    $requiredRoles = @(Get-RequiredRolesForStage -Stage $Stage)
    $missing = @(
        $requiredRoles | Where-Object {
            -not $factRoles.Contains($_) -and -not $waitingRoles.Contains($_)
        }
    )
    if ($missing.Count -gt 0) {
        Stop-E2E -Name 'specialist durable facts' -Detail "durable facts 中缺少角色：$($missing -join '、')；接口虽然可访问，但本轮专业 turn 没有完整落库。建议检查 Agent Runtime 的共享 token、result_sink 和数据库连接。"
    }
    $waitingText = if ($waitingRoles.Count -gt 0) { $waitingRoles -join '、' } else { '无' }
    Add-Check -Name 'specialist durable facts' -Status 'PASS' -Detail "已按 $($sessionIds.Count) 个生命周期 session 查询到 $($facts.Count) 条低敏事实；$Stage 阶段完成角色=$($factRoles -join '、')；受治理等待角色=$waitingText。"
    return [pscustomobject]@{
        SessionIds = $sessionIds
        Count = $facts.Count
        Roles = @($factRoles | Sort-Object)
        WaitingRoles = @($waitingRoles | Sort-Object)
    }
}

function Write-FinalSummary {
    <#
    .SYNOPSIS
        输出本次验收的低敏最终摘要。

    .DESCRIPTION
        最终摘要只统计检查数量和几个稳定状态，不复制任何原始 JSON。这样即使用户把终端输出粘贴到工单，
        也不会把凭据、模型原文、SQL、字段值或连接参数带出去。
    #>
    param(
        [AllowNull()][object]$Response,
        [AllowNull()][object]$PostVerification,
        [AllowNull()][object]$DurableSummary,
        [AllowNull()][object]$ExecutionSummary
    )

    Write-Host ''
    Write-Host '六专业 Agent E2E 验收摘要' -ForegroundColor Cyan
    Write-Host "场景：$Scenario；请求 ID：$RequestId"
    if ($null -ne $PostVerification) {
        $status = Get-FieldValue -Object $PostVerification -Names @('status')
        Write-Host "后置复核状态：$status"
    }
    if ($null -ne $DurableSummary) {
        Write-Host "Durable facts：$($DurableSummary.Count) 条"
    }
    if ($null -ne $ExecutionSummary) {
        Write-Host "真实同步执行：$($ExecutionSummary.State)；对象=$($ExecutionSummary.ObjectCount)；read/write=$($ExecutionSummary.RecordsRead)/$($ExecutionSummary.RecordsWritten)"
    }
    $failures = @($script:Checks | Where-Object { $_.Status -eq 'FAIL' }).Count
    $warnings = @($script:Checks | Where-Object { $_.Status -eq 'WARN' }).Count
    Write-Host "检查统计：$($script:Checks.Count) 项，失败=$failures，警告=$warnings。"
}

function Complete-E2EProcess {
    <#
    .SYNOPSIS
        以唯一且可供 CI 识别的进程退出码结束本次 E2E 验收。

    .DESCRIPTION
        验收脚本既会在断言失败时记录 [FAIL]，也会在 HTTP、流式解析等异常时进入 catch。若这些分支各自
        直接 return，调用方只能看到终端文本而拿到 0，CI 就会把失败误判为通过。这里把所有终止路径收敛到
        同一处：先同步 LASTEXITCODE，再调用 exit，确保 PowerShell -File、-Command 以及外层脚本都能得到
        一致的非零失败码。成功路径也显式写入 0，避免复用 PowerShell 会话中遗留的原生命令状态影响结果。
    #>
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet(0, 1)]
        [int]$ExitCode
    )

    # 共享 PowerShell 会话中的调用方可以观察 LASTEXITCODE，因此这里必须显式同步退出码。
    $global:LASTEXITCODE = $ExitCode
    exit $ExitCode
}

# 状态聚合回归必须与 Keycloak/Gateway 可用性解耦。该开关在请求校验前退出，因此即使 CI 环境故意不提供
# 本地运行凭据或容器，也能验证这条窄化的状态规则。
if ($RunSpecialistStatusAggregationRegressionTest) {
    try {
        Invoke-SpecialistStatusAggregationRegressionTest
        Complete-E2EProcess -ExitCode 0
    } catch {
        $message = Get-LowSensitiveMessage -Text $_.Exception.Message -Fallback 'Specialist 状态聚合回归失败；请检查脚本中的低敏状态夹具和最终状态规则。'
        Write-Host "[FAIL] $message" -ForegroundColor Red
        Complete-E2EProcess -ExitCode 1
    }
}

if ($RunAutopilotPublicContractRegressionTest) {
    try {
        Invoke-AutopilotPublicContractRegressionTest
        Complete-E2EProcess -ExitCode 0
    } catch {
        $message = Get-LowSensitiveMessage -Text $_.Exception.Message -Fallback 'Autopilot 公开合同回归失败；请检查扁平状态夹具、自治恢复分流和有界停止断言。'
        Write-Host "[FAIL] $message" -ForegroundColor Red
        Complete-E2EProcess -ExitCode 1
    }
}

if ($RunRequestContractRegressionTest) {
    try {
        # 离线回归必须自带确定性异名对象夹具，不能依赖调用者额外传参。源端 schema 留空用于覆盖
        # MySQL catalog 场景；其余名称只用于内存中的公开请求合同，不访问网络或数据库。
        $SourceSchemaName = ''
        $SourceObjectName = 'contract_source_orders'
        $TargetSchemaName = 'contract_target_schema'
        $TargetObjectName = 'contract_target_orders'
        Assert-BasicInputs
        $contract = New-AgentRequestBody
        $nestedMappings = @($contract.variables.dataSyncRequest.objectMappings)
        $topLevelMappings = @($contract.variables.objectMappings)
        if ($nestedMappings.Count -ne 1 -or $topLevelMappings.Count -ne 1) {
            throw '请求合同回归失败：结构化对象映射没有同时进入 dataSyncRequest 和顶层 variables。'
        }
        $mapping = $nestedMappings[0]
        $topLevelMapping = $topLevelMappings[0]
        if ($mapping.sourceSchemaName -cne $SourceSchemaName.Trim() -or
            $mapping.sourceObjectName -cne $SourceObjectName.Trim() -or
            $mapping.targetSchemaName -cne $TargetSchemaName.Trim() -or
            $mapping.targetObjectName -cne $TargetObjectName.Trim()) {
            throw '请求合同回归失败：结构化对象映射与用户审核参数不一致。'
        }
        foreach ($fieldName in @(
                'objectKey',
                'sourceSchemaName',
                'sourceObjectName',
                'targetSchemaName',
                'targetObjectName',
                'whereCondition'
            )) {
            if ($topLevelMapping.$fieldName -cne $mapping.$fieldName) {
                throw "请求合同回归失败：顶层 objectMappings.$fieldName 与 dataSyncRequest 不一致。"
            }
        }
        Write-Host '[PASS] AgentRequest 异名对象映射合同回归完成。' -ForegroundColor Green
        Complete-E2EProcess -ExitCode 0
    } catch {
        $message = Get-LowSensitiveMessage -Text $_.Exception.Message -Fallback 'AgentRequest 对象映射合同回归失败。'
        Write-Host "[FAIL] $message" -ForegroundColor Red
        Complete-E2EProcess -ExitCode 1
    }
}

try {
    Assert-BasicInputs
    $requestBody = New-AgentRequestBody

    if (-not $Execute) {
        Show-PlanOnlySummary
        Complete-E2EProcess -ExitCode 0
    }

    $accessToken = Get-KeycloakAccessToken
    $response = Invoke-AgentPlan -AccessToken $accessToken -RequestBody $requestBody
    if ($null -eq $response) {
        Stop-E2E -Name 'Agent 最终响应' -Detail 'Agent API 没有返回可解析的计划对象。'
    }
    Add-Check -Name 'Agent 最终响应' -Status 'PASS' -Detail '已收到低敏 Agent 计划摘要，服务端内容未回显。'

    Add-SpecialistSchedulingDiagnostics -Response $response

    # 首次响应处于用户审批之前，只验收数据源消歧和同步规划；不能要求尚无真实资源的 PRECHECK/MONITOR。
    $roleEvidence = Assert-RoleEvidence -Response $response -Stage 'Planning'
    Assert-RagAndRecoveryEvidence -Response $response
    $null = Assert-BridgeEvidence -Response $response
    $postVerification = $null
    $executionSummary = $null

    # 初始规划响应只证明专业 Agent 已形成受治理计划，不能被误当成任务已经创建。只有调用方显式传入
    # -ConfirmAndExecute，脚本才选择最新完整 Durable Run 并调用 Java 确认入口。确认回执随后必须同时证明
    # 四个生命周期工具成功、可信 task/execution 已生成，以及 PRECHECK/MONITOR 已基于真实资源再次复核。
    # 启用 AUTOPILOT 时，首次 execution 仍先按普通终态读取：首次成功照常验收；首次失败才进入同一 root
    # execution 的公开 recovery 轮询。这样不会让自治授权吞掉正常成功，也不会把第一次失败过早报成 E2E 失败。
    if ($Scenario -eq 'Success' -and $ConfirmAndExecute) {
        $runReference = Get-LifecycleRunReference -Response $response
        if ($null -eq $runReference) {
            Stop-E2E -Name 'Agent Run 显式确认' -Detail 'Agent 尚未生成包含草稿保存、预检查、发布和启动四步的完整同步计划，因此不会确认不确定的运行批次；请补全任务配置后重新规划。'
        }
        $confirmation = Invoke-ConfirmedAgentRun -AccessToken $accessToken -Reference $runReference
        if ($null -eq $confirmation) {
            Stop-E2E -Name 'Agent Run 显式确认' -Detail '确认接口没有返回结构化执行回执，无法证明任务已经创建并提交。'
        }
        $lifecycle = Assert-ConfirmedLifecycle -Confirmation $confirmation
        $autopilotAuthorizationSnapshot = $null
        if ($EnableAutopilot) {
            $autopilotAuthorizationSnapshot = Assert-ConfirmedAutopilotSnapshot `
                -Confirmation $confirmation `
                -Reference $runReference
        }
        $continuation = Get-FieldValue -Object $confirmation -Names @('postConfirmContinuation', 'continuation')
        if ($null -eq $continuation) {
            Stop-E2E -Name '后置 PRECHECK/MONITOR' -Detail '确认回执缺少 postConfirmContinuation，无法验证真实资源产生后的专业 Agent 复核。'
        }
        $postVerification = Assert-PostBridgeVerification -Response $continuation
        if (-not $EnableAutopilot) {
            $executionSummary = Wait-SyncExecutionResult `
                -AccessToken $accessToken `
                -TaskId $lifecycle.TaskId `
                -ExecutionId $lifecycle.ExecutionId
        } else {
            # 将首次终态、Recovery 和当前 execution 的完整序列统一交给同一流程，确保离线回归
            # 与真实 Gateway 路径验证的是同一套分支合同。
            $autopilotFlow = Invoke-AutopilotSuccessRecoveryFlow `
                -TaskId $lifecycle.TaskId `
                -ExecutionId $lifecycle.ExecutionId `
                -AuthorizationSnapshot $autopilotAuthorizationSnapshot `
                -WaitTerminal {
                    param([long]$candidateExecutionId, [string]$operation)
                    Wait-SyncExecutionTerminal `
                        -AccessToken $accessToken `
                        -TaskId $lifecycle.TaskId `
                        -ExecutionId $candidateExecutionId `
                        -Operation $operation
                } `
                -AssertSucceeded {
                    param([long]$candidateExecutionId, [object]$candidateExecution)
                    Assert-SyncExecutionSucceeded `
                        -AccessToken $accessToken `
                        -TaskId $lifecycle.TaskId `
                        -ExecutionId $candidateExecutionId `
                        -Execution $candidateExecution
                } `
                -WaitRecovery {
                    param([object]$authorization)
                    Wait-AutopilotRecoveryResult `
                        -AccessToken $accessToken `
                        -TaskId $lifecycle.TaskId `
                        -ExecutionId $lifecycle.ExecutionId `
                        -AuthorizationSnapshot $authorization
                }
            $executionSummary = $autopilotFlow.ExecutionSummary
        }
    } elseif ($Scenario -eq 'Success') {
        Add-Check -Name '成功场景执行边界' -Status 'PASS' -Detail '未传 -ConfirmAndExecute，本次只验证审批前计划；没有创建或启动同步任务。'
    } else {
        # Recovery 的正确终点是带 RAG 证据的待审批修复方案。它没有新 execution 时不应伪造后置资源复核，
        # 但若服务端返回了低敏复核摘要，仍由统一断言检查其状态并明确保持审批边界。
        $postVerification = Assert-PostBridgeVerification -Response $response
    }

    # 成功确认后的 PRECHECK/MONITOR 事实会继续写入原会话，因此把 durable fact 查询放在确认和 execution
    # 验收之后，确保查询看到的是本轮最终事实集合，而不是确认之前的瞬时快照。未确认的 Success 只能验证
    # 规划事实；Recovery 仍由 Get-RequiredRolesForStage 保持其知识/恢复/监控审批边界，绝不自动批准。
    $durableFactStage = if ($Scenario -eq 'Success' -and $ConfirmAndExecute) { 'PostConfirmation' } else { 'Planning' }
    $durableSummary = Assert-DurableFacts -AccessToken $accessToken -Response $response -Stage $durableFactStage

    Write-FinalSummary `
        -Response $response `
        -PostVerification $postVerification `
        -DurableSummary $durableSummary `
        -ExecutionSummary $executionSummary
    if ($script:FailureCount -gt 0) {
        $script:TerminalFailure = $true
    } else {
        Write-Host '验收通过：当前场景满足脚本定义的六专业 Agent 治理闭环。' -ForegroundColor Green
    }
} catch {
    $script:TerminalFailure = $true
    $message = Get-LowSensitiveMessage -Text $_.Exception.Message -Fallback '验收失败；请根据 traceId 检查 Keycloak、Gateway、Agent Runtime 和 Java 控制面。'
    if ([string]::IsNullOrWhiteSpace($message)) {
        $message = '验收失败，未获得可展示的具体错误信息。'
    }
    Write-Host "[FAIL] 验收终止：$message" -ForegroundColor Red
    Write-Host '建议：先根据上一条人话错误检查 Keycloak/Gateway/Agent Runtime/Java 控制面，再用同一 RequestId 重试；本脚本未自动批准恢复动作。' -ForegroundColor Yellow
}

# 已记录的 FAIL 和抛出的异常都属于发布失败，任何一条路径都不能以退出码 0 结束。
$exitCode = if ($script:TerminalFailure -or $script:FailureCount -gt 0) { 1 } else { 0 }
Complete-E2EProcess -ExitCode $exitCode
