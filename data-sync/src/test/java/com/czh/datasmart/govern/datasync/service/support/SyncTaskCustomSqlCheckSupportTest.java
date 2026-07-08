/**
 * @Author : Cui
 * @Date: 2026/07/08 17:02
 * @Description DataSmart Govern Backend - SyncTaskCustomSqlCheckSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceRunOnceProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCustomSqlCheckRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCustomSqlCheckResponse;
import com.czh.datasmart.govern.datasync.integration.datasource.sql.DatasourceReadOnlySqlClient;
import com.czh.datasmart.govern.datasync.integration.datasource.sql.DatasourceReadOnlySqlRequest;
import com.czh.datasmart.govern.datasync.integration.datasource.sql.DatasourceReadOnlySqlResponse;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL 语句模式创建向导检查测试。
 *
 * <p>这组测试保护的是创建向导的安全边界，而不是 JDBC 驱动本身：
 * data-sync 必须先拦截明显危险 SQL，再把真实语法/对象存在性探测委托给 datasource-management；
 * 即使 datasource-management 返回了 rows，data-sync 也不能把样本数据透传给前端。
 * 这样可以避免“SQL 检查入口”被误用成“数据预览/导出入口”。</p>
 */
class SyncTaskCustomSqlCheckSupportTest {

    /**
     * 危险 SQL 应在 data-sync 静态检查阶段被拦截。
     *
     * <p>业务意义：DELETE、DROP、多语句、注释等输入不应该到达真实数据源。
     * 即使 datasource-management 也有只读门禁，data-sync 作为创建向导入口仍应先 fail-fast，降低无意义远程调用和安全风险。</p>
     */
    @Test
    void checkShouldBlockDangerousSqlBeforeRemoteProbe() {
        RecordingReadOnlySqlClient client = new RecordingReadOnlySqlClient();
        SyncTaskCustomSqlCheckSupport support = support(client);

        SyncTaskCustomSqlCheckResponse response = support.check(request("select * from user; delete from user"), actor());

        assertThat(response.passed()).isFalse();
        assertThat(response.staticSafe()).isFalse();
        assertThat(response.remoteProbeExecuted()).isFalse();
        assertThat(response.blockingIssues())
                .anySatisfy(issue -> assertThat(issue).contains("分号"))
                .anySatisfy(issue -> assertThat(issue).contains("高风险关键字"));
        assertThat(client.called).isFalse();
    }

    /**
     * 允许前端只做静态检查，不访问真实数据源。
     *
     * <p>业务意义：用户在 SQL 输入框中频繁编辑时，前端可以先传 {@code skipRemoteProbe=true}
     * 做实时轻量提示；真正进入字段映射或保存前，再启用远程探测确认源端对象存在和输出列。</p>
     */
    @Test
    void checkShouldSupportStaticOnlyMode() {
        RecordingReadOnlySqlClient client = new RecordingReadOnlySqlClient();
        SyncTaskCustomSqlCheckSupport support = support(client);
        SyncTaskCustomSqlCheckRequest request = request("with recent_user as (select id, name from user_info) select id, name from recent_user");
        request.setSkipRemoteProbe(true);

        SyncTaskCustomSqlCheckResponse response = support.check(request, actor());

        assertThat(response.passed()).isTrue();
        assertThat(response.staticSafe()).isTrue();
        assertThat(response.remoteProbeExecuted()).isFalse();
        assertThat(response.recommendedActions())
                .anySatisfy(action -> assertThat(action).contains("最终保存").contains("远程探测"));
        assertThat(client.called).isFalse();
    }

    /**
     * 远程探测成功后应返回 SQL 输出列和字段映射提示。
     *
     * <p>业务意义：SQL 语句模式不需要用户选择源表，源端字段来自 SELECT 输出列或 alias。
     * 这里固定“alias 会成为源端字段名”的合同，便于前端把字段映射表从 JSON 编辑器升级为可搜索、可勾选、可编辑的表格。</p>
     */
    @Test
    void checkShouldReturnColumnsAndFieldMappingHintsWhenRemoteProbeSucceeds() {
        RecordingReadOnlySqlClient client = new RecordingReadOnlySqlClient();
        client.responseColumns = List.of("user_id", "display_name");
        SyncTaskCustomSqlCheckSupport support = support(client);

        SyncTaskCustomSqlCheckResponse response = support.check(
                request("select id as user_id, name as display_name from user_info"),
                actor());

        assertThat(response.passed()).isTrue();
        assertThat(response.staticSafe()).isTrue();
        assertThat(response.remoteProbeExecuted()).isTrue();
        assertThat(response.outputColumnCount()).isEqualTo(2);
        assertThat(response.outputColumns())
                .extracting(SyncTaskCustomSqlCheckResponse.SqlOutputColumn::columnName)
                .containsExactly("user_id", "display_name");
        assertThat(response.outputColumns())
                .allSatisfy(column -> assertThat(column.derivedFromSqlAlias()).isTrue());
        assertThat(response.derivedFieldMappingHints())
                .extracting(SyncTaskCustomSqlCheckResponse.FieldMappingHint::sourceColumnName,
                        SyncTaskCustomSqlCheckResponse.FieldMappingHint::targetColumnCandidate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("user_id", "user_id"),
                        org.assertj.core.groups.Tuple.tuple("display_name", "display_name"));
        assertThat(client.called).isTrue();
        assertThat(client.capturedRequest.getMaxRows()).isEqualTo(1);
        assertThat(client.capturedRequest.getPurpose()).isEqualTo("DATA_SYNC_CUSTOM_SQL_CREATE_WIZARD_CHECK");
    }

    /**
     * 远程 rows 不应透传到创建向导响应。
     *
     * <p>业务意义：datasource-management 的受控只读 SQL 响应通用能力可能包含 rows；
     * 但 SQL 创建向导只需要列元数据。如果这里透传 rows，前端很容易把检查接口当作数据预览，形成权限、脱敏和导出风险。</p>
     */
    @Test
    void checkShouldNotExposeRemoteRows() {
        RecordingReadOnlySqlClient client = new RecordingReadOnlySqlClient();
        client.responseColumns = List.of("user_id");
        client.responseRows = List.of(Map.of("user_id", "sensitive-sample-value"));
        SyncTaskCustomSqlCheckSupport support = support(client);

        SyncTaskCustomSqlCheckResponse response = support.check(
                request("select id as user_id from user_info"),
                actor());

        assertThat(response.passed()).isTrue();
        assertThat(response.outputColumns()).extracting("columnName").containsExactly("user_id");
        assertThat(response.toString()).doesNotContain("sensitive-sample-value");
    }

    /**
     * 重复输出列名必须阻断继续进入字段映射。
     *
     * <p>业务意义：两个 SELECT 表达式都叫同一个名字时，字段映射无法判断应该把哪个源值写入目标字段。
     * 与其让执行期报错或写错列，不如在创建向导阶段要求用户补充唯一 alias。</p>
     */
    @Test
    void checkShouldBlockDuplicateOutputColumns() {
        RecordingReadOnlySqlClient client = new RecordingReadOnlySqlClient();
        client.responseColumns = List.of("id", "id");
        SyncTaskCustomSqlCheckSupport support = support(client);

        SyncTaskCustomSqlCheckResponse response = support.check(
                request("select id, parent_id as id from user_info"),
                actor());

        assertThat(response.passed()).isFalse();
        assertThat(response.blockingIssues())
                .anySatisfy(issue -> assertThat(issue).contains("重复列名").contains("id"));
    }

    private SyncTaskCustomSqlCheckSupport support(RecordingReadOnlySqlClient client) {
        return new SyncTaskCustomSqlCheckSupport(client, new DataSyncDatasourceRunOnceProperties());
    }

    private SyncTaskCustomSqlCheckRequest request(String sql) {
        SyncTaskCustomSqlCheckRequest request = new SyncTaskCustomSqlCheckRequest();
        request.setSyncMode("CUSTOM_SQL_QUERY");
        request.setSourceDatasourceId(100L);
        request.setTargetDatasourceId(200L);
        request.setTargetSchemaName("public");
        request.setTargetObjectName("target_user");
        request.setSql(sql);
        return request;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(
                10L,
                101L,
                10001L,
                1001L,
                "PROJECT_OWNER",
                "trace-custom-sql-check-test",
                "PROJECT",
                "project_id IN ${actorProjectIds}",
                List.of(101L),
                false
        );
    }

    /**
     * 记录型假客户端。
     *
     * <p>测试不需要启动 datasource-management，也不需要真实数据库。
     * 这里用假客户端模拟远程返回列信息，从而把测试焦点放在 data-sync 的创建向导编排逻辑上。</p>
     */
    private static final class RecordingReadOnlySqlClient implements DatasourceReadOnlySqlClient {

        private boolean called;
        private DatasourceReadOnlySqlRequest capturedRequest;
        private List<String> responseColumns = List.of("id");
        private List<Map<String, Object>> responseRows = List.of();

        @Override
        public DatasourceReadOnlySqlResponse execute(Long datasourceId,
                                                     DatasourceReadOnlySqlRequest request,
                                                     SyncActorContext actorContext) {
            this.called = true;
            this.capturedRequest = request;
            DatasourceReadOnlySqlResponse response = new DatasourceReadOnlySqlResponse();
            response.setDatasourceId(datasourceId);
            response.setDatasourceType("MYSQL");
            response.setExecuted(true);
            response.setReturnedRowCount(responseRows.size());
            response.setColumnCount(responseColumns.size());
            response.setDurationMs(12L);
            response.setColumns(responseColumns);
            response.setRows(new ArrayListRowMapList(responseRows));
            response.setWarnings(List.of("服务端已应用最大返回行数"));
            return response;
        }
    }

    /**
     * 保持 rows 使用可变 LinkedHashMap 的小适配。
     *
     * <p>部分 Jackson/JDBC 场景返回的是 LinkedHashMap，这里模拟真实响应结构；
     * 但测试断言会确认这些行数据不会出现在 data-sync 的创建向导响应中。</p>
     */
    private static final class ArrayListRowMapList extends java.util.ArrayList<Map<String, Object>> {
        private ArrayListRowMapList(List<Map<String, Object>> rows) {
            for (Map<String, Object> row : rows) {
                add(new LinkedHashMap<>(row));
            }
        }
    }
}
