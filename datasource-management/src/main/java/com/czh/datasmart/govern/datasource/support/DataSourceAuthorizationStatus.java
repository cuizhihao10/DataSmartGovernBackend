/**
 * @Author : Cui
 * @Date: 2026/07/09 01:07
 * @Description DataSmart Govern Backend - DataSourceAuthorizationStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.support;

/**
 * 数据源授权记录生命周期状态。
 *
 * <p>授权记录不做物理删除，而是通过 ACTIVE/REVOKED 表达是否生效。
 * 这样做的原因是数据源连接通常属于高敏资源：谁在什么时候把某条连接授权给谁、后来又为什么撤销，
 * 都应该成为可审计事实，而不是被删除操作抹掉。</p>
 */
public final class DataSourceAuthorizationStatus {

    /**
     * 授权当前有效。
     */
    public static final String ACTIVE = "ACTIVE";

    /**
     * 授权已撤销。
     */
    public static final String REVOKED = "REVOKED";

    private DataSourceAuthorizationStatus() {
    }
}
