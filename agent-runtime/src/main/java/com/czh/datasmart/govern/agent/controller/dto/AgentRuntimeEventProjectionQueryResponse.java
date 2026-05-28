/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventProjectionQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent runtime event 投影查询响应。
 *
 * <p>除了事件列表外，响应中保留 appliedLimit 和 totalMatched，用于让前端明确当前是不是只看到了裁剪后的热窗口。
 * 后续接数据库分页时，可以继续扩展 pageNo/pageSize/hasNext，而不改变事件 item 的视图结构。</p>
 */
public record AgentRuntimeEventProjectionQueryResponse(
        int appliedLimit,
        int totalMatched,
        List<AgentRuntimeEventProjectionView> events
) {
}
