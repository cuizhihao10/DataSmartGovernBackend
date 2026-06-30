/**
 * @Author : Cui
 * @Date: 2026/06/30 23:20
 * @Description DataSmart Govern Backend - AgentSkillPublicationLifecycleController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationDraftCreateRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationLifecycleActionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationLifecycleQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationLifecycleView;
import com.czh.datasmart.govern.agent.service.skill.AgentSkillPublicationLifecycleService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Skill 发布生命周期控制器。
 *
 * <p>该控制器负责 Skill Marketplace 写侧管理：创建草稿、提交审核、审核通过、审核拒绝、下线和查询。
 * 它与 {@link AgentSkillRegistryController} 的职责不同：
 * - RegistryController 解决“现有 Skill 能力目录怎么被读取”；</p>
 * <p>- 本控制器解决“新 Skill 如何经过治理流程成为可发布能力”。</p>
 *
 * <p>路由同时暴露 `/agent-runtime/...` 和 `/api/agent/...` 两组路径：
 * 前者适合服务内直连或本地调试，后者适合统一 gateway 转发。生产环境应让 gateway 注入租户、操作者、角色和 traceId，
 * 业务服务只消费可信上下文，而不是相信浏览器自报身份。</p>
 */
@RestController
@RequestMapping({"/agent-runtime/skills/publications", "/api/agent/skills/publications"})
@RequiredArgsConstructor
public class AgentSkillPublicationLifecycleController {

    private final AgentSkillPublicationLifecycleService lifecycleService;

    /**
     * 创建 Skill 发布草稿。
     *
     * <p>该路由只接受低敏元数据，不接受 prompt、SQL、工具参数值、样本数据、模型输出或脚本正文。
     * 返回的 DRAFT 发布单不能被运行时直接消费，必须先提交审核并通过。</p>
     */
    @PostMapping("/drafts")
    public PlatformApiResponse<AgentSkillPublicationLifecycleView> createDraft(
            @Valid @RequestBody AgentSkillPublicationDraftCreateRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String tenantId,
            @RequestHeader(value = "X-DataSmart-Project-Id", required = false) String projectId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        AgentSkillPublicationLifecycleView view =
                lifecycleService.createDraft(request, tenantId, projectId, actorId);
        return PlatformApiResponse.success("Skill 发布草稿已创建，尚未进入运行时目录", view, traceId);
    }

    /**
     * 提交 Skill 发布审核。
     *
     * <p>提交动作会触发服务层发布策略校验：审计、租户隔离、项目隔离、权限声明和高风险人工审批策略必须齐备。
     * 这一步可以理解为“从编辑态进入治理态”，后续管理员才能审核。</p>
     */
    @PostMapping("/{publicationId}/submit-review")
    public PlatformApiResponse<AgentSkillPublicationLifecycleView> submitForReview(
            @PathVariable("publicationId") String publicationId,
            @Valid @RequestBody AgentSkillPublicationLifecycleActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        AgentSkillPublicationLifecycleView view =
                lifecycleService.submitForReview(publicationId, request, actorId);
        return PlatformApiResponse.success("Skill 发布单已提交审核", view, traceId);
    }

    /**
     * 审核通过 Skill 发布单。
     *
     * <p>审核通过后状态变为 READY。READY 代表可进入发布目录，但不替代运行前的权限、readiness、HITL 和工具执行审计。</p>
     */
    @PostMapping("/{publicationId}/approve")
    public PlatformApiResponse<AgentSkillPublicationLifecycleView> approve(
            @PathVariable("publicationId") String publicationId,
            @Valid @RequestBody AgentSkillPublicationLifecycleActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        AgentSkillPublicationLifecycleView view =
                lifecycleService.approve(publicationId, request, actorId, actorRole);
        return PlatformApiResponse.success("Skill 发布单已审核通过", view, traceId);
    }

    /**
     * 拒绝 Skill 发布单。
     *
     * <p>拒绝原因只允许低敏摘要。被拒绝的发布单保留审计价值，不会被删除；如需重新提交，建议创建新版本。</p>
     */
    @PostMapping("/{publicationId}/reject")
    public PlatformApiResponse<AgentSkillPublicationLifecycleView> reject(
            @PathVariable("publicationId") String publicationId,
            @Valid @RequestBody AgentSkillPublicationLifecycleActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        AgentSkillPublicationLifecycleView view =
                lifecycleService.reject(publicationId, request, actorId, actorRole);
        return PlatformApiResponse.success("Skill 发布单已拒绝", view, traceId);
    }

    /**
     * 下线 READY Skill。
     *
     * <p>下线用于灰度撤回、事故止血、能力替换或租户裁剪。下线后默认不应再进入运行时能力候选集。</p>
     */
    @PostMapping("/{publicationId}/deprecate")
    public PlatformApiResponse<AgentSkillPublicationLifecycleView> deprecate(
            @PathVariable("publicationId") String publicationId,
            @Valid @RequestBody AgentSkillPublicationLifecycleActionRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        AgentSkillPublicationLifecycleView view =
                lifecycleService.deprecate(publicationId, request, actorId, actorRole);
        return PlatformApiResponse.success("Skill 发布单已下线", view, traceId);
    }

    /**
     * 查询单个 Skill 发布单。
     */
    @GetMapping("/{publicationId}")
    public PlatformApiResponse<AgentSkillPublicationLifecycleView> getPublication(
            @PathVariable("publicationId") String publicationId,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(lifecycleService.getPublication(publicationId), traceId);
    }

    /**
     * 查询 Skill 发布单列表。
     *
     * <p>当前支持按租户、项目、skillCode、domain、status 和 limit 做轻量过滤。生产管理台后续应继续扩展分页、
     * 创建时间范围、审核人、版本历史和审计 outbox，但不应把高敏 Skill 内容加入列表响应。</p>
     */
    @GetMapping
    public PlatformApiResponse<AgentSkillPublicationLifecycleQueryResponse> query(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "projectId", required = false) String projectId,
            @RequestParam(value = "skillCode", required = false) String skillCode,
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                lifecycleService.query(tenantId, projectId, skillCode, domain, status, limit),
                traceId
        );
    }
}
