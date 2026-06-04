/**
 * @Author : Cui
 * @Date: 2026/05/23 21:38
 * @Description DataSmart Govern Backend - AgentSkillRegistryController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentSkillDescriptorView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillMarketplaceSummaryView;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationManifestView;
import com.czh.datasmart.govern.agent.service.AgentSkillPublicationManifestService;
import com.czh.datasmart.govern.agent.service.AgentSkillRegistryService;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent Skill 注册控制器。
 *
 * <p>该控制器只提供只读 descriptor 和市场摘要，不直接执行 Skill，也不修改 Skill 发布状态。
 * Skill 的真实执行会经过 Python AI Runtime 规划、Java agent-runtime 审计审批、业务微服务工具调用等链路。
 *
 * <p>典型调用方：
 * 1. Python Runtime 启动时拉取 Skill 列表，用于替代本地默认 Skill 清单；
 * 2. 智能网关创建会话时生成当前会话可用能力快照；
 * 3. 前端 Skill 市场或管理页展示能力包依赖、风险、审批策略和市场聚合筛选项；
 * 4. 后续 A2A Agent Card 适配层把这些 descriptor 转换为外部能力说明。
 */
@RestController
@RequestMapping({"/agent-runtime/skills", "/api/agent/skills"})
@RequiredArgsConstructor
public class AgentSkillRegistryController {

    private final AgentSkillRegistryService skillRegistryService;
    private final AgentSkillPublicationManifestService publicationManifestService;

    /**
     * 查询 Agent Skill 发布 Manifest。
     *
     * <p>该接口面向 Python Runtime、智能网关和前端市场页。它不是简单返回 descriptor 列表，
     * 而是生成目录级发布快照：包含内容指纹、发布状态、运行时消费建议和协议兼容说明。
     * Python Runtime 可以用 contentFingerprint 判断远端 Skill 目录是否变化，避免每次启动都盲目刷新。</p>
     *
     * <p>当前 Manifest 是 DataSmart 内部 MCP-style 契约，不代表完整 MCP JSON-RPC Server。
     * 真正完整 MCP Server 还需要 capability negotiation、tools/list、resources/list、prompts/list 等协议处理。
     * 这里先把商业化发布事实源固定住，后续再做协议适配层。</p>
     *
     * @param includeDisabled 是否包含禁用 Skill；默认 false，避免模型规划读取下线能力
     * @param domain 可选治理域过滤
     * @param riskLevel 可选风险等级过滤
     * @param traceId 链路追踪 ID，透传给平台统一响应
     */
    @GetMapping("/publication/manifest")
    public PlatformApiResponse<AgentSkillPublicationManifestView> getPublicationManifest(
            @RequestParam(value = "includeDisabled", required = false, defaultValue = "false") Boolean includeDisabled,
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "riskLevel", required = false) String riskLevel,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                publicationManifestService.buildManifest(includeDisabled, domain, riskLevel),
                traceId
        );
    }

    /**
     * 查询 Agent Skill Marketplace 治理摘要。
     *
     * <p>该接口不是给模型直接执行 Skill 用的，而是给前端市场页、运营看板、Python Runtime 启动诊断
     * 提供“当前能力包目录是否健康”的读侧快照。它会聚合领域、风险、审批、记忆依赖等筛选维度，
     * 同时给出低敏运营提示和下一步建设建议。
     *
     * <p>为什么要有独立摘要接口，而不是让前端自己统计 `/descriptors`？
     * Skill 市场的统计口径会逐步引入租户开关、版本发布、灰度批次、审批策略模板和权限包绑定。
     * 如果每个调用方都自己统计，后续口径会很容易漂移；由 Java 控制面统一输出更适合商业化产品。
     *
     * @param includeDisabled 是否把已禁用 Skill 纳入统计；市场运营视角建议 true，Python 规划视角可传 false
     * @param traceId 链路追踪 ID，透传给平台统一响应
     */
    @GetMapping("/marketplace/summary")
    public PlatformApiResponse<AgentSkillMarketplaceSummaryView> getMarketplaceSummary(
            @RequestParam(value = "includeDisabled", required = false, defaultValue = "true") Boolean includeDisabled,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(skillRegistryService.getMarketplaceSummary(includeDisabled), traceId);
    }

    /**
     * 查询 Skill 描述符列表。
     *
     * <p>该列表面向 Python Runtime、智能网关和前端能力选择器。它返回的是单个 Skill 的稳定描述，
     * 包括依赖工具、权限、触发关键词、示例、治理策略和记忆策略。
     *
     * @param domain 可选治理域过滤，例如 DATA_QUALITY、TASK_MANAGEMENT
     * @param riskLevel 可选风险等级过滤，例如 LOW、MEDIUM、HIGH
     * @param enabledOnly 是否只返回启用 Skill
     * @param traceId 链路追踪 ID，透传给平台统一响应
     */
    @GetMapping("/descriptors")
    public PlatformApiResponse<List<AgentSkillDescriptorView>> listSkillDescriptors(
            @RequestParam(value = "domain", required = false) String domain,
            @RequestParam(value = "riskLevel", required = false) String riskLevel,
            @RequestParam(value = "enabledOnly", required = false, defaultValue = "true") Boolean enabledOnly,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                skillRegistryService.listSkillDescriptors(domain, riskLevel, enabledOnly), traceId);
    }

    /**
     * 查询单个 Skill 描述符。
     *
     * <p>该接口适合 Python Runtime 已经选中 skillCode 后，做执行前二次确认。
     * 如果某个 Skill 已被下线、风险策略调整或依赖工具变化，Runtime 可以在执行前及时感知。
     *
     * @param skillCode 稳定 Skill 编码
     * @param traceId 链路追踪 ID，透传给平台统一响应
     */
    @GetMapping("/{skillCode}/descriptor")
    public PlatformApiResponse<AgentSkillDescriptorView> getSkillDescriptor(
            @PathVariable("skillCode") String skillCode,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(skillRegistryService.getSkillDescriptor(skillCode), traceId);
    }
}
