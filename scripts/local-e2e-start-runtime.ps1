<#
    DataSmart Govern 本地真实 E2E 运行时启动脚本。

    设计意图：
    1. 本脚本只解决“如何稳定启动本地联调运行时”的问题，不创建任务、不执行 SQL、不触发 data-sync worker loop、
       不调用 Python 工具执行，也不读取任何业务数据。
    2. 之前 runbook 中直接使用 `mvn -pl <module> -am spring-boot:run` 容易让 Maven 在父 POM 上执行
       spring-boot:run，父 POM 没有 main class 时会失败。因此这里先安装 platform-common，再进入每个子模块目录
       分别执行 `mvn spring-boot:run`，让 Maven 只启动目标 Spring Boot 应用。
    3. 当前各模块普通 `package` 产物不是可直接 `java -jar` 的 Spring Boot fat jar，本脚本暂不改变打包策略，
       而是复用仓库现阶段最稳定的 spring-boot:run 联调方式。
    4. 所有密码、token、SQL、HTTP 响应正文都不会被打印。脚本只输出服务名、端口、日志路径和启动动作。

    推荐用法：
    - 已通过 docker compose 启动 MySQL/Redis/Kafka/Nacos/Keycloak/Prometheus/Grafana 后：
      .\scripts\local-e2e-start-runtime.ps1
    - 如果本机 Docker MySQL 映射到了 13306：
      .\scripts\local-e2e-start-runtime.ps1 -MySqlPort 13306
    - 只想补启动 Java，不启动 Python Runtime：
      .\scripts\local-e2e-start-runtime.ps1 -SkipPython

    安全边界：
    - data-sync 的 worker-loop 默认在 application.yml 中为 false，本脚本不会覆盖为 true。
    - 本地样例数据库密码默认来自 DATASMART_MYSQL_PASSWORD；没有设置时才使用仓库本地开发默认值 password。
    - 日志落到 logs/local-e2e，该目录已被 .gitignore 忽略，避免误提交本地启动日志。
#>
param(
    [string]$RepoRoot = "",
    [string]$JdkHome = "",
    [string]$MavenLocalRepo = "",
    [int]$MySqlPort = 0,
    [string]$MySqlUser = "",
    [string]$MySqlPassword = "",
    [switch]$SkipPlatformCommonInstall,
    [switch]$SkipJava,
    [switch]$SkipPython
)

$ErrorActionPreference = "Stop"

function Resolve-RepoRoot {
    <#
        解析仓库根目录。

        说明：
        - 脚本通常从仓库根目录调用，但也可能从任意工作目录调用；
        - 这里优先使用参数，其次使用脚本所在目录的上级，避免依赖当前 shell 的 $PWD。
    #>
    if (-not [string]::IsNullOrWhiteSpace($RepoRoot)) {
        return (Resolve-Path -LiteralPath $RepoRoot).Path
    }
    return (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

function Resolve-JdkHome {
    <#
        解析 JDK 21 位置。

        说明：
        - 项目固定 JDK 21，Maven Toolchain 可以帮助编译阶段选择 JDK，但后台启动服务时仍需要 java/mvn
          能在当前进程环境中找到正确 JDK；
        - 如果用户已经设置 JAVA_HOME，则尊重用户环境；
        - 否则使用本机当前已验证可用的 Temurin 21 路径，减少“PATH 没有 JDK 21”造成的误报。
    #>
    if (-not [string]::IsNullOrWhiteSpace($JdkHome)) {
        return $JdkHome
    }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        return $env:JAVA_HOME
    }
    return "C:\Users\Cui\.jdks\temurin-21.0.10"
}

function Resolve-MavenLocalRepo {
    <#
        解析项目级 Maven 本地仓库。

        说明：
        - 本仓库长期使用项目内 `.m2`，可以避免全局 Maven 缓存污染，也能减少不同项目之间依赖版本互相影响；
        - Maven 命令统一带 `-Dmaven.repo.local=...`，让后台启动与测试验证使用同一个依赖缓存。
    #>
    param([string]$ResolvedRepoRoot)

    if (-not [string]::IsNullOrWhiteSpace($MavenLocalRepo)) {
        return $MavenLocalRepo
    }
    return (Join-Path $ResolvedRepoRoot ".m2")
}

function Resolve-MySqlPort {
    <#
        解析本地 MySQL 端口。

        说明：
        - Windows 本机可能存在 MySQL80 服务占用 3306；
        - 项目 `docker-compose.local-e2e.yml` 会把 Docker MySQL 暴露到 13306；
        - 因此优先使用参数，再使用 DATASMART_LOCAL_MYSQL_PORT，最后默认 13306。
    #>
    if ($MySqlPort -gt 0) {
        return $MySqlPort
    }
    if (-not [string]::IsNullOrWhiteSpace($env:DATASMART_LOCAL_MYSQL_PORT)) {
        return [int]$env:DATASMART_LOCAL_MYSQL_PORT
    }
    return 13306
}

function Test-PortOpen {
    <#
        检查端口是否已被服务占用。

        说明：
        - 如果端口已经打开，脚本默认认为对应服务已经在运行，避免重复启动导致端口冲突；
        - 这里不读取服务响应正文，只做 TCP 层可达性判断，保持输出低敏。
    #>
    param([int]$Port)

    return Test-NetConnection -ComputerName "127.0.0.1" -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue
}

function Set-LocalE2eEnvironment {
    <#
        写入当前 PowerShell 进程的本地 E2E 环境变量。

        说明：
        - Start-Process 默认继承父进程环境，因此这里设置一次即可让后续 Java/Python 子进程获得同一配置；
        - datasource URL 统一指向 Docker MySQL 端口，避免模块 application.yml 中的 localhost:3306 默认值
          误连到 Windows 本机 MySQL80；
        - Python 和 Java 都只获得基础设施地址，不获得 token、SQL 或业务数据。
    #>
    param(
        [string]$ResolvedRepoRoot,
        [string]$ResolvedJdkHome,
        [int]$ResolvedMySqlPort,
        [string]$ResolvedMySqlUser,
        [string]$ResolvedMySqlPassword
    )

    $env:JAVA_HOME = $ResolvedJdkHome
    $env:Path = "$ResolvedJdkHome\bin;$env:Path"
    $env:DATASMART_LOCAL_MYSQL_PORT = [string]$ResolvedMySqlPort
    $env:DATASMART_MYSQL_USER = $ResolvedMySqlUser
    $env:DATASMART_MYSQL_PASSWORD = $ResolvedMySqlPassword
    # 本地 E2E 直接从 Windows 宿主机启动 Java 进程，不会继承 Docker Compose 的 TZ/JAVA_OPTS。
    # 这里显式指定 JVM、Jackson 和 JDBC 时区，避免宿主机启动路径仍按 UTC 写入 LocalDateTime。
    $env:TZ = "Asia/Shanghai"
    $env:JAVA_TOOL_OPTIONS = "-Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"
    $env:SPRING_JACKSON_TIME_ZONE = "Asia/Shanghai"
    $env:SPRING_DATASOURCE_URL = "jdbc:mysql://localhost:{0}/datasmart_govern?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8" -f $ResolvedMySqlPort
    $env:SPRING_DATASOURCE_USERNAME = $ResolvedMySqlUser
    $env:SPRING_DATASOURCE_PASSWORD = $ResolvedMySqlPassword
    $env:SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR = "localhost:8848"
    $env:SPRING_KAFKA_BOOTSTRAP_SERVERS = "localhost:9092"
    $env:SPRING_REDIS_HOST = "localhost"
    $env:SPRING_REDIS_PORT = "6379"
    $env:SPRING_NEO4J_URI = "bolt://localhost:7687"
    $env:SPRING_NEO4J_AUTHENTICATION_USERNAME = "neo4j"
    $env:SPRING_NEO4J_AUTHENTICATION_PASSWORD = "password"
    $env:DATASMART_AGENT_RUNTIME_JDBC_URL = "jdbc:mysql://localhost:{0}/datasmart_govern?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true" -f $ResolvedMySqlPort
    $env:DATASMART_AGENT_RUNTIME_JDBC_USERNAME = $ResolvedMySqlUser
    $env:DATASMART_AGENT_RUNTIME_JDBC_PASSWORD = $ResolvedMySqlPassword
    $env:DATASMART_GATEWAY_OIDC_ISSUER_URI = "http://localhost:18080/realms/datasmart"
    $env:PYTHONPATH = Join-Path $ResolvedRepoRoot "python-ai-runtime\src"
}

function Install-PlatformCommon {
    <#
        安装 platform-common 到项目级 Maven 仓库。

        说明：
        - 子模块在独立工作目录中启动时，不再处于完整 reactor 中；
        - 先安装共享契约模块，可以让 permission-admin、gateway、agent-runtime 等服务解析到同版本
          platform-common，避免因本地仓库缺失而启动失败。
    #>
    param(
        [string]$ResolvedRepoRoot,
        [string]$ResolvedMavenLocalRepo
    )

    Write-Host "[INFO] Install platform-common into project Maven repo" -ForegroundColor Cyan
    & mvn -pl platform-common -DskipTests install "-Dmaven.repo.local=$ResolvedMavenLocalRepo" | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "platform-common install failed"
    }
}

function Start-JavaModule {
    <#
        后台启动一个 Java 微服务。

        参数说明：
        - Name：模块目录名，也是日志文件名前缀；
        - Port：模块固定监听端口，用于判断是否已经运行；
        - ResolvedRepoRoot：仓库根目录；
        - ResolvedMavenLocalRepo：项目级 Maven 仓库。

        设计说明：
        - 每个服务从自己的子模块目录启动，避免 Maven 在父 POM 上执行 spring-boot:run；
        - stdout/stderr 分离写入 logs/local-e2e，便于 smoke 失败时按服务定位；
        - 如果端口已经打开，则跳过启动，减少重复进程和端口冲突。
    #>
    param(
        [string]$Name,
        [int]$Port,
        [string]$ResolvedRepoRoot,
        [string]$ResolvedMavenLocalRepo
    )

    if (Test-PortOpen -Port $Port) {
        Write-Host ("[SKIP] {0}:{1} is already listening" -f $Name, $Port) -ForegroundColor Yellow
        return
    }

    $moduleDir = Join-Path $ResolvedRepoRoot $Name
    $logDir = Join-Path -Path $ResolvedRepoRoot -ChildPath 'logs/local-e2e'
    $outLog = Join-Path $logDir ("{0}.out.log" -f $Name)
    $errLog = Join-Path $logDir ("{0}.err.log" -f $Name)

    Write-Host ("[START] {0}:{1} -> {2}" -f $Name, $Port, $outLog) -ForegroundColor Green
    $startProcessArguments = @{
        FilePath = "mvn.cmd"
        ArgumentList = @("spring-boot:run", "-DskipTests", "-Dmaven.repo.local=$ResolvedMavenLocalRepo")
        WorkingDirectory = $moduleDir
        WindowStyle = "Hidden"
        RedirectStandardOutput = $outLog
        RedirectStandardError = $errLog
    }
    Start-Process @startProcessArguments | Out-Null
}

function Start-PythonRuntime {
    <#
        后台启动 Python AI Runtime。

        说明：
        - Python Runtime 负责 Agent Host、能力闭口诊断、Skill Manifest 诊断和模型推理优化控制面；
        - 启动命令使用 FastAPI/uvicorn factory，不触发工具执行、不创建任务、不读取业务数据；
        - 如果 8090 已经监听，则跳过重复启动。
    #>
    param([string]$ResolvedRepoRoot)

    if (Test-PortOpen -Port 8090) {
        Write-Host "[SKIP] python-ai-runtime:8090 is already listening" -ForegroundColor Yellow
        return
    }

    $logDir = Join-Path -Path $ResolvedRepoRoot -ChildPath 'logs/local-e2e'
    Write-Host "[START] python-ai-runtime:8090" -ForegroundColor Green
    $startProcessArguments = @{
        FilePath = "python"
        ArgumentList = @("-m", "uvicorn", "datasmart_ai_runtime.api:create_app", "--factory", "--host", "127.0.0.1", "--port", "8090")
        WorkingDirectory = $ResolvedRepoRoot
        WindowStyle = "Hidden"
        RedirectStandardOutput = (Join-Path $logDir "python-ai-runtime.out.log")
        RedirectStandardError = (Join-Path $logDir "python-ai-runtime.err.log")
    }
    Start-Process @startProcessArguments | Out-Null
}

$resolvedRepoRoot = Resolve-RepoRoot
$resolvedJdkHome = Resolve-JdkHome
$resolvedMavenLocalRepo = Resolve-MavenLocalRepo -ResolvedRepoRoot $resolvedRepoRoot
$resolvedMySqlPort = Resolve-MySqlPort
$resolvedMySqlUser = if ([string]::IsNullOrWhiteSpace($MySqlUser)) {
    if ([string]::IsNullOrWhiteSpace($env:DATASMART_MYSQL_USER)) { "root" } else { $env:DATASMART_MYSQL_USER }
} else {
    $MySqlUser
}
$resolvedMySqlPassword = if ([string]::IsNullOrWhiteSpace($MySqlPassword)) {
    if ([string]::IsNullOrWhiteSpace($env:DATASMART_MYSQL_PASSWORD)) { "password" } else { $env:DATASMART_MYSQL_PASSWORD }
} else {
    $MySqlPassword
}

New-Item -ItemType Directory -Force -Path (Join-Path -Path $resolvedRepoRoot -ChildPath 'logs/local-e2e') | Out-Null
Set-LocalE2eEnvironment `
    -ResolvedRepoRoot $resolvedRepoRoot `
    -ResolvedJdkHome $resolvedJdkHome `
    -ResolvedMySqlPort $resolvedMySqlPort `
    -ResolvedMySqlUser $resolvedMySqlUser `
    -ResolvedMySqlPassword $resolvedMySqlPassword

Write-Host "DataSmart Govern local E2E runtime starter" -ForegroundColor Cyan
Write-Host ("Repo root: {0}" -f $resolvedRepoRoot)
Write-Host ("Maven local repo: {0}" -f $resolvedMavenLocalRepo)
Write-Host ("MySQL port: {0}" -f $resolvedMySqlPort)

if (-not $SkipPlatformCommonInstall) {
    Install-PlatformCommon -ResolvedRepoRoot $resolvedRepoRoot -ResolvedMavenLocalRepo $resolvedMavenLocalRepo
}

if (-not $SkipJava) {
    $javaModules = @(
        @{ Name = "permission-admin"; Port = 8085 },
        @{ Name = "task-management"; Port = 8081 },
        @{ Name = "datasource-management"; Port = 8082 },
        @{ Name = "data-quality"; Port = 8083 },
        @{ Name = "observability"; Port = 8084 },
        @{ Name = "data-sync"; Port = 8086 },
        @{ Name = "agent-runtime"; Port = 8091 },
        @{ Name = "gateway"; Port = 8080 }
    )

    foreach ($module in $javaModules) {
        Start-JavaModule `
            -Name $module.Name `
            -Port $module.Port `
            -ResolvedRepoRoot $resolvedRepoRoot `
            -ResolvedMavenLocalRepo $resolvedMavenLocalRepo
        Start-Sleep -Seconds 2
    }
}

if (-not $SkipPython) {
    Start-PythonRuntime -ResolvedRepoRoot $resolvedRepoRoot
}

Write-Host "[INFO] Start commands submitted. Wait 45-90 seconds, then run:" -ForegroundColor Cyan
Write-Host "  .\scripts\local-e2e-environment-readiness.ps1 -ProbeMySqlCredential"
Write-Host "  .\scripts\local-e2e-smoke-check.ps1 -CheckServiceAccountToken -CheckAgentGatewayDiagnostics"
