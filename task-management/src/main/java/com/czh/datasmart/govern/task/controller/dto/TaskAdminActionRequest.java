package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/04/27 00:55
 * @Description DataSmart Govern Backend - TaskAdminActionRequest.java
 * @Version:1.0.0
 *
 * 任务管理员动作请求。
 *
 * <p>暂停、恢复、取消、强制重试这类动作不是普通字段更新，而是会改变任务生命周期的运营决策。
 * 商业系统里这类动作必须能解释“为什么这么做”，否则事故复盘时只能看到状态变化，看不到业务原因。
 */
@Data
public class TaskAdminActionRequest {

    /**
     * 操作原因。
     *
     * <p>例如：
     * - Kafka 积压过高，暂停低优先级任务；
     * - 下游数据源故障，取消正在运行任务；
     * - 修复目标表权限后，人工触发失败任务重试。
     */
    @Size(max = 500, message = "操作原因不能超过 500 个字符")
    private String reason;

    /**
     * 是否忽略重试次数上限。
     *
     * <p>该字段主要服务强制重试场景。
     * 普通 retry 应遵守 maxRetryCount，防止异常任务无限消耗资源；
     * 但生产事故中，平台管理员或运营人员可能已经修复外部依赖，需要额外补偿一次。
     * 这时允许通过受控接口忽略上限，并把该决定写入执行日志。
     */
    private Boolean ignoreRetryLimit;
}
