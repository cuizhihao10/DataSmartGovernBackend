package com.czh.datasmart.govern.task.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskExecutionLog;

import java.util.List;

/**
 * 任务服务接口
 */
public interface TaskService extends IService<Task> {

    Task createTask(String name, String description, String type, String params, String priority, Integer maxRetryCount);

    boolean startTask(Long taskId);

    boolean pauseTask(Long taskId);

    boolean resumeTask(Long taskId);

    boolean cancelTask(Long taskId);

    Task retryTask(Long taskId);

    boolean updateProgress(Long taskId, Integer progress, String checkpoint);

    boolean completeTask(Long taskId, String result);

    boolean failTask(Long taskId, String errorMessage);

    List<TaskExecutionLog> listExecutionLogs(Long taskId);
}
