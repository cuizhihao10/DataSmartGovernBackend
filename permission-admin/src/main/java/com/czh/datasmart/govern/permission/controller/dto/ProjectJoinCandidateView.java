/**
 * @Author : Cui
 * @Date: 2026/07/10 23:40
 * @Description DataSmart Govern Backend - ProjectJoinCandidateView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import com.czh.datasmart.govern.permission.entity.PermissionProject;

/**
 * Low-sensitive project directory item used by the project-join form.
 *
 * <p>A user who has not joined a project cannot discover it through the normal member-scoped project switcher.
 * This view therefore exposes only the active project's stable identifier, code, name and type. It deliberately
 * excludes owners, descriptions, member lists and every business resource inside the project.</p>
 */
public record ProjectJoinCandidateView(Long projectId,
                                       String projectCode,
                                       String projectName,
                                       String projectType) {

    public static ProjectJoinCandidateView from(PermissionProject project) {
        if (project == null) {
            return null;
        }
        return new ProjectJoinCandidateView(
                project.getProjectId(),
                project.getProjectCode(),
                project.getProjectName(),
                project.getProjectType()
        );
    }
}
