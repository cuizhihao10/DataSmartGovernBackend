/**
 * @Author : Cui
 * @Date: 2026/08/11 00:15
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryReceiptMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryReceipt;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Durable receipt idempotency operations for autopilot recovery cases.
 *
 * <p>The receipt is reserved before a case transition. A failed conditional update rolls the
 * reservation back with the transaction, while an already committed receipt is returned to a
 * duplicate caller without reapplying the state edge.</p>
 */
@Mapper
public interface SyncAutopilotRecoveryReceiptMapper extends BaseMapper<SyncAutopilotRecoveryReceipt> {

    @Select("""
            SELECT *
            FROM data_sync_autopilot_recovery_receipt
            WHERE receipt_id = #{receiptId}
            LIMIT 1
            """)
    SyncAutopilotRecoveryReceipt selectByReceiptId(@Param("receiptId") String receiptId);

    @Insert("""
            INSERT INTO data_sync_autopilot_recovery_receipt (
                receipt_id, case_id, receipt_digest, receipt_type, receipt_state,
                resulting_case_state, resulting_version, create_time, update_time
            ) VALUES (
                #{receipt.receiptId}, #{receipt.caseId}, #{receipt.receiptDigest}, #{receipt.receiptType},
                #{receipt.receiptState}, #{receipt.resultingCaseState}, #{receipt.resultingVersion},
                LOCALTIMESTAMP, LOCALTIMESTAMP
            )
            ON CONFLICT (receipt_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("receipt") SyncAutopilotRecoveryReceipt receipt);

    @Update("""
            UPDATE data_sync_autopilot_recovery_receipt
            SET receipt_state = 'COMPLETED',
                resulting_case_state = #{resultingCaseState},
                resulting_version = #{resultingVersion},
                update_time = LOCALTIMESTAMP
            WHERE receipt_id = #{receiptId}
              AND receipt_state = 'PROCESSING'
            """)
    int completeReceipt(@Param("receiptId") String receiptId,
                        @Param("resultingCaseState") String resultingCaseState,
                        @Param("resultingVersion") Long resultingVersion);
}
