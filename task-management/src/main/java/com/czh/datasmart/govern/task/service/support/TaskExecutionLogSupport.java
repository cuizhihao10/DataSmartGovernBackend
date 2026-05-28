package com.czh.datasmart.govern.task.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskExecutionLog;
import com.czh.datasmart.govern.task.mapper.TaskExecutionLogMapper;
import com.czh.datasmart.govern.task.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * @Author : Cui
 * @Date: 2026/05/05 23:49
 * @Description DataSmart Govern Backend - TaskExecutionLogSupport.java
 * @Version:1.0.0
 *
 * 任务执行日志支持组件。
 *
 * <p>Task 主表保存“当前快照”，TaskExecutionRun 保存“每一次执行尝试”，
 * TaskExecutionLog 则保存“任务生命周期里发生过哪些动作”。
 * 将日志写入逻辑从 TaskServiceImpl 拆出，主要有三个目的：</p>
 *
 * <p>1. 避免生命周期、执行器、管理员强控等多个业务面重复拼装日志实体；</p>
 * <p>2. 统一 operator、details、空值处理等审计字段语义；</p>
 * <p>3. 为未来升级到统一 audit-center、SIEM 投递、日志导出、日志索引表预留集中扩展点。</p>
 *
 * <p>注意：执行器回调幂等已经由 TaskCallbackIdempotencySupport 和
 * task_callback_idempotency 表承担，日志组件不再负责通过 details LIKE 查询做去重。
 * 这样可以让日志专注“可读轨迹”，让幂等专注“并发可靠性”，两者职责更清晰。</p>
 */
@Component
@RequiredArgsConstructor
public class TaskExecutionLogSupport {

    /**
     * 任务主表 Mapper。
     *
     * <p>查询日志前先确认任务存在，能区分“任务不存在”和“任务存在但暂时没有日志”两种业务语义。</p>
     */
    private final TaskMapper taskMapper;

    /**
     * 执行日志 Mapper，负责持久化结构化任务轨迹。
     */
    private final TaskExecutionLogMapper taskExecutionLogMapper;

    /**
     * 查询任务执行日志。
     *
     * @param taskId 任务 ID。
     * @return 按时间倒序排列的任务轨迹列表。
     */
    public List<TaskExecutionLog> listExecutionLogs(Long taskId) {
        requireTask(taskId);
        return taskExecutionLogMapper.selectList(new LambdaQueryWrapper<TaskExecutionLog>()
                .eq(TaskExecutionLog::getTaskId, taskId)
                .orderByDesc(TaskExecutionLog::getCreateTime)
                .orderByDesc(TaskExecutionLog::getId));
    }

    /**
     * 写入系统操作者的执行日志。
     *
     * <p>普通生命周期动作通常没有明确人类操作者，例如执行器回调完成、系统标记失败等，
     * 因此默认 operator=system，避免老接口调用方必须额外传操作者。</p>
     */
    public void saveExecutionLog(Long taskId,
                                 String action,
                                 String fromStatus,
                                 String toStatus,
                                 String message,
                                 String details) {
        saveExecutionLog(taskId, action, fromStatus, toStatus, message, details, "system");
    }

    /**
     * 写入显式操作者的执行日志。
     *
     * @param taskId 任务 ID，用于把轨迹挂到具体任务下。
     * @param action 动作编码，建议使用稳定英文常量，便于未来报表统计和告警规则识别。
     * @param fromStatus 动作前状态；没有前置状态时可为空。
     * @param toStatus 动作后状态；状态不变化的动作可与 fromStatus 相同。
     * @param message 面向用户和运维人员可读的动作摘要。
     * @param details 更详细的上下文，目前使用 key=value 文本，后续可升级为 JSON。
     * @param operator 操作者标签，例如 system、OPERATOR:1001、SERVICE_ACCOUNT:worker-01。
     */
    public void saveExecutionLog(Long taskId,
                                 String action,
                                 String fromStatus,
                                 String toStatus,
                                 String message,
                                 String details,
                                 String operator) {
        TaskExecutionLog executionLog = new TaskExecutionLog();
        executionLog.setTaskId(taskId);
        executionLog.setAction(action);
        executionLog.setFromStatus(fromStatus);
        executionLog.setToStatus(toStatus);
        executionLog.setMessage(message);
        executionLog.setOperator(operator);
        executionLog.setDetails(details);
        executionLog.setCreateTime(LocalDateTime.now());
        taskExecutionLogMapper.insert(executionLog);
    }

    /**
     * 生成管理员动作详情。
     *
     * <p>details 当前是文本字段，所以先使用易读的 key=value 形式记录上下文。
     * 后续如果日志检索、合规导出、告警分析增强，可升级为 JSON 并把 tenantId、actorId、traceId
     * 下沉成独立索引字段。</p>
     */
    public String adminDetails(String reason, TaskActorContext actorContext) {
        return "reason=" + defaultText(reason, "未填写原因")
                + ", tenantId=" + nullSafe(actorContext.tenantId())
                + ", actorId=" + nullSafe(actorContext.actorId())
                + ", actorRole=" + nullSafe(actorContext.actorRole())
                + ", traceId=" + nullSafe(actorContext.traceId());
    }

    /**
     * 生成执行日志 operator 字段。
     *
     * <p>格式固定为“角色:主体 ID”，便于人类阅读，也方便未来按角色或账号做聚合统计。</p>
     */
    public String actorLabel(TaskActorContext actorContext) {
        if (actorContext == null) {
            return "unknown";
        }
        return defaultText(actorContext.actorRole(), "unknown") + ":" + nullSafe(actorContext.actorId());
    }

    /**
     * 文本默认值处理。
     */
    public String defaultText(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    /**
     * 空值安全字符串转换。
     */
    public String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Task requireTask(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new NoSuchElementException("任务不存在: " + taskId);
        }
        return task;
    }
}
