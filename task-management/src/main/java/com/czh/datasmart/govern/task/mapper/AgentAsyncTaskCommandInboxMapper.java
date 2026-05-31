/**
 * @Author : Cui
 * @Date: 2026/05/31 16:42
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandInboxMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.task.entity.AgentAsyncTaskCommandInbox;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 异步工具命令 Inbox Mapper。
 *
 * <p>并发幂等的最终裁决依赖数据库唯一索引，而不是 JVM 内存锁：
 * 多个 task-management 实例同时收到同一条 Kafka 消息时，只有一个实例能够成功插入 Inbox。</p>
 */
@Mapper
public interface AgentAsyncTaskCommandInboxMapper extends BaseMapper<AgentAsyncTaskCommandInbox> {
}
