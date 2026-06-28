/**
 * @Author : Cui
 * @Date: 2026/06/29 00:09
 * @Description DataSmart Govern Backend - DataSyncDatasourceCapabilityProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * data-sync 调用 datasource-management 能力快照接口的配置。
 *
 * <p>这个配置类服务于“同步模板创建阶段”的跨微服务能力事实补全。data-sync 自己不保存
 * JDBC URL、账号、密码、topic、bucket、文件路径等敏感连接信息，也不应该为了判断 connector type
 * 去读取 datasource-management 的完整详情对象；正确做法是调用 datasource-management 暴露的低敏
 * capability snapshot 接口，只消费 connectorType、健康状态、能力标志、原因码和建议动作。</p>
 *
 * <p>为什么把这些值做成配置，而不是在客户端里写死：</p>
 * <p>1. 本地开发、单元测试、集成测试和生产环境的 datasource-management 地址不同；</p>
 * <p>2. 商业部署中可能通过 gateway、service mesh 或 Nacos 服务名访问，而不是直接 localhost；</p>
 * <p>3. 超时、是否校验调用方显式传入的 connector type、是否启用远程补全，都属于运行策略，应该可灰度调整；</p>
 * <p>4. 服务账号 Header 后续会升级为签名、mTLS 或网格鉴权，本类先保留 sourceService/actorRole 作为最小契约。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.data-sync.datasource-capability")
public class DataSyncDatasourceCapabilityProperties {

    /**
     * 是否启用 datasource-management 低敏能力快照调用。
     *
     * <p>默认开启，是因为当前项目正在从“前端/Agent 手动传 connector type”收敛到
     * “data-sync 按 datasourceId 自动读取可信能力事实”。如果某个本地开发环境暂时没有启动
     * datasource-management，可以在配置中显式设置为 false；关闭后，缺少两端 connector type 的旧模板
     * 会继续按历史兼容逻辑只做基础校验，但这不应该作为生产默认。</p>
     */
    private boolean enabled = true;

    /**
     * 是否在调用方已经显式传入 sourceConnectorType/targetConnectorType 时仍然回查 datasource-management。
     *
     * <p>默认关闭，避免每次模板创建都强依赖远程服务，降低当前收敛阶段的联调成本。后续进入更严格的
     * 商业部署时建议开启：开启后可以发现调用方把 datasourceId=MySQL 却伪造成 KAFKA 的不一致事实，
     * 从而防止 Agent 或前端绕过数据源真实类型。</p>
     */
    private boolean validateProvidedConnectorFacts = false;

    /**
     * datasource-management 的基础地址。
     *
     * <p>本地默认值使用 8082，匹配当前 README 和各模块 application.yml 的端口约定。生产环境可以替换为
     * 服务发现地址、内网域名、gateway 内部路由或 service mesh 虚拟服务地址。</p>
     */
    private String baseUrl = "http://localhost:8082";

    /**
     * 低敏能力快照路径模板。
     *
     * <p>路径中的 {datasourceId} 会由 RestClient 替换为具体 datasourceId。这里默认使用 internal 路由，
     * 因为 data-sync 是受信任的后端微服务，不应该走面向前端的公开路由再重复 PROJECT Header 判断。
     * 需要注意的是，internal 路由仍然只返回低敏快照，不会返回凭据或连接串。</p>
     */
    private String snapshotPathTemplate = "/internal/datasources/{datasourceId}/capability-snapshot";

    /**
     * 发送给 datasource-management 的调用方服务名。
     *
     * <p>datasource-management 当前会用 X-DataSmart-Source-Service 做最小白名单判断。该值只表示服务身份，
     * 不表示最终用户身份，也不应该被前端直接伪造。</p>
     */
    private String sourceService = "data-sync";

    /**
     * 发送给 datasource-management 的服务账号角色。
     *
     * <p>当前 internal 路由要求 SERVICE_ACCOUNT。后续如果升级到 HMAC/mTLS，本字段仍可作为审计和日志中的
     * 人类可读身份说明。</p>
     */
    private String actorRole = "SERVICE_ACCOUNT";

    /**
     * HTTP 连接建立超时时间，单位毫秒。
     *
     * <p>模板创建属于用户交互链路，不能因为 datasource-management 不可达而长时间挂起。默认 1000ms 是
     * 保守值：足够覆盖本地与同机房调用，又能在依赖异常时快速失败并返回明确的模板预检错误。</p>
     */
    private long connectTimeoutMs = 1000L;

    /**
     * HTTP 响应读取超时时间，单位毫秒。
     *
     * <p>能力快照应该是轻量读取，不执行连接测试或元数据发现，因此正常情况下应很快返回。默认 1500ms
     * 可以避免把模板创建请求拖成慢请求；如果生产环境跨机房访问，可按实际链路延迟调整。</p>
     */
    private long readTimeoutMs = 1500L;
}
