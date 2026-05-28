/**
 * @Author : Cui
 * @Date: 2026/05/24 22:42
 * @Description DataSmart Govern Backend - AgentToolExecutionOutputRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 工具执行输出记录。
 *
 * <p>它保存的是“某个工具成功执行后返回的结构化输出快照”，用于同一个 Agent Run 内的后续工具继续消费。
 * 例如：</p>
 * <p>1. `datasource.metadata.read` 输出 `metadata`；</p>
 * <p>2. `quality.rule.suggest` 读取前序输出里的 `metadata`，生成质量规则草案；</p>
 * <p>3. 未来 `task.create.draft` 可以读取规则草案，生成待审批任务草稿。</p>
 *
 * <p>当前记录存在内存里，主要用于先固定工具链上下文语义。
 * 生产版本应迁移到数据库、Redis、事件存储或对象存储引用，避免服务重启后丢失，也避免大对象压在内存中。</p>
 */
public record AgentToolExecutionOutputRecord(
        /**
         * 会话 ID，用于隔离不同用户/不同工作空间。
         */
        String sessionId,

        /**
         * Run ID，用于限定输出只能在同一次编排内复用。
         */
        String runId,

        /**
         * 工具执行审计 ID，用于追溯输出来自哪一次具体工具执行。
         */
        String auditId,

        /**
         * 工具编码，例如 datasource.metadata.read。
         */
        String toolCode,

        /**
         * 工具结构化输出。
         */
        Map<String, Object> output,

        /**
         * 输出保存时间。
         */
        LocalDateTime createTime) {
}
