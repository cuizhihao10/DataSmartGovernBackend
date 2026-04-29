/**
 * @Author : Cui
 * @Date: 2026/04/27 22:20
 * @Description DataSmart Govern Backend - TaskCreateResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import lombok.Data;

/**
 * task-management 创建任务后的最小响应模型。
 *
 * <p>data-quality 当前只需要知道任务 ID、状态和类型，不需要复制 task-management 的完整实体。
 */
@Data
public class TaskCreateResponse {

    private Long id;

    private String type;

    private String status;

    private String priority;
}
