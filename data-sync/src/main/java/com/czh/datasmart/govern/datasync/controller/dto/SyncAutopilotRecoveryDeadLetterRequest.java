/**
 * @Author : Cui
 * @Date: 2026/08/12 12:00
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryDeadLetterRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * Narrow internal request used when an Autopilot recovery trigger reaches its Kafka dead-letter topic.
 *
 * <p>The event ID remains in the URL. The body contains only the execution ID copied from the original event,
 * allowing data-sync to prove that the callback belongs to its own durable outbox row. The caller cannot supply a
 * case ID, target state, optimistic version, receipt, error text, model output, or Kafka payload; data-sync reloads
 * every one of those authoritative facts from persistence before it changes a recovery lifecycle.</p>
 *
 * @param currentExecutionId execution identity carried by the original durable outbox event
 */
public record SyncAutopilotRecoveryDeadLetterRequest(Long currentExecutionId) {
}
