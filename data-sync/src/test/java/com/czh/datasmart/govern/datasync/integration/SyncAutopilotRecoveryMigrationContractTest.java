/**
 * @Author : Cui
 * @Date: 2026/08/10 10:00
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryMigrationContractTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SyncAutopilotRecoveryMigrationContractTest {

    @Test
    void v20ShouldPersistTheAutopilotCaseAndReceiptConcurrencyFacts() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/postgresql/data-sync/V20__autopilot_recovery_case.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(migration).contains("CREATE TABLE data_sync_autopilot_recovery_case");
            assertThat(migration).contains("authorization_digest");
            assertThat(migration).contains("policy_digest");
            assertThat(migration).contains("root_execution_id");
            assertThat(migration).contains("current_execution_id");
            assertThat(migration).contains("version BIGINT NOT NULL DEFAULT 0");
            assertThat(migration).contains("CREATE TABLE data_sync_autopilot_recovery_receipt");
            assertThat(migration).contains("UNIQUE (receipt_id)");
            assertThat(migration).contains("ck_data_sync_autopilot_case_state");
        }
    }

    /**
     * V21 必须提供可重启、可退避、可死信的 Kafka trigger outbox，而不是只建一张消息表。
     */
    @Test
    void v21ShouldPersistTheAutopilotTriggerDeliveryStateMachine() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/postgresql/data-sync/V21__autopilot_recovery_trigger_outbox.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(migration).contains("CREATE TABLE data_sync_autopilot_recovery_trigger_outbox");
            assertThat(migration).contains("UNIQUE (event_id)");
            assertThat(migration).contains("root_execution_id");
            assertThat(migration).contains("current_execution_id");
            assertThat(migration).contains("attempt_count");
            assertThat(migration).contains("next_retry_at");
            assertThat(migration).contains("DEAD_LETTER");
            assertThat(migration).contains("ix_data_sync_autopilot_trigger_due");
        }
    }

    /**
     * V22 must bind consumer results to the original trigger without giving persistence a model-text column.
     */
    @Test
    void v22ShouldPersistOnlyCompactDurableConsumerResultFacts() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/postgresql/data-sync/V22__autopilot_recovery_trigger_consumer_result.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(migration).contains("ALTER TABLE data_sync_autopilot_recovery_trigger_outbox");
            assertThat(migration).contains("consumer_result_digest");
            assertThat(migration).contains("consumer_result_status");
            assertThat(migration).contains("consumer_result_reason_code");
            assertThat(migration).contains("consumer_result_case_id");
            assertThat(migration).contains("consumed_at");
            assertThat(migration).contains("ck_data_sync_autopilot_trigger_consumer_result");
            assertThat(migration).contains("RECOVERY_STARTED");
            assertThat(migration).contains("ATTENTION_REQUIRED");
        }
    }
    @Test
    void v23ShouldPersistOnlyTheBoundedSidecarReplayFacts() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/postgresql/data-sync/V23__autopilot_recovery_sidecar_compensation.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(migration).contains("CREATE TABLE data_sync_autopilot_recovery_sidecar_compensation");
            assertThat(migration).contains("UNIQUE (compensation_key)");
            assertThat(migration).contains("TRIGGER_FAILURE");
            assertThat(migration).contains("SUCCESS_FINALIZATION");
            assertThat(migration).contains("RETRY_WAIT");
            assertThat(migration).contains("DEAD_LETTER");
            assertThat(migration).contains("claim_token VARCHAR(64)");
            assertThat(migration).contains("max_attempt_count INTEGER NOT NULL");
            assertThat(migration).contains("ix_data_sync_autopilot_sidecar_due");
            assertThat(migration).doesNotContain("exception_message");
            assertThat(migration).doesNotContain("payload_json");
        }
    }

    /** V24 persists replay-safe autonomous quarantine completion without storing row IDs or source data. */
    @Test
    void v24ShouldPersistLowSensitiveAutonomousQuarantineReceipts() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/postgresql/data-sync/V24__autopilot_recovery_quarantine_receipt.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(migration).contains("CREATE TABLE data_sync_autopilot_recovery_quarantine_receipt");
            assertThat(migration).contains("UNIQUE (receipt_id)");
            assertThat(migration).contains("request_digest CHAR(64)");
            assertThat(migration).contains("preview_digest CHAR(64)");
            assertThat(migration).contains("action_fingerprint CHAR(64)");
            assertThat(migration).contains("represented_actor_id");
            assertThat(migration).contains("agent_id");
            assertThat(migration).contains("delegation_id");
            assertThat(migration).doesNotContain("selected_sample_ids");
            assertThat(migration).doesNotContain("source_record");
            assertThat(migration).doesNotContain("model_output");
        }
    }

    /** V25 exposes model retrieval choice without storing RAG answers, citations, or raw evidence. */
    @Test
    void v25ShouldPersistOnlyTheLowSensitiveRetrievalProjection() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/postgresql/data-sync/V25__autopilot_recovery_retrieval_evidence_projection.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(migration).contains("retrieval_decision");
            assertThat(migration).contains("retrieval_strategy");
            assertThat(migration).contains("retrieval_evidence_count");
            assertThat(migration).contains("retrieval_evidence_digest");
            assertThat(migration).contains("SEARCH");
            assertThat(migration).contains("SKIP");
            assertThat(migration).doesNotContain("rag_answer");
            assertThat(migration).doesNotContain("document_text");
            assertThat(migration).doesNotContain("model_reasoning");
        }
    }

    /** V27 必须区分异步命令和直接工具入口，且直接入口不能伪造 commandId。 */
    @Test
    void v27ShouldSupportDirectAgentToolCorrelationWithoutFakeCommand() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/postgresql/data-sync/V27__direct_agent_tool_execution_correlation.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(migration).contains("ALTER COLUMN command_id DROP NOT NULL");
            assertThat(migration).contains("entry_mode VARCHAR(32)");
            assertThat(migration).contains("ASYNC_AGENT_COMMAND");
            assertThat(migration).contains("DIRECT_AGENT_TOOL");
        }
    }
}
