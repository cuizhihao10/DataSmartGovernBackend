/**
 * @Author : Cui
 * @Date: 2026/05/10 13:52
 * @Description DataSmart Govern Backend - PlatformAuthorizedProjectHeaderSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.context;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 把“项目 -> 项目内角色”集合编码成可信 Header 值。
     *
     * <p>格式采用 `projectId:role`，多项用逗号分隔，例如 `101:MANAGER,205:READER`。
     * 这里没有使用 JSON，是因为该字段会频繁出现在网关日志、服务间 Header、测试断言和故障排查命令中；
     * 逗号分隔格式更短、更容易人工阅读，也与已有项目 ID Header 的传输习惯保持一致。</p>
     *
     * <p>如果同一个 projectId 因为历史数据、组织继承或多条成员关系出现多次，编码时会取“权限更强”的角色。
     * 例如 OWNER 优先于 MANAGER，MANAGER 优先于 READER。这样可以避免下游服务拿到同一项目多个角色后各自解释，
     * 形成不一致的授权结果。</p>
     *
     * @param projectRoles permission-admin 物化出的项目角色快照
     * @return 可写入 `X-DataSmart-Authorized-Project-Roles` 的 Header 值
     */
    public static String formatRoles(List<PlatformAuthorizedProjectRole> projectRoles) {
        if (projectRoles == null || projectRoles.isEmpty()) {
            return "";
        }
        Map<Long, String> roleByProjectId = new LinkedHashMap<>();
        for (PlatformAuthorizedProjectRole projectRole : projectRoles) {
            if (projectRole == null || projectRole.projectId() == null || projectRole.projectId() <= 0) {
                continue;
            }
            String normalizedRole = normalizeProjectRole(projectRole.projectRole());
            if (normalizedRole == null) {
                continue;
            }
            roleByProjectId.merge(projectRole.projectId(), normalizedRole,
                    PlatformAuthorizedProjectHeaderSupport::strongerProjectRole);
        }
        return roleByProjectId.entrySet()
                .stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(","));
    }

    /**
     * 从可信 Header 解析“项目 -> 项目内角色”集合。
     *
     * <p>解析规则继续遵循安全容错原则：</p>
     * <p>1. 空 Header 返回空集合，表示 PROJECT 范围下没有可用于写权限判断的项目角色；</p>
     * <p>2. projectId 非数字、非正数、角色为空的片段会被忽略；</p>
     * <p>3. 历史角色会被归一化，例如 MAINTAINER/MEMBER 统一为 MANAGER，VIEWER 统一为 READER；</p>
     * <p>4. 同一项目重复出现时取权限更强角色，避免重复片段导致下游判断不稳定。</p>
     *
     * @param value Header 原始值，例如 `101:MANAGER,205:READER`
     * @return 去重、归一化后的项目角色快照
     */
    public static List<PlatformAuthorizedProjectRole> parseRoles(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, String> roleByProjectId = new LinkedHashMap<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .forEach(fragment -> {
                    String[] parts = fragment.split(":", 2);
                    if (parts.length != 2) {
                        return;
                    }
                    Long projectId = parsePositiveLong(parts[0].trim());
                    String projectRole = normalizeProjectRole(parts[1]);
                    if (projectId == null || projectId <= 0 || projectRole == null) {
                        return;
                    }
                    roleByProjectId.merge(projectId, projectRole,
                            PlatformAuthorizedProjectHeaderSupport::strongerProjectRole);
                });
        return roleByProjectId.entrySet()
                .stream()
                .map(entry -> new PlatformAuthorizedProjectRole(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * 归一化项目内角色。
     *
     * <p>历史版本中项目成员表曾出现 OWNER、MAINTAINER、VIEWER、MEMBER、SERVICE 等角色。
     * 当前产品语义收敛为应用内项目角色：OWNER、MANAGER、READER、SERVICE。
     * 这里把旧角色映射到新角色，保证旧数据卷、旧迁移脚本和新下游服务可以在同一协议上工作。</p>
     */
    public static String normalizeProjectRole(String projectRole) {
        if (projectRole == null || projectRole.trim().isEmpty()) {
            return null;
        }
        String normalized = projectRole.trim().toUpperCase();
        return switch (normalized) {
            case "OWNER" -> "OWNER";
            case "MANAGER", "MAINTAINER", "MEMBER" -> "MANAGER";
            case "READER", "VIEWER" -> "READER";
            case "SERVICE", "SERVICE_ACCOUNT" -> "SERVICE";
            default -> "READER";
        };
    }

    /**
     * 比较两个项目角色，返回权限更强的一个。
     *
     * <p>该方法只处理项目内角色强度，不代表平台级 actorRole 的强度。
     * 平台管理员、租户管理员这类更高层级权限仍然由 permission-admin 的路由策略和数据范围策略决定。</p>
     */
    public static String strongerProjectRole(String left, String right) {
        String normalizedLeft = normalizeProjectRole(left);
        String normalizedRight = normalizeProjectRole(right);
        if (normalizedLeft == null) {
            return normalizedRight;
        }
        if (normalizedRight == null) {
            return normalizedLeft;
        }
        return roleRank(normalizedRight) > roleRank(normalizedLeft) ? normalizedRight : normalizedLeft;
    }

    private static int roleRank(String role) {
        return switch (normalizeProjectRole(role)) {
            case "OWNER" -> 400;
            case "MANAGER" -> 300;
            case "READER" -> 200;
            case "SERVICE" -> 150;
            default -> 0;
        };
    }

    private static Long parsePositiveLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
