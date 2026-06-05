/**
 * @Author : Cui
 * @Date: 2026/06/06 01:32
 * @Description DataSmart Govern Backend - AgentExternalProtocolStaticPreviewCatalog.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentExternalProtocolMappingView;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpPromptPreviewView;
import com.czh.datasmart.govern.agent.controller.dto.AgentMcpResourcePreviewView;

import java.util.List;

/**
 * MCP/A2A 外部协议预览中的静态目录。
 *
 * <p>该类专门承载“不依赖请求过滤条件，也不依赖当前 Skill/Tool 动态目录”的静态内容：
 * MCP Resource 目录、MCP Prompt 目录、内部概念到外部协议概念的映射说明、协议参考链接和产品扩展建议。
 * 这些内容如果继续放在 `AgentExternalProtocolAdapterPreviewService` 里，会让主服务在完成一次小功能后就超过
 * 500 行，不符合当前项目的解耦规范。因此这里把静态 catalog 拆出，让主服务只负责聚合事实源和转换动态目录。</p>
 *
 * <p>为什么不把这些内容放到配置文件：
 * 当前阶段这些目录仍是产品架构草案，且需要大量中文说明帮助学习理解。先放在 Java catalog 中能让测试、
 * 代码注释和业务语义保持在同一处。后续如果 MCP/A2A 进入可运营阶段，再把 Prompt 模板、Resource 可见性、
 * Agent Card 扩展字段迁移到数据库或配置中心，会更自然。</p>
 */
final class AgentExternalProtocolStaticPreviewCatalog {

    private AgentExternalProtocolStaticPreviewCatalog() {
        throw new IllegalStateException("静态协议预览目录不需要实例化");
    }

    /**
     * 构建 MCP Resource 目录。
     *
     * <p>这些资源都是“控制面资源”，例如 Skill Manifest、工具目录、runtime event、模型路由快照。
     * 它们对 Agent 很有价值，但正文可能包含租户、项目、运行状态和审计信息，所以当前只输出目录。
     * 后续如果实现 resources/read，必须先接 permission-admin、脱敏策略、分页和审计事件。</p>
     */
    static List<AgentMcpResourcePreviewView> buildMcpResources() {
        return List.of(
                resource("datasmart://agent-runtime/skill-publication-manifest",
                        "skill-publication-manifest",
                        "Skill Publication Manifest",
                        "DataSmart Agent Skill 发布目录摘要，用于判断哪些能力可被运行时或外部 Agent 发现。",
                        "application/json",
                        "agent:skill:read"),
                resource("datasmart://agent-runtime/tool-descriptors",
                        "tool-descriptors",
                        "Tool Descriptor Catalog",
                        "Agent 工具描述符目录摘要，用于协议适配和工具治理，不包含工具实参。",
                        "application/json",
                        "agent:tool:read"),
                resource("datasmart://agent-runtime/runtime-events",
                        "runtime-events",
                        "Runtime Event Timeline",
                        "Agent 运行事件时间线摘要，用于审计、回放和故障诊断。",
                        "application/json",
                        "agent:runtime-event:read"),
                resource("datasmart://agent-runtime/model-gateway-routing-snapshots",
                        "model-gateway-routing-snapshots",
                        "Model Gateway Routing Snapshots",
                        "模型网关路由决策摘要，用于解释 provider 选择、fallback 和预算状态。",
                        "application/json",
                        "agent:model-gateway:read"),
                resource("datasmart://agent-runtime/handoff-dags",
                        "handoff-dags",
                        "Agent Handoff DAG",
                        "Master Agent、Specialist、Guardrail、Tool Control 与二轮反馈的协作结构摘要。",
                        "application/json",
                        "agent:handoff-dag:read")
        );
    }

    private static AgentMcpResourcePreviewView resource(String uri,
                                                       String name,
                                                       String title,
                                                       String description,
                                                       String mimeType,
                                                       String requiredPermission) {
        return new AgentMcpResourcePreviewView(
                uri,
                name,
                title,
                description,
                mimeType,
                false,
                false,
                requiredPermission,
                "METADATA_ONLY_NO_TEXT_NO_BINARY_NO_SAMPLE_DATA"
        );
    }

    /**
     * 构建 MCP Prompt 目录。
     *
     * <p>Prompt 目录只描述“有哪些用户可选择的治理工作流入口”。真实 prompt 正文可能包含系统策略、
     * 角色边界、合规规则和防注入指令，不能在 preview 中返回。后续 prompts/get 也必须按角色授权。</p>
     */
    static List<AgentMcpPromptPreviewView> buildMcpPrompts() {
        return List.of(
                prompt("datasmart.governance.task-planning",
                        "治理任务规划",
                        "把用户的数据治理目标转成可审批、可追踪、可恢复的任务草案。",
                        List.of("tenantId", "projectId", "businessGoal", "riskTolerance")),
                prompt("datasmart.data-quality.rule-design",
                        "数据质量规则设计",
                        "根据元数据、历史异常和业务口径生成质量规则草案。",
                        List.of("tenantId", "projectId", "datasourceRef", "qualityGoal")),
                prompt("datasmart.datasource.metadata-summary",
                        "数据源元数据总结",
                        "帮助用户理解数据源结构、字段含义、采集状态和潜在治理风险。",
                        List.of("tenantId", "projectId", "datasourceRef")),
                prompt("datasmart.compliance.review-checklist",
                        "合规复核清单",
                        "为高风险治理动作生成审批前检查项和审计关注点。",
                        List.of("tenantId", "projectId", "operationType", "riskLevel"))
        );
    }

    private static AgentMcpPromptPreviewView prompt(String name,
                                                   String title,
                                                   String description,
                                                   List<String> argumentNames) {
        return new AgentMcpPromptPreviewView(
                name,
                title,
                description,
                argumentNames,
                true,
                "METADATA_ONLY_NO_MESSAGES_NO_TEMPLATE_BODY"
        );
    }

    /**
     * 构建内部概念到外部协议概念的映射说明。
     *
     * <p>这些说明用于帮助后续实现者理解：MCP 和 A2A 不应该替代内部治理链路，而应该成为受控协议入口。
     * 每一条 mapping 都同时描述 MCP 侧、A2A 侧和 DataSmart 内部执行边界。</p>
     */
    static List<AgentExternalProtocolMappingView> buildMappings() {
        return List.of(
                new AgentExternalProtocolMappingView(
                        "Skill Publication Manifest",
                        "MCP prompts/resources catalog",
                        "A2A Agent Card skills",
                        "Skill 是 DataSmart 的能力包，适合先作为外部 Agent 可发现能力，而不是直接变成工具执行入口。",
                        "Skill 选择后仍必须回到 Java/Python 受控编排、权限预检、审批和审计链路。"
                ),
                new AgentExternalProtocolMappingView(
                        "Agent Tool Registry",
                        "MCP tools/list",
                        "A2A skill implementation details hidden behind task handling",
                        "工具目录可映射为 MCP Tool，但 preview 阶段只暴露低敏 schema 引用和治理摘要。",
                        "真实 tools/call 必须经过 tool preflight、sandbox、confirmation、outbox 和 runtime event。"
                ),
                new AgentExternalProtocolMappingView(
                        "Handoff DAG",
                        "MCP resource for collaboration topology",
                        "A2A delegation and multi-turn task collaboration",
                        "Handoff DAG 描述 Master、Specialist、Guardrail、Tool Control、Feedback 的协作结构。",
                        "外部协议只能观察或委派，不能直接修改 DAG 或绕过 Guardrail。"
                ),
                new AgentExternalProtocolMappingView(
                        "Runtime Event Projection",
                        "MCP resource for timeline and replay",
                        "A2A task history and status observation",
                        "事件投影让外部协议调用具备可解释、可回放、可审计的运行轨迹。",
                        "事件只写低敏摘要，不写 prompt、工具实参、资源正文、模型输出或密钥。"
                ),
                new AgentExternalProtocolMappingView(
                        "Model Gateway Routing",
                        "MCP resource for model routing diagnostics",
                        "A2A agent capability and quality diagnostics",
                        "模型路由解释 provider 选择、fallback、预算和缓存计划，帮助外部 Agent 理解执行质量。",
                        "协议适配层不能直接改写模型路由，只能读取低敏 projection 或请求受控配置变更。"
                )
        );
    }

    static List<String> protocolReferenceUrls() {
        return List.of(
                "https://modelcontextprotocol.io/specification/2025-11-25/server/tools",
                "https://modelcontextprotocol.io/specification/2025-11-25/server/resources",
                "https://modelcontextprotocol.io/specification/2025-11-25/server/prompts",
                "https://a2a-protocol.org/latest/specification/"
        );
    }

    static List<String> productExpansionNotes() {
        return List.of(
                "后续真实 MCP Server 应先支持只读 tools/list、resources/list、prompts/list，再逐步接 tools/call。",
                "A2A Agent Card 应区分 public card 与 authenticated extended card，扩展卡片必须经过权限校验。",
                "外部协议调用需要独立限流、租户配额、来源签名、幂等键、审计 outbox 和异常回放。",
                "高风险工具应默认进入 dry-run、人工确认、审批单和二轮解释，而不是直接允许外部 Agent 调用。",
                "未来可以引入 Agent Registry，把 DataSmart Master Agent、专项治理 Agent 和外部客户 Agent 做可信发现。"
        );
    }
}
