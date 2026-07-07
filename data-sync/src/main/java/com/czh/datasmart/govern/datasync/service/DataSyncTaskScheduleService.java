/**
 * @Author : Cui
 * @Date: 2026/07/07 23:06
 * @Description DataSmart Govern Backend - DataSyncTaskScheduleService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskScheduleDispatchRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskScheduleDispatchResult;

/**
 * data-sync 任务级定时调度服务。
 *
 * <p>该接口只负责“到点生成 execution”，不负责真实数据读写。真实执行继续由已有 worker loop 和
 * datasource-management run-once/离线 Runner 合同完成。这样可以保持控制面与执行面的边界清晰：</p>
 * <p>1. 任务调度服务关心 scheduleConfig、misfire、并发冲突和 execution 创建；</p>
 * <p>2. worker loop 关心 execution claim、lease、dispatch、complete/fail；</p>
 * <p>3. datasource-management 关心源/目标连接、SQL 生成、字段映射、脏数据和 JDBC 写入。</p>
 */
public interface DataSyncTaskScheduleService {

    /**
     * 执行一轮到期定时任务派发。
     *
     * @param request 扫描参数，可为空。
     * @param actorContext 当前调度触发者，后台任务会使用服务账号上下文。
     * @return 低敏调度结果摘要。
     */
    SyncTaskScheduleDispatchResult dispatchDueTasks(SyncTaskScheduleDispatchRequest request,
                                                    SyncActorContext actorContext);
}
