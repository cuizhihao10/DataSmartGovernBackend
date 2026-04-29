package com.czh.datasmart.govern.datasource.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncTemplateRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateSyncTemplateRequest;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;

import java.util.Map;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - SyncTemplateService.java
 * @Version:1.0.0
 *
 * 同步模板服务接口。
 * 模板服务负责把“数据源连接注册”提升为“可重复执行的数据同步配置”。
 */
public interface SyncTemplateService extends IService<SyncTemplate> {

    /**
     * 创建同步模板。
     */
    SyncTemplate createTemplate(CreateSyncTemplateRequest request);

    /**
     * 更新同步模板。
     */
    SyncTemplate updateTemplate(Long id, UpdateSyncTemplateRequest request);

    /**
     * 校验模板配置是否具备进入任务层的基本条件。
     */
    Map<String, Object> validateTemplate(Long id, Long actorId, String actorRole, Long actorTenantId);

    /**
     * 生成面向前端或运维的模板预览摘要。
     */
    Map<String, Object> previewTemplate(Long id);
}
