/**
 * @Author : Cui
 * @Date: 2026/05/25 00:03
 * @Description DataSmart Govern Backend - TaskDraftConvertRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.dto;

import lombok.Data;

/**
 * 草稿转换真实任务请求。
 *
 * <p>当前只保留转换说明，后续可以扩展幂等键、执行窗口、队列策略、审批单 ID 和任务模板版本。</p>
 */
@Data
public class TaskDraftConvertRequest {

    private String comment;
}
