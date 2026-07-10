/**
 * @Author : Cui
 * @Date: 2026/07/10 11:39
 * @Description DataSmart Govern Backend - PermissionApprovalCenterMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.mapper;

import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterItemView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Read-only mapper that unions all currently supported approval workflow facts.
 *
 * <p>The concrete workflow tables remain independent so their state machines and constraints stay explicit. This mapper
 * only builds a console projection and never writes workflow state.</p>
 */
@Mapper
public interface PermissionApprovalCenterMapper {

    @Select("""
            <script>
            SELECT request_type, request_id, tenant_id, application_id, project_id, project_code, project_name,
                   applicant_actor_id, applicant_name, requested_project_role, request_reason, status,
                   reviewer_actor_id, reviewer_actor_role, review_comment, review_time, result_resource_id,
                   create_time, update_time
            FROM (
                SELECT 'PROJECT_CREATION' AS request_type,
                       creation.id AS request_id,
                       creation.tenant_id,
                       creation.application_id,
                       creation.created_project_id AS project_id,
                       creation.project_code,
                       creation.project_name,
                       creation.applicant_actor_id,
                       creation.applicant_name,
                       'OWNER' AS requested_project_role,
                       creation.request_reason,
                       creation.status,
                       creation.reviewer_actor_id,
                       creation.reviewer_actor_role,
                       creation.review_comment,
                       creation.review_time,
                       creation.created_project_id AS result_resource_id,
                       creation.create_time,
                       creation.update_time
                FROM permission_project_creation_request creation
                UNION ALL
                SELECT 'PROJECT_JOIN' AS request_type,
                       join_request.id AS request_id,
                       join_request.tenant_id,
                       project.application_id,
                       join_request.project_id,
                       project.project_code,
                       project.project_name,
                       join_request.applicant_actor_id,
                       join_request.applicant_name,
                       join_request.requested_project_role,
                       join_request.request_reason,
                       join_request.status,
                       join_request.reviewer_actor_id,
                       join_request.reviewer_actor_role,
                       join_request.review_comment,
                       join_request.review_time,
                       join_request.membership_id AS result_resource_id,
                       join_request.create_time,
                       join_request.update_time
                FROM permission_project_join_request join_request
                LEFT JOIN permission_project project ON project.project_id = join_request.project_id
            ) approval_item
            WHERE 1 = 1
            <if test="tenantId != null">AND tenant_id = #{tenantId}</if>
            <if test="applicantActorId != null">AND applicant_actor_id = #{applicantActorId}</if>
            <if test="requestType != null and requestType != ''">AND request_type = #{requestType}</if>
            <if test="status != null and status != ''">AND status = #{status}</if>
            ORDER BY update_time DESC, request_id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ApprovalCenterItemView> selectApprovalPage(@Param("tenantId") Long tenantId,
                                                    @Param("applicantActorId") Long applicantActorId,
                                                    @Param("requestType") String requestType,
                                                    @Param("status") String status,
                                                    @Param("limit") long limit,
                                                    @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(1)
            FROM (
                SELECT 'PROJECT_CREATION' AS request_type,
                       tenant_id,
                       applicant_actor_id,
                       status
                FROM permission_project_creation_request
                UNION ALL
                SELECT 'PROJECT_JOIN' AS request_type,
                       tenant_id,
                       applicant_actor_id,
                       status
                FROM permission_project_join_request
            ) approval_item
            WHERE 1 = 1
            <if test="tenantId != null">AND tenant_id = #{tenantId}</if>
            <if test="applicantActorId != null">AND applicant_actor_id = #{applicantActorId}</if>
            <if test="requestType != null and requestType != ''">AND request_type = #{requestType}</if>
            <if test="status != null and status != ''">AND status = #{status}</if>
            </script>
            """)
    long countApprovals(@Param("tenantId") Long tenantId,
                        @Param("applicantActorId") Long applicantActorId,
                        @Param("requestType") String requestType,
                        @Param("status") String status);
}
