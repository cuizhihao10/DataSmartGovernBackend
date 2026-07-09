-- MySQL compatibility migration: project creation request approval workflow.
--
-- PostgreSQL is the target system of record, but local compatibility environments may still
-- replay MySQL migration batches. Keep the workflow table available there as well.

CREATE TABLE IF NOT EXISTS permission_project_creation_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    application_id BIGINT NULL,
    project_code VARCHAR(64) NULL,
    project_name VARCHAR(128) NOT NULL,
    project_type VARCHAR(64) NOT NULL DEFAULT 'DATA_GOVERNANCE',
    applicant_actor_id BIGINT NOT NULL,
    applicant_name VARCHAR(128) NULL,
    owner_actor_id BIGINT NOT NULL,
    description VARCHAR(1000) NULL,
    request_reason VARCHAR(500) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    reviewer_actor_id BIGINT NULL,
    reviewer_actor_role VARCHAR(64) NULL,
    review_comment VARCHAR(500) NULL,
    review_time DATETIME NULL,
    created_project_id BIGINT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_permission_project_creation_request_reviewer (tenant_id, status, update_time),
    INDEX idx_permission_project_creation_request_applicant (tenant_id, applicant_actor_id, status, update_time),
    INDEX idx_permission_project_creation_request_code (tenant_id, project_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Project creation approval workflow facts';

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, 'Ordinary user applies to create project', 'ORDINARY_USER', 'POST', '/api/permission/project-creation-requests', 'PROJECT_CREATION_REQUEST', 'APPLY', 'ALLOW', 760, TRUE,
 'Ordinary users can request project creation. Approval is required before project data scope exists.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Project owner applies to create project', 'PROJECT_OWNER', 'POST', '/api/permission/project-creation-requests', 'PROJECT_CREATION_REQUEST', 'APPLY', 'ALLOW', 760, TRUE,
 'Project owners can request additional projects, but tenant or platform administrators still approve creation.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Operator applies to create project', 'OPERATOR', 'POST', '/api/permission/project-creation-requests', 'PROJECT_CREATION_REQUEST', 'APPLY', 'ALLOW', 760, TRUE,
 'Operators can request operational projects when tenant administrators approve them.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'User views own project creation requests', 'ORDINARY_USER', 'GET', '/api/permission/project-creation-requests/my', 'PROJECT_CREATION_REQUEST', 'VIEW_OWN', 'ALLOW', 760, TRUE,
 'Users can query only their own project creation requests.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Project owner views own project creation requests', 'PROJECT_OWNER', 'GET', '/api/permission/project-creation-requests/my', 'PROJECT_CREATION_REQUEST', 'VIEW_OWN', 'ALLOW', 760, TRUE,
 'Project owners can query their own project creation requests.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Operator views own project creation requests', 'OPERATOR', 'GET', '/api/permission/project-creation-requests/my', 'PROJECT_CREATION_REQUEST', 'VIEW_OWN', 'ALLOW', 760, TRUE,
 'Operators can query their own project creation requests.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'User cancels own project creation request', 'ORDINARY_USER', 'POST', '/api/permission/project-creation-requests/*/cancel', 'PROJECT_CREATION_REQUEST', 'CANCEL_OWN', 'ALLOW', 760, TRUE,
 'Users can cancel their own still-pending project creation requests.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Project owner cancels own project creation request', 'PROJECT_OWNER', 'POST', '/api/permission/project-creation-requests/*/cancel', 'PROJECT_CREATION_REQUEST', 'CANCEL_OWN', 'ALLOW', 760, TRUE,
 'Project owners can cancel their own still-pending project creation requests.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Operator cancels own project creation request', 'OPERATOR', 'POST', '/api/permission/project-creation-requests/*/cancel', 'PROJECT_CREATION_REQUEST', 'CANCEL_OWN', 'ALLOW', 760, TRUE,
 'Operators can cancel their own still-pending project creation requests.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Tenant administrator reviews project creation requests', 'TENANT_ADMINISTRATOR', 'ANY', '/api/permission/project-creation-requests/**', 'PROJECT_CREATION_REQUEST', 'REVIEW', 'ALLOW', 820, TRUE,
 'Tenant administrators can approve or reject project creation requests in their tenant.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'Platform administrator reviews project creation requests', 'PLATFORM_ADMINISTRATOR', 'ANY', '/api/permission/project-creation-requests/**', 'PROJECT_CREATION_REQUEST', 'REVIEW', 'ALLOW', 900, TRUE,
 'Platform administrators can approve or reject project creation requests across tenants.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
