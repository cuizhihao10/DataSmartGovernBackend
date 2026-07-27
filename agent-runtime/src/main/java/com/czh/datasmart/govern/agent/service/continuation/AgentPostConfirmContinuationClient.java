/**
 * @Author : Cui
 * @Date: 2026/07/27 00:00
 * @Description DataSmart Govern Backend - AgentPostConfirmContinuationClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.continuation;

import com.czh.datasmart.govern.agent.controller.dto.AgentPostConfirmContinuationRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentPostConfirmContinuationView;

/** 可替换的 Java -> Python 确认后续跑客户端。 */
public interface AgentPostConfirmContinuationClient {

    AgentPostConfirmContinuationView continueAfterConfirmedTools(AgentPostConfirmContinuationRequest request);
}
