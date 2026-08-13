/**
 * @Author : Cui
 * @Date: 2026/08/11 00:05
 * @Description DataSmart Govern Backend - SyncAutopilotExecutionMode.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * Execution mode recorded by the autonomous recovery control plane.
 *
 * <p>The new recovery-case table is intentionally limited to AUTOPILOT. Manual recovery
 * continues to use the existing task and execution lifecycle, so it cannot accidentally gain
 * an autopilot authorization by sharing this state machine.</p>
 */
public enum SyncAutopilotExecutionMode {

    /** A recovery decision evaluated from the task-local autopilot authorization. */
    AUTOPILOT
}
