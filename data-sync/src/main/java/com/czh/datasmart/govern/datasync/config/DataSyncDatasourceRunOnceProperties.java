/**
 * @Author : Cui
 * @Date: 2026/06/29 12:36
 * @Description DataSmart Govern Backend - DataSyncDatasourceRunOnceProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * data-sync 调用 datasource-management run-once 执行入口的配置。
 *
 * <p>该配置服务于“同步执行闭环”的跨微服务调用阶段。和 datasource capability snapshot 不同，
 * run-once 会触发真实源端读取和目标端写入，属于有副作用的内部机器协议，因此必须比能力快照更保守：
 * 默认只允许 data-sync 服务账号调用 datasource-management 的 internal 路由，后续生产环境应继续叠加 gateway HMAC、
 * mTLS、服务网格身份或 permission-admin 机器账号策略。</p>
 *
 * <p>为什么先做 HTTP 配置而不是直接引入 datasource-management 模块依赖：</p>
 * <p>1. data-sync 和 datasource-management 是两个独立微服务，业务边界应通过显式 JSON 契约交互；</p>
 * <p>2. 直接 Java 依赖会让 data-sync 编译期耦合 datasource-management 内部 DTO 和执行类，后续拆服务、灰度和独立部署都会变难；</p>
 * <p>3. HTTP client 先隔离在接口后面，未来如果切换为 gRPC、Kafka command、服务网格代理或本地 SDK，只需替换客户端实现。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.data-sync.datasource-run-once")
public class DataSyncDatasourceRunOnceProperties {

    /**
     * 是否启用 datasource-management run-once 调用。
     *
     * <p>默认开启代表当前收敛阶段希望进入真实批处理闭环。如果本地只启动 data-sync、不启动 datasource-management，
     * 可以临时关闭；关闭后派发服务会 fail-closed，而不是让 execution 无限保持 RUNNING。</p>
     */
    private boolean enabled = true;

    /**
     * datasource-management 基础地址。
     *
     * <p>本地默认端口 8082 对齐当前 README。生产可替换为 Nacos 服务名、内部 gateway、service mesh 虚拟服务地址或专用内网域名。</p>
     */
    private String baseUrl = "http://localhost:8082";

    /**
     * datasource-management 的内部单批执行路径。
     */
    private String runOncePath = "/internal/sync-batch-runs/run-once";

    /**
     * datasource-management 的内部 splitPk 范围探测路径。
     *
     * <p>AUTO_SPLIT_PK 需要先知道源端 splitPk 的 min/max，才能生成确定的 ID_RANGE 分片账本。
     * 该路径只做只读探测，不执行目标端写入；仍然必须走服务账号 internal 路由，避免普通用户绕过 data-sync
     * 状态机直接探测业务表范围。</p>
     */
    private String partitionRangeProbePath = "/internal/sync-partitions/range-probe";

    /**
     * datasource-management 的元数据发现路径模板。
     *
     * <p>SCHEMA_FULL 和 DATABASE_FULL 不应该由 data-sync 自己直连源库扫描表清单，
     * 因为 datasource-management 才是数据源连接、权限、缓存和元数据能力的所有者。
     * data-sync 只通过该路径请求“低敏表结构摘要”，再把结果转换为 OBJECT_LIST 执行计划。</p>
     */
    private String metadataDiscoveryPathTemplate = "/datasources/{datasourceId}/metadata/discover";

    /**
     * datasource-management 的受控只读 SQL 执行路径模板。
     *
     * <p>该路径当前主要服务 {@code CUSTOM_SQL_QUERY} 创建向导的 SQL 检查：
     * data-sync 不直接持有数据源密码、不直接打开 JDBC 连接，而是把“只读 SQL 是否安全、语法是否可执行、
     * SQL 中引用的源端表/字段是否存在、最终输出列名/别名是什么”委托给 datasource-management。
     * 这样可以让数据源连接、权限、审计、SQL 脱敏和查询超时继续集中在 datasource-management 中治理。</p>
     */
    private String readOnlySqlExecutePathTemplate = "/datasources/{datasourceId}/sql/read-only/execute";

    /**
     * 发送给 datasource-management 的服务名 Header。
     */
    private String sourceService = "data-sync";

    /**
     * 发送给 datasource-management 的服务账号角色 Header。
     */
    private String actorRole = "SERVICE_ACCOUNT";

    /**
     * HTTP 连接建立超时时间。
     *
     * <p>run-once 会触发真实读写，耗时可能高于 capability snapshot，但连接建立不应长时间阻塞 worker 租约。</p>
     */
    private long connectTimeoutMs = 1500L;

    /**
     * HTTP 响应读取超时时间。
     *
     * <p>当前最小 run-once 是单批执行，默认 10 秒用于本地与小批量场景。生产应结合批大小、目标端性能、worker lease TTL 调整。</p>
     */
    private long readTimeoutMs = 10000L;

    /**
     * 默认读取批大小。
     *
     * <p>data-sync 生成 datasource run-once 执行计划时会带上该建议值。它不是强制值，真实 JDBC reader 可以结合方言和连接器能力调整。</p>
     */
    private int defaultFetchSize = 512;

    /**
     * 默认写入批大小。
     */
    /**
     * 单次 data-sync 派发允许连续调用 datasource-management run-once 的最大批次数。
     *
     * <p>该配置用于模拟 DataX 风格的受控批次循环：data-sync 负责调度和状态机，
     * datasource-management 负责 Java Reader/Writer 的真实抽取与写入。设置上限可以避免
     * offset、where 条件或方言实现异常时形成无限重复读写。</p>
     */
    private int maxRunOnceBatches = 100;

    private int defaultWriteBatchSize = 256;

    /**
     * 默认写入提交间隔。
     */
    private int defaultCommitIntervalRecords = 256;

    /**
     * 默认单批超时时间，单位秒。
     */
    private int defaultTimeoutSeconds = 600;

    /**
     * 默认最大重试次数。
     *
     * <p>当前 data-sync 尚未实现外层多批循环和退避重试，因此先设为 0。后续接 worker loop 后再统一纳入 retryPolicy。</p>
     */
    private int defaultMaxRetryCount = 0;

    /**
     * 默认脏数据数量阈值。
     *
     * <p>对齐 DataX 的 maxDirtyNumber 思路：少量坏数据可以结构化落盘并继续处理，超过阈值则让任务失败或转人工。
     * 生产环境应按租户、连接器、任务类型配置更精细的策略。</p>
     */
    private long defaultMaxDirtyRecordCount = 100L;

    /**
     * 默认脏数据比例阈值。
     *
     * <p>例如 0.01 表示当前批次或累计上下文中脏数据比例超过 1% 时，执行面应建议 fail-closed。</p>
     */
    private double defaultMaxDirtyRecordRatio = 0.01D;
}
