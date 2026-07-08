/**
 * @Author : Cui
 * @Date: 2026/07/09 01:07
 * @Description DataSmart Govern Backend - RevokeDataSourceAuthorizationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

/**
 * 撤销数据源授权请求。
 *
 * <p>撤销授权不是物理删除，而是把授权记录置为 REVOKED，并记录撤销人、撤销时间和撤销原因。
 * 这样审计员仍然可以还原历史上某个用户为什么曾经拥有过某条数据源的访问权。</p>
 */
@Data
public class RevokeDataSourceAuthorizationRequest {

    /**
     * 撤销原因。前端可选填；生产环境建议要求高风险数据源必须填写。
     */
    private String revokeReason;
}
