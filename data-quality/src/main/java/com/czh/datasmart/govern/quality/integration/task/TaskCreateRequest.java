/**
 * @Author : Cui
 * @Date: 2026/04/27 22:20
 * @Description DataSmart Govern Backend - TaskCreateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import lombok.Data;

/**
 * 调用 task-management 创建任务接口的本地请求模型。
 *
 * <p>字段与 task-management 的 CreateTaskRequest 对齐，但定义在 data-quality 模块内。
 * 这样可以避免两个微服务在编译期直接依赖对方内部 DTO。
 */
@Data
public class TaskCreateRequest {

    private String name;

    private String description;

    private String type;

    private String params;

    private String priority;

    private Integer maxRetryCount;
}
