/**
 * @Author : Cui
 * @Date: 2026/07/07 23:59
 * @Description DataSmart Govern Backend - SyncTransferModeCatalogSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncTransferModeOption;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同步任务传输模式目录测试。
 *
 * <p>这个测试保护的是“前端新建任务页”和“Agent 创建任务工具”共同依赖的产品契约：
 * 用户可选择的传输模式只能是全量、定期全量、定期批量、SQL 自定义、实时五类。
 * 失败回放、历史补数、离线导入、离线导出、按时间/主键增量等能力仍可作为运行期恢复、窗口策略、
 * 文件制品流程或内部兼容能力存在，但不能出现在新建任务模式下拉框里。</p>
 */
class SyncTransferModeCatalogSupportTest {

    private final SyncTransferModeCatalogSupport catalogSupport = new SyncTransferModeCatalogSupport();

    @Test
    void listUserSelectableModesShouldOnlyExposeFiveProductTransferModes() {
        List<SyncTransferModeOption> modes = catalogSupport.listUserSelectableModes();

        assertThat(modes)
                .extracting(SyncTransferModeOption::mode)
                .containsExactly(
                        "FULL",
                        "SCHEDULED_FULL",
                        "SCHEDULED_BATCH",
                        "CUSTOM_SQL_QUERY",
                        "CDC_STREAMING"
                );
        assertThat(modes)
                .extracting(SyncTransferModeOption::mode)
                .doesNotContain(
                        "INCREMENTAL_TIME",
                        "INCREMENTAL_ID",
                        "ONE_TIME_MIGRATION",
                        "REPLAY",
                        "BACKFILL",
                        "OFFLINE_IMPORT",
                        "OFFLINE_EXPORT"
                );
    }

    @Test
    void scheduledFullShouldRequireScheduleConfigButFullShouldNotAllowIt() {
        List<SyncTransferModeOption> modes = catalogSupport.listUserSelectableModes();

        SyncTransferModeOption full = findMode(modes, "FULL");
        SyncTransferModeOption scheduledFull = findMode(modes, "SCHEDULED_FULL");
        SyncTransferModeOption scheduledBatch = findMode(modes, "SCHEDULED_BATCH");

        assertThat(full.scheduleRequired()).isFalse();
        assertThat(full.scheduleAllowed()).isFalse();
        assertThat(scheduledFull.scheduleRequired()).isTrue();
        assertThat(scheduledFull.scheduleAllowed()).isTrue();
        assertThat(scheduledBatch.scheduleRequired()).isTrue();
        assertThat(scheduledBatch.scheduleAllowed()).isTrue();
    }

    private SyncTransferModeOption findMode(List<SyncTransferModeOption> modes, String mode) {
        return modes.stream()
                .filter(item -> mode.equals(item.mode()))
                .findFirst()
                .orElseThrow();
    }
}
