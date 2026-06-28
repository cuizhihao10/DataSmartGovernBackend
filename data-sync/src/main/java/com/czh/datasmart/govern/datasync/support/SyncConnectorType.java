/**
 * @Author : Cui
 * @Date: 2026/06/28 23:28
 * @Description DataSmart Govern Backend - SyncConnectorType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 数据同步连接器类型。
 *
 * <p>这个枚举不是为了把所有连接器实现一次性写完，而是先固定“产品层应该认识哪些连接器家族”。
 * 如果没有统一的 connector type，后续模板校验、执行器调度、并发配额、checkpoint 策略和运营看板都会各自写一套字符串，
 * 项目很快会变成“每接一个数据库就复制一遍逻辑”。</p>
 *
 * <p>设计原则：</p>
 * <ul>
 *     <li>枚举值表达连接器家族，不表达某个具体实例；真实连接串、账号、库名、topic、bucket 都必须保存在 datasource-management
 *     或密钥系统中，data-sync 只消费低敏的类型与能力。</li>
 *     <li>同一连接器家族可以支持读、写、全量、增量、流式、离线导入导出等多种能力，具体由能力矩阵描述，不要把能力塞进枚举名。</li>
 *     <li>新增连接器时优先扩展能力注册表和校验测试，再接真实 worker，避免 API 已开放但执行器并不支持。</li>
 * </ul>
 */
public enum SyncConnectorType {

    /**
     * MySQL 关系型数据库连接器。
     */
    MYSQL,

    /**
     * PostgreSQL 关系型数据库连接器。
     */
    POSTGRESQL,

    /**
     * SQL Server 企业数据库连接器。
     */
    SQL_SERVER,

    /**
     * Oracle 企业数据库连接器。
     */
    ORACLE,

    /**
     * MongoDB 文档型数据库连接器。
     */
    MONGODB,

    /**
     * Kafka 消息与流式数据连接器。
     */
    KAFKA,

    /**
     * Hive 数仓或大数据批处理连接器。
     */
    HIVE,

    /**
     * ClickHouse 分析型数据库连接器。
     */
    CLICKHOUSE,

    /**
     * 本地或上传文件连接器，例如 CSV、Excel、JSON、Parquet。
     */
    FILE,

    /**
     * 对象存储连接器，例如 MinIO、S3-compatible 存储。
     */
    OBJECT_STORAGE,

    /**
     * REST API 连接器。
     */
    REST_API
}
