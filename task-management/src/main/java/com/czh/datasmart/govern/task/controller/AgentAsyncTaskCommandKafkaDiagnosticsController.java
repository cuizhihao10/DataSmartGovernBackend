/**
 * @Author : Cui
 * @Date: 2026/05/31 23:16
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaDiagnosticsController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller;

import com.czh.datasmart.govern.task.common.ApiResponse;
import com.czh.datasmart.govern.task.event.AgentAsyncTaskCommandKafkaDiagnosticsService;
import com.czh.datasmart.govern.task.event.AgentAsyncTaskCommandKafkaDiagnosticsSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 异步工具命令 Kafka 消费诊断内部控制器。
 *
 * <p>该控制器只暴露只读诊断快照，不提供“跳过消息、重放消息、清空 offset”等高风险动作。
 * 原因是 Kafka offset、DLQ 重放和任务幂等都属于生产事故处理能力，必须结合权限、审批、审计和回滚策略设计。
 * 当前先让平台能看见坏消息分类和最近失败样本，为后续运维台和告警系统打基础。</p>
 *
 * <p>安全边界：路由位于 `/internal/**` 下，生产环境应由 gateway、服务网格或内网策略保护，
 * 只允许平台管理员、运维服务账号或自动化巡检任务访问。虽然响应不包含原始 payload，
 * 但 failure reason 和字节数仍属于运行诊断信息，不应暴露给普通租户用户。</p>
 */
@RestController
@RequestMapping("/internal/agent-async-task-commands/kafka")
@RequiredArgsConstructor
public class AgentAsyncTaskCommandKafkaDiagnosticsController {

    private final AgentAsyncTaskCommandKafkaDiagnosticsService diagnosticsService;

    /**
     * 获取当前 task-management 实例内的 Kafka 消费诊断快照。
     *
     * @return 当前实例的失败计数、DLQ 候选配置和最近失败样本。
     */
    @GetMapping("/diagnostics")
    public ResponseEntity<ApiResponse<AgentAsyncTaskCommandKafkaDiagnosticsSnapshot>> diagnostics() {
        return ResponseEntity.ok(ApiResponse.success("Agent 异步命令 Kafka 消费诊断快照查询完成",
                diagnosticsService.snapshot()));
    }
}
