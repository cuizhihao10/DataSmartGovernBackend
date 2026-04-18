package com.czh.datasmart.govern.task.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskExecutionLog;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:14
 * @Description DataSmart Govern Backend - TaskService.java
 * @Version:1.0.0
 *
 * 任务服务接口。
 * 这一层的意义不是简单罗列 CRUD，而是把任务生命周期中“对外可操作的业务动作”显式表达出来。
 *
 * 也就是说，这个接口不是围绕数据库操作命名，
 * 而是围绕业务语义命名：
 * - createTask 表示登记任务。
 * - startTask 表示启动执行。
 * - retryTask 表示重新进入下一轮执行。
 * - listExecutionLogs 表示查询任务轨迹。
 *
 * 这样的接口设计更接近领域服务，也更容易让学习者理解“任务模块到底提供了哪些能力”。
 */
public interface TaskService extends IService<Task> {

    /**
     * 创建任务。
     */
    Task createTask(String name, String description, String type, String params, String priority, Integer maxRetryCount);

    /**
     * 启动任务。
     */
    boolean startTask(Long taskId);

    /**
     * 暂停任务。
     */
    boolean pauseTask(Long taskId);

    /**
     * 恢复任务。
     */
    boolean resumeTask(Long taskId);

    /**
     * 取消任务。
     */
    boolean cancelTask(Long taskId);

    /**
     * 重试任务。
     */
    Task retryTask(Long taskId);

    /**
     * 更新任务进度。
     */
    boolean updateProgress(Long taskId, Integer progress, String checkpoint);

    /**
     * 标记任务完成。
     */
    boolean completeTask(Long taskId, String result);

    /**
     * 标记任务失败。
     */
    boolean failTask(Long taskId, String errorMessage);

    /**
     * 查询任务执行日志。
     */
    List<TaskExecutionLog> listExecutionLogs(Long taskId);
}
