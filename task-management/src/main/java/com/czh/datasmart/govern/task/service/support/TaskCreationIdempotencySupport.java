/**
 * @Author : Cui
 * @Date: 2026/06/28 22:06
 * @Description DataSmart Govern Backend - TaskCreationIdempotencySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 任务创建幂等支持组件。
 *
 * <p>它解决的是“创建任务这个真实副作用能不能被安全重试”的问题。普通的任务执行回调已经有
 * {@code task_callback_idempotency} 保护，但跨服务创建任务时还存在另一个风险：调用方已经把请求发给
 * task-management，随后网络超时或上游进程崩溃，上游不知道任务是否已经创建，于是再次调用 {@code POST /tasks}。
 * 如果没有创建幂等键，第二次调用就会产生第二条真实任务，后续 worker 会重复执行治理动作。</p>
 *
 * <p>这里把幂等逻辑拆成独立 support，而不是继续堆进 {@link TaskLifecycleSupport}，主要有三个原因：</p>
 * <p>1. 任务生命周期状态机已经负责创建、启动、暂停、重试、完成、失败、defer 等动作，继续把幂等校验塞进去会降低可读性；</p>
 * <p>2. 幂等键清洗、冲突复用、唯一键并发兜底是一组可独立学习的可靠性主题，单独成类更容易理解；</p>
 * <p>3. 后续 data-sync、asset、compliance 等模块也会通过 task-management 创建任务，创建幂等会成为通用能力。</p>
 *
 * <p>安全边界：幂等键必须是低敏机器标识，例如 {@code agent-tool:run-1:command-2}。
 * 它不能包含 SQL、prompt、URL、凭据、样本数据、工具参数正文或模型输出。任务中心只用它做副作用去重，
 * 不把它当成可展示业务内容，也不依赖它反推出真实 payload。</p>
 */
@Component
@RequiredArgsConstructor
public class TaskCreationIdempotencySupport {

    /**
     * 创建幂等键最大长度。
     *
     * <p>长度控制不是单纯的数据库字段限制，它还有两个产品含义：</p>
     * <p>1. 防止调用方把大段 JSON、prompt、SQL 或异常样本误塞进幂等键；</p>
     * <p>2. 让唯一索引在 MySQL utf8mb4 下保持稳定，不因为超长索引影响迁移和查询性能。</p>
     */
    private static final int MAX_KEY_LENGTH = 180;

    /**
     * 低敏幂等键允许字符。
     *
     * <p>允许冒号、短横线、下划线和点号，是为了兼容当前 Agent command、data-sync worker command、
     * 外部工单号和未来服务账号提交链路中常见的命名空间写法；禁止空格、斜杠、花括号、引号等字符，
     * 是为了避免它退化成 URL、JSON、SQL 或自由文本载体。</p>
     */
    private static final Pattern SAFE_KEY_PATTERN = Pattern.compile("[A-Za-z0-9:_.\\-]{1," + MAX_KEY_LENGTH + "}");

    private final TaskMapper taskMapper;

    /**
     * 归一化并校验创建幂等键。
     *
     * @param rawKey 调用方传入的原始幂等键，可为空。
     * @return 归一化后的低敏幂等键；当调用方没有传入时返回 {@code null}，表示保持普通“每次创建新任务”的语义。
     */
    public String normalize(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String key = rawKey.trim();
        if (key.length() > MAX_KEY_LENGTH || !SAFE_KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("任务创建幂等键只能包含字母、数字、冒号、点号、下划线和短横线，长度不能超过 "
                    + MAX_KEY_LENGTH);
        }
        if (looksLikeSensitivePayload(key)) {
            throw new IllegalArgumentException("任务创建幂等键只能保存低敏机器标识，不能包含 SQL、prompt、凭据、URL 或工具参数正文");
        }
        return key;
    }

    /**
     * 尝试按创建幂等键读取已存在任务。
     *
     * @param normalizedKey 已通过 {@link #normalize(String)} 处理的键。
     * @return 命中的任务快照；未传幂等键或没有命中时返回空。
     */
    public Optional<Task> findExisting(String normalizedKey) {
        if (normalizedKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                .eq(Task::getCreationIdempotencyKey, normalizedKey)
                .last("LIMIT 1")));
    }

    /**
     * 复用已存在任务前做身份一致性校验。
     *
     * <p>相同幂等键只能代表同一次业务创建意图。如果调用方用同一个键提交了不同租户、项目、负责人或任务类型，
     * 这通常意味着客户端 bug、重放攻击、错误补偿脚本或测试数据污染。此时不能静默返回旧任务，否则调用方会把
     * “任务 A 已存在”误认为“任务 B 创建成功”。</p>
     *
     * @param existing 数据库里已经存在的任务。
     * @param candidate 当前请求准备创建的任务快照。
     * @return 可安全复用的已存在任务。
     */
    public Task reuseExisting(Task existing, Task candidate) {
        if (existing == null) {
            throw new IllegalStateException("任务创建幂等键已发生冲突，但没有找到可复用的既有任务");
        }
        if (!same(existing.getType(), candidate.getType())
                || !same(existing.getTenantId(), candidate.getTenantId())
                || !same(existing.getProjectId(), candidate.getProjectId())
                || !same(existing.getOwnerId(), candidate.getOwnerId())) {
            throw new IllegalStateException("任务创建幂等键被不同任务身份复用，已拒绝返回旧任务；请检查调用方 commandId/idempotencyKey 生成规则");
        }
        return existing;
    }

    /**
     * 唯一索引并发冲突后的兜底读取。
     *
     * <p>首轮 {@link #findExisting(String)} 可以挡住绝大多数重试请求，但在高并发下仍可能出现两个事务同时未查到旧任务、
     * 又同时插入同一个幂等键的竞态。数据库唯一索引会让其中一个事务失败，本方法在捕获唯一键冲突后重新读取既有任务，
     * 并再次执行身份一致性校验，形成“应用预查 + 数据库最终裁决”的双层保护。</p>
     */
    public Task recoverAfterDuplicateKey(String normalizedKey, Task candidate) {
        return reuseExisting(findExisting(normalizedKey).orElse(null), candidate);
    }

    private boolean same(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private boolean looksLikeSensitivePayload(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("select")
                || lower.contains("insert")
                || lower.contains("update")
                || lower.contains("delete")
                || lower.contains("prompt")
                || lower.contains("password")
                || lower.contains("authorization")
                || lower.contains("bearer")
                || lower.contains("http")
                || lower.contains("jdbc");
    }
}
