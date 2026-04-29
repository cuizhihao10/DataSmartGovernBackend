/**
 * @Author : Cui
 * @Date: 2026/04/25 22:30
 * @Description DataSmart Govern Backend - PlatformPageResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 平台级分页响应载荷。
 *
 * 这个对象只负责表达分页数据本身，不负责表达接口成功或失败。
 * 使用方式通常是：PlatformApiResponse<PlatformPageResponse<SomeView>>。
 *
 * 为什么要单独定义分页载荷：
 * 1. 不同模块目前可能使用 MyBatis-Plus 的 IPage、Page 或自定义列表结构；
 * 2. 直接把框架分页对象暴露给前端，会把后端持久化框架细节泄漏到 API 契约；
 * 3. 统一分页字段后，前端、网关、API 文档和测试工具都能使用同一套解析逻辑。
 *
 * @param <T> 当前页记录类型。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlatformPageResponse<T> {

    /**
     * 当前页码，从 1 开始。
     */
    private Long current;

    /**
     * 每页大小。
     */
    private Long size;

    /**
     * 总记录数。
     */
    private Long total;

    /**
     * 总页数。
     */
    private Long pages;

    /**
     * 当前页数据。
     */
    private List<T> records;

    /**
     * 构建分页响应。
     * 这里不直接依赖 MyBatis-Plus，是为了让后续 Mongo、Neo4j、搜索引擎或远程服务分页也能复用该契约。
     */
    public static <T> PlatformPageResponse<T> of(Long current, Long size, Long total, List<T> records) {
        long safeSize = size == null || size <= 0 ? 10L : size;
        long safeTotal = total == null ? 0L : total;
        long pages = safeTotal == 0 ? 0 : (safeTotal + safeSize - 1) / safeSize;
        return new PlatformPageResponse<>(current == null ? 1L : current, safeSize, safeTotal, pages, records);
    }
}
