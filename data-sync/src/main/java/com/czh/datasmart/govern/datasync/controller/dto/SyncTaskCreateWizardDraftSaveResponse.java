/**
 * @Author : Cui
 * @Date: 2026/07/08 22:36
 * @Description DataSmart Govern Backend - SyncTaskCreateWizardDraftSaveResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步任务创建向导草稿保存响应。
 *
 * <p>taskId 是创建向导唯一需要保存的资源标识。后续步骤、恢复编辑、预检查和发布都围绕同一个任务进行。</p>
 */
@Data
public class SyncTaskCreateWizardDraftSaveResponse {

    /**
     * 草稿任务 ID。
     */
    private Long taskId;

    /**
     * 本次请求是否创建了新草稿。
     *
     * <p>true 表示第一步进入第二步时新建；false 表示第二步、第三步或用户恢复编辑时更新已有草稿。</p>
     */
    private boolean created;

    /**
     * 草稿任务当前主状态，正常应为 DRAFT。
     */
    private String currentState;

    /**
     * 草稿是否启用调度。
     *
     * <p>DRAFT 阶段固定为 false，即使选择的是定期全量或定期批量，也只保存 scheduleConfig，不让调度器扫描。</p>
     */
    private Boolean scheduleEnabled;

    /**
     * 下一次触发时间。
     *
     * <p>DRAFT 阶段固定为空；发布后才会由调度配置计算。</p>
     */
    private LocalDateTime nextFireTime;

    private String groupCode;
    private String groupName;

    /**
     * 后端建议前端下一步要做的事情。
     *
     * <p>例如进入对象映射后拉取两端元数据、进入字段步骤后逐对象配置字段映射、进入预检查步骤后自动运行预检查。</p>
     */
    private List<String> nextActions;

    /**
     * 最新草稿任务快照。
     */
    private SyncTask task;

    /**
     * 最新任务定义快照。
     */
    private SyncTaskDefinition definition;
}
