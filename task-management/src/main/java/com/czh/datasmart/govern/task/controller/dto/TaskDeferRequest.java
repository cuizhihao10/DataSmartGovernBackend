/**
 * @Author : Cui
 * @Date: 2026/04/29 23:06
 * @Description DataSmart Govern Backend - TaskDeferRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 执行器延迟回队列请求。
 *
 * <p>该 DTO 服务于 `POST /tasks/{id}/defer`。
 * 它不是给普通用户点按钮用的接口，而是给 data-quality、data-sync、Agent Runtime 等执行器在“已经认领任务，
 * 但当前不适合继续执行”时调用。
 *
 * <p>为什么需要 defer，而不是直接 fail：
 * 1. fail 表示业务执行确实失败，例如 SQL 报错、规则不支持、源表不存在；
 * 2. defer 表示系统做容量保护，例如当前 worker 并发满、单租户配额满、单数据源配额满、下游限流；
 * 3. 两者如果混在 FAILED 里，运营人员无法区分“代码/数据问题”和“容量/调度问题”，失败率指标也会误导扩容决策。
 */
@Data
public class TaskDeferRequest {

    /**
     * 当前执行 run ID。
     *
     * <p>defer 会结束当前 run 并把任务重新放回可认领队列，因此必须确认请求来自当前 run。
     * 如果旧 run 的 defer 迟到，就可能错误释放新 run 的租约，造成任务重复执行。
     */
    @NotNull(message = "执行 runId 不能为空")
    private Long runId;

    /**
     * 执行器实例 ID。
     *
     * <p>必须与任务当前租约持有者一致，防止非持有者随意把任务延迟回队列。
     */
    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    /**
     * 幂等键。
     *
     * <p>容量背压场景下，执行器可能因为 HTTP 超时重复调用 defer。
     * 幂等键用于识别同一次退避动作，避免重复增加 deferCount 或重复写入容量告警。
     */
    @NotBlank(message = "幂等键不能为空")
    private String idempotencyKey;

    /**
     * 延迟原因。
     *
     * <p>该字段会写入任务执行日志和当前 run 的 error_message，用于事故复盘和容量分析。
     * 建议调用方写清楚触发退避的维度，例如 GLOBAL 并发已满、TENANT 并发已满、DATASOURCE 并发已满、
     * 下游 HTTP 429、源库连接池耗尽等。
     */
    @NotBlank(message = "延迟原因不能为空")
    @Size(max = 1000, message = "延迟原因不能超过 1000 个字符")
    private String reason;

    /**
     * 延迟秒数。
     *
     * <p>任务中心会把 task.queued_time 设置为当前时间加该秒数。
     * 认领查询只会扫描 queued_time 已到期的 PENDING/DEFERRED 任务，因此该字段可以形成轻量级退避队列。
     * 当前限制 1 到 3600 秒，是为了避免错误配置导致任务被立刻疯狂重试，或被延迟到难以发现。
     */
    @Min(value = 1, message = "延迟秒数不能小于 1")
    @Max(value = 3600, message = "延迟秒数不能超过 3600")
    private Integer delaySeconds;
}
