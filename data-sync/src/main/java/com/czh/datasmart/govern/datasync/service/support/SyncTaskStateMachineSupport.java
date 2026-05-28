/**
 * @Author : Cui
 * @Date: 2026/05/07 21:30
 * @Description DataSmart Govern Backend - SyncTaskStateMachineSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 同步任务状态机支撑组件。
 *
 * <p>状态机独立出来，是为了防止 ServiceImpl 里散落大量 if/else。
 * 对数据同步这种任务型产品来说，状态流转是核心业务规则：哪些状态能运行、哪些状态能暂停、失败后如何重试、
 * 部分成功是否允许补偿，都应该有统一入口。
 */
@Component
public class SyncTaskStateMachineSupport {

    private static final Set<SyncTaskState> RUN_ALLOWED_STATES = Set.of(
            SyncTaskState.CONFIGURED,
            SyncTaskState.SCHEDULED,
            SyncTaskState.PAUSED,
            SyncTaskState.FAILED,
            SyncTaskState.PARTIALLY_SUCCEEDED
    );

    /**
     * 校验任务是否允许进入队列。
     */
    public void assertCanQueue(String currentState) {
        SyncTaskState state = resolveState(currentState);
        if (!RUN_ALLOWED_STATES.contains(state)) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前状态不允许进入同步队列: " + state.name());
        }
    }

    /**
     * 解析状态编码。
     */
    public SyncTaskState resolveState(String state) {
        if (state == null || state.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "同步任务状态不能为空");
        }
        try {
            return SyncTaskState.valueOf(state.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "未知同步任务状态: " + state);
        }
    }
}
