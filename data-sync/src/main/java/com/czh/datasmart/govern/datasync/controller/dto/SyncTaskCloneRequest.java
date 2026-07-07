/**
 * @Author : Cui
 * @Date: 2026/07/07 18:15
 * @Description DataSmart Govern Backend - SyncTaskCloneRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

/**
 * 同步任务克隆请求。
 *
 * <p>克隆不是简单复制数据库行，而是一个面向产品运营的“派生任务”动作：
 * 来源任务可能已经成功、失败、下线或进入回收站，但新任务必须拥有独立名称、独立生命周期和独立 execution 历史。
 * 因此请求体只允许覆盖低风险字段，例如名称、描述、负责人、是否保留调度配置和是否立即执行。</p>
 */
@Data
public class SyncTaskCloneRequest {

    /**
     * 新任务名称。
     *
     * <p>为空时服务端会生成“原名称-copy-时间戳”形式的低冲突名称。生产环境导入/克隆时通常还会有 taskCode，
     * 当前表结构尚未引入 taskCode，所以先以 tenant + project + name 做应用层冲突提示。</p>
     */
    private String name;

    /**
     * 新任务说明。
     *
     * <p>为空时继承来源任务说明。说明只用于运营展示，不参与执行器计划生成。</p>
     */
    private String description;

    /**
     * 新任务负责人。
     *
     * <p>为空时默认继承来源任务负责人；如果来源任务没有负责人，则由服务层使用当前操作者作为负责人。</p>
     */
    private Long ownerId;

    /**
     * 克隆后任务所属分组编码。
     *
     * <p>为空时默认继承来源任务分组。这样用户在“订单域同步任务”详情中克隆新任务时，新任务仍在同一业务分组下，
     * 便于后续组级查看、导出和批量运营。如果希望把克隆任务放到新分组，可显式传入新的 groupCode；
     * 如果希望取消分组，后续应通过专门的移组接口执行，避免克隆请求里 null 的含义变得模糊。</p>
     */
    private String groupCode;

    /**
     * 克隆后任务所属分组展示名称。
     *
     * <p>为空时默认继承来源任务分组名称；如果指定 groupCode 但未指定 groupName，服务端会使用 groupCode
     * 作为展示名称兜底。展示名称不作为稳定引用，后续允许通过分组重命名接口统一调整。</p>
     */
    private String groupName;

    /**
     * 是否保留来源任务调度配置。
     *
     * <p>默认 false。克隆出来的任务通常先进入 DRAFT，让用户确认目标表、where 条件、调度窗口和容量风险。
     * 如果设置为 true，仅复制 scheduleConfig 文本，不会自动开启 scheduleEnabled，也不会写 nextFireTime。</p>
     */
    private Boolean keepScheduleConfig;

    /**
     * 克隆成功后是否立即手工执行一次。
     *
     * <p>默认 false。为 true 时，服务端会在克隆任务通过执行前预检后创建 MANUAL execution，并把新任务置为 QUEUED。
     * 注意这不是启用周期调度，周期调度仍需要后续编辑/发布能力显式打开。</p>
     */
    private Boolean runImmediately;
}
