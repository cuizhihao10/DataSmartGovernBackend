/**
 * @Author : Cui
 * @Date: 2026/07/07 19:23
 * @Description DataSmart Govern Backend - SyncTaskImportOptions.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

/**
 * 同步任务导入选项。
 *
 * <p>导入任务定义时，文件内容只回答“要创建哪些任务”；导入选项回答“这次导入怎么落地”。
 * 把二者分开可以避免用户在 Excel 里偷偷写一个 runImmediately 字段绕过页面确认，也方便 Agent 在执行前做 dry-run。</p>
 */
@Data
public class SyncTaskImportOptions {

    /**
     * 文件名。
     *
     * <p>用于推断格式、写审计摘要和错误提示。服务端不会信任文件名决定权限，只把它当作诊断信息。</p>
     */
    private String fileName;

    /**
     * 显式格式。
     *
     * <p>允许值 CSV、XLSX、EXCEL。为空时根据文件名后缀推断。显式格式适合脚本调用或 Agent 工具调用。</p>
     */
    private String format;

    /**
     * 是否只校验不写入。
     *
     * <p>dry-run 是批量导入的安全预演入口：它会解析文件、检查模板、检测唯一键冲突和调度配置，
     * 但不会插入任务、不会发布任务、不会创建 execution。</p>
     */
    private Boolean dryRun;

    /**
     * 导入成功后是否立即执行一次。
     *
     * <p>false 或 null：任务进入 DRAFT 编辑中，由用户后续发布；true：服务端会先发布任务，再创建一次 MANUAL execution。
     * 如果某行任务需要审批、模板不可执行或调度配置非法，整批导入会在写入前失败。</p>
     */
    private Boolean runImmediately;
}
