/**
 * @Author : Cui
 * @Date: 2026/05/09 20:18
 * @Description DataSmart Govern Backend - DataSyncExecutorSecurityProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * data-sync 执行器服务账号签名配置。
 *
 * <p>执行器协议和普通用户 API 的风险模型不同：普通用户请求通常由 gateway 解析登录态、角色、菜单和数据权限，
 * 但 worker / executor 是后台机器进程，如果仍然只相信 {@code X-Actor-Role} 这类可伪造 Header，
 * 那么任何能访问 data-sync 内网地址的进程都有机会伪装成执行器并篡改执行状态。
 *
 * <p>因此这里提供一套轻量 HMAC 服务账号签名配置，用于保护 claim、heartbeat、defer、start、checkpoint、complete、fail
 * 这些“机器协议动作”。它不是为了替代 gateway RBAC，而是补齐微服务内部调用的第二道边界：
 * 1. gateway / permission-admin 负责判断“这个入口是否允许访问”；
 * 2. data-sync 自己负责判断“调用者是否真的是被平台发放密钥的执行器服务账号”；
 * 3. 租约、幂等和状态机继续负责判断“这次动作在业务状态上是否允许发生”。
 *
 * <p>当前阶段默认关闭强制校验，是为了不破坏本地开发和还没有接入真实 worker 的环境。
 * 商用部署时应开启 {@code enabled=true}，并通过环境变量、Nacos 或密钥管理系统注入 {@code secrets}，
 * 不建议把生产密钥明文提交到 Git。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.data-sync.executor.security")
public class DataSyncExecutorSecurityProperties {

    /**
     * 是否启用执行器服务账号签名校验。
     *
     * <p>false 表示当前仍处于开发兼容模式，控制器不会拒绝缺少签名的执行器请求；
     * true 表示所有接入该校验组件的机器协议接口都必须携带合法签名，否则返回未认证错误。
     */
    private boolean enabled = false;

    /**
     * 签名时间戳允许的最大时钟偏差，单位秒。
     *
     * <p>执行器和 data-sync 可能部署在不同节点，系统时间存在少量偏差是正常现象；
     * 但窗口过大又会扩大重放攻击空间，所以默认 300 秒。
     */
    private long allowedClockSkewSeconds = 300L;

    /**
     * 本实例内 nonce 防重放缓存的最大容量。
     *
     * <p>nonce 用于表达“一次请求只能被接受一次”。当前阶段先使用本地内存缓存，
     * 可以防住单实例内的短窗口重放；多实例生产环境后续应升级为 Redis / Caffeine 集群缓存，
     * 让不同 data-sync 实例之间也共享防重放事实。
     */
    private int maxNonceCacheSize = 100000;

    /**
     * HMAC 算法名称。
     *
     * <p>默认 HmacSHA256 是目前服务端签名协议的通用基线，安全性和实现复杂度比较均衡。
     * 如果客户环境有国密要求，后续可以在这一层扩展 SM3/HMAC 或适配 KMS 签名。
     */
    private String signatureAlgorithm = "HmacSHA256";

    /**
     * 服务账号 ID Header 名称。
     */
    private String serviceAccountIdHeader = "X-DataSmart-Service-Account-Id";

    /**
     * 请求时间戳 Header 名称。
     *
     * <p>当前约定使用 Unix epoch 毫秒，便于 Java、Go、Python、Node.js worker 统一实现。
     */
    private String timestampHeader = "X-DataSmart-Service-Account-Timestamp";

    /**
     * 请求随机数 Header 名称。
     *
     * <p>同一个服务账号在允许时间窗口内不能复用同一个 nonce。
     */
    private String nonceHeader = "X-DataSmart-Service-Account-Nonce";

    /**
     * 签名值 Header 名称。
     *
     * <p>服务端同时接受 Base64 和十六进制两种 HMAC 输出，方便不同语言的执行器快速接入。
     */
    private String signatureHeader = "X-DataSmart-Service-Account-Signature";

    /**
     * 服务账号密钥映射。
     *
     * <p>key 是服务账号 ID，例如 {@code sync-executor-default}、{@code tenant-1001-worker}；
     * value 是对应的共享密钥。生产环境应从配置中心或密钥系统注入，避免进入代码仓库。
     */
    private Map<String, String> secrets = new LinkedHashMap<>();

    /**
     * 返回经过边界裁剪后的时钟偏差窗口。
     */
    public long effectiveAllowedClockSkewSeconds() {
        if (allowedClockSkewSeconds < 30L) {
            return 30L;
        }
        return Math.min(allowedClockSkewSeconds, 3600L);
    }

    /**
     * 返回经过边界裁剪后的 nonce 缓存容量。
     */
    public int effectiveMaxNonceCacheSize() {
        if (maxNonceCacheSize < 1000) {
            return 1000;
        }
        return Math.min(maxNonceCacheSize, 1000000);
    }
}
