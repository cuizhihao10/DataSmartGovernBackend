/**
 * @Author : Cui
 * @Date: 2026/05/31 23:20
 * @Description DataSmart Govern Backend - DataSyncAgentTaskExecutionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service;

import com.czh.datasmart.govern.datasync.controller.dto.AgentSyncTaskExecuteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.AgentSyncTaskExecuteResponse;

/**
 * Agent 专用的数据同步执行入口。
 *
 * <p>该接口不是公开同步任务 API 的替代品，而是为 task-management 的 Agent 异步工具 worker 提供
 * “幂等创建同步任务 + 幂等入队执行”的内部业务动作。</p>
 */
public interface DataSyncAgentTaskExecutionService {

    /**
     * 执行 Agent 触发的数据同步动作。
     *
     * @param request Agent 工具调用解析后的受控参数。
     * @return 同步任务与执行记录的创建/入队结果。
     */
    AgentSyncTaskExecuteResponse executeAgentSyncTask(AgentSyncTaskExecuteRequest request);
}
