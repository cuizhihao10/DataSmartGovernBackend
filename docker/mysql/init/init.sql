-- 初始化脚本：创建 Nacos 所需数据库
CREATE DATABASE IF NOT EXISTS nacos CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON nacos.* TO 'root'@'%' IDENTIFIED BY 'password';
FLUSH PRIVILEGES;

-- 初始化业务数据库
CREATE DATABASE IF NOT EXISTS datasmart_govern CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE datasmart_govern;

CREATE TABLE IF NOT EXISTS task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '任务ID',
    name VARCHAR(255) NOT NULL COMMENT '任务名称',
    description TEXT COMMENT '任务描述',
    type VARCHAR(50) NOT NULL COMMENT '任务类型',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',
    params TEXT COMMENT '任务参数(JSON)',
    progress INT DEFAULT 0 COMMENT '执行进度(0-100)',
    checkpoint TEXT COMMENT '断点续跑信息(JSON)',
    priority VARCHAR(10) DEFAULT 'MEDIUM' COMMENT '任务优先级',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    max_retry_count INT NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    start_time DATETIME COMMENT '开始执行时间',
    end_time DATETIME COMMENT '执行结束时间',
    result TEXT COMMENT '执行结果或错误信息',
    INDEX idx_status (status),
    INDEX idx_type (type),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务表';

CREATE TABLE IF NOT EXISTS task_execution_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '日志ID',
    task_id BIGINT NOT NULL COMMENT '任务ID',
    action VARCHAR(32) NOT NULL COMMENT '执行动作',
    from_status VARCHAR(20) COMMENT '变更前状态',
    to_status VARCHAR(20) COMMENT '变更后状态',
    message VARCHAR(255) NOT NULL COMMENT '日志摘要',
    operator VARCHAR(64) DEFAULT 'system' COMMENT '操作人',
    details TEXT COMMENT '日志详情',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    INDEX idx_task_id_create_time (task_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务执行日志表';

CREATE TABLE IF NOT EXISTS datasource_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '数据源ID',
    name VARCHAR(128) NOT NULL COMMENT '数据源名称',
    type VARCHAR(32) NOT NULL COMMENT '数据源类型',
    jdbc_url VARCHAR(512) NOT NULL COMMENT 'JDBC连接地址',
    username VARCHAR(128) NOT NULL COMMENT '连接用户名',
    password VARCHAR(256) NOT NULL COMMENT '连接密码',
    driver_class_name VARCHAR(256) NOT NULL COMMENT '驱动类名',
    description VARCHAR(512) COMMENT '数据源描述',
    status VARCHAR(32) NOT NULL COMMENT '数据源状态',
    last_test_status VARCHAR(32) DEFAULT 'UNKNOWN' COMMENT '最近一次连接测试状态',
    last_test_message VARCHAR(512) COMMENT '最近一次连接测试消息',
    last_test_time DATETIME COMMENT '最近一次连接测试时间',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_datasource_name (name),
    INDEX idx_datasource_type (type),
    INDEX idx_datasource_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据源配置表';

CREATE TABLE IF NOT EXISTS quality_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '质量规则ID',
    name VARCHAR(128) NOT NULL COMMENT '规则名称',
    rule_type VARCHAR(32) NOT NULL COMMENT '规则类型',
    target_object VARCHAR(256) NOT NULL COMMENT '规则作用对象',
    comparison_operator VARCHAR(16) NOT NULL COMMENT '比较运算符',
    expected_value DECIMAL(20,4) NOT NULL COMMENT '期望阈值',
    severity VARCHAR(16) NOT NULL COMMENT '严重级别',
    description VARCHAR(512) COMMENT '规则描述',
    status VARCHAR(32) NOT NULL COMMENT '规则状态',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_quality_rule_name (name),
    INDEX idx_quality_rule_type (rule_type),
    INDEX idx_quality_rule_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质量规则表';

CREATE TABLE IF NOT EXISTS quality_check_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '质量报告ID',
    rule_id BIGINT NOT NULL COMMENT '规则ID',
    rule_name VARCHAR(128) NOT NULL COMMENT '规则名称',
    target_object VARCHAR(256) NOT NULL COMMENT '检测对象',
    measured_value DECIMAL(20,4) NOT NULL COMMENT '实际观测值',
    expected_value DECIMAL(20,4) NOT NULL COMMENT '期望阈值',
    comparison_operator VARCHAR(16) NOT NULL COMMENT '比较运算符',
    check_status VARCHAR(16) NOT NULL COMMENT '检测结果状态',
    sample_size INT NOT NULL COMMENT '样本数量',
    exception_count INT NOT NULL COMMENT '异常数量',
    summary VARCHAR(1024) NOT NULL COMMENT '结果摘要',
    notes VARCHAR(1024) COMMENT '补充说明',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    INDEX idx_quality_report_rule_id (rule_id),
    INDEX idx_quality_report_status (check_status),
    INDEX idx_quality_report_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质量检测报告表';
