/**
 * @Author : Cui
 * @Date: 2026/07/22 18:35
 * @Description DataSmart Govern Backend - SyncTaskImportArtifact.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Immutable CSV/XLSX import artifact metadata and bounded body.
 *
 * <p>The model never receives {@link #contentBody}. It sees only {@code artifactRef},
 * dry-run diagnostics and digests. A repair creates another row linked by
 * {@code parentArtifactId}; the original file therefore remains auditable.</p>
 */
@Data
@TableName("sync_task_import_artifact")
public class SyncTaskImportArtifact {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String artifactRef;
    private Long tenantId;
    private Long projectId;
    private Long ownerId;
    private Long parentArtifactId;
    private Integer versionNumber;
    private String fileName;
    private String fileFormat;
    private String contentHash;
    @JsonIgnore
    private byte[] contentBody;
    private Long contentSizeBytes;
    private String artifactState;
    private String dryRunStatus;
    private String dryRunDigest;
    @JsonIgnore
    private String diagnosticsJson;
    private String repairPatchDigest;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
