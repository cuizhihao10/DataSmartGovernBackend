package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - ConnectorType.java
 * @Version:1.0.0
 *
 * 数据连接器类型枚举。
 * 这个枚举不是简单罗列“当前能连哪些数据库”，而是把数据同步产品未来的连接器抽象提前固化下来。
 * 这样做有两个直接收益：
 * 1. 当前代码虽然主要以 JDBC 数据源为主，但领域模型已经能容纳文件、对象存储、API、消息流等连接器家族。
 * 2. 后续接入真正的连接器 SPI、能力发现、健康探测、并发策略时，不需要推翻数据库字段和接口契约。
 *
 * 当前阶段并不是所有枚举值都已经具备真正的连接能力：
 * - 部分值目前只是“产品级预留”，用于保证建模和 API 方向正确；
 * - 真正的连接、读取、写入、采样能力会在后续连接器子系统中逐步实现。
 */
public enum ConnectorType {
    MYSQL,
    POSTGRESQL,
    SQL_SERVER,
    ORACLE,
    MONGODB,
    KAFKA,
    HIVE,
    CLICKHOUSE,
    FILE,
    OBJECT_STORAGE,
    REST_API;

    /**
     * 将外部输入归一化为平台内部统一的连接器类型。
     * 这里显式允许大小写不敏感，是为了降低前端调用、网关透传和外部集成时的摩擦成本。
     */
    public static ConnectorType fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的连接器类型: " + value));
    }
}
