/**
 * @Author : Cui
 * @Date: 2026/05/25 00:08
 * @Description DataSmart Govern Backend - TaskDraftController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.czh.datasmart.govern.task.common.ApiResponse;
import com.czh.datasmart.govern.task.controller.dto.CreateTaskDraftRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskDraftConvertRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskDraftReviewRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskDraftSubmitRequest;
import com.czh.datasmart.govern.task.controller.dto.UpdateTaskDraftRequest;
import com.czh.datasmart.govern.task.controller.support.TaskActorContextResolver;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskDraft;
import com.czh.datasmart.govern.task.service.TaskDraftService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务草稿控制器。
 *
 * <p>该控制器提供“草稿创建、编辑、提交审批、审批、转换真实任务”的 HTTP 契约。
 * 它和 `/tasks` 分开，是为了让调用方一眼区分：`/task-drafts` 不会产生可执行队列任务，
 * `/tasks` 才代表真实任务资源。</p>
 */
@RestController
@RequestMapping("/task-drafts")
@RequiredArgsConstructor
public class TaskDraftController {

    private final TaskDraftService taskDraftService;
    private final TaskActorContextResolver actorContextResolver;

    @PostMapping
    public ResponseEntity<ApiResponse<TaskDraft>> createDraft(@Valid @RequestBody CreateTaskDraftRequest request,
                                                              HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务草稿创建成功",
                taskDraftService.createDraft(request, actorContextResolver.resolve(httpRequest))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TaskDraft>> updateDraft(@PathVariable Long id,
                                                              @Valid @RequestBody UpdateTaskDraftRequest request,
                                                              HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务草稿更新成功",
                taskDraftService.updateDraft(id, request, actorContextResolver.resolve(httpRequest))));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<ApiResponse<TaskDraft>> submitDraft(@PathVariable Long id,
                                                              @RequestBody(required = false) TaskDraftSubmitRequest request,
                                                              HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务草稿已提交审批",
                taskDraftService.submitDraft(id, request == null ? null : request.getComment(),
                        actorContextResolver.resolve(httpRequest))));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<TaskDraft>> approveDraft(@PathVariable Long id,
                                                               @RequestBody(required = false) TaskDraftReviewRequest request,
                                                               HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务草稿审批通过",
                taskDraftService.approveDraft(id, request == null ? null : request.getComment(),
                        actorContextResolver.resolve(httpRequest))));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<TaskDraft>> rejectDraft(@PathVariable Long id,
                                                              @RequestBody(required = false) TaskDraftReviewRequest request,
                                                              HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务草稿已拒绝",
                taskDraftService.rejectDraft(id, request == null ? null : request.getComment(),
                        actorContextResolver.resolve(httpRequest))));
    }

    @PostMapping("/{id}/convert")
    public ResponseEntity<ApiResponse<Task>> convertDraft(@PathVariable Long id,
                                                          @RequestBody(required = false) TaskDraftConvertRequest request,
                                                          HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务草稿已转换为真实任务",
                taskDraftService.convertApprovedDraft(id, request == null ? null : request.getComment(),
                        actorContextResolver.resolve(httpRequest))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TaskDraft>> getDraft(@PathVariable Long id,
                                                           HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务草稿详情查询成功",
                taskDraftService.getDraftDetail(id, actorContextResolver.resolve(httpRequest))));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<IPage<TaskDraft>>> listDrafts(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) Long projectId,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success("任务草稿列表查询成功",
                taskDraftService.listDrafts(current, size, status, type, tenantId, ownerId, projectId,
                        actorContextResolver.resolve(httpRequest))));
    }
}
