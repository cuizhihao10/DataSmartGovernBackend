/**
 * @Author : Cui
 * @Date: 2026/05/24 23:36
 * @Description DataSmart Govern Backend - AgentToolOutputReferenceResolver.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 工具输出引用解析器。
 *
 * <p>该组件是 Run 内工具链上下文从“隐式最近输出”升级到“显式可审计引用”的第一步。
 * 它把 ToolPlan 中的引用对象解析为真实输出片段，供请求工厂继续构造下游请求。</p>
 *
 * <p>当前支持两类用法：</p>
 * <p>1. 显式引用：`{"toolCode":"quality.rule.suggest","auditId":"audit-001","jsonPath":"suggestion"}`；</p>
 * <p>2. 兼容回退：如果 ToolPlan 暂时没有引用对象，则仍可读取某个默认工具的最近成功输出。</p>
 *
 * <p>为什么当前只实现轻量 jsonPath：</p>
 * <p>项目目前没有引入额外 JSONPath 依赖，且工具输出主要是 Map/List 结构。
 * 为了避免为了一个小能力引入不必要依赖，当前支持点号路径和数组下标，例如：
 * `metadata.tables[0].columns`。后续如果输出结构复杂，再替换为标准 JSONPath 引擎也不影响上层协议。</p>
 */
@Component
public class AgentToolOutputReferenceResolver {

    private final AgentToolExecutionOutputStore outputStore;

    public AgentToolOutputReferenceResolver(AgentToolExecutionOutputStore outputStore) {
        this.outputStore = outputStore;
    }

    /**
     * 按显式引用或默认工具输出解析目标数据。
     *
     * @param context 当前工具执行上下文，用于限定 session/run，避免跨会话或跨 Run 读取输出。
     * @param referenceCandidate ToolPlan 中的引用对象，可以是 Map，也可以为空。
     * @param defaultToolCode 没有显式引用时默认读取的来源工具。
     * @param defaultJsonPath 没有显式引用时默认读取的输出路径。
     * @return 输出路径对应的数据；找不到时返回空。
     */
    public Optional<Object> resolve(AgentToolExecutionContext context,
                                    Object referenceCandidate,
                                    String defaultToolCode,
                                    String defaultJsonPath) {
        AgentToolOutputReference reference = parseReference(referenceCandidate, defaultToolCode, defaultJsonPath);
        if (reference.toolCode() == null || reference.toolCode().isBlank()) {
            return Optional.empty();
        }

        Optional<AgentToolExecutionOutputRecord> record = reference.auditId() == null || reference.auditId().isBlank()
                ? outputStore.findLatest(context.session().getSessionId(), context.run().getRunId(), reference.toolCode())
                : outputStore.findByAuditId(context.session().getSessionId(), context.run().getRunId(), reference.auditId());

        return record.map(AgentToolExecutionOutputRecord::output)
                .flatMap(output -> readPath(output, reference.jsonPath()));
    }

    /**
     * 解析 ToolPlan 中的引用对象。
     *
     * <p>为了兼容 Python Runtime 逐步演进，这里接受多个字段名：
     * `toolCode/fromTool`、`auditId/fromAuditId`、`jsonPath/path`。
     * 这种宽容只存在于 Java 控制面入口，内部仍统一转换为 `AgentToolOutputReference`。</p>
     */
    private AgentToolOutputReference parseReference(Object candidate, String defaultToolCode, String defaultJsonPath) {
        if (candidate instanceof Map<?, ?> map) {
            return new AgentToolOutputReference(
                    stringValue(firstNonNull(map.get("toolCode"), map.get("fromTool")), defaultToolCode),
                    stringValue(firstNonNull(map.get("auditId"), map.get("fromAuditId")), null),
                    normalizePath(stringValue(firstNonNull(map.get("jsonPath"), map.get("path")), defaultJsonPath))
            );
        }
        return new AgentToolOutputReference(defaultToolCode, null, normalizePath(defaultJsonPath));
    }

    /**
     * 读取 Map/List 中的轻量路径。
     *
     * <p>路径语法刻意保持简单：点号进入对象字段，`[n]` 读取数组元素。
     * 这已经可以覆盖当前工具输出的主要场景，例如 `metadata`、`suggestion`、
     * `metadata.tables[0]`、`taskDraft.params.qualityRuleSuggestions[0]`。</p>
     */
    private Optional<Object> readPath(Object root, String path) {
        if (root == null) {
            return Optional.empty();
        }
        String safePath = normalizePath(path);
        if (safePath == null || safePath.isBlank()) {
            return Optional.of(root);
        }
        Object current = root;
        for (String segment : safePath.split("\\.")) {
            current = readSegment(current, segment);
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.of(current);
    }

    private Object readSegment(Object current, String segment) {
        String fieldName = segment;
        Integer index = null;
        int bracketStart = segment.indexOf('[');
        if (bracketStart >= 0 && segment.endsWith("]")) {
            fieldName = segment.substring(0, bracketStart);
            String indexText = segment.substring(bracketStart + 1, segment.length() - 1);
            try {
                index = Integer.parseInt(indexText);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        Object next = current;
        if (!fieldName.isBlank()) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            next = map.get(fieldName);
        }
        if (index != null) {
            if (!(next instanceof List<?> list) || index < 0 || index >= list.size()) {
                return null;
            }
            return list.get(index);
        }
        return next;
    }

    private String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.trim();
        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String stringValue(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? defaultValue : text;
    }
}
