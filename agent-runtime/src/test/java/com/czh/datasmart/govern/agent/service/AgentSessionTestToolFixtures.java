/**
 * @Author : Cui
 * @Date: 2026/07/02 04:25
 * @Description DataSmart Govern Backend - AgentSessionTestToolFixtures.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.service.tool.AgentToolAdapter;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionContext;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionOutcome;

import java.util.Map;

/**
 * AgentSessionService 测试使用的无副作用工具适配器夹具。
 *
 * <p>夹具只返回固定低敏摘要，不连接真实 datasource-management，也不读取客户元数据。
 */
final class AgentSessionTestToolFixtures {

    private AgentSessionTestToolFixtures() {
    }

    static AgentToolAdapter metadataReadAdapterForTest() {
        return new AgentToolAdapter() {
            @Override
            public boolean supports(String toolCode) {
                return "datasource.metadata.read".equals(toolCode);
            }

            @Override
            public AgentToolExecutionOutcome execute(AgentToolExecutionContext context) {
                return AgentToolExecutionOutcome.succeeded(
                        "测试适配器模拟数据源元数据读取成功",
                        Map.of("datasourceId", context.audit().getTargetResourceId(), "tableCount", 2)
                );
            }
        };
    }
}
