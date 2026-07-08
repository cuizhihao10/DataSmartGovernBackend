package com.czh.datasmart.govern.datasource.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - DataSourceConnectionTestResult.java
 * @Version:1.0.0
 *
 * 连接测试结果对象。
 * 这个对象不落库，主要用于接口返回和服务内部表达一次测试动作的结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceConnectionTestResult {

    /**
     * 被测试的数据源 ID。
     */
    private Long datasourceId;

    /**
     * 测试状态。
     */
    private String testStatus;

    /**
     * 测试说明消息。
     */
    private String message;

    /**
     * 测试时间。
     */
    private LocalDateTime testedAt;

    /**
     * 数据库产品名称。
     *
     * <p>连接测试原来只返回 SUCCESS/FAILED，用户无法判断这条连接到底连到了 MySQL、PostgreSQL，
     * 还是某个代理层。返回产品名称后，前端可以把“连到了什么”展示出来，排查误连环境、端口映射错误、
     * Docker 容器名错误时会直观很多。</p>
     */
    private String productName;

    /**
     * 数据库产品版本。
     *
     * <p>版本信息不参与业务判断，但它对连接器兼容性非常关键，例如 MySQL 5.7/8.0、PostgreSQL 13/17
     * 在元数据、JSON 类型、时区和 SQL 方言上都有差异。</p>
     */
    private String productVersion;

    /**
     * JDBC 驱动名称。
     *
     * <p>同一种数据库可能使用不同驱动或不同代理驱动。把驱动名称返回给前端，有助于学习和排障：
     * “连接成功”背后实际走的是 DriverManager 加载的哪个 JDBC 驱动。</p>
     */
    private String driverName;

    /**
     * 当前连接生效的 catalog。
     *
     * <p>对 MySQL 来说，catalog 基本等同于业务上常说的 database。连接测试成功但 catalog 为空时，
     * 后续创建任务很可能查不到业务表，所以这里必须把该事实显式暴露出来。</p>
     */
    private String currentCatalog;

    /**
     * 当前连接生效的 schema。
     *
     * <p>PostgreSQL/SQL Server 更依赖 schema 语义；MySQL 通常没有 PostgreSQL 风格 schema。
     * 前端可以据此解释为什么 MySQL 只开放“按表选择”，而 PostgreSQL 可以开放“按 schema/表选择”。</p>
     */
    private String currentSchema;

    /**
     * 本次连接测试是否同时验证到了“元数据可发现”。
     *
     * <p>这是为了解决“测试连接成功，但创建任务查不到表”的产品误导。JDBC 能打开连接只代表账号、网络、
     * 地址和密码基本正确；同步任务还需要账号能读取 DatabaseMetaData 并能发现用户表。</p>
     */
    private Boolean metadataDiscoverable;

    /**
     * 轻量元数据探测发现的用户表数量。
     *
     * <p>这里是轻量探测，不是完整元数据采集，所以只用于诊断“是否至少能发现表”。真正的表/字段详情仍由
     * /metadata/discover 接口按创建任务场景读取。</p>
     */
    private Integer discoveredTableCount;

    /**
     * 连接测试的诊断提示。
     *
     * <p>当 JDBC 连通但元数据不可见、MySQL URL 未指定 database、当前账号没有表权限、或探测结果为空时，
     * 后端会把低敏原因写到这里，前端不应再只显示一个“成功”。</p>
     */
    private List<String> warnings;

    public DataSourceConnectionTestResult(Long datasourceId,
                                          String testStatus,
                                          String message,
                                          LocalDateTime testedAt) {
        this.datasourceId = datasourceId;
        this.testStatus = testStatus;
        this.message = message;
        this.testedAt = testedAt;
        this.warnings = List.of();
    }
}
