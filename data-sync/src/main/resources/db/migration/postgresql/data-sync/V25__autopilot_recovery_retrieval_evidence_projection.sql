-- Low-sensitive projection of the model's autonomous recovery-retrieval choice.
-- Only SEARCH/SKIP, a bounded strategy code, a count, and an evidence-ID digest are stored.
-- RAG answers, document text, citations, prompts, raw logs, and model reasoning remain outside data-sync.
ALTER TABLE data_sync_autopilot_recovery_trigger_outbox
    ADD COLUMN retrieval_decision VARCHAR(16),
    ADD COLUMN retrieval_strategy VARCHAR(96),
    ADD COLUMN retrieval_evidence_count INTEGER,
    ADD COLUMN retrieval_evidence_digest VARCHAR(71),
    ADD CONSTRAINT ck_data_sync_autopilot_trigger_retrieval_projection CHECK (
        (
            retrieval_decision IS NULL
            AND retrieval_strategy IS NULL
            AND retrieval_evidence_count IS NULL
            AND retrieval_evidence_digest IS NULL
        )
        OR
        (
            retrieval_decision IN ('SEARCH', 'SKIP')
            AND retrieval_strategy ~ '^[A-Z][A-Z0-9_]{0,95}$'
            AND retrieval_evidence_count BETWEEN 0 AND 1000
            AND (
                (
                    retrieval_decision = 'SEARCH'
                    AND retrieval_evidence_count > 0
                    AND retrieval_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
                )
                OR
                (
                    retrieval_decision = 'SKIP'
                    AND retrieval_evidence_count = 0
                    AND retrieval_evidence_digest IS NULL
                )
            )
        )
    );

COMMENT ON COLUMN data_sync_autopilot_recovery_trigger_outbox.retrieval_decision IS
    'Model-selected SEARCH or SKIP decision after Java planner-contract validation';
COMMENT ON COLUMN data_sync_autopilot_recovery_trigger_outbox.retrieval_evidence_digest IS
    'SHA-256 digest of grounded RAG evidence IDs; never evidence or model text';
