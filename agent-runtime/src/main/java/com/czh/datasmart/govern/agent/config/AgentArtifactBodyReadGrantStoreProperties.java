/**
 * @Author : Cui
 * @Date: 2026/06/26 20:27
 * @Description DataSmart Govern Backend - AgentArtifactBodyReadGrantStoreProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * artifact 正文读取授权事实仓库配置。
 *
 * <p>正文读取 grant 是 Agent Host 控制面里的关键安全事实。它和普通的 HTTP token 不同：
 * grantDecisionReference 只是一段低敏审计引用，真正能否继续 final-check 或 object-store probe，
 * 必须由服务端回查 grant store 中的事实记录后决定。这样可以避免调用方伪造一个“格式正确”的引用，
 * 也为后续过期、撤销、审计导出、限流和 MySQL 持久化留下稳定扩展点。</p>
 *
 * <p>当前默认提供 memory store，适合本地学习、单元测试和单实例联调；生产环境最终应扩展 MySQL store，
 * 并配套迁移脚本、TTL 归档任务、管理员撤销接口和审计查询权限。配置类先把能力边界显式暴露出来，
 * 避免业务服务直接依赖某个具体存储实现。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.artifact-body-read-grants")
public class AgentArtifactBodyReadGrantStoreProperties {

    /**
     * grant fact 的承载介质。
     *
     * <p>memory：默认值，不要求本地启动 MySQL，适合开发、测试、单实例演示；JVM 重启后会丢失记录，
     * 多实例之间也不共享，因此不能被描述成完整生产持久化。</p>
     *
     * <p>mysql：预留生产化方向。切换到 mysql 时应同时开启
     * datasmart.agent-runtime.persistence.database-enabled=true，并先准备 grant fact 表、索引、归档策略和备份。</p>
     */
    private String store = "memory";

    /**
     * memory store 最多保留多少条 grant fact。
     *
     * <p>正文读取授权通常是短 TTL、高频、低敏的控制面事实。如果不设置容量上限，
     * 压测、异常 Agent 循环或大量批处理任务可能把 JVM 内存持续撑大。该值只保护 memory 模式；
     * 生产 MySQL 模式应通过表分区、TTL 清理、归档和分页查询控制数据规模。</p>
     */
    private Integer memoryMaxRecords = 10000;
}
