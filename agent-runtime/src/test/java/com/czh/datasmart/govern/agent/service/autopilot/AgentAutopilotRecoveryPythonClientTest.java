/**
 * @Author : Cui
 * @Date: 2026/08/11 22:20
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryPythonClientTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.tool.AgentToolDownstreamHttpSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that malformed Python planner transport contracts are retryable technical failures.
 *
 * <p>A raw HTTP server is used instead of a mock fluent RestClient so this test reaches the same HTTP/1.1 request
 * factory selected for Python Runtime traffic in production. It does not need a real Python service or Kafka broker.</p>
 */
class AgentAutopilotRecoveryPythonClientTest {

    /**
     * A real data-sync retry receipt must be followed by the fixed two-role verification contract.
     *
     * <p>This test proves that Java sends the receipt-bound task/execution and accepts the response only when both
     * PRECHECK_AGENT and MONITOR_AGENT completed. It does not invoke real specialists or a Kafka broker.</p>
     */
    @Test
    void shouldVerifyPostRecoveryActionAgainstBothSpecialistRoles() throws IOException {
        withPostRecoveryResponse("""
                {"schemaVersion":"datasmart.autopilot.post-recovery-verification.v1",
                 "status":"VERIFIED","eventId":"event-1","taskId":31,"executionId":41,
                 "executedRoles":["MONITOR_AGENT","PRECHECK_AGENT"],
                 "completedRoles":["MONITOR_AGENT","PRECHECK_AGENT"],
                 "batchStatus":"COMPLETED","checkpointThreadId":"autopilot-post-recovery:abc",
                "replayed":false,
                 "payloadPolicy":"LOW_SENSITIVE_AUTOPILOT_POST_RECOVERY_VERIFICATION_ONLY"}
                """, (client, requestBody) -> {
            assertThat(client.verifyPostRecoveryAction(
                    trigger(), recoveryCase(), "RETRY_EXECUTION", retryReceipt()).status())
                    .isEqualTo("VERIFIED");
            assertThat(requestBody.get()).contains("\"eventId\":\"event-1\"");
            assertThat(requestBody.get()).contains("\"taskId\":31");
            assertThat(requestBody.get()).contains("\"executionId\":41");
            assertThat(requestBody.get()).contains("\"caseId\":81");
            assertThat(requestBody.get()).contains("\"recoveryAction\":\"RETRY_EXECUTION\"");
        });
    }

    /** A missing MONITOR fact must remain a technical failure so Kafka cannot acknowledge early. */
    @Test
    void shouldRejectIncompletePostRecoverySpecialistResponse() throws IOException {
        withPostRecoveryResponse("""
                {"schemaVersion":"datasmart.autopilot.post-recovery-verification.v1",
                 "status":"VERIFIED","eventId":"event-1","taskId":31,"executionId":41,
                 "executedRoles":["PRECHECK_AGENT"],"completedRoles":["PRECHECK_AGENT"],
                 "batchStatus":"COMPLETED","checkpointThreadId":"autopilot-post-recovery:abc",
                 "replayed":false,
                 "payloadPolicy":"LOW_SENSITIVE_AUTOPILOT_POST_RECOVERY_VERIFICATION_ONLY"}
                """, (client, ignored) -> assertThatThrownBy(() -> client.verifyPostRecoveryAction(
                trigger(), recoveryCase(), "RETRY_EXECUTION", retryReceipt()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PYTHON_AUTOPILOT_POST_RECOVERY_VERIFICATION_RESPONSE_INVALID"));
    }

    /**
     * An empty successful HTTP response cannot be interpreted as a business attention decision.
     *
     * <p>The client must throw {@link IllegalStateException}, allowing the Consumer to propagate the failure to its
     * retry topic. Treating this as a {@code PlatformBusinessException} would risk acknowledging an event whose Python
     * planning result was never actually received.</p>
     */
    @Test
    void shouldTreatEmptyPlannerResponseAsTechnicalFailure() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/autopilot/recovery/plan", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            AgentAutopilotRecoveryPythonClient client = client(
                    "http://127.0.0.1:" + server.getAddress().getPort());

            assertThatThrownBy(() -> client.plan(trigger()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("PYTHON_AUTOPILOT_RECOVERY_PLANNER_RESPONSE_INVALID");
        } finally {
            server.stop(0);
        }
    }

    /**
     * A 2xx response with an unknown schema version cannot be safely interpreted as the documented planner contract.
     *
     * <p>HTTP success only proves that a server responded. The client must still reject a response whose schema could
     * have different field meanings, then let the consumer count planning as failed and delegate redelivery to Kafka.
     * No business callback is created from this untrusted payload.</p>
     */
    @Test
    void shouldTreatUnexpectedPlannerSchemaAsTechnicalFailure() throws IOException {
        withPlannerResponse("""
                {"schemaVersion":"datasmart.autopilot.recovery-candidate.v2","eventId":"event-1",
                "status":"ATTENTION_REQUIRED","reasonCode":"RECOVERY_REQUIRES_REVIEW",
                "errorFingerprint":"%s","payloadPolicy":"LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY"}
                """.formatted("a".repeat(64)), client -> assertThatThrownBy(() -> client.plan(trigger()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PYTHON_AUTOPILOT_RECOVERY_PLANNER_RESPONSE_INVALID"));
    }

    /**
     * A schema-compatible payload with an unrecognized status must not be counted as a successful plan.
     *
     * <p>The finite status set is part of the Java/Python contract. Rejecting an invented status here keeps it out of
     * execution and produces the same retryable technical exception as other malformed planner responses.</p>
     */
    @Test
    void shouldTreatUnknownPlannerStatusAsTechnicalFailure() throws IOException {
        withPlannerResponse("""
                {"schemaVersion":"datasmart.autopilot.recovery-candidate.v1","eventId":"event-1",
                "status":"RECOVERY_COMPLETE","reasonCode":"RECOVERY_REQUIRES_REVIEW",
                "errorFingerprint":"%s","payloadPolicy":"LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY"}
                """.formatted("a".repeat(64)), client -> assertThatThrownBy(() -> client.plan(trigger()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PYTHON_AUTOPILOT_RECOVERY_PLANNER_RESPONSE_INVALID"));
    }

    /**
     * A valid finite blocking status remains available to the consumer as a durable non-execution outcome.
     *
     * <p>This positive case prevents the schema validation from accidentally treating every non-candidate response as
     * a transport error. The consumer will later persist this response's low-sensitive reason before acknowledging the
     * Kafka record.</p>
     */
    @Test
    void shouldAcceptKnownAttentionRequiredPlannerResponse() throws IOException {
        withPlannerResponse("""
                {"schemaVersion":"datasmart.autopilot.recovery-candidate.v1","eventId":"event-1",
                "status":"ATTENTION_REQUIRED","reasonCode":"RECOVERY_REQUIRES_REVIEW",
                "errorFingerprint":"%s","payloadPolicy":"LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY"}
                """.formatted("a".repeat(64)), client -> assertThat(client.plan(trigger()).status())
                .isEqualTo("ATTENTION_REQUIRED"));
    }

    /**
     * Creates the verified trigger shape that the Python client reads when building its fixed request body.
     *
     * <p>The session is mocked only for its already-verified workspace key. The test exercises response classification,
     * not session persistence, so no delegation or authorization object needs to be fabricated.</p>
     *
     * @return verified trigger with a stable low-sensitive event identity
     */
    private AgentAutopilotVerifiedRecoveryTrigger trigger() {
        AgentSessionRecord session = mock(AgentSessionRecord.class);
        when(session.getWorkspaceKey()).thenReturn("tenant:11:project:13");
        AgentAutopilotRecoveryTriggerEvent event = new AgentAutopilotRecoveryTriggerEvent(
                "datasmart.autopilot.recovery-trigger.v1", "event-1", "session-1", "run-1",
                11L, 12L, 13L, "14", "14", "main-agent", "delegation-1",
                31L, 40L, 41L, 1, 5, "2099-01-01T00:00:00Z", "a".repeat(64),
                0, null, List.of("OBJECT_TRANSFER_FAILED"), Map.of(),
                "sha256:" + "c".repeat(64), "2026-08-11T00:00:00Z");
        return new AgentAutopilotVerifiedRecoveryTrigger(
                event, session, null, null,
                OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                OffsetDateTime.parse("2026-08-11T00:00:00Z"));
    }

    /**
     * Creates the real Python client with a controlled local base URL.
     *
     * <p>The shared HTTP support keeps its production timeout and HTTP/1.1 selection behavior, while the properties
     * object limits the test to one known service address.</p>
     *
     * @param pythonBaseUrl local raw-server base URL
     * @return Python client under test
     */
    private AgentAutopilotRecoveryPythonClient client(String pythonBaseUrl) {
        return client(pythonBaseUrl, RestClient.builder());
    }

    /** Creates a client around a caller-owned builder for focused transport tests. */
    private AgentAutopilotRecoveryPythonClient client(String pythonBaseUrl, RestClient.Builder builder) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("python-ai-runtime", pythonBaseUrl);
        return new AgentAutopilotRecoveryPythonClient(
                builder, new AgentToolDownstreamHttpSupport(properties));
    }

    /** Builds the persisted data-sync case used to bind post-action verification. */
    private AgentAutopilotRecoveryCaseView recoveryCase() {
        return new AgentAutopilotRecoveryCaseView(
                81L, 31L, 40L, 41L, "RECOVERY_STARTED", 1L, 1, 5,
                "RETRY_EXECUTION", null, "c".repeat(64), "d".repeat(64));
    }

    /** Builds the real retry receipt shape expected from data-sync. */
    private AgentAutopilotRecoveryRetryReceipt retryReceipt() {
        return new AgentAutopilotRecoveryRetryReceipt(
                31L, 41L, 2, "QUEUED", "RETRYING");
    }

    /** Callback used by the raw HTTP helper after it has captured the request body. */
    @FunctionalInterface
    private interface PostRecoveryAssertion {
        /** Execute assertions against the client and the body captured by the server. */
        void accept(
                AgentAutopilotRecoveryPythonClient client,
                java.util.function.Supplier<String> requestBody);
    }

    /**
     * Serve one fixed post-action response through the same HTTP/1.1 path used in production.
     *
     * <p>The request body is read by the server before the assertion runs. Because the client call itself happens
     * inside the assertion, the server stores the body in a one-element array and the callback reads it after the
     * request completes. This avoids MockRestServiceServer, whose request factory is intentionally replaced by the
     * production Python HTTP/1.1 safeguard.</p>
     */
    private void withPostRecoveryResponse(
            String responseBody,
            PostRecoveryAssertion assertion) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String[] capturedBody = {""};
        server.createContext(
                "/internal/agent/autopilot/recovery/post-action-verification",
                exchange -> {
                    capturedBody[0] = new String(
                            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            AgentAutopilotRecoveryPythonClient client = client(
                    "http://127.0.0.1:" + server.getAddress().getPort());
            /*
             * Run the client first through a small wrapper, then expose the captured body. The assertion itself may
             * need to assert a thrown response-contract exception, so it owns the invocation; body checks in the
             * successful case are performed after that invocation by reading the mutable holder.
             */
            assertion.accept(client, () -> capturedBody[0]);
        } finally {
            server.stop(0);
        }
    }

    /**
     * Runs one callback against a local HTTP server that always returns the supplied planner JSON document.
     *
     * <p>The helper owns the server lifecycle so every test gets an isolated port and the server is stopped even when
     * an assertion fails. It deliberately exposes only a fixed endpoint and response body, keeping the tests focused on
     * client-side 2xx contract validation rather than real Python Runtime behavior.</p>
     *
     * @param responseBody JSON document returned with HTTP 200
     * @param assertion callback that exercises the client configured for this temporary server
     * @throws IOException when the embedded HTTP server cannot bind a local port
     */
    private void withPlannerResponse(String responseBody,
                                     java.util.function.Consumer<AgentAutopilotRecoveryPythonClient> assertion)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/autopilot/recovery/plan", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertion.accept(client("http://127.0.0.1:" + server.getAddress().getPort()));
        } finally {
            server.stop(0);
        }
    }
}
