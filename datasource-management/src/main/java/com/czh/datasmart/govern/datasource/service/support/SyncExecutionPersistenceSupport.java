/**
 * @Author : Cui
 * @Date: 2026/05/05 18:58
 * @Description DataSmart Govern Backend - SyncExecutionPersistenceSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasource.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasource.entity.SyncExecution;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasource.support.SyncTaskState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 同步执行记录与检查点持久化支持组件。
 *
 * <p>该组件专门处理执行记录和检查点的查询、创建、校验和 upsert。
 * 它从 `SyncTaskServiceImpl` 中拆出后，主服务不再需要关心“执行记录编号如何递增”
 * 或“检查点如何按分片覆盖写入”这类持久化细节。
 *
 * <p>执行记录和检查点是商业化数据同步平台的关键可恢复性基础：
 * 1. 执行记录回答“任务哪一次执行、由谁触发、当前执行状态是什么”；
 * 2. 检查点回答“任务执行到哪里了，失败后能否续跑或回放”；
 * 3. 后续引入分片执行、CDC 位点、增量同步、水位线和断点续传时，这里会继续扩展。
 *
 * <p>当前仍使用 MyBatis-Plus Mapper 直接访问本地表，保持模块内实现简单。
 * 如果未来拆出独立执行器服务或调度服务，可以把这里演进成远程执行记录客户端。
 */
@Component
@RequiredArgsConstructor
public class SyncExecutionPersistenceSupport {

    /**
     * 同步执行记录 Mapper。
     */
    private final SyncExecutionMapper syncExecutionMapper;

    /**
     * 同步检查点 Mapper。
     */
    private final SyncCheckpointMapper syncCheckpointMapper;

    /**
     * 审计/文本支持组件。
     *
     * <p>这里复用统一截断逻辑，保证执行触发原因、检查点类型、检查点值和分片标识都遵循同一安全长度。
     */
    private final SyncAuditSupport syncAuditSupport;

    /**
     * 查询任务的执行记录列表。
     *
     * <p>按创建时间倒序返回，便于前端优先展示最近一次执行。
     */
    public List<SyncExecution> listExecutions(Long taskId) {
        return syncExecutionMapper.selectList(new LambdaQueryWrapper<SyncExecution>()
                .eq(SyncExecution::getSyncTaskId, taskId)
                .orderByDesc(SyncExecution::getCreateTime));
    }

    /**
     * 查询任务相关检查点。
     *
     * <p>检查点挂在 executionId 下面，因此需要先收集该任务的执行记录 ID。
     * 当前按 updateTime 倒序返回，便于运维人员优先看到最新位点。
     */
    public List<SyncCheckpoint> listCheckpoints(Long taskId) {
        List<Long> executionIds = listExecutions(taskId).stream()
                .map(SyncExecution::getId)
                .toList();
        if (executionIds.isEmpty()) {
            return List.of();
        }
        return syncCheckpointMapper.selectList(new LambdaQueryWrapper<SyncCheckpoint>()
                .in(SyncCheckpoint::getExecutionId, executionIds)
                .orderByDesc(SyncCheckpoint::getUpdateTime));
    }

    /**
     * 创建执行记录。
     *
     * <p>控制面在任务进入 RUNNING/RETRYING 时立即创建执行记录，
     * 可以确保“任务开始执行”这个事实有数据库落点，后续心跳、完成、失败和检查点都能挂到同一个 executionId。
     */
    public SyncExecution createExecution(SyncTask task, SyncTaskState state, Long actorId, String triggerReason) {
        SyncExecution execution = new SyncExecution();
        execution.setSyncTaskId(task.getId());
        execution.setExecutionNo(nextExecutionNo(task.getId()));
        execution.setState(state.name());
        execution.setStartedAt(LocalDateTime.now());
        execution.setRecordsRead(0L);
        execution.setRecordsWritten(0L);
        execution.setFailedRecordCount(0L);
        execution.setTriggeredBy(actorId);
        execution.setExecutorId(null);
        execution.setHeartbeatAt(null);
        execution.setLeaseExpireAt(null);
        execution.setTriggerReason(syncAuditSupport.truncate(triggerReason));
        syncExecutionMapper.insert(execution);
        return execution;
    }

    /**
     * 获取并校验指定执行记录属于当前任务。
     */
    public SyncExecution getRequiredExecution(SyncTask task, Long executionId) {
        SyncExecution execution = syncExecutionMapper.selectById(executionId);
        if (execution == null || !task.getId().equals(execution.getSyncTaskId())) {
            throw new NoSuchElementException("执行记录不存在或不属于当前任务: " + executionId);
        }
        return execution;
    }

    /**
     * 获取任务最近一次执行记录。
     *
     * <p>暂停、完成、失败等动作依赖最近一次执行记录。
     * 如果任务没有 lastExecutionId，说明它还没有进入执行阶段，调用方应立即失败。
     */
    public SyncExecution getLatestExecution(SyncTask task) {
        if (task.getLastExecutionId() == null) {
            throw new IllegalStateException("当前任务没有可操作的执行记录");
        }
        return getRequiredExecution(task, task.getLastExecutionId());
    }

    /**
     * 获取任务最近一次执行记录，如果不存在则返回 null。
     *
     * <p>取消、强制取消和租约恢复需要容忍“任务存在但执行记录不存在”的历史数据或边界场景。
     */
    public SyncExecution getLastExecutionIfPresent(SyncTask task) {
        return task.getLastExecutionId() == null ? null : syncExecutionMapper.selectById(task.getLastExecutionId());
    }

    /**
     * 检查点 upsert。
     *
     * <p>当前采用 executionId + shardOrPartition 的简化唯一语义：
     * 1. 单分片任务可以只写一个空 shard 的检查点；
     * 2. 分片任务可以按分片分别维护检查点；
     * 3. 后续 CDC/增量同步可以把 binlog 位点、offset、水位线等写入 checkpointValue。
     */
    public void upsertCheckpoint(Long executionId,
                                 String checkpointType,
                                 String checkpointValue,
                                 String shardOrPartition) {
        LambdaQueryWrapper<SyncCheckpoint> wrapper = new LambdaQueryWrapper<SyncCheckpoint>()
                .eq(SyncCheckpoint::getExecutionId, executionId)
                .eq(shardOrPartition != null && !shardOrPartition.isBlank(), SyncCheckpoint::getShardOrPartition, shardOrPartition);
        SyncCheckpoint checkpoint = syncCheckpointMapper.selectOne(wrapper);
        if (checkpoint == null) {
            checkpoint = new SyncCheckpoint();
            checkpoint.setExecutionId(executionId);
            checkpoint.setCheckpointType(syncAuditSupport.truncate(checkpointType));
            checkpoint.setCheckpointValue(syncAuditSupport.truncate(checkpointValue));
            checkpoint.setShardOrPartition(syncAuditSupport.truncate(shardOrPartition));
            syncCheckpointMapper.insert(checkpoint);
            return;
        }

        checkpoint.setCheckpointType(syncAuditSupport.truncate(checkpointType));
        checkpoint.setCheckpointValue(syncAuditSupport.truncate(checkpointValue));
        checkpoint.setShardOrPartition(syncAuditSupport.truncate(shardOrPartition));
        checkpoint.setUpdateTime(LocalDateTime.now());
        syncCheckpointMapper.updateById(checkpoint);
    }

    /**
     * 计算下一次执行编号。
     *
     * <p>当前用已有执行记录数量 + 1，适合早期单库控制面。
     * 高并发场景下如果同一任务可能被并发创建执行记录，应升级为数据库唯一约束、乐观锁或序列表。
     */
    private long nextExecutionNo(Long taskId) {
        Long count = syncExecutionMapper.selectCount(new LambdaQueryWrapper<SyncExecution>()
                .eq(SyncExecution::getSyncTaskId, taskId));
        return (count == null ? 0L : count) + 1L;
    }
}
