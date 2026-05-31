/**
 * @Author : Cui
 * @Date: 2026/05/31 16:45
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandConsumerService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.task.controller.dto.AgentAsyncTaskCommandConsumeResponse;
import com.czh.datasmart.govern.task.controller.dto.AgentAsyncTaskCommandRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.entity.AgentAsyncTaskCommandInbox;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.mapper.AgentAsyncTaskCommandInboxMapper;
import com.czh.datasmart.govern.task.support.AgentAsyncTaskCommandState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Agent 异步工具命令消费服务。
 *
 * <p>该服务是 `agent-runtime -> task-management` 异步任务化链路的消费侧核心。
 * 当前内部 HTTP 联调入口和未来 Kafka listener 都应该调用本服务，不能分别实现两套消费逻辑。
 * 这样无论消息来自联调请求、Kafka 正常投递、重试、重放还是死信恢复，幂等和任务创建语义都保持一致。</p>
 *
 * <p>核心流程：</p>
 * <p>1. 校验协议版本、命令类型、租户/项目/工作空间边界和安全引用；</p>
 * <p>2. 尝试插入 Inbox，依赖数据库唯一索引裁决重复 command；</p>
 * <p>3. 首次消费时创建一条通用任务中心任务，状态为 PENDING，进入现有队列、租约和重试体系；</p>
 * <p>4. 回写 Inbox.taskId 和 TASK_CREATED；</p>
 * <p>5. 重复消费时直接返回首次创建的 taskId，不重复创建任务。</p>
 *
 * <p>事务边界非常重要：Inbox 插入、Task 创建和 Inbox 回写必须位于同一个本地数据库事务中。
 * 如果任务创建失败，Inbox PROCESSING 记录也会回滚，Kafka 后续重试才能重新尝试，而不是形成永久卡住的半成品。
 * 后续真正接入 Kafka 后，listener 应在本事务成功提交后再确认 offset。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAsyncTaskCommandConsumerService {

    /**
     * 当前消费侧支持的协议版本。
     */
    public static final String SUPPORTED_SCHEMA_VERSION = "datasmart.agent.async-task-command.v1";

    /**
     * 当前消费侧支持的命令类型。
     */
    public static final String SUPPORTED_COMMAND_TYPE = "AGENT_TOOL_ASYNC_TASK_REQUESTED";

    /**
     * 受控载荷引用协议。
     *
     * <p>第一阶段只允许从 Agent 工具审计快照读取参数。后续如扩展 MinIO、workspace artifact 或 secret manager，
     * 应新增显式白名单协议与专用 resolver，不能接受任意 URL。</p>
     */
    private static final String PAYLOAD_REFERENCE_PREFIX = "agent-tool-audit://";

    /**
     * 参数名白名单。
     *
     * <p>参数名允许常见 JSONPath 风格字符，但禁止空格、换行和 JSON 结构字符，
     * 避免运维台、日志或后续策略表达式被构造内容污染。</p>
     */
    private static final Pattern ARGUMENT_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.\\[\\]-]{1,128}");

    private static final int MAX_ARGUMENT_NAMES = 200;

    private final AgentAsyncTaskCommandInboxMapper inboxMapper;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    /**
     * 消费一条 Agent 异步工具命令。
     *
     * @param request Agent Runtime dispatcher 或内部联调入口提交的命令。
     * @return 消费回执。重复投递不会报错，而是返回 duplicate=true 和首次创建的 taskId。
     */
    @Transactional
    public AgentAsyncTaskCommandConsumeResponse consume(AgentAsyncTaskCommandRequest request) {
        validateCommand(request);
        AgentAsyncTaskCommandInbox inbox = buildInbox(request);
        try {
            inboxMapper.insert(inbox);
        } catch (DuplicateKeyException exception) {
            /*
             * MySQL 唯一索引承担多实例竞争裁决。这里捕获重复键后查询首次记录，
             * 并返回已有 taskId。不能简单“忽略异常后重新创建任务”，否则去重形同虚设。
             */
            return duplicateResponse(request);
        }

        Task task = createTask(request);
        inbox.setTaskId(task.getId());
        inbox.setConsumeState(AgentAsyncTaskCommandState.TASK_CREATED);
        inbox.setLastSeenTime(LocalDateTime.now());
        inbox.setUpdateTime(LocalDateTime.now());
        inboxMapper.updateById(inbox);

        return new AgentAsyncTaskCommandConsumeResponse(
                request.getCommandId(),
                request.getIdempotencyKey(),
                AgentAsyncTaskCommandState.TASK_CREATED,
                false,
                true,
                task.getId(),
                "Agent 异步工具命令已转换为可恢复任务"
        );
    }

    /**
     * 构造 Inbox 记录。
     *
     * <p>字段值已经经过 validateCommand 校验。参数名列表保存为 JSON，但列表里只有字段名，没有字段值。</p>
     */
    private AgentAsyncTaskCommandInbox buildInbox(AgentAsyncTaskCommandRequest request) {
        LocalDateTime now = LocalDateTime.now();
        AgentAsyncTaskCommandInbox inbox = new AgentAsyncTaskCommandInbox();
        inbox.setCommandId(request.getCommandId().trim());
        inbox.setIdempotencyKey(request.getIdempotencyKey().trim());
        inbox.setSchemaVersion(request.getSchemaVersion().trim());
        inbox.setCommandType(request.getCommandType().trim());
        inbox.setAuditId(request.getAuditId().trim());
        inbox.setSessionId(request.getSessionId().trim());
        inbox.setRunId(request.getRunId().trim());
        inbox.setToolCode(request.getToolCode().trim());
        inbox.setTargetService(request.getTargetService().trim());
        inbox.setTargetEndpoint(request.getTargetEndpoint().trim());
        inbox.setTenantId(request.getTenantId());
        inbox.setProjectId(request.getProjectId());
        inbox.setWorkspaceId(request.getWorkspaceId());
        inbox.setActorId(request.getActorId().trim());
        inbox.setTraceId(request.getTraceId().trim());
        inbox.setPayloadReference(request.getPayloadReference().trim());
        inbox.setArgumentNames(toJson(normalizeArgumentNames(request.getArgumentNames())));
        inbox.setSensitiveArgumentNames(toJson(normalizeArgumentNames(request.getSensitiveArgumentNames())));
        inbox.setConsumeState(AgentAsyncTaskCommandState.PROCESSING);
        inbox.setFirstSeenTime(now);
        inbox.setLastSeenTime(now);
        inbox.setCreateTime(now);
        inbox.setUpdateTime(now);
        return inbox;
    }

    /**
     * 把命令转换为任务中心的通用任务。
     *
     * <p>task.params 同样只写入安全引用和治理元数据。worker 后续认领任务后，必须根据 payloadReference
     * 访问受控 resolver 读取真正参数。这样 task 表、列表接口和执行日志都不会复制敏感值。</p>
     */
    private Task createTask(AgentAsyncTaskCommandRequest request) {
        String params = toJson(buildSafeTaskParams(request));
        TaskActorContext serviceAccount = new TaskActorContext(
                0L,
                null,
                "SERVICE_ACCOUNT",
                request.getTraceId(),
                null,
                List.of()
        );
        return taskService.createTask(
                "Agent 异步工具: " + request.getToolCode(),
                "由 Agent Runtime 异步命令创建，auditId=" + request.getAuditId()
                        + "，目标服务=" + request.getTargetService(),
                "AGENT_ASYNC_TOOL",
                params,
                request.getPriority(),
                request.getMaxRetryCount(),
                request.getMaxDeferCount(),
                request.getTenantId(),
                null,
                request.getProjectId(),
                serviceAccount
        );
    }

    /**
     * 生成任务参数安全摘要。
     *
     * <p>这里明确采用字段白名单，不直接序列化 request。即使未来 DTO 新增敏感字段，
     * 也不会因为自动序列化而意外落进 task.params。</p>
     */
    private Map<String, Object> buildSafeTaskParams(AgentAsyncTaskCommandRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("schemaVersion", request.getSchemaVersion());
        params.put("commandId", request.getCommandId());
        params.put("auditId", request.getAuditId());
        params.put("sessionId", request.getSessionId());
        params.put("runId", request.getRunId());
        params.put("toolCode", request.getToolCode());
        params.put("targetService", request.getTargetService());
        params.put("targetEndpoint", request.getTargetEndpoint());
        params.put("workspaceId", request.getWorkspaceId());
        params.put("payloadReference", request.getPayloadReference());
        params.put("argumentNames", normalizeArgumentNames(request.getArgumentNames()));
        params.put("sensitiveArgumentNames", normalizeArgumentNames(request.getSensitiveArgumentNames()));
        return params;
    }

    /**
     * 返回重复消费结果。
     *
     * <p>如果 commandId 或 idempotencyKey 已经存在，但另一个字段、auditId 不一致，说明调用方错误复用了身份。
     * 这种情况不能静默返回成功，否则可能把一个新动作错误绑定到旧任务。</p>
     */
    private AgentAsyncTaskCommandConsumeResponse duplicateResponse(AgentAsyncTaskCommandRequest request) {
        AgentAsyncTaskCommandInbox existing = inboxMapper.selectOne(new LambdaQueryWrapper<AgentAsyncTaskCommandInbox>()
                .and(wrapper -> wrapper.eq(AgentAsyncTaskCommandInbox::getCommandId, request.getCommandId().trim())
                        .or()
                        .eq(AgentAsyncTaskCommandInbox::getIdempotencyKey, request.getIdempotencyKey().trim()))
                .last("LIMIT 1"));
        if (existing == null) {
            throw new IllegalStateException("检测到异步命令唯一键冲突，但未找到已有 Inbox 记录");
        }
        if (!request.getCommandId().trim().equals(existing.getCommandId())
                || !request.getIdempotencyKey().trim().equals(existing.getIdempotencyKey())
                || !request.getAuditId().trim().equals(existing.getAuditId())) {
            throw new IllegalStateException("异步命令身份冲突：commandId、idempotencyKey 或 auditId 被错误复用");
        }
        existing.setLastSeenTime(LocalDateTime.now());
        existing.setUpdateTime(LocalDateTime.now());
        inboxMapper.updateById(existing);
        return new AgentAsyncTaskCommandConsumeResponse(
                existing.getCommandId(),
                existing.getIdempotencyKey(),
                existing.getConsumeState(),
                true,
                AgentAsyncTaskCommandState.TASK_CREATED.equals(existing.getConsumeState()),
                existing.getTaskId(),
                "检测到重复投递，已复用首次消费结果"
        );
    }

    /**
     * 校验消息语义。
     *
     * <p>Bean Validation 只在 HTTP Controller 上自动生效，而未来 Kafka listener 会直接调用 Service。
     * 因此关键安全规则必须在 Service 再校验一次，不能把消息安全寄托在某一种传输适配器上。</p>
     */
    private void validateCommand(AgentAsyncTaskCommandRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Agent 异步工具命令不能为空");
        }
        requireText(request.getSchemaVersion(), "schemaVersion");
        requireText(request.getCommandId(), "commandId");
        requireText(request.getIdempotencyKey(), "idempotencyKey");
        requireText(request.getCommandType(), "commandType");
        requireText(request.getAuditId(), "auditId");
        requireText(request.getSessionId(), "sessionId");
        requireText(request.getRunId(), "runId");
        requireText(request.getToolCode(), "toolCode");
        requireText(request.getTargetService(), "targetService");
        requireText(request.getTargetEndpoint(), "targetEndpoint");
        requireText(request.getActorId(), "actorId");
        requireText(request.getTraceId(), "traceId");
        requireText(request.getPayloadReference(), "payloadReference");
        if (!SUPPORTED_SCHEMA_VERSION.equals(request.getSchemaVersion().trim())) {
            throw new IllegalArgumentException("不支持的 Agent 异步命令 schemaVersion: " + request.getSchemaVersion());
        }
        if (!SUPPORTED_COMMAND_TYPE.equals(request.getCommandType().trim())) {
            throw new IllegalArgumentException("不支持的 Agent 异步命令 commandType: " + request.getCommandType());
        }
        requirePositive(request.getTenantId(), "tenantId");
        requirePositive(request.getProjectId(), "projectId");
        requirePositive(request.getWorkspaceId(), "workspaceId");
        if (!request.getPayloadReference().trim().startsWith(PAYLOAD_REFERENCE_PREFIX)) {
            throw new IllegalArgumentException("payloadReference 必须使用受控 agent-tool-audit:// 协议");
        }
        List<String> argumentNames = normalizeArgumentNames(request.getArgumentNames());
        List<String> sensitiveNames = normalizeArgumentNames(request.getSensitiveArgumentNames());
        if (!new LinkedHashSet<>(argumentNames).containsAll(sensitiveNames)) {
            throw new IllegalArgumentException("sensitiveArgumentNames 必须是 argumentNames 的子集");
        }
    }

    private List<String> normalizeArgumentNames(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_ARGUMENT_NAMES) {
            throw new IllegalArgumentException("参数名数量不能超过 " + MAX_ARGUMENT_NAMES);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String name = requireText(value, "argumentName").trim();
            if (!ARGUMENT_NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException("参数名格式不合法: " + name);
            }
            normalized.add(name);
        }
        return new ArrayList<>(normalized);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }

    private void requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " 必须大于 0");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 异步命令安全摘要序列化失败", exception);
        }
    }
}
