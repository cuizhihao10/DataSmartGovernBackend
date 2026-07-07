/**
 * @Author : Cui
 * @Date: 2026/07/07 20:00
 * @Description DataSmart Govern Backend - SyncTaskBatchExportRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步任务批量导出请求。
 *
 * <p>普通导出接口按租户、项目、分组、状态等筛选条件导出，适合“导出一个视图范围”；
 * 批量导出接口则按前端或 Agent 明确选中的 taskId 列表导出，适合“用户勾选了这些任务，只导出这些任务”的场景。
 *
 * <p>生产级批量导出必须避免两个风险：</p>
 * <p>1. 不能因为列表页筛选条件变化而导出用户没有选中的任务；</p>
 * <p>2. 不能绕过任务详情的数据范围校验，普通用户仍然只能导出自己可见的任务。</p>
 */
@Data
public class SyncTaskBatchExportRequest {

    /**
     * 需要导出的同步任务 ID 列表。
     *
     * <p>这里限制单次最多 1000 个任务，是为了保护网关、data-sync 服务和浏览器下载链路。
     * 如果后续客户需要一次导出几万条任务定义，应升级为异步导出任务：先创建导出作业，再由后台生成文件并写入 MinIO。</p>
     */
    @NotEmpty(message = "批量导出任务 ID 列表不能为空")
    @Size(max = 1000, message = "单次批量导出最多支持 1000 个同步任务")
    private List<@NotNull(message = "批量导出任务 ID 不能包含空值") Long> taskIds = new ArrayList<>();

    /**
     * 导出文件格式。
     *
     * <p>允许值为 CSV、XLSX 或 EXCEL；为空时默认 CSV。
     * 批量导出的字段与普通导出保持一致，只包含低敏任务定义，不包含连接串、密码、完整 SQL 或样本数据。</p>
     */
    private String format;
}
