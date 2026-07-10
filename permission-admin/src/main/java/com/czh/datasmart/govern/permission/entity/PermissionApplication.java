/**
 * @Author : Cui
 * @Date: 2026/07/10 14:00
 * @Description DataSmart Govern Backend - PermissionApplication.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户应用主数据实体。
 *
 * <p>应用表示云平台提供给租户的产品，例如本项目的 FlashSync。开租时自动创建一条 FlashSync 应用，
 * 普通用户无需填写 applicationId；项目审批通过后由后端自动选择该租户的默认可用应用。</p>
 */
@Data
@TableName("permission_application")
public class PermissionApplication {

    @TableId(value = "application_id", type = IdType.INPUT)
    private Long applicationId;

    private Long tenantId;

    private String applicationCode;

    private String applicationName;

    private String applicationType;

    private String status;

    private String homepagePath;

    private Long defaultProjectId;

    private Long ownerActorId;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
