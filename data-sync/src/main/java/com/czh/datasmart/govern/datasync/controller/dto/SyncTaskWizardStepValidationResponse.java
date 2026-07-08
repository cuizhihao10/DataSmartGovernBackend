/**
 * @Author : Cui
 * @Date: 2026/07/08 15:22
 * @Description DataSmart Govern Backend - SyncTaskWizardStepValidationResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * 同步任务创建向导单步校验响应。
 *
 * <p>该响应把“能否进入下一步”和“为什么不能进入下一步”明确分开：</p>
 * <p>1. {@code passed=false} 表示当前步骤存在阻断项，前端应禁止进入下一步；</p>
 * <p>2. {@code warningMessages} 表示可以进入下一步，但建议用户补充配置或等待后端元数据发现/预检查；</p>
 * <p>3. {@code derivedRunMode} 和 {@code normalizedWriteStrategy} 是后端推导出的结果，前端不需要再让用户手工选择。</p>
 *
 * @param passed 当前步骤是否通过
 * @param stepCode 当前步骤编码
 * @param derivedRunMode 后端根据 syncMode 推导的运行方式，通常为 MANUAL 或 SCHEDULED
 * @param normalizedWriteStrategy 后端归一化后的写入策略，只应是 INSERT 或 UPDATE
 * @param blockingMessages 阻断进入下一步的问题
 * @param warningMessages 非阻断提示
 * @param nextStepActions 前端或 Agent 可以继续执行的下一步动作
 */
public record SyncTaskWizardStepValidationResponse(
        boolean passed,
        String stepCode,
        String derivedRunMode,
        String normalizedWriteStrategy,
        List<String> blockingMessages,
        List<String> warningMessages,
        List<String> nextStepActions
) {
}
