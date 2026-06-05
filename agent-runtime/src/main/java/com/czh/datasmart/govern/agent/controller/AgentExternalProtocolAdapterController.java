/**
 * @Author : Cui
 * @Date: 2026/06/06 01:19
 * @Description DataSmart Govern Backend - AgentExternalProtocolAdapterController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentExternalProtocolAdapterPreviewResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentExternalProtocolAdapterPreviewService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部 Agent 协议适配控制器。
 *
 * <p>本控制器当前只提供 MCP/A2A adapter preview，目标是让前端、运维、Python Runtime 和架构评审者
 * 看到 DataSmart 内部能力将如何映射到外部 Agent 协议。它不是 MCP JSON-RPC Server，也不是 A2A
 * task endpoint，不接受工具调用，不读取资源正文，不返回 prompt 正文。</p>
 *
 * <p>为什么单独建控制器：
 * runtime event projection、tool registry、skill registry 都已经有各自控制器。如果把外部协议预览继续
 * 塞到其中任何一个控制器里，职责会变得含混：外部协议不是纯事件查询，也不是单纯工具目录或 Skill 目录。
 * 单独控制器可以把“协议适配层”作为独立产品能力演进，后续再逐步加入 MCP tools/list、A2A Agent Card、
 * Agent Registry、签名校验和协议调用审计。</p>
 */
@RestController
@RequestMapping({
        "/agent-runtime/protocol-adapters",
        "/api/agent/protocol-adapters"
})
@RequiredArgsConstructor
public class AgentExternalProtocolAdapterController {

    private final AgentExternalProtocolAdapterPreviewService previewService;

    /**
     * 查询 MCP/A2A 外部协议适配预览。
     *
     * <p>路由语义：
     * `/mcp-a2a/preview` 表示“同时查看 MCP 与 A2A 的映射草案”。当前选择合并预览，是因为 DataSmart 的
     * Agent 能力天然同时涉及“Agent 到工具/资源/Prompt”（MCP）和“Agent 到 Agent 协作/委派”（A2A）。
     * 如果过早把两者拆成完全独立接口，容易让调用方忽略它们共享的权限、审批、审计和租户边界。</p>
     *
     * <p>参数语义：
     * - `domain`：按治理域过滤 Skill，例如 DATA_QUALITY、TASK_MANAGEMENT；
     * - `riskLevel`：按风险等级过滤 Skill，例如 LOW、MEDIUM、HIGH；
     * - `includeNonReady`：是否在诊断视图中包含非 READY 或禁用 Skill。默认 false，适合运行时安全视角；
     *   管理员排查为什么某些能力不能对外暴露时，可传 true。</p>
     *
     * <p>返回行为：
     * 返回 `PlatformApiResponse` 包装的只读 preview。响应中只包含低敏能力元数据、协议映射说明和下一步建议，
     * 不包含工具实参、prompt 正文、资源正文、下游 endpoint、模型输出、密钥或客户样例数据。</p>
     *
     * @param domain 可选治理域过滤
     * @param riskLevel 可选风险等级过滤
     * @param includeNonReady 是否包含非 READY Skill 的诊断视图
     * @param traceId 链路追踪 ID，透传给统一响应
     */
    @GetMapping("/mcp-a2a/preview")
    public PlatformApiResponse<AgentExternalProtocolAdapterPreviewResponse> previewMcpA2aAdapter(
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "riskLevel", required = false) String riskLevel,
            @RequestParam(value = "includeNonReady", required = false, defaultValue = "false") Boolean includeNonReady,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                previewService.buildPreview(domain, riskLevel, includeNonReady),
                traceId
        );
    }
}
