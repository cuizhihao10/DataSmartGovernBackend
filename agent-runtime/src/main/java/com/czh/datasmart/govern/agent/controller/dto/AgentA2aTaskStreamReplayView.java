/**
 * @Author : Cui
 * @Date: 2026/06/06 13:03
 * @Description DataSmart Govern Backend - AgentA2aTaskStreamReplayView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * A2A Task stream replay 预览。
 *
 * <p>A2A streaming 更像实时体验，不能替代可靠历史。真实产品里客户端断线后，应通过 task 查询和 sequence cursor
 * 恢复状态，而不是要求服务端永久保留所有 stream 连接上下文。本视图用于说明 DataSmart 将如何使用历史事件支持恢复。</p>
 *
 * @param replaySupported 是否支持 replay
 * @param nextSequenceCursor 下一次查询或订阅可使用的 sequence 游标
 * @param historyLengthApplied 本次 preview 实际返回的历史条数
 * @param streamContract stream 事件契约说明
 * @param recoveryPolicy 断线恢复策略
 * @param unsupportedOperations 当前仍未启用的操作
 */
public record AgentA2aTaskStreamReplayView(
        boolean replaySupported,
        String nextSequenceCursor,
        int historyLengthApplied,
        List<String> streamContract,
        List<String> recoveryPolicy,
        List<String> unsupportedOperations
) {
}
