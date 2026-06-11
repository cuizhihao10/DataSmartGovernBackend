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
import com.czh.datasmart.govern.task.support.AgentAsyncTaskCommandContractSupport;
import com.czh.datasmart.govern.task.support.AgentAsyncTaskCommandContractSupport.CommandKind;
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
    public static final String SUPPORTED_SCHEMA_VERSION =
            AgentAsyncTaskCommandContractSupport.SUPPORTED_SCHEMA_VERSION;

    /**
     * 历史消费侧支持的命令类型。
     *
     * <p>保留该常量是为了兼容既有测试、Kafka 样例和 4.x/5.x 早期 Run 级 async-task outbox。
     * 新增的工具动作受控命令请使用 {@link #SUPPORTED_TOOL_ACTION_COMMAND_TYPE}。</p>
     */
    public static final String SUPPORTED_COMMAND_TYPE =
            AgentAsyncTaskCommandContractSupport.COMMAND_TYPE_ASYNC_TASK_REQUESTED;

    /**
     * 新工具动作受控命令类型。
     *
     * <p>该命令进入 Inbox 后只创建 `AGENT_TOOL_ACTION_CONTROLLED` 任务，不会被旧 worker 直接认领。
     * 这样可以让项目先补齐 command 接单、去重、审计台账，再逐步实现专门的 payload store 与执行器。</p>
     */
    public static final String SUPPORTED_TOOL_ACTION_COMMAND_TYPE =
            AgentAsyncTaskCommandContractSupport.COMMAND_TYPE_TOOL_ACTION_CONTROLLED;

    /**
     * 参数名白名单。
     *
     * <p>参数名允许常见 JSONPath 风格字符，但禁止空格、换行和 JSON 结构字符，
     * 避免运维台、日志或后续策略表达式被构造内容污染。</p>
     */
    private static final Pattern ARGUMENT_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.\\[\\]-]{1,128}");

    /**
     * 低敏事实 ID 白名单。
     *
     * <p>新工具动作命令的 approval/clarification 事实后续可能来自 permission-admin、DAG confirmation、
     * 外部审批系统或前端确认事实表，因此不能像历史 async-task 那样只允许 `dag-confirmation:` 前缀。
     * 但它仍必须是短 ID，不允许 URL、SQL、JSON、换行或密钥片段。</p>
     */
    private static final Pattern SAFE_FACT_ID_PATTERN = Pattern.compile("[A-Za-z0-9:_.\\-]{1,160}");

    private static final int MAX_ARGUMENT_NAMES = 200;
    private static final int MAX_EVIDENCE_ITEMS = 20;
    private static final int MAX_EVIDENCE_LENGTH = 512;

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
        CommandKind commandKind = validateCommand(request);
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

        Task task = createTask(request, commandKind);
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
        CommandKind commandKind = AgentAsyncTaskCommandContractSupport.requireSupportedCommandType(request.getCommandType());
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
        inbox.setTargetEndpoint(AgentAsyncTaskCommandContractSupport.normalizeTargetEndpoint(
                commandKind,
                request.getTargetEndpoint()
        ));
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
    private Task createTask(AgentAsyncTaskCommandRequest request, CommandKind commandKind) {
        String params = toJson(buildSafeTaskParams(request, commandKind));
        TaskActorContext serviceAccount = new TaskActorContext(
                0L,
                null,
                "SERVICE_ACCOUNT",
                request.getTraceId(),
                null,
                List.of()
        );
        String taskType = AgentAsyncTaskCommandContractSupport.taskType(commandKind);
        return taskService.createTask(
                taskName(request, commandKind),
                taskDescription(request, commandKind),
                taskType,
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
    private Map<String, Object> buildSafeTaskParams(AgentAsyncTaskCommandRequest request, CommandKind commandKind) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("schemaVersion", request.getSchemaVersion());
        params.put("commandId", request.getCommandId());
        params.put("commandType", request.getCommandType());
        params.put("commandKind", commandKind.name());
        params.put("auditId", request.getAuditId());
        params.put("sessionId", request.getSessionId());
        params.put("runId", request.getRunId());
        params.put("toolCode", request.getToolCode());
        params.put("targetService", request.getTargetService());
        params.put("targetEndpoint", AgentAsyncTaskCommandContractSupport.normalizeTargetEndpoint(
                commandKind,
                request.getTargetEndpoint()
        ));
        params.put("workspaceId", request.getWorkspaceId());
        params.put("actorId", request.getActorId());
        params.put("payloadReference", request.getPayloadReference());
        params.put("payloadReferenceType", AgentAsyncTaskCommandContractSupport.payloadReferenceType(commandKind));
        /*
         * workerDispatchEnabled 用于把“已经接单的控制面命令”和“可以被现有 worker 执行的历史异步任务”区分开。
         * 目前只有 AGENT_ASYNC_TOOL 会被旧 worker 认领；新工具动作命令必须等待后续专用执行器接入。
         */
        params.put("workerDispatchEnabled", CommandKind.ASYNC_TASK_REQUESTED.equals(commandKind));
        params.put("argumentNames", normalizeArgumentNames(request.getArgumentNames()));
        params.put("sensitiveArgumentNames", normalizeArgumentNames(request.getSensitiveArgumentNames()));
        params.put("confirmationId", normalizeOptionalText(request.getConfirmationId()));
        params.put("policyVersions", normalizeEvidenceList(request.getPolicyVersions(), "policyVersions"));
        params.put("delegationEvidence", normalizeEvidenceList(request.getDelegationEvidence(), "delegationEvidence"));
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
    private CommandKind validateCommand(AgentAsyncTaskCommandRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Agent 异步工具命令不能为空");
        }
        requireText(request.getSchemaVersion(), "schemaVersion");
        requireText(request.getCommandId(), "commandId");
        requireText(request.getIdempotencyKey(), "idempotencyKey");
        requireText(request.getCommandType(), "commandType");
        CommandKind commandKind = AgentAsyncTaskCommandContractSupport.requireSupportedCommandType(request.getCommandType());
        requireText(request.getAuditId(), "auditId");
        requireText(request.getSessionId(), "sessionId");
        requireText(request.getRunId(), "runId");
        requireText(request.getToolCode(), "toolCode");
        requireText(request.getTargetService(), "targetService");
        AgentAsyncTaskCommandContractSupport.validateTargetService(commandKind, request.getTargetService());
        AgentAsyncTaskCommandContractSupport.normalizeTargetEndpoint(commandKind, request.getTargetEndpoint());
        requireText(request.getActorId(), "actorId");
        requireText(request.getTraceId(), "traceId");
        requireText(request.getPayloadReference(), "payloadReference");
        if (!SUPPORTED_SCHEMA_VERSION.equals(request.getSchemaVersion().trim())) {
            throw new IllegalArgumentException("不支持的 Agent 异步命令 schemaVersion: " + request.getSchemaVersion());
        }
        requirePositive(request.getTenantId(), "tenantId");
        requirePositive(request.getProjectId(), "projectId");
        if (AgentAsyncTaskCommandContractSupport.workspaceRequired(commandKind)) {
            requirePositive(request.getWorkspaceId(), "workspaceId");
        } else if (request.getWorkspaceId() != null) {
            requirePositive(request.getWorkspaceId(), "workspaceId");
        }
        AgentAsyncTaskCommandContractSupport.validatePayloadReference(
                commandKind,
                request.getPayloadReference(),
                request.getSessionId(),
                request.getRunId(),
                request.getAuditId()
        );
        List<String> argumentNames = normalizeArgumentNames(request.getArgumentNames());
        List<String> sensitiveNames = normalizeArgumentNames(request.getSensitiveArgumentNames());
        if (!new LinkedHashSet<>(argumentNames).containsAll(sensitiveNames)) {
            throw new IllegalArgumentException("sensitiveArgumentNames 必须是 argumentNames 的子集");
        }
        validateExecutionEvidence(request, commandKind);
        return commandKind;
    }

    /**
     * 校验执行前证据字段。
     *
     * <p>这不是最终 worker pre-check 的全部能力，而是 task-management 消费侧的第一道契约防线：
     * 如果 command 声明自己来自 selected-node confirmation，就必须使用稳定的 confirmationId 前缀，
     * policyVersion 与 delegationEvidence 也只能是短文本摘要。后续真正执行工具前，还应回查 agent-runtime
     * confirmation、permission-admin 策略版本、payloadReference 和工具状态。</p>
     */
    private void validateExecutionEvidence(AgentAsyncTaskCommandRequest request, CommandKind commandKind) {
        String confirmationId = normalizeOptionalText(request.getConfirmationId());
        if (confirmationId != null
                && CommandKind.ASYNC_TASK_REQUESTED.equals(commandKind)
                && !confirmationId.startsWith("dag-confirmation:")) {
            throw new IllegalArgumentException("confirmationId 必须来自 DAG selected-node 确认记录");
        }
        if (confirmationId != null
                && CommandKind.TOOL_ACTION_CONTROLLED.equals(commandKind)
                && (!SAFE_FACT_ID_PATTERN.matcher(confirmationId).matches()
                || looksLikeSensitivePayload(confirmationId))) {
            throw new IllegalArgumentException("工具动作 confirmationId 只能是低敏事实 ID，不能包含 URL、SQL、prompt 或密钥片段");
        }
        normalizeEvidenceList(request.getPolicyVersions(), "policyVersions");
        normalizeEvidenceList(request.getDelegationEvidence(), "delegationEvidence");
    }

    /**
     * 构造任务名称。
     *
     * <p>任务名称面向列表页和运营台展示，所以既要保留 toolCode，又要让用户区分“可被 worker 执行的异步工具”
     * 与“只完成控制面接单的新工具动作”。</p>
     */
    private String taskName(AgentAsyncTaskCommandRequest request, CommandKind commandKind) {
        if (CommandKind.TOOL_ACTION_CONTROLLED.equals(commandKind)) {
            return "Agent 受控工具动作: " + request.getToolCode();
        }
        return "Agent 异步工具: " + request.getToolCode();
    }

    /**
     * 构造任务描述。
     *
     * <p>描述同样只使用低敏字段。这里不写 payload 正文、参数值或内部 endpoint，
     * 只说明该任务来自哪个 command、当前目标控制面以及是否会被现有 worker 认领。</p>
     */
    private String taskDescription(AgentAsyncTaskCommandRequest request, CommandKind commandKind) {
        if (CommandKind.TOOL_ACTION_CONTROLLED.equals(commandKind)) {
            return "由 Agent Runtime 工具动作控制面命令创建，auditId=" + request.getAuditId()
                    + "，当前只完成 Inbox 去重和任务台账登记，等待后续专用 tool-action executor 回查 payload store 并写入 receipt。";
        }
        return "由 Agent Runtime 异步命令创建，auditId=" + request.getAuditId()
                + "，目标服务=" + request.getTargetService();
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

    private List<String> normalizeEvidenceList(List<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_EVIDENCE_ITEMS) {
            throw new IllegalArgumentException(fieldName + " 数量不能超过 " + MAX_EVIDENCE_ITEMS);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = normalizeOptionalText(value);
            if (item == null) {
                continue;
            }
            if (item.length() > MAX_EVIDENCE_LENGTH) {
                throw new IllegalArgumentException(fieldName + " 单项长度不能超过 " + MAX_EVIDENCE_LENGTH);
            }
            if (looksLikeSensitivePayload(item)) {
                throw new IllegalArgumentException(fieldName + " 只能保存低敏审计摘要，不能包含原始 payload、SQL、prompt 或密钥片段");
            }
            normalized.add(item);
        }
        return new ArrayList<>(normalized);
    }

    private boolean looksLikeSensitivePayload(String value) {
        String lower = value.toLowerCase();
        return lower.contains("select ")
                || lower.contains("insert ")
                || lower.contains("authorization:")
                || lower.contains("bearer ")
                || lower.contains("password")
                || lower.contains("prompt:");
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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
