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
    - Recovery 场景最多验证高风险动作已经进入审批/Java handoff，不会自动提交批准，也不会直接执行恢复动作；
    - 只解析低敏角色、状态、稳定 ID、计数和建议，不输出 prompt、模型原文、SQL、工具参数或连接信息。

    典型用法：
    - 只查看当前验收计划：
      .\scripts\local-six-agent-governed-e2e.ps1
    - 成功场景，流式调用 Gateway：
      $env:DATASMART_KEYCLOAK_LOCAL_USER_PASSWORD = '<本地 project-owner 密码>'
      .\scripts\local-six-agent-governed-e2e.ps1 -Execute -ConfirmAndExecute `
        -SourceDatasourceName 'FlashSync MySQL 源' `
        -TargetDatasourceName 'FlashSync PostgreSQL 目标' `
        -Objective '将 MySQL 中的两张测试表全量同步到 PostgreSQL public schema 的同名表，并完成预检查后执行'
    - 恢复场景，只验证 RAG -> Recovery -> 审批等待：
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

    # 允许调用方显式写出只读模式；与默认行为等价，但不能和 -Execute 同时使用。
    [switch]$PlanOnly,

    # 成功场景验证同步规划和后置复核；恢复场景验证 RAG/恢复审批门禁而不自动批准。
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
    'KNOWLEDGE_AGENT',
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
        Stop-E2E -Name '恢复审批边界' -Detail 'Recovery 场景禁止使用 -ConfirmAndExecute；改表、清理数据、修改任务和重试必须由用户在产品界面逐项审核。'
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
            # Windows PowerShell 5.1 may throw "Argument types do not match"
            # when a Generic.List[object] is wrapped directly with @(...).
            # ToArray preserves every parsed frame and lets the caller continue
            # with assertions after the final result frame.
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
    $request = New-HttpRequestMessage -Method $Method -Uri $uri -AccessToken $AccessToken -JsonBody $jsonBody
    try {
        $response = $client.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
        ).GetAwaiter().GetResult()
        $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            $detail = Get-SafeHttpErrorDetail -StatusCode ([int]$response.StatusCode) -Body $responseBody -Operation $Operation
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
    } catch {
        if (Test-SafeE2EException -Exception $_.Exception) {
            throw
        }
        Stop-E2E -Name $Operation -Detail 'Gateway 调用失败；请检查服务健康状态、当前项目权限和 traceId。'
    } finally {
        $request.Dispose()
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

        # Durable summary normally exposes submittedToolNames. Accept the legacy object form as well, but never read
        # arguments or serialize plans: confirmation selection only needs stable tool codes.
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
    $result = Invoke-GatewayJson `
        -Method 'POST' `
        -Path "/api/agent/sessions/$sessionId/runs/$runId/confirm-and-execute" `
        -AccessToken $AccessToken `
        -Body @{
            confirmed = $true
            comment = '六专业 Agent 本地 E2E：用户显式确认 Success 场景同步生命周期计划。'
        } `
        -Operation 'Agent Run 显式确认'
    Add-Check -Name 'Agent Run 显式确认' -Status 'PASS' -Detail "已批准来源=$($Reference.Source) 的同步生命周期 Run；运行标识与服务端内容均未回显。"
    return $result
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

function Wait-SyncExecutionResult {
    <#
    .SYNOPSIS
        等待本次真实 data-sync execution 到达终态，并核对计数、对象账本和运行日志。

    .DESCRIPTION
        Agent 的创建任务目标在“任务已创建并提交执行”时已经完成，但六 Agent E2E 还需要更强证据证明 worker
        真正消费了 execution。这里通过 Gateway 以当前用户身份轮询，不直连业务数据库、不调用内部 worker API；
        最终要求 execution=SUCCEEDED、失败数为 0、读写计数合理、所有对象成功且至少存在一条运行日志。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][long]$TaskId,
        [Parameter(Mandatory = $true)][long]$ExecutionId
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($ExecutionTimeoutSeconds)
    $terminalStates = @('SUCCEEDED', 'FAILED', 'PARTIAL_SUCCESS', 'CANCELLED')
    $execution = $null
    do {
        $page = Invoke-GatewayJson `
            -Method 'GET' `
            -Path "/api/sync/sync-tasks/$TaskId/executions?current=1&size=100" `
            -AccessToken $AccessToken `
            -Body $null `
            -Operation '查询同步 execution'
        $execution = Get-PageRecords $page | Where-Object {
            [string](Get-FieldValue -Object $_ -Names @('id', 'executionId')) -eq [string]$ExecutionId
        } | Select-Object -First 1
        $state = if ($null -eq $execution) {
            'NOT_VISIBLE_YET'
        } else {
            Get-SafeStatusToken -Text (Get-FieldValue -Object $execution -Names @('executionState', 'state')) -Fallback 'UNKNOWN'
        }
        if ($terminalStates -contains $state) { break }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)

    if ($null -eq $execution) {
        Stop-E2E -Name '真实同步执行' -Detail '确认接口返回了 executionId，但在超时前始终无法从任务执行历史查询到它。'
    }
    $state = Get-SafeStatusToken -Text (Get-FieldValue -Object $execution -Names @('executionState', 'state')) -Fallback 'UNKNOWN'
    if ($state -ne 'SUCCEEDED') {
        $errorMessage = Get-LowSensitiveMessage `
            -Text (Get-FieldValue -Object $execution -Names @('errorMessage', 'failureReason')) `
            -Fallback '执行未成功；请在任务运行详情查看失败阶段、日志和 Agent 恢复入口。'
        Stop-E2E -Name '真实同步执行' -Detail "execution 状态=$state；$errorMessage"
    }

    $recordsRead = [long](Get-FieldValue -Object $execution -Names @('recordsRead'))
    $recordsWritten = [long](Get-FieldValue -Object $execution -Names @('recordsWritten'))
    $failedRecords = [long](Get-FieldValue -Object $execution -Names @('failedRecordCount'))
    if ($failedRecords -ne 0 -or $recordsRead -le 0 -or $recordsWritten -le 0) {
        Stop-E2E -Name '同步记录计数' -Detail "execution 已成功但计数不合理：read=$recordsRead、written=$recordsWritten、failed=$failedRecords。"
    }
    if ($ExpectedRecordCount -gt 0 -and ($recordsRead -ne $ExpectedRecordCount -or $recordsWritten -ne $ExpectedRecordCount)) {
        Stop-E2E -Name '同步记录计数' -Detail "期望 read/write=$ExpectedRecordCount，实际 read=$recordsRead、written=$recordsWritten。"
    }
    Add-Check -Name '真实同步执行' -Status 'PASS' -Detail 'worker 已把本次 execution 推进到 SUCCEEDED。'
    Add-Check -Name '同步记录计数' -Status 'PASS' -Detail "read=$recordsRead、written=$recordsWritten、failed=$failedRecords。"

    $objectsPage = Invoke-GatewayJson `
        -Method 'GET' `
        -Path "/api/sync/sync-tasks/$TaskId/executions/$ExecutionId/objects?current=1&size=100" `
        -AccessToken $AccessToken `
        -Body $null `
        -Operation '查询同步对象账本'
    $objects = @(Get-PageRecords $objectsPage)
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
        State = $state
        RecordsRead = $recordsRead
        RecordsWritten = $recordsWritten
        FailedRecordCount = $failedRecords
        ObjectCount = $objects.Count
        LogCount = $logs.Count
    }
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
                # A later success is a real recovery only when it belongs to the same role and follows a non-success result.
                $recoveredByRole[$record.Role] = "$($record.Role)=$($previous.Status)/$($previous.ErrorCode)=>$($record.Status)"
            } elseif (-not $currentSucceeded) {
                # A later failure supersedes any earlier recovery; only the final role state may clear the warning.
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
    # A failed batch is only recoverable when the response identifies at least one known failing role in that exact phase.
    # Otherwise a failed unknown role or an incomplete payload would disappear behind unrelated successful role entries.
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

        Recovery 的目标是产生带审批门禁的恢复方案，而非自动创建或重试任务，所以无论传入哪个阶段，
        都继续要求知识、恢复和监控角色。-RequireAllSixRolesExecuted 是显式诊断覆盖项，优先级最高，
        用于人工验证所有角色同时可用，不能作为日常 Success/Recovery 验收的默认要求。
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

        Recovery 场景维持原有边界：验证 KNOWLEDGE_AGENT、RECOVERY_AGENT、MONITOR_AGENT 的受治理参与，
        但本脚本永远不会自动批准恢复动作。-RequireAllSixRolesExecuted 仅用于人工构造的全角色诊断场景，
        默认关闭，避免为了测试而无意义地触发 RAG 或高风险恢复分析。
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
        Stop-E2E -Name '专业 Agent 参与' -Detail "当前 $Stage 阶段缺少成功完成结果：$($missingExecuted -join '、')；未完成状态=$nonCompletedText。PARTIALLY_FAILED 不属于成功完成。成功场景中的恢复 Agent 只有在失败上下文中才应执行；若本次是恢复验收，请提供真实 TaskId/ExecutionId 和失败信息。"
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
    # One aggregation is shared with role assertions so a recovered role cannot be PASS in one check and WARN in another.
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
        6. 生命周期确认只能选择含四个同步步骤的同一 Durable Run。

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
    # Exercise the visible diagnostic path as well as the pure aggregation result. A regression here would be user-visible
    # even if Get-RoleEvidence itself remained correct, because the E2E summary derives its warning count from Add-Check.
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

    Write-Host '[PASS] Specialist 脚本回归：状态聚合、低敏成功摘要和生命周期 Run 选择均符合预期。' -ForegroundColor Green
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
        验证 Recovery 场景的 RAG -> 审批准备链路，且确认 Recovery 尚未执行。

    .DESCRIPTION
        六角色名册只能证明角色存在，不能证明故障恢复确实引用了历史案例。Recovery 场景因此还必须看到
        KNOWLEDGE_AGENT 的 grounded=true、至少一条低敏引用和至少一条 evidenceReference；随后检查
        RECOVERY_AGENT 的结构化结果声明 requiresApproval/javaToolPlanPending=true 且 executed=false。
        Success 场景按需不调用 RAG，因此没有 KNOWLEDGE_AGENT 结果是合法的；只有当响应确实包含
        KNOWLEDGE_AGENT 时才检查其低敏结果。函数不显示引用 ID、标题、正文、恢复参数、SQL 或模型输出。
    #>
    param([Parameter(Mandatory = $true)][object]$Response)

    $knowledgeResult = Get-SpecialistResultByRole -Response $Response -Role 'KNOWLEDGE_AGENT'
    if ($null -eq $knowledgeResult) {
        if ($Scenario -eq 'Recovery') {
            Stop-E2E -Name 'RAG 专业结果' -Detail '恢复场景没有 KNOWLEDGE_AGENT 的结构化结果，无法确认历史案例检索链路。建议检查 RAG 注册、项目范围和 specialist durable runner。'
        }
        Add-Check -Name 'RAG 专业结果' -Status 'PASS' -Detail 'Success 场景未提出知识或故障案例检索需求，RAG 按需待命，未强制调用。'
        return
    }
    $knowledgeStatus = Get-SafeStatusToken -Text (Get-FieldValue -Object $knowledgeResult -Names @('status')) -Fallback 'UNKNOWN'
    if ($knowledgeStatus -ne 'COMPLETED') {
        Stop-E2E -Name 'RAG 专业结果' -Detail "KNOWLEDGE_AGENT 状态=$knowledgeStatus，知识专业 turn 没有完成。建议检查 RAG 服务健康状态和项目可见知识范围。"
    }

    $knowledgeOutput = Get-FieldValue -Object $knowledgeResult -Names @('structuredOutput')
    $grounded = Test-TrueFlag (Get-FieldValue -Object $knowledgeOutput -Names @('grounded'))
    $citations = @(Get-Items (Get-FieldValue -Object $knowledgeOutput -Names @('citations')))
    $evidenceReferences = @(Get-Items (Get-FieldValue -Object $knowledgeResult -Names @('evidenceReferences')))
    if ($Scenario -eq 'Recovery') {
        if (-not $grounded -or $citations.Count -le 0 -or $evidenceReferences.Count -le 0) {
            Stop-E2E -Name 'RAG 案例证据' -Detail 'Recovery 已执行 KNOWLEDGE_AGENT，但没有返回 grounded 引用证据；恢复方案不能脱离历史案例直接进入审批。建议检查 RAG 索引、项目范围和 evidence gate。'
        }
        Add-Check -Name 'RAG 案例证据' -Status 'PASS' -Detail "Recovery 已获得 grounded RAG 证据（引用数=$($citations.Count)，证据引用数=$($evidenceReferences.Count)）。"

        $recoveryResult = Get-SpecialistResultByRole -Response $Response -Role 'RECOVERY_AGENT'
        if ($null -eq $recoveryResult) {
            Stop-E2E -Name '恢复审批准备' -Detail '没有 RECOVERY_AGENT 结构化结果，无法确认恢复方案是否已进入审批门禁。'
        }
        $recoveryOutput = Get-FieldValue -Object $recoveryResult -Names @('structuredOutput')
        $requiresApproval = Test-TrueFlag (Get-FieldValue -Object $recoveryOutput -Names @('requiresApproval'))
        $javaToolPlanPending = Test-TrueFlag (Get-FieldValue -Object $recoveryOutput -Names @('javaToolPlanPending'))
        $executed = Test-TrueFlag (Get-FieldValue -Object $recoveryOutput -Names @('executed'))
        $planAvailable = Test-TrueFlag (Get-FieldValue -Object $recoveryOutput -Names @('planAvailable'))
        $readOnly = Test-TrueFlag (Get-FieldValue -Object $recoveryOutput -Names @('readOnly'))
        $repairActionCount = @(Get-Items (Get-FieldValue -Object $recoveryOutput -Names @('repairActions'))).Count
        if ($executed) {
            Stop-E2E -Name '恢复审批准备' -Detail 'Recovery specialist 声称已经在 Python 中执行恢复动作；任何恢复副作用都必须进入 Java 工具治理，已终止验收。'
        }
        if ($requiresApproval) {
            if (-not $javaToolPlanPending) {
                Stop-E2E -Name '恢复审批准备' -Detail 'Recovery 已提出高风险动作，但没有声明等待 Java ToolPlan；重试、改表或数据修复不得绕过审批。'
            }
            Add-Check -Name '恢复审批准备' -Status 'PASS' -Detail "Recovery 已生成 $repairActionCount 个待审批动作，等待 Java 控制面，不会自动执行。"
        } elseif ($planAvailable -or $readOnly -or $repairActionCount -gt 0) {
            # Recovery 允许先提出只读诊断或 preview。这些动作本身无需制造一张用户审批单，
            # 但仍必须由后续 Bridge 做工具可见性、schema、RBAC 和结果引用校验，不能在 specialist 内执行。
            Add-Check -Name '恢复审批准备' -Status 'PASS' -Detail "Recovery 已生成 $repairActionCount 个低风险诊断/预览建议；当前无需审批，仍等待 Java 受控工具链处理。"
        } else {
            Stop-E2E -Name '恢复审批准备' -Detail 'Recovery 没有形成可交接动作或人话恢复草案；请检查诊断事实是否足够，以及模型是否按规范动作合同返回建议。'
        }
    } elseif ($grounded -and $citations.Count -gt 0) {
        Add-Check -Name 'RAG 专业结果' -Status 'PASS' -Detail "KNOWLEDGE_AGENT 已完成并返回低敏证据统计（引用数=$($citations.Count)）；Success 场景继续按控制面任务结果验收。"
    } else {
        Add-Check -Name 'RAG 专业结果' -Status 'WARN' -Detail 'KNOWLEDGE_AGENT 已完成，但本次 Success 需求没有可用案例引用；按设计不阻断清晰的同步任务验收。'
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
            # Each session is queried through Gateway so Java repeats tenant/project/object authorization.
            # Only the returned low-sensitive fact views are merged in memory; no fact body is printed.
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
            # The response-level RAG and bridge assertions run before this query and prove why Recovery is
            # waiting. The durable fact only has to prove that the governed turn itself was recorded; it must
            # not relabel an approval/evidence wait as COMPLETED.
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

    # LASTEXITCODE is observable by callers that invoke this script from a shared PowerShell session.
    $global:LASTEXITCODE = $ExitCode
    exit $ExitCode
}

# Keep the aggregation regression independent from Keycloak/Gateway availability. The switch exits before request validation,
# so CI can verify this narrow status rule even when it intentionally has no local runtime credentials or containers.
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
        $continuation = Get-FieldValue -Object $confirmation -Names @('postConfirmContinuation', 'continuation')
        if ($null -eq $continuation) {
            Stop-E2E -Name '后置 PRECHECK/MONITOR' -Detail '确认回执缺少 postConfirmContinuation，无法验证真实资源产生后的专业 Agent 复核。'
        }
        $postVerification = Assert-PostBridgeVerification -Response $continuation
        $executionSummary = Wait-SyncExecutionResult `
            -AccessToken $accessToken `
            -TaskId $lifecycle.TaskId `
            -ExecutionId $lifecycle.ExecutionId
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

# A recorded FAIL and a thrown exception are both release failures. Do not let either path fall through with 0.
$exitCode = if ($script:TerminalFailure -or $script:FailureCount -gt 0) { 1 } else { 0 }
Complete-E2EProcess -ExitCode $exitCode
