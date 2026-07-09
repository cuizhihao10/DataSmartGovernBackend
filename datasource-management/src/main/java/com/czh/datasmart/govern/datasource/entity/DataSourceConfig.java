package com.czh.datasmart.govern.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - DataSourceConfig.java
 * @Version:1.0.0
 *
 * 数据源配置主表实体。
 * 这张表对应的是“平台登记过哪些外部数据源”。
 * 当前阶段重点不是直接读取外部业务数据，而是先把“配置登记、可用性验证、启停管理”打通。
 *
 * 从学习角度看，这张表很适合理解一个典型管理型模块的建模方式：
 * 1. 展示与识别字段。
 * 2. 技术连接信息。
 * 3. 生命周期状态。
 * 4. 最近一次检测结果。
 */
@Data
@TableName("datasource_config")
public class DataSourceConfig {

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID。
     * 数据源属于高敏感治理资源，同一个部署实例可能服务多个企业租户，因此必须从数据模型层保留租户维度。
     * 后续权限中心、审计报表、资源配额和成本统计都会依赖该字段判断“这条连接配置属于哪个客户”。
     */
    private Long tenantId;

    /**
     * 项目 ID。
     * 租户内部往往会继续拆分为多个业务项目，例如零售订单项目、风控标签项目、财务对账项目。
     * PROJECT 数据范围最终会落到该字段，避免项目负责人看到同租户其他项目的数据源连接和元数据。
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     * 工作空间通常比项目更细，适合表达“某个项目下的研发空间、生产空间、临时分析空间”等协作边界。
     * 当前先作为可选字段保留，后续可以继续扩展为空间级配额、空间级元数据缓存和空间级访问审计。
     */
    private Long workspaceId;

    /**
     * 数据源所有者 actorId。
     *
     * <p>数据源虽然挂在项目下用于项目级隔离，但它仍然有明确的个人所有者。所有者可以在
     * 已加入该项目的前提下维护连接信息、授权协作者使用数据源，并在需要时删除自己的数据源。
     * 这与项目 MANAGER/OWNER 的项目级管理权形成双保险：普通被授权用户可以协作使用或编辑，
     * 但不会因为拿到实例授权就自动获得删除能力。</p>
     */
    private Long ownerId;

    /**
     * 创建人 actorId。
     *
     * <p>通常与 ownerId 相同，单独保留是为了后续支持管理员代创建、Agent 代创建或资源转移：
     * createdBy 记录“谁创建了这条记录”，ownerId 记录“当前谁对这条数据源负主要责任”。</p>
     */
    private Long createdBy;

    /**
     * 当前请求 actor 对这条数据源实际可执行的低敏动作快照。
     *
     * <p>该字段不是数据库列，而是 Controller 根据项目角色、owner 关系和实例级授权动态计算后返回给前端。
     * 它只表达 VIEW/USE/MANAGE 这类实例能力：MANAGE 允许编辑和受控使用，但删除仍由后端单独要求 owner
     * 或项目 MANAGER/OWNER，避免前端把“可维护连接”误解成“可删除资源”。</p>
     */
    @TableField(exist = false)
    private List<String> effectiveActions;

    /**
     * 数据源名称。
     * 用于前台展示和人工识别。
     */
    private String name;

    /**
     * 数据源类型，例如 MYSQL、POSTGRESQL、SQLSERVER。
     */
    private String type;

    /**
     * 数据源用途：SOURCE / TARGET。
     *
     * <p>该字段是给数据同步产品使用的“业务角色”标记，而不是数据库连接能力本身。比如同样是 PostgreSQL，
     * 一个只读账号可以登记为 SOURCE，一个数据仓库写入账号可以登记为 TARGET。新建同步任务时，
     * 后端列表接口会按照用途过滤候选数据源，避免用户把源端和目标端选反。</p>
     */
    private String usagePurpose;

    /**
     * JDBC 连接地址。
     */
    private String jdbcUrl;

    /**
     * 用户名。
     */
    private String username;

    /**
     * 外部数据源连接密码的存储值。
     *
     * <p>安全边界说明：</p>
     * <p>1. 该字段在数据库中应保存为 {@code ENC[v1]} 格式的 AES-GCM 密文，历史明文仅作为升级兼容对象存在；</p>
     * <p>2. 运行时只有 datasource-management 内部连接测试、元数据发现、只读 SQL 和同步执行链路允许解密使用；</p>
     * <p>3. REST API 绝不能返回明文或密文，因此这里加 {@link JsonIgnore}，Controller 也会额外返回 password=null 的低敏副本；</p>
     * <p>4. 该字段不能改成 bcrypt/Argon2 哈希，因为 JDBC 连接必须拿到可还原的真实密码。</p>
     */
    @JsonIgnore
    private String password;

    /**
     * 驱动类名。
     * 该字段由 type 推导得到，保存后便于后续连接测试直接使用。
     */
    private String driverClassName;

    /**
     * 数据源描述。
     */
    private String description;

    /**
     * 业务状态：ACTIVE / INACTIVE / DELETED。
     */
    private String status;

    /**
     * 最近一次连接测试状态。
     */
    private String lastTestStatus;

    /**
     * 最近一次连接测试返回消息。
     */
    private String lastTestMessage;

    /**
     * 最近一次连接测试时间。
     */
    private LocalDateTime lastTestTime;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
