/**
 * @Author : Cui
 * @Date: 2026/05/10 13:52
 * @Description DataSmart Govern Backend - PlatformAuthorizedProjectHeaderSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.context;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 授权项目 Header 编解码工具。
 *
 * <p>`X-DataSmart-Authorized-Project-Ids` 是 gateway 和各业务服务之间传递 PROJECT 数据范围的统一协议字段。
 * 它看起来只是一个逗号分隔字符串，但实际处在平台权限安全边界上：
 * 1. permission-admin 负责计算 actor 能访问哪些项目；
 * 2. gateway 负责把项目集合编码成 Header；
 * 3. data-sync、datasource-management、data-quality 等业务服务负责把 Header 解码成安全查询条件。
 *
 * <p>如果每个模块都自己 split 字符串，很容易出现有的模块接受负数、有的模块不去重、有的模块遇到坏片段直接 500。
 * 因此把规则集中到 platform-common，后续模块接入时只需要复用本类，权限语义就能保持一致。
 */
public final class PlatformAuthorizedProjectHeaderSupport {

    private PlatformAuthorizedProjectHeaderSupport() {
        throw new UnsupportedOperationException("PlatformAuthorizedProjectHeaderSupport 是工具类，不允许实例化");
    }

    /**
     * 把项目 ID 集合编码为 Header 值。
     *
     * <p>编码时会过滤 null、0、负数，并做 distinct 去重。
     * 返回空字符串表示没有可透传的项目 ID，调用方通常应选择不写 Header 或写入空授权语义。
     *
     * @param projectIds permission-admin 物化出的项目授权集合
     * @return 逗号分隔的项目 ID，例如 `101,102,205`
     */
    public static String format(List<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return "";
        }
        return projectIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    /**
     * 从 Header 值解析授权项目集合。
     *
     * <p>解析规则保持“安全容错”：
     * 1. 空 Header 返回空集合，便于下游明确表达“无授权项目”；
     * 2. 空片段、非数字片段、0 和负数会被忽略；
     * 3. 重复项目 ID 会被去重，避免生成冗余 SQL 条件；
     * 4. 不在这里抛异常，避免代理或灰度脚本产生的坏片段直接把业务请求打成 500。
     *
     * @param value Header 原始值
     * @return 去重后的正整数项目 ID 集合
     */
    public static List<Long> parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(text -> !text.trim().isEmpty())
                .map(PlatformAuthorizedProjectHeaderSupport::parsePositiveLong)
                .filter(id -> id != null && id > 0)
                .distinct()
                .collect(Collectors.toList());
    }

    private static Long parsePositiveLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
