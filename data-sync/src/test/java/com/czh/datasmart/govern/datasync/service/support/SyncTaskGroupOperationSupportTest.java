/**
 * @Author : Cui
 * @Date: 2026/07/08 02:35
 * @Description DataSmart Govern Backend - SyncTaskGroupOperationSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupSummary;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupTreeNode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
import com.czh.datasmart.govern.datasync.entity.SyncTaskGroup;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskGroupMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同步任务分组支撑组件测试。
 *
 * <p>这组测试关注“后端返回给前端的分组树合同”，而不是数据库 SQL 本身。
 * 当前产品层级已经收敛为 tenant/project 分组，因此同一个 groupCode 只在单个项目作用域内唯一；
 * 如果后端 DTO 不提供组合 key 和范围展示字段，前端就容易把多个 DEFAULT 渲染成重复的“默认分组”。
 * 这里用 mock 数据复现跨项目 DEFAULT 场景，保证后端输出对前端足够友好。</p>
 */
class SyncTaskGroupOperationSupportTest {

    /**
     * 跨项目查询分组树时，多个 DEFAULT 应拥有不同 treeKey，并通过 displayName/displayPath 明确区分范围。
     *
     * <p>业务含义：
     * 项目 101 和项目 102 都可以有自己的默认分组，它们不是同一个业务对象。
     * 后端不能为了页面不重复而直接合并它们，否则用户点击“默认分组”时无法判断到底筛选哪个项目；
     * 更合理的方式是返回稳定组合 key 和可读范围标签，让前端按产品策略选择“聚合显示”或“范围化显示”。</p>
     */
    @Test
    void listTaskGroupTreeShouldExposeScopedTreeKeyAndDisplayNameForDuplicateDefaultGroups() {
        Fixture fixture = fixture();
        SyncActorContext actor = actor();
        SyncDataVisibility visibility = projectVisibility();
        when(fixture.dataScopeSupport().resolveVisibility(any(), any(), any(), any())).thenReturn(visibility);
        when(fixture.groupMapper().selectVisibleGroups(10L, null, null, true, List.of(101L, 102L), null, 100))
                .thenReturn(List.of(
                        group(1L, 10L, 101L, null, null, "DEFAULT", "默认分组", 0, true),
                        group(2L, 10L, 101L, null, "DEFAULT", "TEST", "测试", 100, false),
                        group(3L, 10L, 102L, null, null, "DEFAULT", "默认分组", 0, true)
                ));
        when(fixture.taskMapper().selectTaskGroupSummaries(10L, null, null, true, List.of(101L, 102L),
                null, null, 100)).thenReturn(List.of(
                summary(10L, 101L, null, "DEFAULT", "默认分组", 20L, 20L, 1L, 0L, 0L, 0L),
                summary(10L, 101L, null, "TEST", "测试", 3L, 2L, 0L, 1L, 0L, 0L),
                summary(10L, 102L, null, "DEFAULT", "默认分组", 0L, 0L, 0L, 0L, 0L, 0L)
        ));

        List<SyncTaskGroupTreeNode> roots = fixture.support().listTaskGroupTree(criteria(), actor);

        assertThat(roots).hasSize(2);
        SyncTaskGroupTreeNode project101Default = roots.stream()
                .filter(node -> Long.valueOf(101L).equals(node.getProjectId()))
                .findFirst()
                .orElseThrow();
        SyncTaskGroupTreeNode project102Default = roots.stream()
                .filter(node -> Long.valueOf(102L).equals(node.getProjectId()))
                .findFirst()
                .orElseThrow();

        assertThat(project101Default.getTreeKey()).isEqualTo("tenant:10/project:101/group:DEFAULT");
        assertThat(project102Default.getTreeKey()).isEqualTo("tenant:10/project:102/group:DEFAULT");
        assertThat(project101Default.getTreeKey()).isNotEqualTo(project102Default.getTreeKey());
        assertThat(project101Default.getDisplayName()).isEqualTo("默认分组（项目 101）");
        assertThat(project102Default.getDisplayName()).isEqualTo("默认分组（项目 102）");
        assertThat(project101Default.getDisplayPath()).isEqualTo("项目级：租户 10 / 项目 101 / 默认分组（项目 101）");
        assertThat(project101Default.getScopeType()).isEqualTo("PROJECT");
        assertThat(project101Default.getScopeLabel()).isEqualTo("项目级：租户 10 / 项目 101");

        assertThat(project101Default.getTaskCount()).isEqualTo(20L);
        assertThat(project101Default.getSubtreeTaskCount()).isEqualTo(23L);
        assertThat(project101Default.getSubtreeActiveTaskCount()).isEqualTo(22L);
        assertThat(project101Default.getChildren()).hasSize(1);
        assertThat(project101Default.getChildren().get(0).getParentTreeKey())
                .isEqualTo(project101Default.getTreeKey());
        assertThat(project101Default.getChildren().get(0).getDisplayPath())
                .isEqualTo("项目级：租户 10 / 项目 101 / 默认分组（项目 101） / 测试");
    }

    /**
     * 平铺分组摘要也要补齐 treeKey/displayName，避免非树形下拉框继续只按 groupCode 区分选项。
     */
    @Test
    void listTaskGroupsShouldExposeDisplayContractForFlatGroupSummary() {
        Fixture fixture = fixture();
        SyncActorContext actor = actor();
        when(fixture.dataScopeSupport().resolveVisibility(any(), any(), any(), any())).thenReturn(projectVisibility());
        when(fixture.taskMapper().selectTaskGroupSummaries(10L, null, null, true, List.of(101L, 102L),
                null, null, 100)).thenReturn(List.of(
                summary(10L, 101L, null, "DEFAULT", "默认分组", 20L, 20L, 1L, 0L, 0L, 0L),
                summary(10L, 102L, null, "DEFAULT", "默认分组", 0L, 0L, 0L, 0L, 0L, 0L)
        ));

        List<SyncTaskGroupSummary> summaries = fixture.support().listTaskGroups(criteria(), actor);

        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(0).getTreeKey()).isEqualTo("tenant:10/project:101/group:DEFAULT");
        assertThat(summaries.get(0).getDisplayName()).isEqualTo("默认分组（项目 101）");
        assertThat(summaries.get(0).getDisplayPath()).isEqualTo("项目级：租户 10 / 项目 101 / 默认分组（项目 101）");
        assertThat(summaries.get(1).getTreeKey()).isEqualTo("tenant:10/project:102/group:DEFAULT");
        assertThat(summaries.get(1).getDisplayName()).isEqualTo("默认分组（项目 102）");
    }

    private Fixture fixture() {
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncTaskGroupMapper groupMapper = mock(SyncTaskGroupMapper.class);
        SyncDataScopeSupport dataScopeSupport = mock(SyncDataScopeSupport.class);
        SyncQuerySupport querySupport = mock(SyncQuerySupport.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        return new Fixture(
                new SyncTaskGroupOperationSupport(taskMapper, groupMapper, dataScopeSupport, querySupport, auditSupport,
                        new SyncTaskGroupDisplayContractSupport()),
                taskMapper,
                groupMapper,
                dataScopeSupport
        );
    }

    private SyncActorContext actor() {
        return new SyncActorContext(10L, 1001L, "PROJECT_OWNER", "trace-group-tree");
    }

    private SyncTaskQueryCriteria criteria() {
        return new SyncTaskQueryCriteria(10L, null, null, null, null, null,
                null, null, 1L, 100L, null);
    }

    private SyncDataVisibility projectVisibility() {
        return new SyncDataVisibility(10L, null, List.of(101L, 102L), null,
                true, false, "PROJECT", "project_id in (101,102)", false);
    }

    private SyncTaskGroup group(Long id,
                                Long tenantId,
                                Long projectId,
                                Long workspaceId,
                                String parentGroupCode,
                                String groupCode,
                                String groupName,
                                Integer displayOrder,
                                Boolean defaultGroup) {
        SyncTaskGroup group = new SyncTaskGroup();
        group.setId(id);
        group.setTenantId(tenantId);
        group.setProjectId(projectId);
        group.setWorkspaceId(workspaceId);
        group.setParentGroupCode(parentGroupCode);
        group.setGroupCode(groupCode);
        group.setGroupName(groupName);
        group.setDisplayOrder(displayOrder);
        group.setDefaultGroup(defaultGroup);
        group.setArchived(false);
        return group;
    }

    private SyncTaskGroupSummary summary(Long tenantId,
                                         Long projectId,
                                         Long workspaceId,
                                         String groupCode,
                                         String groupName,
                                         Long taskCount,
                                         Long activeTaskCount,
                                         Long scheduledTaskCount,
                                         Long runningTaskCount,
                                         Long failedTaskCount,
                                         Long recycledTaskCount) {
        SyncTaskGroupSummary summary = new SyncTaskGroupSummary();
        summary.setTenantId(tenantId);
        summary.setProjectId(projectId);
        summary.setWorkspaceId(workspaceId);
        summary.setGroupCode(groupCode);
        summary.setGroupName(groupName);
        summary.setTaskCount(taskCount);
        summary.setActiveTaskCount(activeTaskCount);
        summary.setScheduledTaskCount(scheduledTaskCount);
        summary.setRunningTaskCount(runningTaskCount);
        summary.setFailedTaskCount(failedTaskCount);
        summary.setRecycledTaskCount(recycledTaskCount);
        return summary;
    }

    private record Fixture(SyncTaskGroupOperationSupport support,
                           SyncTaskMapper taskMapper,
                           SyncTaskGroupMapper groupMapper,
                           SyncDataScopeSupport dataScopeSupport) {
    }
}
