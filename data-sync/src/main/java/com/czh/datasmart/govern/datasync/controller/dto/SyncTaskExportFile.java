/**
 * @Author : Cui
 * @Date: 2026/07/07 19:22
 * @Description DataSmart Govern Backend - SyncTaskExportFile.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 同步任务导出文件。
 *
 * <p>导出接口不能使用普通 {@code PlatformApiResponse} 包裹，因为浏览器、脚本和运营工具都希望直接下载二进制文件。
 * 因此服务层返回这个轻量对象，由 Controller 转换为 {@code ResponseEntity<byte[]>} 并设置文件名、Content-Type。</p>
 */
@Data
@AllArgsConstructor
public class SyncTaskExportFile {

    /**
     * 下载文件名。
     *
     * <p>文件名会包含导出格式和时间戳，方便用户区分多次导出的任务定义包。</p>
     */
    private String fileName;

    /**
     * HTTP Content-Type。
     *
     * <p>CSV 使用 {@code text/csv;charset=UTF-8}；Excel 使用 Office OpenXML 的 xlsx MIME 类型。</p>
     */
    private String contentType;

    /**
     * 文件字节内容。
     *
     * <p>只包含低敏任务定义字段，不包含连接串、密码、完整 SQL、样本数据或执行器内部计划。</p>
     */
    private byte[] content;
}
