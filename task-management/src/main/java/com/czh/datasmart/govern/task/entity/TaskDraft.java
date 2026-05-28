/**
 * @Author : Cui
 * @Date: 2026/05/24 23:59
 * @Description DataSmart Govern Backend - TaskDraft.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务草稿实体。
 *
 * <p>这张表承载“还没有进入调度队列的任务意图”。它解决的是 Agent、模板、人工配置台
 * 和审批流之间的缓冲问题：用户或 Agent 可以先生成任务草稿，经过编辑、提交、审批后，
 * 再转换为真正的 `task` 记录。</p>
 *
 * <p>为什么不直接给 task 表增加 DRAFT 状态：</p>
 * <p>1. 当前 task 表已经被执行器认领逻辑当作轻量队列使用，混入 DRAFT 容易误调度；</p>
 * <p>2. 草稿需要保存来源工具、审批人、转换任务 ID 等审批上下文，和执行任务快照职责不同；</p>
 * <p>3. 独立表可以让草稿列表、审批工作台、真实任务队列各自保持清晰边界。</p>
 */
@Data
@TableName("task_draft")
public class TaskDraft {

    /**
     * 草稿主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 草稿名称，通常会成为真实任务名称。
     */
    private String name;

    /**
     * 草稿说明，描述任务目标、业务背景、风险说明和审批依据。
     */
    private String description;

    /**
     * 目标任务类型，例如 DATA_QUALITY_SCAN、DATA_SYNC、MANUAL_REVIEW。
     */
    private String type;

    /**
     * 租户 ID，用于多租户隔离。
     */
    private Long tenantId;

    /**
     * 负责人 ID，转换为真实任务时默认写入 task.owner_id。
     */
    private Long ownerId;

    /**
     * 项目 ID，用于 PROJECT 数据范围、项目审批和项目级队列治理。
     */
    private Long projectId;

    /**
     * 草稿状态，取值来自 TaskDraftStatus。
     */
    private String status;

    /**
     * 草稿参数 JSON。
     *
     * <p>当前保持字符串形式，方便承接 Agent 输出、任务模板参数、同步配置和质量规则建议。
     * 真实转换前仍应由任务类型对应的校验器做 schema 校验。</p>
     */
    private String params;

    /**
     * 建议优先级，转换真实任务时会写入 task.priority。
     */
    private String priority;

    /**
     * 建议最大重试次数。
     */
    private Integer maxRetryCount;

    /**
     * 建议最大连续退避次数。
     */
    private Integer maxDeferCount;

    /**
     * 草稿来源类型，例如 AGENT、TEMPLATE、MANUAL、API。
     */
    private String sourceType;

    /**
     * 来源引用，例如 Agent auditId、templateId、外部工单 ID。
     */
    private String sourceRef;

    /**
     * 创建人 ID。
     */
    private Long createdBy;

    /**
     * 提交审批人 ID。
     */
    private Long submittedBy;

    /**
     * 审批人 ID。
     */
    private Long approvedBy;

    /**
     * 审批说明或拒绝原因。
     */
    private String approvalComment;

    /**
     * 转换出的真实任务 ID。
     */
    private Long convertedTaskId;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    private LocalDateTime updateTime;

    /**
     * 提交审批时间。
     */
    private LocalDateTime submitTime;

    /**
     * 审批时间。
     */
    private LocalDateTime approvalTime;

    /**
     * 转换真实任务时间。
     */
    private LocalDateTime convertTime;
}
