/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryQuarantineReceiptMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryQuarantineReceipt;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Atomic reservation and completion operations for autonomous quarantine receipts. */
@Mapper
public interface SyncAutopilotRecoveryQuarantineReceiptMapper
        extends BaseMapper<SyncAutopilotRecoveryQuarantineReceipt> {

    @Select("SELECT * FROM data_sync_autopilot_recovery_quarantine_receipt "
            + "WHERE receipt_id = #{receiptId} LIMIT 1")
    SyncAutopilotRecoveryQuarantineReceipt selectByReceiptId(@Param("receiptId") String receiptId);

    /** Returns the most recent durable quarantine receipt for status and E2E observation. */
    @Select("""
            SELECT *
            FROM data_sync_autopilot_recovery_quarantine_receipt
            WHERE case_id = #{caseId}
            ORDER BY update_time DESC, id DESC
            LIMIT 1
            """)
    SyncAutopilotRecoveryQuarantineReceipt selectLatestByCaseId(@Param("caseId") Long caseId);

    @Insert("""
            INSERT INTO data_sync_autopilot_recovery_quarantine_receipt (
                receipt_id, case_id, request_digest, preview_digest, action_fingerprint,
                sync_task_id, execution_id, represented_actor_id, agent_id, delegation_id,
                selected_count, affected_count,
                operation_state, receipt_state, create_time, update_time
            ) VALUES (
                #{receipt.receiptId}, #{receipt.caseId}, #{receipt.requestDigest},
                #{receipt.previewDigest}, #{receipt.actionFingerprint}, #{receipt.syncTaskId},
                #{receipt.executionId}, #{receipt.representedActorId}, #{receipt.agentId},
                #{receipt.delegationId}, #{receipt.selectedCount}, #{receipt.affectedCount},
                #{receipt.operationState}, #{receipt.receiptState}, LOCALTIMESTAMP, LOCALTIMESTAMP
            ) ON CONFLICT (receipt_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("receipt") SyncAutopilotRecoveryQuarantineReceipt receipt);

    @Update("""
            UPDATE data_sync_autopilot_recovery_quarantine_receipt
            SET affected_count = #{affectedCount}, operation_state = #{operationState},
                receipt_state = 'COMPLETED', update_time = LOCALTIMESTAMP
            WHERE receipt_id = #{receiptId} AND receipt_state = 'PROCESSING'
              AND selected_count = #{selectedCount}
            """)
    int completeReceipt(@Param("receiptId") String receiptId,
                        @Param("selectedCount") Integer selectedCount,
                        @Param("affectedCount") Integer affectedCount,
                        @Param("operationState") String operationState);
}
