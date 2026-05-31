/**
 * @Author : Cui
 * @Date: 2026/06/01 14:20
 * @Description DataSmart Govern Backend - AgentRunToolDagConfirmationProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DAG selected-node 确认记录配置。
 *
 * <p>selected-node outbox enqueue 是 Agent 工具执行链路里第一次真正产生下游副作用的入口：
 * 用户、智能网关或 Python Agent loop 先看到 dry-run 预案，再显式确认其中一批 ready 节点进入 command outbox。
 * 如果只把确认结果放在 HTTP 响应中，后续排查时就很难回答“谁在什么时候确认了哪版预案、确认后生成了哪些 command”。
 * 因此这里把确认事实抽象成独立仓储，并通过配置决定使用 memory 还是后续 MySQL 实现。</p>
 *
 * <p>当前默认仍使用 memory，是为了保持本地学习、单元测试和无数据库联调的轻量启动体验。
 * 但配置命名和表结构会先固定下来，后续把 store 切到 mysql 时，上层 selected-node 服务不需要重写。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.tool-dag.confirmations")
public class AgentRunToolDagConfirmationProperties {

    /**
     * 是否启用确认记录。
     *
     * <p>生产环境建议保持 true。关闭后 selected-node 仍可入 outbox，但会缺失独立确认事实，
     * 审计、策略版本复核、管理员补偿台和用户争议排查都会变弱。</p>
     */
    private boolean enabled = true;

    /**
     * 确认记录仓储类型。
     *
     * <p>可选值：</p>
     * <p>1. memory：默认值，适合本地学习和单元测试，服务重启会丢失；</p>
     * <p>2. mysql：预留生产形态，配合 {@code agent_run_tool_dag_confirmation} 表实现多实例共享和重启恢复。</p>
     */
    private String store = "memory";

    /**
     * 确认记录有效期秒数。
     *
     * <p>确认记录不是永久业务资产，它表达的是某次 dry-run 预案在某个时间窗口内被确认。
     * 设置过期时间的意义是：后续执行前策略版本校验、补偿台展示和审计导出可以区分“近期有效确认”
     * 与“历史确认事实”。默认 30 分钟偏保守，避免用户打开旧审批卡片很久后继续执行。</p>
     */
    private long confirmationTtlSeconds = 1800;

    /**
     * 单个 run 在 memory 仓储中最多保留的确认记录数。
     *
     * <p>这个上限用于保护 JVM 内存，也能暴露产品侧的一个真实问题：
     * 如果一个 run 需要非常多次人工确认，后续应考虑把 run 拆成阶段、批次或审批任务，而不是无限增长确认列表。</p>
     */
    private int maxConfirmationsPerRun = 200;

    /**
     * 当前 JVM 内所有确认记录的总上限。
     *
     * <p>生产 MySQL 实现应使用保留周期、归档任务和 tenant/project 条件查询控制数据量。
     * memory 实现只能用硬上限避免异常请求或压力测试把本地进程打爆。</p>
     */
    private int maxTotalRecords = 2000;
}
