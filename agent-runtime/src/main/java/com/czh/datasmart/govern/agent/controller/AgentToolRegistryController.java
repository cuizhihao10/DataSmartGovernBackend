/**
 * @Author : Cui
 * @Date: 2026/05/13 23:30
 * @Description DataSmart Govern Backend - AgentToolRegistryController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolDefinitionView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDescriptorView;
import com.czh.datasmart.govern.agent.service.AgentToolRegistryService;
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
 * Agent 工具注册控制器。
 *
 * <p>该控制器提供“工具目录”只读接口。
 * 它不执行工具，也不修改工具配置；它只让前端、Agent 编排器、运营排障和审计系统知道当前平台暴露了哪些 Agent 可用能力。
 *
 * <p>为什么工具目录要独立成 API：
 * 1. 前端需要展示可绑定工具、风险等级和输入参数；
 * 2. Agent 编排器需要根据 toolCode 和 inputSchema 生成工具调用计划；
 * 3. 运维需要排查“为什么 Agent 没有选择某个工具”；
 * 4. 审计需要复核某个工具在当时是否启用、是否只读、是否要求审批。
 */
@RestController
@RequestMapping({"/agent-runtime/tools", "/api/agent/tools"})
@RequiredArgsConstructor
public class AgentToolRegistryController {

    private final AgentToolRegistryService toolRegistryService;

    /**
     * 查询 Agent 工具目录。
     *
     * <p>支持按工具类型、风险等级和启用状态过滤。
     * 典型使用场景：
     * 1. 前端创建会话时展示可绑定工具；
     * 2. 运营人员查看当前环境是否启用了某个工具；
     * 3. Agent 编排器启动前拉取当前工具能力快照。
     */
    @GetMapping
    public PlatformApiResponse<List<AgentToolDefinitionView>> listTools(
            @RequestParam(value = "toolType", required = false) String toolType,
            @RequestParam(value = "riskLevel", required = false) String riskLevel,
            @RequestParam(value = "enabledOnly", required = false, defaultValue = "true") Boolean enabledOnly,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(toolRegistryService.listTools(toolType, riskLevel, enabledOnly), traceId);
    }

    /**
     * 查询 Agent 工具标准化描述符列表。
     *
     * <p>该接口面向 Python Runtime、智能网关和未来 MCP-style 工具导出。
     * 它与普通目录接口的差异是：普通目录更适合人看，描述符更适合机器规划和跨运行时消费。
     *
     * <p>典型使用场景：
     * 1. Python Runtime 启动时拉取当前可用工具描述，构建工具 planner 的可选动作集合；
     * 2. 智能网关生成某个会话可用工具快照，并把风险、审批、租户/项目边界写入审计；
     * 3. 后续实现 MCP Server 时，把该描述符转换为 MCP tools/list 的返回结构。
     */
    @GetMapping("/descriptors")
    public PlatformApiResponse<List<AgentToolDescriptorView>> listToolDescriptors(
            @RequestParam(value = "toolType", required = false) String toolType,
            @RequestParam(value = "riskLevel", required = false) String riskLevel,
            @RequestParam(value = "enabledOnly", required = false, defaultValue = "true") Boolean enabledOnly,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(
                toolRegistryService.listToolDescriptors(toolType, riskLevel, enabledOnly), traceId);
    }

    /**
     * 查询单个 Agent 工具详情。
     *
     * <p>详情接口适合在绑定工具、生成审批提示、展示高风险说明时调用。
     */
    @GetMapping("/{toolCode}")
    public PlatformApiResponse<AgentToolDefinitionView> getTool(
            @PathVariable("toolCode") String toolCode,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(toolRegistryService.getTool(toolCode), traceId);
    }

    /**
     * 查询单个 Agent 工具标准化描述符。
     *
     * <p>该接口适合工具规划前的精确拉取。
     * 例如模型已经选择 `quality.rule.suggest` 后，Python Runtime 可以再拉一次描述符，
     * 确认哪些参数必填、哪些字段敏感、是否需要审批、调用后是否允许写入记忆。
     */
    @GetMapping("/{toolCode}/descriptor")
    public PlatformApiResponse<AgentToolDescriptorView> getToolDescriptor(
            @PathVariable("toolCode") String toolCode,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(toolRegistryService.getToolDescriptor(toolCode), traceId);
    }
}
