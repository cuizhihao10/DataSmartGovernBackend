/**
 * @Author : Cui
 * @Date: 2026/07/08 22:36
 * @Description DataSmart Govern Backend - SyncTaskCreateWizardDraftSaveResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步任务创建向导草稿保存响应。
 *
 * <p>响应同时返回 taskId 与 templateId，是为了让前端后续步骤继续保存到同一条草稿，而不是每点击一次下一步就创建一套新模板和新任务。
 * 这也是“第二步以后任务视为保存”的关键：前端拿到 taskId 后即可在任务列表展示、关闭后重新进入编辑。</p>
 */
@Data
public class SyncTaskCreateWizardDraftSaveResponse {

    /**
     * 草稿任务 ID。
     */
    private Long taskId;

    /**
     * 草稿任务绑定的模板 ID。
     */
    private Long templateId;

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
     * 最新草稿模板快照。
     */
    private SyncTemplate template;
}
