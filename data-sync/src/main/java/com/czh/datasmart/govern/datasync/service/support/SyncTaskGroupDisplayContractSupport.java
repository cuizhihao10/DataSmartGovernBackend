/**
 * @Author : Cui
 * @Date: 2026/07/08 02:45
 * @Description DataSmart Govern Backend - SyncTaskGroupDisplayContractSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupSummary;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupTreeNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 同步任务分组前端展示合同补齐组件。
 *
 * <p>这个组件只处理“如何把后端分组事实转换成前端可安全渲染的展示合同”，不负责创建分组、删除分组、
 * 移动任务、权限校验或数据库查询。把它从 {@link SyncTaskGroupOperationSupport} 拆出来有两个目的：</p>
 *
 * <p>1. 降低耦合：分组 CRUD 和分组树展示是两个变化方向。以后如果前端需要 breadcrumb、虚拟树、拖拽排序、
 * 国际化显示名或聚合默认分组，只需要改这里，不必触碰删除分组和任务迁移这类高风险业务逻辑。</p>
 *
 * <p>2. 明确合同：当前产品层级已经从“租户 -> 项目 -> 工作空间 -> 资源”收敛为“租户 -> 项目 -> 资源”。
 * 数据库里仍然保留 workspace_id，是为了历史任务、审计事实和 Agent 内部 workspace key 兼容；但是前端菜单、分组树、
 * 新建任务和新建数据源不再把工作空间当成用户可感知层级。因此这里生成的 treeKey、scopeLabel、displayName、
 * displayPath 都只使用 tenant/project/group 这条产品主线，让前端不必再猜测 workspace 的历史语义。</p>
 */
@Component
public class SyncTaskGroupDisplayContractSupport {

    /**
     * 为平铺分组摘要补齐前端友好的展示合同。
     *
     * <p>GET /sync-tasks/groups 历史上只返回 groupCode/groupName 和计数。这个设计在单项目页面可以工作，
     * 但平台管理员、项目负责人或 Agent 查询跨项目数据时，同一个 DEFAULT 会在多个作用域出现。
     * 因此这里在不改变旧字段语义的前提下，额外补充 treeKey、scopeLabel、displayName 和 displayPath：
     * 旧前端继续读 groupCode/groupName，新前端则使用这些字段避免 key 冲突和重复“默认分组”误导。</p>
     */
    public void enrichSummaries(List<SyncTaskGroupSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return;
        }
        Map<String, Integer> duplicateCounter = new LinkedHashMap<>();
        for (SyncTaskGroupSummary summary : summaries) {
            String displayKey = displayCollisionKey(summary.getGroupCode(), summary.getGroupName());
            duplicateCounter.merge(displayKey, 1, Integer::sum);
        }
        for (SyncTaskGroupSummary summary : summaries) {
            summary.setTreeKey(treeKey(summary.getTenantId(), summary.getProjectId(), summary.getGroupCode()));
            summary.setScopeType(scopeType(summary.getTenantId(), summary.getProjectId()));
            summary.setScopeLabel(scopeLabel(summary.getTenantId(), summary.getProjectId()));
            String baseName = resolveGroupName(summary.getGroupName(), summary.getGroupCode());
            summary.setDisplayName(displayName(baseName, summary.getGroupCode(),
                    summary.getTenantId(), summary.getProjectId(), duplicateCounter));
            summary.setDisplayPath(summary.getScopeLabel() + " / " + summary.getDisplayName());
        }
    }

    /**
     * 为树节点补齐稳定身份、展示路径与子树汇总计数。
     *
     * <p>该步骤故意放在分组操作组件完成父子挂载和排序之后执行，因为 displayPath 和 subtree 计数都依赖最终树结构。
     * 这相当于把“数据库事实”和“前端渲染合同”分成两个阶段：前者保证可见范围和业务数据正确，
     * 后者保证 UI 不再猜测节点身份。</p>
     */
    public void enrichTreeNodes(List<SyncTaskGroupTreeNode> roots) {
        if (roots == null || roots.isEmpty()) {
            return;
        }
        List<SyncTaskGroupTreeNode> flattened = new ArrayList<>();
        flattenTree(roots, flattened);
        Map<String, Integer> duplicateCounter = new LinkedHashMap<>();
        for (SyncTaskGroupTreeNode node : flattened) {
            String displayKey = displayCollisionKey(node.getGroupCode(), node.getGroupName());
            duplicateCounter.merge(displayKey, 1, Integer::sum);
        }
        for (SyncTaskGroupTreeNode node : flattened) {
            String parentGroupCode = trimToNull(node.getParentGroupCode());
            node.setTreeKey(treeKey(node.getTenantId(), node.getProjectId(), node.getGroupCode()));
            node.setParentTreeKey(parentGroupCode == null
                    ? null
                    : treeKey(node.getTenantId(), node.getProjectId(), parentGroupCode));
            node.setScopeType(scopeType(node.getTenantId(), node.getProjectId()));
            node.setScopeLabel(scopeLabel(node.getTenantId(), node.getProjectId()));
            String baseName = resolveGroupName(node.getGroupName(), node.getGroupCode());
            node.setDisplayName(displayName(baseName, node.getGroupCode(),
                    node.getTenantId(), node.getProjectId(), duplicateCounter));
        }
        for (SyncTaskGroupTreeNode root : roots) {
            fillDisplayPath(root, root.getScopeLabel());
            fillSubtreeSummary(root);
        }
    }

    /**
     * 递归展开树，供“同名节点消歧”这类全局视角逻辑使用。
     */
    private void flattenTree(List<SyncTaskGroupTreeNode> nodes, List<SyncTaskGroupTreeNode> flattened) {
        for (SyncTaskGroupTreeNode node : nodes) {
            flattened.add(node);
            flattenTree(node.getChildren(), flattened);
        }
    }

    /**
     * 递归生成展示路径。
     *
     * <p>根节点路径以作用域标签开头，子节点在父路径后追加自身 displayName。
     * 这样前端搜索到任意子分组时，都可以展示“这个分组到底属于哪个项目、位于哪条分组链路下”。</p>
     */
    private void fillDisplayPath(SyncTaskGroupTreeNode node, String parentPath) {
        String currentPath = parentPath + " / " + node.getDisplayName();
        node.setDisplayPath(currentPath);
        for (SyncTaskGroupTreeNode child : node.getChildren()) {
            fillDisplayPath(child, currentPath);
        }
    }

    /**
     * 递归汇总子树计数。
     *
     * <p>这里保留 taskCount 作为“直接任务数”，同时新增 subtree* 作为“含子分组汇总数”。
     * 前端左侧“全部同步任务”应汇总所有根节点的 subtreeTaskCount；父分组徽标如果希望展示含子分组数量，
     * 也可以直接使用 subtreeTaskCount。</p>
     */
    private GroupTreeSummary fillSubtreeSummary(SyncTaskGroupTreeNode node) {
        GroupTreeSummary summary = new GroupTreeSummary(
                nullToZero(node.getTaskCount()),
                nullToZero(node.getActiveTaskCount()),
                nullToZero(node.getScheduledTaskCount()),
                nullToZero(node.getRunningTaskCount()),
                nullToZero(node.getFailedTaskCount()),
                nullToZero(node.getRecycledTaskCount())
        );
        for (SyncTaskGroupTreeNode child : node.getChildren()) {
            summary = summary.plus(fillSubtreeSummary(child));
        }
        node.setSubtreeTaskCount(summary.taskCount());
        node.setSubtreeActiveTaskCount(summary.activeTaskCount());
        node.setSubtreeScheduledTaskCount(summary.scheduledTaskCount());
        node.setSubtreeRunningTaskCount(summary.runningTaskCount());
        node.setSubtreeFailedTaskCount(summary.failedTaskCount());
        node.setSubtreeRecycledTaskCount(summary.recycledTaskCount());
        return summary;
    }

    /**
     * 生成前端稳定树 key。
     *
     * <p>key 中显式写出字段名而不是只用斜杠拼值，是为了让排查日志更直观：
     * tenant:10/project:101/group:DEFAULT 一眼就能看出 DEFAULT 属于哪个项目范围。
     * 这里故意不再拼入 workspaceId：工作空间已经从产品可见层级退场，如果继续把历史 workspace_id 放进
     * UI key，同一个项目下的历史 DEFAULT 会被前端误判成多个默认分组，用户也会看到自己不需要理解的内部维度。</p>
     */
    private String treeKey(Long tenantId, Long projectId, String groupCode) {
        return "tenant:" + keyPart(tenantId)
                + "/project:" + keyPart(projectId)
                + "/group:" + keyPart(groupCode);
    }

    private String keyPart(Long value) {
        return value == null ? "_" : String.valueOf(value);
    }

    private String keyPart(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? "_" : normalized.toUpperCase(Locale.ROOT);
    }

    private String scopeType(Long tenantId, Long projectId) {
        if (projectId != null) {
            return "PROJECT";
        }
        if (tenantId != null) {
            return "TENANT";
        }
        return "GLOBAL";
    }

    private String scopeLabel(Long tenantId, Long projectId) {
        String type = scopeType(tenantId, projectId);
        if ("PROJECT".equals(type)) {
            return "项目级：" + scopeParts(tenantId, projectId);
        }
        if ("TENANT".equals(type)) {
            return "租户级：" + scopeParts(tenantId, null);
        }
        return "全局级";
    }

    private String scopeParts(Long tenantId, Long projectId) {
        List<String> parts = new ArrayList<>();
        if (tenantId != null) {
            parts.add("租户 " + tenantId);
        }
        if (projectId != null) {
            parts.add("项目 " + projectId);
        }
        return parts.isEmpty() ? "未限定范围" : String.join(" / ", parts);
    }

    /**
     * 生成用于检测“同批响应内显示名重复”的键。
     */
    private String displayCollisionKey(String groupCode, String groupName) {
        return keyPart(groupCode) + "|" + resolveGroupName(groupName, keyPart(groupCode));
    }

    private String displayName(String baseName,
                               String groupCode,
                               Long tenantId,
                               Long projectId,
                               Map<String, Integer> duplicateCounter) {
        String displayKey = displayCollisionKey(groupCode, baseName);
        if (duplicateCounter.getOrDefault(displayKey, 0) <= 1) {
            return baseName;
        }
        return baseName + "（" + scopeShortLabel(tenantId, projectId) + "）";
    }

    private String scopeShortLabel(Long tenantId, Long projectId) {
        if (projectId != null) {
            return "项目 " + projectId;
        }
        if (tenantId != null) {
            return "租户 " + tenantId;
        }
        return "全局";
    }

    private String resolveGroupName(String rawGroupName, String groupCode) {
        String groupName = trimToNull(rawGroupName);
        return truncate(groupName == null ? groupCode : groupName, 128);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 分组树递归汇总结果。
     *
     * <p>使用 record 承载递归返回值，可以让 {@link #fillSubtreeSummary(SyncTaskGroupTreeNode)} 的意图更清楚：
     * 每个节点先统计自己的直接任务数，再把所有子节点汇总值逐层向上累加，最终写回 subtree* 字段。</p>
     */
    private record GroupTreeSummary(long taskCount,
                                    long activeTaskCount,
                                    long scheduledTaskCount,
                                    long runningTaskCount,
                                    long failedTaskCount,
                                    long recycledTaskCount) {

        private GroupTreeSummary plus(GroupTreeSummary other) {
            return new GroupTreeSummary(
                    taskCount + other.taskCount,
                    activeTaskCount + other.activeTaskCount,
                    scheduledTaskCount + other.scheduledTaskCount,
                    runningTaskCount + other.runningTaskCount,
                    failedTaskCount + other.failedTaskCount,
                    recycledTaskCount + other.recycledTaskCount
            );
        }
    }
}
