/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryQuarantinePreview.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import java.util.List;

/**
 * Java-validated subset of a model-provided quarantine preview.
 *
 * <p>The value is created only after {@link AgentAutopilotRecoveryQuarantinePreviewVerifier} binds it to one
 * recovery trigger and recomputes its action fingerprint. It carries the exact preview digest and selected sample
 * identifiers required by the fixed data-sync apply contract; it is not an authorization grant or a mutable model
 * payload. The selected-ID order is preserved because data-sync may bind its preview digest to that exact list.</p>
 */
public record AgentAutopilotRecoveryQuarantinePreview(
        String confirmationDigest,
        List<Long> selectedSampleIds,
        String outputRef) {

    /**
     * Freezes the selected identifiers after Java validates their cardinality and uniqueness.
     *
     * <p>This shallow immutable copy prevents an execution caller from replacing, adding, or removing selected IDs
     * after the fingerprint check. The values themselves are scalar {@link Long}s, so no nested mutable payload
     * remains reachable through this record. Construction has no I/O, persistence, or authorization side effect.</p>
     */
    public AgentAutopilotRecoveryQuarantinePreview {
        selectedSampleIds = List.copyOf(selectedSampleIds);
    }
}
