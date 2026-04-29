/**
 * @Author : Cui
 * @Date: 2026/04/28 19:46
 * @Description DataSmart Govern Backend - TaskFailRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import lombok.Data;

/**
 * 调用 task-management 标记任务失败接口的本地请求模型。
 *
 * <p>质量执行器如果 payload 校验失败、data-quality 回调失败、数据源扫描失败或自身异常，
 * 应同时调用 data-quality failTaskExecution 和 task-management failTask。
 * 前者保留质量域执行失败证据，后者让任务中心进入失败态并触发重试/运营关注逻辑。
 */
@Data
public class TaskFailRequest {

    /**
     * 失败原因。
     */
    private String errorMessage;
}
