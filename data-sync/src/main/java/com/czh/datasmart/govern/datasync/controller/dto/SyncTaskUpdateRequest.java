/**
 * @Author : Cui
 * @Date: 2026/07/07 19:08
 * @Description DataSmart Govern Backend - SyncTaskUpdateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 同步任务编辑请求。
 *
 * <p>这个 DTO 对应“任务定义管理”里的编辑动作，而不是执行动作。真实数据同步产品里，用户经常需要先把任务保存为草稿，
 * 修改任务名称、负责人、分组、调度窗口或说明，再由“发布”动作把任务推进到 CONFIGURED / SCHEDULED / PENDING_APPROVAL。
 * 这样做能避免“用户只是改了一半配置，后台调度器却已经开始执行”的生产事故。</p>
 *
 * <p>当前编辑范围刻意收敛在任务定义字段，不允许修改 templateId、source/target datasource、字段映射和 where 条件。
 * 这些执行级配置仍然属于同步模板。如果未来要支持“任务级覆盖模板配置”，应新增专门的版本化配置表，并引入草稿版本、
 * 发布版本、审批版本和执行版本，不能简单在任务表上直接改写高风险字段。</p>
 */
@Data
public class SyncTaskUpdateRequest {

    /**
     * 任务名称。
     *
     * <p>名称主要用于列表、详情、审计摘要和 Agent 回复。为空表示不修改；如果传入空白字符串，服务端会拒绝，
     * 因为任务一旦进入运营台，必须有一个人类可识别的名称。</p>
     */
    @Size(max = 160, message = "同步任务名称不能超过 160 个字符")
    private String name;

    /**
     * 任务说明。
     *
     * <p>说明用于记录业务目的、同步范围、上线窗口、责任团队等低敏信息。它不会进入 worker plan，
     * 也不应包含密码、token、完整 SQL、样本数据或连接串。</p>
     */
    @Size(max = 1000, message = "同步任务说明不能超过 1000 个字符")
    private String description;

    /**
     * 任务优先级。
     *
     * <p>当前支持 LOW、MEDIUM、HIGH、URGENT。优先级未来可参与调度排序、队列容量、告警升级和 Agent 建议。
     * 本轮只保存字段，不改变 worker-loop 的认领顺序，避免在收敛阶段引入新的调度复杂度。</p>
     */
    @Size(max = 32, message = "同步任务优先级不能超过 32 个字符")
    private String priority;

    /**
     * 负责人 ID。
     *
     * <p>负责人影响 SELF 数据范围、列表筛选、告警归属和 Agent 后续解释“谁需要处理这个任务”。为空表示不修改。</p>
     */
    private Long ownerId;

    /**
     * 新分组编码。
     *
     * <p>为空表示不修改分组；如果希望清空分组，请显式设置 {@link #clearGroup}=true。
     * 之所以不用 null 表示清空，是因为 PATCH/PUT 请求里 null 既可能是“前端没传”，也可能是“用户想清空”，
     * 商用 API 不应让这种歧义进入任务管理状态机。</p>
     */
    @Size(max = 64, message = "任务分组编码不能超过 64 个字符")
    private String groupCode;

    /**
     * 新分组展示名称。
     *
     * <p>仅当同时传入 groupCode 时生效；展示名称不作为稳定唯一键，只用于前端与 Agent 回复。</p>
     */
    @Size(max = 128, message = "任务分组展示名称不能超过 128 个字符")
    private String groupName;

    /**
     * 是否清空任务分组。
     *
     * <p>true 表示把任务移出分组；false 或 null 表示不主动清空。该字段优先级高于 groupCode/groupName。</p>
     */
    private Boolean clearGroup;

    /**
     * 调度配置 JSON。
     *
     * <p>该字段保存 cron、interval、timezone、misfirePolicy 等调度元数据。编辑调度配置只会把任务退回 DRAFT，
     * 并关闭 scheduleEnabled；只有后续调用发布接口后，任务才可能重新进入 SCHEDULED 等待调度。</p>
     */
    @Size(max = 4000, message = "同步任务调度配置不能超过 4000 个字符")
    private String scheduleConfig;

    /**
     * 是否清空调度配置。
     *
     * <p>true 表示清空 scheduleConfig、关闭 scheduleEnabled、清空 nextFireTime，并把任务退回 DRAFT。
     * 该字段用于明确表达“取消周期调度配置”，避免把空字符串或 null 误判为清空。</p>
     */
    private Boolean clearScheduleConfig;

    /**
     * 运行模式。
     *
     * <p>常见值包括 TEMPLATE、MANUAL、SCHEDULED、BACKFILL、REPLAY。当前只做低敏保存和展示，
     * 真正执行模式仍由模板 syncMode、triggerType 和 execution 创建入口共同决定。</p>
     */
    @Size(max = 64, message = "同步任务运行模式不能超过 64 个字符")
    private String runMode;

    /**
     * 编辑原因。
     *
     * <p>原因会进入 data-sync 审计摘要。请只填写低敏说明，例如“调整每日同步窗口”“改为项目负责人负责”，
     * 不要填写 SQL、连接串、密码、token、样本数据或业务敏感明细。</p>
     */
    @Size(max = 500, message = "同步任务编辑原因不能超过 500 个字符")
    private String reason;
}
