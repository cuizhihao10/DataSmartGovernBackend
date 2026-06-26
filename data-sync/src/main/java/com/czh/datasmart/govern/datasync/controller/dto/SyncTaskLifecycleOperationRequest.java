/**
 * @Author : Cui
 * @Date: 2026/06/27 16:30
 * @Description DataSmart Govern Backend - SyncTaskLifecycleOperationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 同步任务生命周期操作请求。
 *
 * <p>暂停、恢复、重试、取消这类动作和“创建任务”不同，它们更像运营控制台里的显式操作：
 * 用户或运营人员通常需要说明为什么暂停、为什么重试、为什么取消，后续审计、事故复盘和客户支持都会依赖这段说明。
 *
 * <p>当前请求体只保留 reason 一个低敏字段，是为了避免过早把 force、override、priority、timeout 等管理员能力混入普通用户入口。
 * 后续如果要做强制取消、强制重试、批量维护窗口，应放到独立的 admin controller，并接入更严格的权限与审批策略。
 */
@Data
public class SyncTaskLifecycleOperationRequest {

    /**
     * 操作原因。
     *
     * <p>业务含义：
     * 1. 暂停时，说明是维护窗口、目标端限流、字段映射待确认，还是用户主动冻结；
     * 2. 恢复时，说明前置问题是否已处理，例如目标库容量已扩容、连接器已升级；
     * 3. 重试时，说明重试依据，例如临时网络故障已恢复、失败样本已确认可重放；
     * 4. 取消时，说明任务不再执行的原因，例如需求撤销、配置错误、风险过高。
     *
     * <p>安全边界：
     * reason 会进入审计摘要，因此不应写入密码、token、完整 SQL、样本数据、连接串或客户敏感数据。
     * 服务层会做基础敏感词兜底脱敏，但真正的产品规范仍应要求前端提示用户只填写低敏说明。
     */
    @Size(max = 500, message = "生命周期操作原因不能超过 500 个字符")
    private String reason;
}
