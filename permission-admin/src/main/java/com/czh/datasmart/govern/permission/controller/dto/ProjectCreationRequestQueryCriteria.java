/**
 * @Author : Cui
 * @Date: 2026/07/10 04:29
 * @Description DataSmart Govern Backend - ProjectCreationRequestQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * Query criteria for project creation approval requests.
 *
 * <p>The request workflow is intentionally separated from real project data. Filters here only query workflow facts,
 * while the approved project itself is still queried through {@link PermissionProjectQueryCriteria}.</p>
 */
public record ProjectCreationRequestQueryCriteria(Long tenantId,
                                                  Long applicationId,
                                                  Long applicantActorId,
                                                  Long createdProjectId,
                                                  String status,
                                                  Long current,
                                                  Long size) {
}
