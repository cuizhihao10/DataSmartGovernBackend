/**
 * @Author : Cui
 * @Date: 2026/04/28 19:46
 * @Description DataSmart Govern Backend - TaskCompleteRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import lombok.Data;

/**
 * 调用 task-management 标记任务完成接口的本地请求模型。
 *
 * <p>质量执行器完成扫描并成功回写 data-quality 报告后，应再调用 task-management complete，
 * 让任务中心的主状态也进入 SUCCESS。否则 data-quality 有报告，但 task-management 仍显示 RUNNING，
 * 两个模块状态会产生割裂。
 */
@Data
public class TaskCompleteRequest {

    /**
     * 完成结果摘要。
     *
     * <p>建议记录质量 executionId、reportId、检测结果、异常数量等信息，方便任务中心列表快速阅读。
     */
    private String result;
}
