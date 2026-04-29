/**
 * @Author : Cui
 * @Date: 2026/04/28 19:46
 * @Description DataSmart Govern Backend - TaskResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * task-management 任务快照的本地响应模型。
 *
 * <p>这里只复制 data-quality 执行器真正需要的字段，而不是完整依赖 task-management 实体。
 * 这种“局部合同”模式能降低微服务之间的编译期耦合，也能让 data-quality 明确知道自己消费了哪些远程字段。
 */
@Data
public class TaskResponse {

    /**
     * 任务 ID。
     */
    private Long id;

    /**
     * 任务名称。
     */
    private String name;

    /**
     * 任务类型，例如 DATA_QUALITY_SCAN。
     */
    private String type;

    /**
     * 任务状态，例如 PENDING、RUNNING、SUCCESS、FAILED。
     */
    private String status;

    /**
     * 任务参数 JSON。
     *
     * <p>质量执行器会用 `QualityTaskPayloadParser` 解析该字段。
     */
    private String params;

    /**
     * 当前进度。
     */
    private Integer progress;

    /**
     * 当前执行记录 ID。
     */
    private Long currentExecutionRunId;

    /**
     * 当前执行器 ID。
     */
    private String currentExecutorId;

    /**
     * 租约过期时间。
     */
    private LocalDateTime leaseExpireTime;

    /**
     * 任务结果或失败原因摘要。
     */
    private String result;
}
