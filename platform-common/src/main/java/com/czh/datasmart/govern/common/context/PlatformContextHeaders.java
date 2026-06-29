/**
 * @Author : Cui
 * @Date: 2026/04/25 22:30
 * @Description DataSmart Govern Backend - PlatformContextHeaders.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.context;

/**
 * 平台上下文 HTTP Header 常量。
 *
 * gateway 后续应该负责生成、校验和透传这些 Header；各业务微服务只消费这些上下文，不应该各自发明不同名称。
 * 统一 Header 的意义在于：
 * 1. 网关、服务、日志、审计、指标可以使用同一套字段关联请求；
 * 2. 服务间调用时可以稳定传递租户、操作者、traceId；
 * 3. 后续接入 OpenTelemetry 或集中式审计时，不需要逐模块做字段适配。
 */
public final class PlatformContextHeaders {

    public static final String TRACE_ID = "X-DataSmart-Trace-Id";
    public static final String TENANT_ID = "X-DataSmart-Tenant-Id";
    public static final String ACTOR_ID = "X-DataSmart-Actor-Id";
    public static final String ACTOR_ROLE = "X-DataSmart-Actor-Role";
    public static final String ACTOR_TYPE = "X-DataSmart-Actor-Type";
    public static final String SOURCE_SERVICE = "X-DataSmart-Source-Service";
    public static final String WORKSPACE_ID = "X-DataSmart-Workspace-Id";
    public static final String REQUEST_SOURCE = "X-DataSmart-Request-Source";

    /**
     * 服务账号主体 ID。
     *
     * <p>当请求由机器身份发起时，`ACTOR_ID` 通常已经是服务账号自身 ID；但在“服务账号代表用户执行”
     * 的链路里，审计系统还需要明确保存机器主体。该 Header 允许上游受信网关、任务执行器或 Agent Runtime
     * 显式声明服务账号 actorId。外部客户端不能自报该字段，必须由 gateway 清理后基于可信身份或受信上游重建。</p>
     */
    public static final String SERVICE_ACCOUNT_ACTOR_ID = "X-DataSmart-Service-Account-Actor-Id";

    /**
     * 服务账号可读编码。
     *
     * <p>actorId 适合数据库关联，但事故排查时更需要稳定的可读标识，例如 `datasmart-agent-runtime`、
     * `datasmart-sync-worker`。该字段只允许保存低敏主体编码，不允许保存 client secret、token、证书指纹或内部 endpoint。</p>
     */
    public static final String SERVICE_ACCOUNT_CODE = "X-DataSmart-Service-Account-Code";

    /**
     * 被服务账号代表的业务主体。
     *
     * <p>Agent 工具执行、data-sync worker、task-management 补偿任务都可能出现“机器账号代表某个用户或任务”
     * 执行动作的场景。该字段用于审计责任链：谁实际发起 HTTP 调用，谁是被代表的业务主体。它不应承载 prompt、
     * SQL、工具参数、样本数据或任何高敏业务正文。</p>
     */
    public static final String REPRESENTED_ACTOR_ID = "X-DataSmart-Represented-Actor-Id";

    /**
     * 服务账号委托类型。
     *
     * <p>推荐值如 `SERVICE_ACCOUNT_ON_BEHALF_OF_ACTOR`、`SYSTEM_COMPENSATION`、`SCHEDULED_JOB`。
     * permission-admin 不应因为该字段存在就自动放行，它只作为审计、策略解释和未来审批流的低敏上下文。</p>
     */
    public static final String DELEGATION_TYPE = "X-DataSmart-Delegation-Type";

    /**
     * 服务账号委托原因摘要。
     *
     * <p>该字段用于解释为什么机器身份需要代表上游主体调用权限中心，例如“Agent 已确认工具节点入箱”。
     * 它必须保持低敏、短文本，不允许写入 prompt、SQL、工具参数值、模型输出、样本数据或客户业务正文。</p>
     */
    public static final String DELEGATION_REASON = "X-DataSmart-Delegation-Reason";

    /**
     * 调用方期望沿用的权限策略版本。
     *
     * <p>该字段为后续“预检时命中 A 版本，执行时必须仍然是 A 版本”的强一致权限链路预留。
     * 当前 permission-admin 会返回实际命中的 policyVersion；未来执行器可把预检版本回传，权限中心再判定是否过期。</p>
     */
    public static final String REQUESTED_POLICY_VERSION = "X-DataSmart-Requested-Policy-Version";

    /**
     * 当前租户套餐编码。
     *
     * <p>该字段用于智能网关做能力裁剪、工具预算、缓存隔离和审计解释。
     * 例如 STANDARD、PROFESSIONAL、ENTERPRISE 等套餐可能拥有不同 Skill 数量、自动执行额度和高风险操作能力。
     * 当前 gateway 先从配置中写入默认值，后续应由 permission-admin 或租户配置中心返回真实套餐事实。</p>
     */
    public static final String TENANT_PLAN_CODE = "X-DataSmart-Tenant-Plan-Code";

    /**
     * 当前 workspace 风险等级。
     *
     * <p>workspace 风险会影响高风险 Skill 是否可见、工具预算是否收紧、模型缓存是否允许跨请求复用。
     * 该 Header 必须由 gateway 或受信控制面写入，终端不能自报 NORMAL 来绕过 HIGH_RISK workspace 策略。</p>
     */
    public static final String WORKSPACE_RISK_LEVEL = "X-DataSmart-Workspace-Risk-Level";

    /**
     * 工具预算策略版本。
     *
     * <p>会话级 READY Skill cache 必须把预算策略版本纳入 key。原因是同一个租户、角色和项目下，
     * 如果工具预算从“最多自动 3 个工具”调整为“最多自动 1 个工具”，可见 Skill 和后续工具暴露都可能变化。
     * 缓存 key 不包含策略版本会导致旧能力边界被错误复用。</p>
     */
    public static final String TOOL_BUDGET_POLICY_VERSION = "X-DataSmart-Tool-Budget-Policy-Version";

    /**
     * gateway 传给 Python Runtime 的工具治理策略 envelope。
     *
     * <p>该 Header 用于承载一份经过 gateway 清理、permission-admin 评估、并由 HMAC 签名保护的低敏 JSON 快照。
     * 推荐结构包含 `toolCallBudget` 与 `toolExecutionReadinessPolicy` 两个对象，分别服务模型工具候选预算
     * 和执行前 readiness 判断。</p>
     *
     * <p>安全边界非常重要：该 Header 只能包含策略数字、枚举、布尔开关、策略版本和 influenceCodes，
     * 不能包含 prompt、SQL、工具参数值、样本数据、模型输出、凭证、权限对象明细或内部 endpoint。
     * 它必须进入 gateway -> Python Runtime 签名原文，否则 Python 不应把它升级为 trustedControlPlane。</p>
     */
    public static final String TOOL_POLICY_ENVELOPE = "X-DataSmart-Tool-Policy-Envelope";

    /**
     * gateway 生成的 Skill 可见性缓存协议版本。
     *
     * <p>该协议不是缓存响应体，而是给 Python Runtime 一个“当前控制面事实快照可以如何分组复用”的低敏提示。
     * 版本字段用于未来调整 key 拼接规则时做灰度兼容。</p>
     */
    public static final String SKILL_VISIBILITY_CACHE_VERSION = "X-DataSmart-Skill-Visibility-Cache-Version";

    /**
     * gateway 生成的 Skill 可见性缓存 key。
     *
     * <p>该值是控制面事实快照的 SHA-256 摘要，不包含 prompt、objective、SQL、工具参数或完整权限清单。
     * Python Runtime 会在此基础上继续拼接 projectId、sessionId 和 Manifest/本地注册表指纹，形成最终缓存 key。</p>
     */
    public static final String SKILL_VISIBILITY_CACHE_KEY = "X-DataSmart-Skill-Visibility-Cache-Key";

    /**
     * Skill 可见性缓存 TTL 秒数。
     *
     * <p>短 TTL 用于平衡性能与权限一致性：权限、套餐或 workspace 风险变化后，缓存最多只在短窗口内保留。
     * 更严格的生产环境还应叠加权限策略变更事件主动失效。</p>
     */
    public static final String SKILL_VISIBILITY_CACHE_TTL_SECONDS = "X-DataSmart-Skill-Visibility-Cache-Ttl-Seconds";

    /**
     * Skill 可见性缓存范围说明。
     *
     * <p>当前推荐值是 session-ready-skill-admission，表示缓存的是“某个会话控制面边界下的 Skill 准入结果”，
     * 不是完整 AgentPlan、模型输出或工具执行结果。</p>
     */
    public static final String SKILL_VISIBILITY_CACHE_SCOPE = "X-DataSmart-Skill-Visibility-Cache-Scope";

    /**
     * gateway -> Python AI Runtime 服务间签名协议版本。
     *
     * <p>该签名不是给浏览器或普通业务客户端使用的。它只用于 Python Runtime 判断：
     * “当前收到的 X-DataSmart-* 控制面 Header 是否确实由统一 gateway 清理、重建并签名”。
     *
     * <p>版本字段必须进入签名原文，便于未来从 HMAC-SHA256 v1 平滑升级到新的规范，
     * 而不是在多个服务里悄悄改变字符串拼接顺序，导致联调和灰度环境难以排查。
     */
    public static final String GATEWAY_SIGNATURE_VERSION = "X-DataSmart-Gateway-Signature-Version";

    /**
     * gateway 生成签名时使用的 epoch milliseconds 时间戳。
     *
     * <p>Python Runtime 会检查时间窗口，拒绝明显过旧的签名，降低请求被截获后长期重放的风险。
     */
    public static final String GATEWAY_SIGNATURE_TIMESTAMP = "X-DataSmart-Gateway-Signature-Timestamp";

    /**
     * 单次请求随机 nonce。
     *
     * <p>当前 Python Runtime 先校验 nonce 存在并把它绑定进签名原文。后续接入 Redis 后，可以进一步保存
     * 短 TTL nonce 使用记录，拒绝时间窗口内的重复重放请求。
     */
    public static final String GATEWAY_SIGNATURE_NONCE = "X-DataSmart-Gateway-Signature-Nonce";

    /**
     * 签名密钥标识。
     *
     * <p>keyId 不是秘密，它用于密钥轮换。未来 Python Runtime 可以同时保留 current/previous 两把密钥，
     * 让 gateway 与 Python Runtime 在滚动升级期间仍然可以平滑验证请求。
     */
    public static final String GATEWAY_SIGNATURE_KEY_ID = "X-DataSmart-Gateway-Signature-Key-Id";

    /**
     * gateway 对可信上下文快照计算出的 URL-safe Base64 HMAC-SHA256 签名。
     */
    public static final String GATEWAY_SIGNATURE = "X-DataSmart-Gateway-Signature";

    /**
     * 权限中心判定出的数据范围级别。
     *
     * <p>该 Header 通常由 gateway 根据 permission-admin 的判定结果写入，下游业务服务只消费它。
     * 典型取值包括 SELF、PROJECT、TENANT、PLATFORM。
     */
    public static final String DATA_SCOPE_LEVEL = "X-DataSmart-Data-Scope-Level";

    /**
     * 权限中心返回的数据范围表达式。
     *
     * <p>表达式描述“为什么是这个范围”或“未来应该如何精确过滤”，例如 owner_id=${actorId}。
     * gateway 不解析表达式，业务服务也不能直接拼接到 SQL；正确做法是把它翻译成安全的查询构造器条件。
     */
    public static final String DATA_SCOPE_EXPRESSION = "X-DataSmart-Data-Scope-Expression";

    /**
     * 权限中心物化后的项目授权集合。
     *
     * <p>PROJECT 数据范围如果只下发 `project_id IN ${actorProjectIds}` 这种表达式，下游服务仍然不知道
     * `${actorProjectIds}` 到底是多少。该 Header 用逗号分隔的项目 ID 列表承载 permission-admin 已经计算好的授权快照，
     * 例如 `101,102,205`。
     *
     * <p>安全边界：该 Header 必须由 gateway 在调用 permission-admin 后写入；业务服务不能相信外部客户端直传的值。
     */
    public static final String AUTHORIZED_PROJECT_IDS = "X-DataSmart-Authorized-Project-Ids";

    /**
     * 当前访问是否需要审批。
     *
     * <p>查询链路可以先把它作为上下文透传和审计字段；高风险写操作后续可以用它进入审批流。
     */
    public static final String APPROVAL_REQUIRED = "X-DataSmart-Approval-Required";

    private PlatformContextHeaders() {
        throw new UnsupportedOperationException("PlatformContextHeaders 是常量类，不允许实例化");
    }
}
