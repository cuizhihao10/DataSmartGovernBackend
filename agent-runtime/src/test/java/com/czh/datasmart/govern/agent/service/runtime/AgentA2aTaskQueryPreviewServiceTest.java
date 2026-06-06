/**
 * @Author : Cui
 * @Date: 2026/06/06 13:03
 * @Description DataSmart Govern Backend - AgentA2aTaskQueryPreviewServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskHistoryEventView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskQueryPreviewResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2A Task 查询预览服务测试。
 *
 * <p>这组测试保护 5.30 的边界：我们只是在演示未来 task 查询如何消费 5.29 的事件契约，而不是实现真实 `tasks/get`。
 * 因此测试关注低敏响应形态、historyLength 行为、artifact 引用、终态/中断态语义和敏感字段不泄露。</p>
 */
class AgentA2aTaskQueryPreviewServiceTest {

    private AgentA2aTaskQueryPreviewService service;

    @BeforeEach
    void setUp() {
        service = new AgentA2aTaskQueryPreviewService();
    }

    /**
     * 验证 completed 场景可以从事件历史恢复终态任务和 artifact 引用。
     *
     * <p>真实 `GetTask` 最终也应该是类似聚合：task fact 提供当前状态，runtime event history 提供可回放历史，
     * artifact metadata 提供结果引用。这里不读取正文，确保查询响应不会变成敏感数据出口。</p>
     */
    @Test
    void completedScenarioShouldExposeTerminalTaskAndArtifactReference() {
        AgentA2aTaskQueryPreviewResponse response = service.buildPreview("completed", null);

        assertTrue(response.previewOnly());
        assertFalse(response.taskEndpointEnabled());
        assertEquals("completed", response.scenario());
        assertEquals("TASK_STATE_COMPLETED", response.task().currentState());
        assertTrue(response.task().terminal());
        assertFalse(response.task().interrupted());
        assertEquals(4, response.task().sequence());
        assertEquals(4, response.historyEvents().size());
        assertEquals(1, response.artifactReferences().size());
        assertTrue(response.artifactReferences().getFirst().metadataOnly());
        assertEquals("after-sequence:4", response.streamReplay().nextSequenceCursor());
        assertFalse(response.pushPreview().pushEnabled());
    }

    /**
     * 验证 historyLength 只截取尾部历史。
     *
     * <p>A2A 查询允许限制 history 长度。真实服务端必须防止调用方一次性拉取无界历史，否则 task 查询会变成
     * 大规模审计导出接口。本测试确保 preview 也体现这个产品边界。</p>
     */
    @Test
    void historyLengthShouldReturnTailEventsOnly() {
        AgentA2aTaskQueryPreviewResponse response = service.buildPreview("completed", 2);
        List<Long> sequences = response.historyEvents()
                .stream()
                .map(AgentA2aTaskHistoryEventView::sequence)
                .toList();

        assertEquals(List.of(3L, 4L), sequences);
        assertEquals(2, response.streamReplay().historyLengthApplied());
    }

    /**
     * 验证中断态场景会展示可继续动作，但不会生成 artifact 引用。
     *
     * <p>input-required 和 auth-required 都是“等待外部条件”的中断态，不是失败，也不是完成。前端可以提示用户补充输入
     * 或完成审批，但不能展示不存在的结果 artifact。</p>
     */
    @Test
    void interruptedScenariosShouldExposeContinueOperationsWithoutArtifacts() {
        AgentA2aTaskQueryPreviewResponse inputRequired = service.buildPreview("input-required", null);
        AgentA2aTaskQueryPreviewResponse authRequired = service.buildPreview("auth_required", null);

        assertEquals("TASK_STATE_INPUT_REQUIRED", inputRequired.task().currentState());
        assertTrue(inputRequired.task().interrupted());
        assertTrue(inputRequired.task().allowedClientOperations().contains("continue-with-input"));
        assertTrue(inputRequired.artifactReferences().isEmpty());

        assertEquals("auth-required", authRequired.scenario());
        assertEquals("TASK_STATE_AUTH_REQUIRED", authRequired.task().currentState());
        assertTrue(authRequired.task().interrupted());
        assertTrue(authRequired.task().allowedClientOperations().contains("continue-after-authorization"));
        assertTrue(authRequired.artifactReferences().isEmpty());
    }

    /**
     * 验证失败、取消和未知场景的边界。
     *
     * <p>失败和取消都属于终态，不能继续订阅实时更新；未知场景回退到 completed，避免管理端调试时返回不可解释状态。</p>
     */
    @Test
    void terminalAndUnknownScenariosShouldBeHandledConservatively() {
        AgentA2aTaskQueryPreviewResponse failed = service.buildPreview("failed", null);
        AgentA2aTaskQueryPreviewResponse canceled = service.buildPreview("canceled", null);
        AgentA2aTaskQueryPreviewResponse unknown = service.buildPreview("not-a-scenario", null);

        assertEquals("TASK_STATE_FAILED", failed.task().currentState());
        assertTrue(failed.task().terminal());
        assertTrue(failed.streamReplay().unsupportedOperations().contains("终态 task 不支持继续订阅实时更新。"));

        assertEquals("TASK_STATE_CANCELED", canceled.task().currentState());
        assertTrue(canceled.task().terminal());
        assertFalse(canceled.task().cancelRequested());

        assertEquals("completed", unknown.scenario());
        assertEquals("TASK_STATE_COMPLETED", unknown.task().currentState());
    }

    /**
     * 验证 working 场景仍然是非终态，且完成时间为空。
     *
     * <p>这条测试保护“进行中任务”和“终态任务”的基本差异。真实查询如果把非终态任务也填 completedAt，
     * 前端和外部 Agent 会误判任务已经结束。</p>
     */
    @Test
    void workingScenarioShouldRemainNonTerminal() {
        AgentA2aTaskQueryPreviewResponse response = service.buildPreview("working", 0);

        assertEquals("TASK_STATE_WORKING", response.task().currentState());
        assertFalse(response.task().terminal());
        assertNull(response.task().completedAt());
        assertTrue(response.historyEvents().isEmpty());
        assertEquals("after-sequence:2", response.streamReplay().nextSequenceCursor());
    }

    /**
     * 验证查询预览不泄露敏感 payload 英文键或内部执行细节。
     *
     * <p>中文注释和中文响应可以解释“不返回什么”，但序列化响应里不应该出现容易被外部 Agent 当作字段契约的敏感英文键。</p>
     */
    @Test
    void previewShouldNotExposeSensitivePayloadTokens() throws Exception {
        String serialized = objectMapper()
                .writeValueAsString(service.buildPreview("completed", null))
                .toLowerCase(Locale.ROOT);
        List<String> forbiddenTokens = List.of(
                "toolarguments",
                "targetendpoint",
                "api-key",
                "secret",
                "\"prompt\"",
                "modeloutput",
                "sample data",
                "drop table",
                "internal.example.com"
        );

        for (String token : forbiddenTokens) {
            assertFalse(serialized.contains(token), "查询预览不应包含敏感 token：" + token);
        }
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
