/**
 * DataSmart Govern 运维与事故语料的通俗诊断画像库。
 *
 * 这里保存的是纯合成、可公开用于评测的排障知识，不读取生产日志、数据库、环境变量或 Secret。每个错误码
 * 同时描述用户、运维和开发三个视角，目的是让 RAG 不只会复述技术术语，还能回答“用户做了什么、页面
 * 看到什么、应该去哪个服务查什么日志、如何从日志判断根因、最终改配置还是改代码”。
 */

const MANUAL_RECOVERY_ERROR_CODES = new Set([
  "AUTHENTICATION_FAILED",
  "PERMISSION_DENIED",
  "DDL_REQUIRED",
]);

/**
 * 把文档中的基础设施展示名称转换为仓库 docker-compose 文件中的真实服务名。
 *
 * 展示名称可以使用 Kafka、PostgreSQL 等产品写法，但命令行参数必须与 Compose 的 service key 完全一致；
 * 如果直接拼接展示名称，读者复制命令后会得到“no such service”，反而妨碍事故定位。
 */
const COMPOSE_SERVICE_NAMES = {
  Kafka: "kafka",
  PostgreSQL: "postgresql",
  Redis: "redis",
  MinIO: "minio",
};

/** 返回可直接传给 `docker compose logs` 的服务名。 */
function composeServiceName(displayName) {
  return COMPOSE_SERVICE_NAMES[displayName] || displayName;
}

/**
 * 生成可复制的 Compose 日志命令。
 *
 * 所有 DOCX、XLSX、JSONL 和 LOG 语料都必须经过同一入口，避免某一种格式再次把展示名称误当成
 * service key。
 */
export function composeLogCommandFor(displayName) {
  return `docker compose logs ${composeServiceName(displayName)}`;
}

/**
 * 错误诊断画像。
 *
 * 字段说明：
 * - `feature/trigger/userSymptom/userMessage`：还原用户实际操作与页面表现；
 * - `primaryService/relatedServices/logDetail`：告诉运维人员先查哪个微服务、寻找哪条稳定日志特征；
 * - `diagnosisFocus/developerDiagnosis`：把日志事实收敛到配置、代码、SQL、网络或依赖故障；
 * - `plainRootCause/technicalRootCause`：分别服务普通用户和研发人员；
 * - `repairSteps/changeDescription/verification`：给出可执行修复、变更内容和验收方法。
 */
const ERROR_DIAGNOSTIC_PROFILES = {
  CONNECTION_TIMEOUT: {
    feature: "新建全量同步任务时选择源数据源、测试连接，以及任务启动后的首批读取",
    trigger: "用户在新建任务向导中选定源数据源并点击下一步，或已发布任务开始读取第一个对象",
    userSymptom: "数据源能够在下拉框中看到，但连接校验持续转圈，随后任务停在预检或读取阶段",
    userMessage: "连接源数据源超时，请稍后重试；若持续出现，请联系运维并提供 traceId",
    primaryService: "datasource-management",
    relatedServices: "data-sync、Gateway、DNS/网络和源数据库",
    logDetail: "connect timeout after 30000ms while probing datasource endpoint; transportFailure=true",
    diagnosisFocus: "先区分 DNS 解析失败、端口不通、目标实例未启动、Nacos 旧实例地址和客户端 timeout 过小",
    developerDiagnosis: "如果目标健康且同机网络可达，但请求仍固定访问旧地址，应检查服务发现缓存和 HTTP 客户端地址刷新；如果只有大表首批读取超时，应检查 run-once 超时投影而不是误判为凭据失败",
    plainRootCause: "系统在规定时间内没有连上所选数据源，常见原因是地址或端口填错、数据库没启动、网络不通，或者连接等待时间配置得太短",
    technicalRootCause: "数据源探测或 run-once HTTP 调用抛出 transport timeout，未在客户端截止时间内取得成功 envelope；错误发生在业务 SQL 执行之前",
    repairSteps: "核对数据源主机和端口；从运行节点测试 DNS 与 TCP；确认目标数据库健康；清理失效服务实例；仅在连接确实较慢且授权允许时有界增加 timeout；重新执行连接测试和预检",
    changeDescription: "配置问题修改数据源地址、服务发现实例或 timeout 上限；若代码错误缓存了旧地址，则修复客户端实例刷新逻辑并增加连接拒绝、DNS 失败和读取超时回归测试",
    verification: "连接测试在预算内成功；PRECHECK_AGENT 返回通过；同一对象重新执行后读写量增长且没有再次出现 CONNECTION_TIMEOUT",
  },
  AUTHENTICATION_FAILED: {
    feature: "数据源登记、连接测试和同步任务读取外部数据库",
    trigger: "用户选择了已登记的数据源并执行连接测试，或任务读取时引用了已经轮换的凭据版本",
    userSymptom: "连接测试立即失败，任务详情提示认证失败，但页面不会展示用户名、密码或 Token 原文",
    userMessage: "数据源认证失败，请由有权限的管理员检查凭据引用是否有效",
    primaryService: "datasource-management",
    relatedServices: "Secret 管理、源数据库、data-sync",
    logDetail: "authentication rejected by datasource; credentialRefVersion mismatch; secretValueRedacted=true",
    diagnosisFocus: "查看数据库返回的认证状态、当前 credentialRef 版本和最近一次成功执行使用的引用版本，禁止在日志中打印密码",
    developerDiagnosis: "确认连接器是否把凭据引用解析成了最新 Secret，以及轮换后连接池是否仍持有旧连接；401/28P01 等认证错误不能被错误标记为自动重试",
    plainRootCause: "系统保存的是凭据引用而不是明文密码；当前引用已经失效、版本选错，或数据库端账号被锁定，因此连接器被拒绝",
    technicalRootCause: "连接器建立会话时收到认证拒绝，credential reference 与 Secret 当前版本或数据库账号状态不一致",
    repairSteps: "由有权限主体核对账号状态；在 Secret 管理系统轮换或修正凭据；更新数据源的凭据引用；关闭旧连接池；重新连接测试。自治 Loop 不得猜测或修改凭据",
    changeDescription: "通常修改 Secret 或凭据引用；若轮换后连接池未刷新，则修复连接池失效逻辑并增加凭据版本切换测试",
    verification: "新凭据引用连接成功；旧引用不可再用；日志只保留版本和拒绝码；审计能关联执行人和轮换时间",
  },
  PERMISSION_DENIED: {
    feature: "创建、发布、运行、查看或恢复同步任务",
    trigger: "用户对任务或数据源执行当前角色没有获授权的操作，或审批已过期、范围不覆盖目标项目",
    userSymptom: "按钮可能不可用，或提交后收到 403；已有任务状态不会因为拒绝而改变",
    userMessage: "当前账号没有执行该操作的权限，或批准范围与目标资源不匹配",
    primaryService: "permission-admin",
    relatedServices: "Gateway、data-sync、agent-runtime",
    logDetail: "authorization decision DENY; resourceAction or dataScope not satisfied; sideEffectStarted=false",
    diagnosisFocus: "核对 actor、role、resourceType、action、tenant/project 范围、审批主体、有效期和批准是否已消费",
    developerDiagnosis: "确认 Gateway 的路由动作映射与业务服务二次对象校验一致，排除缓存未失效或把 GET/POST 映射到错误动作的代码问题",
    plainRootCause: "当前账号的角色或审批只允许查看，不能执行这次修改；也可能是用户切到了错误项目或批准已经过期",
    technicalRootCause: "permission-admin 返回 DENY，资源动作、数据范围或双主体审批事实至少一项不满足，控制面在副作用前 fail-closed",
    repairSteps: "确认当前租户和项目；查看所需资源动作；由不同的有权限批准人补充最小范围审批；策略变更后刷新授权缓存；重新提交原操作",
    changeDescription: "配置正确角色、路由策略、数据范围或审批，不通过改代码绕过授权；若动作映射错误，修复 Gateway 规则并补权限回归测试",
    verification: "授权诊断返回 ALLOW；业务服务对象范围复核通过；审计同时记录请求主体和批准主体；越权账号仍返回 403",
  },
  RATE_LIMIT_EXCEEDED: {
    feature: "批量同步、API 数据源读取和目标端批量写入",
    trigger: "任务并发或批量超过来源/目标允许速率，外部接口返回 429 或连接器本地容量门禁拒绝",
    userSymptom: "任务速度突然下降并进入有界重试，执行详情显示限流而不是普通网络失败",
    userMessage: "对端请求过于频繁，系统已降低速率并等待重试",
    primaryService: "data-sync",
    relatedServices: "datasource-management、外部 API、目标数据库",
    logDetail: "remote endpoint returned 429; retryAfterSeconds=30; currentChannel exceeds governed capacity",
    diagnosisFocus: "查看 429、Retry-After、当前 channel/batch、连接器能力快照和最近成功配置，确认是不是突然放大并发",
    developerDiagnosis: "核对连接器是否正确解析 Retry-After 并使用有界退避，防止多个 worker 同时立即重试形成重试风暴",
    plainRootCause: "任务一次发出的请求太多，对方系统主动限速；不是数据内容错误",
    technicalRootCause: "来源或目标端限流策略拒绝当前吞吐，运行参数超过能力快照或租户配额",
    repairSteps: "读取对端限流头；将 channel 和 batch 降到上次成功值或连接器上限以内；按 Retry-After 退避；只重放失败对象",
    changeDescription: "调整受治理运行参数；如果代码忽略 Retry-After，则修复退避调度和抖动算法并增加并发重试测试",
    verification: "429 不再持续出现；吞吐稳定；失败分片重放成功；没有扩大同步范围或重复已成功对象",
  },
  SCHEMA_DRIFT_DETECTED: {
    feature: "同步任务预检、字段映射和已发布任务再次运行",
    trigger: "源表新增、删除、重命名字段，或字段类型与任务发布时保存的 schema 指纹不同",
    userSymptom: "任务在正式写入前被预检阻断，字段映射页面提示来源结构已变化",
    userMessage: "检测到数据结构变化，请刷新元数据并确认字段映射",
    primaryService: "datasource-management",
    relatedServices: "data-sync、源数据库元数据接口",
    logDetail: "schema fingerprint changed; publishedSchemaHash does not match currentMetadataHash",
    diagnosisFocus: "比较当前元数据、发布版本 schema 指纹、最近成功版本和字段增删改清单",
    developerDiagnosis: "确认元数据规范化顺序稳定，避免字段排序变化造成假漂移；真实改名应进入唯一候选映射而不是直接覆盖发布版本",
    plainRootCause: "源表结构在任务发布后被改过，旧任务仍按原来的字段清单运行，因此系统先停下来避免写错列",
    technicalRootCause: "当前 metadata fingerprint 与不可变发布版本不一致，预检检测到字段集合、顺序或类型差异",
    repairSteps: "刷新元数据；查看差异；唯一且类型兼容的改名可以受治理修复；新增可空字段可忽略；删除必需字段或歧义映射转人工确认；重新预检并发布新版本",
    changeDescription: "修改任务字段映射或发布配置；若是假漂移，修复元数据规范化/哈希算法并增加顺序无关测试",
    verification: "新 schema 指纹稳定；映射无歧义；预检通过；样本执行列数和目标字段一致",
  },
  FIELD_MAPPING_MISSING: {
    feature: "新建同步任务的字段映射步骤和旧任务结构变更后的恢复",
    trigger: "目标必填字段没有来源映射，或源字段改名后旧映射仍指向不存在的字段",
    userSymptom: "字段映射页突出显示未映射字段，运行时则可能出现目标列缺失或写入失败",
    userMessage: "目标字段缺少来源映射，请选择唯一兼容字段或补充转换规则",
    primaryService: "data-sync",
    relatedServices: "datasource-management、目标数据库",
    logDetail: "required target field has no active source mapping; candidateCount evaluated from metadata",
    diagnosisFocus: "检查目标字段是否必填、是否有默认值、当前映射是否引用已删除字段、候选源字段数量和类型兼容性",
    developerDiagnosis: "确认映射修复器只在候选唯一、目标未占用、类型和主键属性一致时自动改名，不能把歧义候选静默取第一项",
    plainRootCause: "任务不知道应该把哪个源字段写入目标字段，继续运行可能把数据写错列，所以系统停止",
    technicalRootCause: "发布映射无法解析到当前元数据中的源字段，且目标字段需要显式值或唯一兼容候选尚未确认",
    repairSteps: "刷新两端元数据；核对字段含义；唯一兼容候选可自动改映射；存在多个候选时由用户选择；保存新版本并重新预检",
    changeDescription: "更新字段映射配置；若候选算法漏掉合法字段，修复类型/序号/主键兼容判断并增加歧义测试",
    verification: "每个目标必填字段有且只有一个来源；映射预览正确；20 行合成数据写入并核对列值",
  },
  NOT_NULL_VIOLATION: {
    feature: "同步任务写入目标表的字段约束校验",
    trigger: "源记录的字段为空，但目标列声明 NOT NULL 且没有数据库默认值或已批准转换",
    userSymptom: "任务读取成功但写入失败，脏数据数量增加，错误详情显示具体目标字段不能为空",
    userMessage: "目标字段不允许为空，请修正字段映射、数据清洗规则或目标默认值",
    primaryService: "data-sync",
    relatedServices: "目标 PostgreSQL、data-quality",
    logDetail: "SQLState=23502 not-null constraint violation; targetColumn required and resolvedValue is null",
    diagnosisFocus: "按 SQLState 23502 定位目标列，检查源字段空值率、映射、转换规则、目标默认值和最近成功配置",
    developerDiagnosis: "确认 writer 没有把空字符串错误转成 null，也没有漏应用已批准默认值；禁止在代码里用固定假值掩盖约束",
    plainRootCause: "源数据这一列没有值，但目标表要求每一行都必须有值",
    technicalRootCause: "写入 SQL 为 NOT NULL 目标列提供了 null，数据库约束在提交时拒绝该行",
    repairSteps: "定位空值来源；修正映射或清洗规则；仅使用业务已经批准的默认值；若确需放宽目标约束则转 DDL 人工审批；重放失败记录",
    changeDescription: "修改映射/转换配置或经审批修改 DDL；若转换器丢值，修复字段转换代码并增加 null/空串边界测试",
    verification: "失败字段不再产生 null；SQLState 23502 消失；脏数据和目标行数与预期一致",
  },
  DATA_TYPE_MISMATCH: {
    feature: "字段映射预检和目标表写入",
    trigger: "源字段类型或实际值无法无损转换成目标字段类型，例如文本日期写入时间列",
    userSymptom: "预检提示类型不兼容，或任务写入阶段返回类型转换失败",
    userMessage: "字段类型不兼容，请调整映射或配置明确的转换规则",
    primaryService: "data-sync",
    relatedServices: "datasource-management、目标数据库",
    logDetail: "SQLState=22P02 invalid input syntax or incompatible sourceTargetType conversion",
    diagnosisFocus: "查看源/目标类型、样本统计、失败值类型摘要、精度长度和已配置转换器，不记录原始敏感值",
    developerDiagnosis: "确认转换器注册表选择了正确 sourceType->targetType 处理器，并区分解析失败和数据库驱动绑定类型错误",
    plainRootCause: "源字段的数据格式和目标列要求不一致，系统无法确定怎样安全转换",
    technicalRootCause: "字段转换或 JDBC 参数绑定无法满足目标类型合同，数据库返回类型/语法错误",
    repairSteps: "配置无损转换；修正日期格式或枚举映射；有损截断、精度下降或语义不明确时转人工；重新预检和样本执行",
    changeDescription: "修改字段转换配置；若合法类型组合未注册，增加转换器并覆盖正常、非法、边界值测试",
    verification: "预检类型矩阵通过；失败样本可转换；目标列类型和写入值统计一致",
  },
  NUMERIC_OVERFLOW: {
    feature: "金额、数量等数值字段写入目标表",
    trigger: "源数据最大值或小数位超过目标 numeric/decimal 的 precision 或 scale",
    userSymptom: "少数大额记录写入失败，普通记录可能已经成功",
    userMessage: "数值超出目标字段可保存范围，系统未进行静默截断",
    primaryService: "data-sync",
    relatedServices: "目标 PostgreSQL、data-quality",
    logDetail: "SQLState=22003 numeric value out of range; target precision or scale exceeded",
    diagnosisFocus: "检查失败字段的最值摘要、目标 precision/scale、超限记录数量和最近成功数据分布",
    developerDiagnosis: "确认 Decimal 转换没有先经过浮点数导致精度膨胀，并检查 JDBC scale 绑定",
    plainRootCause: "目标列的数字格子太小，当前数值放不下",
    technicalRootCause: "目标 numeric precision/scale 无法表示源值，数据库返回 22003",
    repairSteps: "禁止截断；评估调整字段映射、单位换算或目标列精度；DDL 变更必须审批；修复后仅重放失败对象",
    changeDescription: "修改转换规则或经审批扩大目标字段精度；若代码使用浮点中转，改用 Decimal/BigDecimal 并增加极值测试",
    verification: "最大值和最小值均可无损写入；金额聚合与源端一致；没有新增截断告警",
  },
  STRING_TRUNCATION_RISK: {
    feature: "文本字段预检和目标写入",
    trigger: "源字段最大长度超过目标 varchar 长度",
    userSymptom: "任务在预检阶段阻断，或超长记录被标为脏数据但不会被静默裁剪",
    userMessage: "文本长度超过目标字段限制，请调整映射、清洗规则或目标长度",
    primaryService: "data-sync",
    relatedServices: "目标 PostgreSQL、data-quality",
    logDetail: "SQLState=22001 value too long for target column; silentTruncationPrevented=true",
    diagnosisFocus: "查看字段画像 maxLength、目标长度、超限行数和是否存在批准的可逆清洗规则",
    developerDiagnosis: "确认 writer 在数据库报错前执行长度预检，并且没有 substring 静默截断",
    plainRootCause: "源文本比目标字段允许的长度更长",
    technicalRootCause: "目标 varchar 长度合同不足，数据库返回 22001 或预检提前阻断",
    repairSteps: "评估扩大目标字段、改写目标字段、业务确认后清洗或拆分；不允许自动截断；修改后重放失败记录",
    changeDescription: "修改映射/清洗配置或经审批修改 DDL；若代码截断，删除隐式 substring 并增加超长测试",
    verification: "最长文本完整写入或按批准规则处理；源目标长度统计可对账；无 22001",
  },
  FOREIGN_KEY_MISSING: {
    feature: "存在父子表依赖的多对象同步",
    trigger: "子表记录先于对应父表记录写入，或父记录被过滤在本次范围之外",
    userSymptom: "父表可能成功，子表部分记录失败，任务详情显示外键约束错误",
    userMessage: "关联的父记录尚未写入，请检查对象依赖顺序和同步范围",
    primaryService: "data-sync",
    relatedServices: "目标 PostgreSQL、任务 DAG",
    logDetail: "SQLState=23503 foreign key violation; parent key not found before child write",
    diagnosisFocus: "读取约束名、父子对象、任务 DAG、父对象状态、过滤范围和失败键摘要",
    developerDiagnosis: "确认拓扑排序没有遗漏元数据外键边，并检查并发 worker 是否绕过父对象完成屏障",
    plainRootCause: "系统先写了子记录，但它依赖的父记录还不存在",
    technicalRootCause: "对象执行顺序或范围导致目标外键查不到父键，数据库返回 23503",
    repairSteps: "补齐外键元数据；确保父对象先完成；确认父记录在授权范围内；再按父后子的顺序重放失败分片。禁用外键属于高风险人工操作",
    changeDescription: "修改任务 DAG 或范围配置；若拓扑排序代码漏边，修复依赖图构建并增加并发父子表测试",
    verification: "父对象先成功；子表重放后 23503 消失；父子行数和关联完整性检查通过",
  },
  UNIQUE_CONSTRAINT_VIOLATION: {
    feature: "增量同步、失败重放和幂等写入",
    trigger: "同一业务键被重复写入，或重试未识别已经成功提交的回执",
    userSymptom: "任务重试后出现重复键错误，已成功数据不会被系统直接覆盖",
    userMessage: "目标中已存在相同唯一键，请确认这是幂等重放还是业务数据冲突",
    primaryService: "data-sync",
    relatedServices: "目标 PostgreSQL、checkpoint/幂等账本",
    logDetail: "SQLState=23505 unique constraint violation; existingReceipt lookup required",
    diagnosisFocus: "检查约束名、键摘要、上次提交回执、checkpoint、attempt 和目标现有记录来源",
    developerDiagnosis: "确认提交成功与 checkpoint 更新之间是否存在崩溃窗口，并检查 upsert/insert 策略是否符合任务模式",
    plainRootCause: "目标表里已经有相同编号的记录，系统不能确定应该忽略、更新还是报错",
    technicalRootCause: "业务键重复或幂等回执丢失，INSERT 触发 23505",
    repairSteps: "先判断是否为同一次执行的已提交记录；幂等重放可确认成功并前移位点；真实业务冲突转人工决定，不自动覆盖",
    changeDescription: "修复 checkpoint 与提交回执顺序或调整经审核的 upsert 策略；增加提交后崩溃重放测试",
    verification: "同一幂等键只产生一条目标记录；真实冲突仍被拒绝；checkpoint 与回执一致",
  },
  CHECKPOINT_NOT_FOUND: {
    feature: "增量任务恢复、暂停后继续和失败分片重放",
    trigger: "用户点击恢复或系统进入 Recovery，但对象从未保存成功位点，或位点被错误清理",
    userSymptom: "恢复按钮提交后任务没有继续，页面提示找不到安全恢复位置",
    userMessage: "没有可用的恢复位点，需要从安全起点重新执行或由运维确认",
    primaryService: "data-sync",
    relatedServices: "PostgreSQL checkpoint 表、对象台账",
    logDetail: "checkpoint lookup returned empty for execution object; fallbackRequiresAuthorization=true",
    diagnosisFocus: "查询对象台账、checkpoint 表、任务模式、首次执行时间和清理记录，确认是否本来就不应有位点",
    developerDiagnosis: "检查 checkpoint 写入事务是否与批次回执一致，以及保留任务是否误删仍被执行引用的位点",
    plainRootCause: "系统没有找到上次安全停下的位置，所以不能直接从中间继续",
    technicalRootCause: "对象缺少持久 checkpoint 或引用已经失效，恢复状态机无法计算 resume cursor",
    repairSteps: "确认允许的安全起点；全量任务可在授权范围内重跑失败对象；增量任务需人工确认游标；修复位点保留策略后重试",
    changeDescription: "修改 checkpoint 数据或保留配置；若事务漏写，修复回执/位点原子性并增加崩溃恢复测试",
    verification: "恢复请求读取到明确 cursor；重复恢复不丢数据也不重复提交；对象台账收敛",
  },
  CHECKPOINT_STALE: {
    feature: "长时间增量同步和任务失败后继续",
    trigger: "持久位点落后于已经成功提交的目标批次，或指向早于当前配置版本的数据位置",
    userSymptom: "恢复预检提示位点不一致，系统停止以避免重复写入",
    userMessage: "恢复位点与已提交结果不一致，请先完成位点对账",
    primaryService: "data-sync",
    relatedServices: "目标数据库、checkpoint/worker receipt",
    logDetail: "checkpoint timestamp precedes committed worker receipt; replay duplication risk detected",
    diagnosisFocus: "比较 checkpoint 时间、worker 回执、目标提交时间、配置版本和对象 attempt",
    developerDiagnosis: "检查提交事务、回执和 checkpoint 的先后顺序，定位是否存在写目标成功但位点更新失败的窗口",
    plainRootCause: "系统记录的进度比目标数据库实际完成的位置更旧，直接继续可能重复写数据",
    technicalRootCause: "checkpoint 与 authoritative commit receipt 不一致，恢复游标过期",
    repairSteps: "选择最近已确认提交的位点；对唯一键和目标统计做去重验证；更新位点后只重放未确认批次",
    changeDescription: "修正 checkpoint；若代码顺序错误，将目标提交、回执和位点更新纳入可靠事务/补偿流程",
    verification: "位点等于最后确认回执；重放没有重复键；源目标计数和校验和一致",
  },
  KAFKA_BACKLOG_HIGH: {
    feature: "Agent/Java 异步命令、Recovery 事件和同步任务队列消费",
    trigger: "生产消息速度持续高于消费者处理速度，或坏消息反复进入 retry topic",
    userSymptom: "用户提交任务后长时间停在排队中，状态更新明显延迟",
    userMessage: "任务已受理但队列处理延迟较高，系统正在排队",
    primaryService: "Kafka",
    relatedServices: "agent-runtime、python-ai-runtime、data-sync",
    logDetail: "consumer group lag exceeds budget; oldestMessageAgeSeconds growing; retryTopic hot",
    diagnosisFocus: "按 consumer group 查看 partition lag、最老消息、消费速率、实例数、重试/DLT 和处理时延",
    developerDiagnosis: "检查单条消息是否长时间阻塞消费线程、是否缺少幂等导致反复失败，以及分区键是否形成热点",
    plainRootCause: "进入队列的任务比系统处理得快，或者某条坏消息一直重试占住队列",
    technicalRootCause: "消费者吞吐低于生产吞吐或 retry 循环造成 group lag 持续增长",
    repairSteps: "隔离坏消息；确认消费者健康；在容量许可下扩容；优化慢处理；保持 retry 次数有界；不通过删除消息掩盖问题",
    changeDescription: "调整消费者容量/分区或修复阻塞处理代码；增加积压、热点分区和 DLT 回归",
    verification: "lag 和最老消息年龄持续下降；新消息可及时消费；DLT 有明确处置记录",
  },
  OUTBOX_DELIVERY_TIMEOUT: {
    feature: "任务命令、Agent 工具结果和 Recovery 触发事件的可靠投递",
    trigger: "业务事务已经写入 outbox，但 dispatcher 在截止时间内没有获得 broker 确认",
    userSymptom: "页面显示请求已保存，但下游执行迟迟没有开始",
    userMessage: "任务已保存，异步投递暂时延迟，请勿重复创建任务",
    primaryService: "agent-runtime",
    relatedServices: "data-sync、Kafka、PostgreSQL outbox",
    logDetail: "outbox state remains PENDING after delivery deadline; broker acknowledgement missing",
    diagnosisFocus: "查询 outbox 状态、attempt、nextAttemptAt、broker 健康、producer 错误和 consumer result",
    developerDiagnosis: "确认 dispatcher 只重投未确认 outbox，并且消息键稳定；禁止重新执行已经提交的业务事务",
    plainRootCause: "系统已经把任务保存下来，但负责送到消息队列的投递步骤暂时没有成功",
    technicalRootCause: "事务 outbox 持久化成功，Kafka publish acknowledgement 在截止时间内缺失",
    repairSteps: "恢复 broker/网络；重投同一 outbox；检查 producer 权限和 topic；等待 consumer result；不要重新创建业务对象",
    changeDescription: "修复 Kafka 配置或 dispatcher；若状态判断错误，修复 outbox CAS/幂等逻辑并增加确认丢失测试",
    verification: "outbox 进入 DELIVERED；同一消息只消费一次；业务对象没有重复创建",
  },
  CONNECTOR_VERSION_INCOMPATIBLE: {
    feature: "数据源连接器配置、元数据采集和任务执行",
    trigger: "任务配置使用了当前连接器版本不支持的模式或参数",
    userSymptom: "配置可以保存草稿，但预检提示连接器版本不支持该能力",
    userMessage: "当前连接器版本不支持所选模式或参数，请使用兼容配置或升级连接器",
    primaryService: "datasource-management",
    relatedServices: "data-sync、连接器运行时",
    logDetail: "connector capability mismatch; requested option absent from runtime version capability snapshot",
    diagnosisFocus: "比较连接器运行时版本、能力快照、任务配置字段和最近成功版本",
    developerDiagnosis: "检查能力探测是否来自真实运行时而不是静态枚举，以及升级后缓存是否刷新",
    plainRootCause: "当前安装的连接器版本太旧或能力不同，无法理解任务中的某个配置项",
    technicalRootCause: "任务所需 capability 不在当前 connector runtime manifest 中",
    repairSteps: "回滚到上次兼容配置，或在变更窗口升级连接器；刷新能力快照；重新预检",
    changeDescription: "修改任务参数或升级连接器；若能力缓存过期，修复刷新逻辑并增加版本切换测试",
    verification: "能力快照包含所需参数；预检和隔离任务成功；旧版本回滚路径可用",
  },
  TARGET_CAPACITY_EXCEEDED: {
    feature: "目标数据库批量写入和长任务并发执行",
    trigger: "目标连接数、磁盘、I/O 或配额达到阈值",
    userSymptom: "多个任务同时变慢或写入暂停，目标端可能返回连接过多或磁盘不足",
    userMessage: "目标系统容量不足，系统已暂停放大负载并等待恢复",
    primaryService: "data-sync",
    relatedServices: "目标 PostgreSQL、监控系统",
    logDetail: "target capacity guard rejected write; connectionPool or storage threshold exceeded",
    diagnosisFocus: "检查目标连接数、等待事件、磁盘、WAL、I/O、任务并发和配额，不只看单个任务日志",
    developerDiagnosis: "确认连接池归还、批次提交和背压生效，排除连接泄漏或无界队列",
    plainRootCause: "目标数据库当前太忙或空间不足，继续写入会让更多任务失败",
    technicalRootCause: "目标资源指标超过治理阈值，容量门禁或数据库拒绝新写入",
    repairSteps: "降低 channel/batch；暂停非关键任务；释放异常连接；扩容需审批；容量恢复后重放失败对象",
    changeDescription: "调整受治理运行参数或基础设施容量；若连接泄漏，修复资源关闭逻辑并增加压力测试",
    verification: "连接和磁盘回到阈值内；写入延迟稳定；重放成功且无连接持续增长",
  },
  DIRTY_RECORD_THRESHOLD_EXCEEDED: {
    feature: "数据清洗、质量规则和同步任务脏数据控制",
    trigger: "失败或不合规记录比例超过任务配置的停止阈值",
    userSymptom: "任务处理一部分数据后停止，页面显示脏数据数量和规则摘要",
    userMessage: "脏数据超过允许阈值，请检查数据质量规则和失败样本摘要",
    primaryService: "data-sync",
    relatedServices: "data-quality、对象台账",
    logDetail: "dirty record ratio exceeds governed threshold; raw record content omitted",
    diagnosisFocus: "按规则码统计失败数量、比例、字段和时间分布，读取脱敏样本引用，不查看完整敏感行",
    developerDiagnosis: "确认阈值使用实际处理行数作分母，并检查同一失败记录是否被重复计数",
    plainRootCause: "本批数据中不符合规则的记录太多，超过任务允许范围",
    technicalRootCause: "dirtyRecords/processedRecords 超过发布配置阈值，worker 按治理策略停止",
    repairSteps: "定位主要规则；修复上游数据或清洗映射；必要时隔离明确坏行；不得无审批提高阈值掩盖质量问题",
    changeDescription: "修改清洗/映射配置或上游数据；若统计重复，修复计数幂等并增加重试测试",
    verification: "脏数据比例低于阈值；隔离记录可追溯；有效记录计数与源端一致",
  },
  DDL_REQUIRED: {
    feature: "字段映射、目标约束和 schema 演进",
    trigger: "安全修复需要新增/改名字段、扩大长度、修改精度、默认值、非空或外键约束",
    userSymptom: "系统解释了结构差异，但不会自动修改目标表，任务停在需要审批状态",
    userMessage: "修复需要修改目标数据库结构，请由数据库管理员评估并审批",
    primaryService: "data-sync",
    relatedServices: "datasource-management、目标 PostgreSQL、审批中心",
    logDetail: "required remediation classified as DDL; autonomous execution blocked; approvalRequired=true",
    diagnosisFocus: "读取元数据差异、目标约束、受影响数据量、锁表风险、回滚 SQL 和业务窗口",
    developerDiagnosis: "确认系统没有把 ALTER TABLE 包装成低风险工具；DDL 建议必须保留目标对象、影响和验证但不能自动执行",
    plainRootCause: "要解决问题必须改变目标表结构，这可能锁表或影响其他任务，所以系统不会自行修改",
    technicalRootCause: "当前 schema 无法满足数据合同，唯一修复涉及 DDL，超出首次授权盒低风险动作目录",
    repairSteps: "生成变更建议；由 DBA 审核 SQL、锁和回滚；在窗口执行；刷新元数据；重新预检和失败对象重放",
    changeDescription: "经审批执行明确 DDL；若系统错误分类为 DDL，修复元数据差异判断并增加约束回归测试",
    verification: "目标 schema 与批准方案一致；迁移审计完整；预检通过；读写和回滚演练成功",
  },
};

/** 运维作业的通俗说明，保持“标准作业”语义，不把运维手册改造成事故流水账。 */
const OPERATIONS_PLAIN_GUIDES = {
  "服务健康巡检": ["页面大面积出现 502/503 或功能一直加载", "gateway", "actuator health 为 DOWN 或实例数为 0", "某个基础服务没有正常提供能力，先确认是哪一个服务不健康", "检查健康聚合是否把可选依赖误判为致命依赖，并核对 Nacos 旧实例", "恢复服务或依赖，清理失效实例，再执行只读 smoke"],
  "Kafka 积压巡检": ["任务已提交但长时间排队，状态更新明显变慢", "Kafka", "consumer lag 和最老消息年龄持续增长", "进入队列的消息比消费者处理得快，或者坏消息反复重试", "检查消费线程阻塞、分区热点、retry/DLT 和幂等失败", "隔离坏消息，恢复消费者，在容量范围内扩容并观察 lag 回落"],
  "数据库容量巡检": ["列表和任务写入普遍变慢，偶发连接失败", "PostgreSQL", "连接数、锁等待、磁盘或 WAL 达到阈值", "数据库太忙、被锁住或空间不足", "检查慢 SQL、连接泄漏、长事务和索引退化", "终止异常长事务需审批；释放连接、扩容或优化 SQL 后复核"],
  "pgvector 索引巡检": ["知识检索变慢、召回结果异常或维度报错", "python-ai-runtime", "向量维度不一致、索引失效或 ANN 延迟升高", "向量模型输出和数据库索引的规格不一致，或索引需要维护", "核对模型维度、chunk 版本、HNSW 参数和查询计划", "停止错误维度写入，重建受影响索引并跑黄金集 smoke"],
  "Redis 状态巡检": ["会话、实时事件或短期状态偶发丢失", "Redis", "内存淘汰、持久化失败或阻塞客户端增加", "缓存内存不足或某条命令占用时间过长", "检查大 key、无界队列、连接池和过期策略", "限制大对象、恢复持久化、调整容量并验证会话恢复"],
  "对象存储巡检": ["导出文件、报告或归档附件无法下载", "MinIO", "容量不足、上传未完成或权限拒绝", "文件没有成功写入对象存储，或访问策略不允许读取", "检查 bucket、对象键、multipart 状态和签名有效期", "完成/清理挂起上传，修正最小权限并重新生成合成文件"],
  "任务执行巡检": ["任务一直运行、读写量不增长或对象状态互相矛盾", "data-sync", "execution 与 object ledger 长时间无推进", "任务执行器没有继续处理对象，或者状态汇总与对象明细不一致", "按 executionId 检查 worker lease、checkpoint、批次回执和状态聚合", "恢复 worker、修复账本或重放失败对象，不重复成功对象"],
  "调度触发巡检": ["定时任务到点没有产生新的执行记录", "data-sync", "应触发数大于实际触发数或 misfire 未处理", "调度器错过了执行窗口，或任务状态不允许触发", "检查时区、cron、锁、misfire 策略和重复执行保护", "修正调度配置或锁逻辑，补发一次受治理执行并验证下一周期"],
  "Recovery 巡检": ["失败任务一直恢复中，轮次增加但问题没有变化", "agent-runtime", "Recovery cycle 没有新增证据或动作反复", "自动恢复没有找到新的有效修复，正在接近循环上限", "检查模型决策、证据引用、动作 receipt、错误指纹和预算", "同错三次或需越权时停止 Loop，给出人工根因和步骤"],
  "Agent Runtime 巡检": ["Agent 规划停住、Specialist 不更新或模型请求失败", "python-ai-runtime", "session/run/checkpoint 不完整或 Provider degraded", "Agent 的规划状态没有持久化，或外部模型暂时不可用", "检查 LangGraph checkpoint、Provider 健康、Java fact sink 和 Kafka 消费", "恢复依赖后从稳定 checkpoint 继续，不能伪造已完成事实"],
  "RAG 检索巡检": ["回答引用不准确、找不到已知文档或应拒答时仍给结果", "python-ai-runtime", "召回、引用、拒答或范围指标低于门禁", "检索器没有把最相关且允许访问的证据排到前面", "检查 chunk、范围过滤、Embedding、Reranker、MMR 和阈值", "先修范围/引用硬错误，再调召回和重排并重跑黄金集"],
  "授权缓存巡检": ["刚授权仍然 403，或撤权后短时间仍可访问", "gateway", "策略版本与缓存版本不一致", "权限规则已经变化，但网关仍使用旧缓存", "检查失效事件、缓存键范围、TTL 和 permission-admin 决策", "刷新缓存；若事件丢失，修复失效通知并增加撤权测试"],
  "审计完整性巡检": ["操作完成但审计页找不到请求、批准或执行记录", "agent-runtime", "主体、时间、结果或关联 ID 缺失", "业务做完了，但审计链少了一段，无法证明谁在何时做了什么", "检查事务边界、outbox、投影 sink 和低敏字段裁剪", "补偿投影并修复事务/投递逻辑，禁止凭空补造业务成功"],
  "备份结果巡检": ["恢复演练找不到可用备份或校验失败", "PostgreSQL", "备份状态失败、大小异常或校验和不匹配", "备份文件不完整、已损坏或超出保留期", "检查备份任务、对象存储、加密和恢复点", "重新生成备份并做隔离恢复演练，不直接覆盖生产"],
  "证书与 Secret 到期巡检": ["连接突然认证失败或 TLS 握手失败", "gateway", "证书/Secret 接近到期或当前引用版本失效", "访问凭据或证书过期，服务之间无法建立可信连接", "只检查到期时间、引用版本和轮换状态，不输出 Secret 值", "按双人审批轮换并验证新旧切换，随后撤销旧版本"],
  "容器资源巡检": ["服务变慢、反复重启或任务中途被终止", "Docker/Kubernetes", "CPU/内存/句柄达到限制或 OOMKilled", "服务运行资源不够，操作系统终止了进程", "检查容器 limit、堆/线程、泄漏和任务并发", "降低负载、修复泄漏或按容量评审扩容，再跑压力 smoke"],
  "日志采集巡检": ["执行详情没有日志，或日志时间明显滞后", "observability", "采集延迟、丢弃量或解析失败增加", "业务服务产生了日志，但采集链没有及时保存或索引", "检查采集 agent、格式解析、背压和脱敏过滤", "恢复采集并补采允许范围内日志，敏感原文不得补入"],
  "告警投递巡检": ["系统发生故障但值班人员没有收到通知", "observability", "告警已触发但路由、聚合或送达失败", "监控发现了问题，但通知没有到达正确的人", "检查规则、静默窗口、路由、通道回执和确认状态", "修复路由或通道，发送合成测试告警并确认闭环"],
  "时间同步巡检": ["跨服务时间线顺序错乱，日志看起来先完成后开始", "observability", "节点时间偏差超过事件排序容忍值", "服务器时钟不一致，导致同一请求的日志顺序看起来错误", "检查 NTP、数据库时间、容器时区和 occurredAt 生成", "恢复时间同步并使用稳定 sequence 辅助排序，不改写历史事实"],
  "发布后巡检": ["发布后某个功能立即出现新错误或性能下降", "gateway", "新版本错误率、迁移或关键 smoke 异常", "刚发布的代码或配置改变了原本正常的行为", "对比发布前后版本、配置、数据库迁移和错误堆栈", "停止放量，按已审核方案回滚，再用回归测试复现并修复"],
};

/** 返回错误码的完整诊断画像；未登记错误码必须显式使用保守兜底，避免生成 undefined。 */
export function diagnosticProfileFor(errorCode) {
  return ERROR_DIAGNOSTIC_PROFILES[errorCode] || {
    feature: "当前任务操作",
    trigger: "用户提交操作后服务端返回未分类错误",
    userSymptom: "页面显示操作失败并提供 traceId",
    userMessage: "操作未完成，请联系运维并提供 traceId",
    primaryService: "data-sync",
    relatedServices: "Gateway 与相关业务服务",
    logDetail: "unclassified governed operation failure",
    diagnosisFocus: "先按 traceId 还原调用链，再依据最早权威错误定位责任服务",
    developerDiagnosis: "补充稳定错误码和最小回归测试，禁止只依赖异常文本",
    plainRootCause: "系统遇到了尚未分类的问题",
    technicalRootCause: "当前错误尚未映射到稳定诊断画像",
    repairSteps: "保留现场并转人工排查，不自动执行副作用",
    changeDescription: "定位后补充配置或代码修复以及回归测试",
    verification: "原场景成功且新增稳定错误码与证据",
  };
}

/** 返回标准运维作业的通俗指南。 */
export function operationsGuideFor(jobName) {
  const values = OPERATIONS_PLAIN_GUIDES[jobName] || [
    "用户看到功能响应变慢或状态长时间不更新",
    "observability",
    "相关健康、指标或结构化日志偏离正常基线",
    "某个依赖或服务状态异常，需要先找到最早失败点",
    "按 traceId 检查调用链和最近变更，并补稳定错误码",
    "恢复依赖或回滚最近变更，再执行只读验证",
  ];
  const logCommand = values[1] === "Docker/Kubernetes"
    ? "docker compose ps；docker stats --no-stream"
    : composeLogCommandFor(values[1]);
  return {
    userSymptom: values[0],
    primaryService: values[1],
    logDetail: values[2],
    plainJudgment: values[3],
    developerAdvice: values[4],
    handling: values[5],
    logCommand,
  };
}

/** 需要凭据、权限或 DDL 的错误必须退出无人值守 Loop。 */
export function isManualRecoveryError(errorCode) {
  return MANUAL_RECOVERY_ERROR_CODES.has(errorCode);
}

/**
 * 生成可复制但不含真实数据的结构化日志摘录。
 *
 * 日志保留时间、服务、trace/task/execution/object 和稳定错误码，足够在多微服务之间关联；地址、SQL、
 * 凭据和原始业务行都只显示是否已脱敏，不进入评测资产。
 */
export function buildDiagnosticLogExcerpt(profile, context) {
  return `${context.occurredAt} level=ERROR service=${profile.primaryService} traceId=${context.traceId} taskId=${context.taskId} executionId=${context.executionId} objectId=${context.objectId} errorCode=${context.errorCode} message="${profile.logDetail}" sensitivePayloadOmitted=true`;
}

/** 给出平台日志入口、真实 API 形状和容器日志命令，避免只写“查看日志”。 */
export function buildDiagnosticLogLocation(profile, context) {
  const logCommand = composeLogCommandFor(profile.primaryService);
  return `平台入口：数据同步 > 执行详情 > 运行日志，按 traceId=${context.traceId} 过滤；API：GET /api/sync/sync-tasks/${context.taskId}/executions/${context.executionId}/logs；服务日志：${logCommand}，再按同一 traceId 检索。`;
}

/**
 * 生成从用户报错到技术根因的逐步定位路径。
 *
 * 顺序固定为“页面标识 -> 权威执行日志 -> 生命周期图 -> 责任服务日志 -> 配置/元数据/SQL 证据 ->
 * 最近成功基线 -> 结论”，保证运维人员能够复现判断过程，而不是先写结论再倒推证据。
 */
export function buildDiagnosisPath(profile, context) {
  return [
    `1. 先从页面复制 traceId=${context.traceId}、taskId=${context.taskId} 和 executionId=${context.executionId}。`,
    `2. 调用执行日志接口，找到最早出现的 errorCode=${context.errorCode}，确认不是后续级联报错。`,
    `3. 查询 /api/sync/sync-tasks/${context.taskId}/executions/${context.executionId}/lifecycle-graph，确认失败停在 Agent、Kafka、worker 还是 Recovery。`,
    `4. 进入 ${profile.primaryService} 日志，按 traceId 检索关键行；同时核对关联服务 ${profile.relatedServices}。`,
    `5. 围绕“${profile.diagnosisFocus}”读取配置、元数据、SQLState、指标或依赖健康证据。`,
    `6. 与最近一次成功配置 ${context.lastSuccessfulConfigVersion} 和成功 execution 对比，排除本次变更之外的噪声。`,
    `7. 只有日志、状态和配置证据一致时才确认：${profile.technicalRootCause}。`,
  ].join(" ");
}
