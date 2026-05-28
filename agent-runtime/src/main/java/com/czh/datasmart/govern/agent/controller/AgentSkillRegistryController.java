/**
 * @Author : Cui
 * @Date: 2026/05/23 21:38
 * @Description DataSmart Govern Backend - AgentSkillRegistryController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentSkillDescriptorView;
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
 * <p>该控制器只提供只读 descriptor，不直接执行 Skill。
 * Skill 的真实执行会经过 Python AI Runtime 规划、Java agent-runtime 审计审批、业务微服务工具调用等链路。
 *
 * <p>典型调用方：
 * 1. Python Runtime 启动时拉取 Skill 列表，用于替代本地默认 Skill 清单；
 * 2. 智能网关创建会话时生成当前会话可用能力快照；
 * 3. 前端 Skill 市场或管理页展示能力包依赖、风险和审批策略；
 * 4. 后续 A2A Agent Card 适配层把这些 descriptor 转换为外部能力说明。
 */
@RestController
@RequestMapping({"/agent-runtime/skills", "/api/agent/skills"})
@RequiredArgsConstructor
public class AgentSkillRegistryController {

    private final AgentSkillRegistryService skillRegistryService;

    /**
     * 查询 Skill 描述符列表。
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
     */
    @GetMapping("/{skillCode}/descriptor")
    public PlatformApiResponse<AgentSkillDescriptorView> getSkillDescriptor(
            @PathVariable("skillCode") String skillCode,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(skillRegistryService.getSkillDescriptor(skillCode), traceId);
    }
}
