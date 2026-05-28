/**
 * @Author : Cui
 * @Date: 2026/05/23 18:24
 * @Description DataSmart Govern Backend - DataQualityControllerProjectScopeTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import com.czh.datasmart.govern.quality.service.DataQualityService;
import com.czh.datasmart.govern.quality.service.support.QualityProjectScopeSupport;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import com.czh.datasmart.govern.quality.service.support.QualityRuleSuggestionSupport;
import com.czh.datasmart.govern.quality.support.QualityRuleStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * data-quality 控制器项目范围测试。
 *
 * <p>这组测试专门验证一个商业化平台里很容易被忽略但后果很严重的问题：
 * 同一个质量规则、同一份报告、同一组异常样本，是否真的只能被当前请求可见的项目访问。</p>
 *
 * <p>为什么要把这类测试放在 Controller 层而不是只测 support：
 * 1. support 只负责解释项目范围，但最终是否真的接入到列表、详情和报告接口，还要看 Controller 是否把它用起来；
 * 2. 列表接口和详情接口的收口方式不同，列表可以空结果，详情必须拒绝；
 * 3. 报告查询虽然不是直接拒绝返回，但它必须把解析出的可见范围继续传给 service，让服务层在查询时落地过滤。</p>
 */
class DataQualityControllerProjectScopeTest {

    /**
     * PROJECT 范围下如果授权项目集合为空，规则列表应该直接收口为空。
     *
     * <p>这比“返回全部规则再让前端过滤”安全得多，也符合商业系统里“无权限即无数据”的原则。</p>
     */
    @Test
    void listRulesShouldReturnEmptyPageWhenProjectScopeHasNoAuthorizedProjects() {
        DataQualityService service = mock(DataQualityService.class);
        DataQualityController controller = controller(service);

        var response = controller.listRules(1, 10, null, null, null, null, "PROJECT", "");

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isInstanceOf(Page.class);
        IPage<QualityRule> page = response.getBody().getData();
        assertThat(page.getRecords()).isEmpty();
        assertThat(page.getTotal()).isZero();
        verify(service, never()).page(any(), any());
    }

    /**
     * 详情接口必须在读取规则后再次校验项目归属。
     *
     * <p>这是因为详情、更新、启停和删除这类接口通常只有 ID，没有显式 projectId。
     * 如果只靠 ID 查出来后不校验，用户就可能通过猜 ID 越权访问其他项目的规则。</p>
     */
    @Test
    void getRuleShouldRejectUnauthorizedProjectAccess() {
        DataQualityService service = mock(DataQualityService.class);
        DataQualityController controller = controller(service);
        QualityRule rule = new QualityRule();
        rule.setId(1001L);
        rule.setProjectId(999L);
        rule.setStatus(QualityRuleStatus.ACTIVE);
        when(service.getById(1001L)).thenReturn(rule);

        assertThrows(IllegalArgumentException.class, () -> controller.getRule(1001L, "PROJECT", "101,102"));
    }

    /**
     * 报告查询接口需要把可见范围传给服务层。
     *
     * <p>这里不直接断言 service 内部 SQL，因为那是 service 的职责；
     * 我们只确认 Controller 已经把 PROJECT 范围和授权项目集合传递正确，避免后续重构时把 Header 解析链路丢掉。</p>
     */
    @Test
    void reportControllerShouldPassResolvedVisibilityToService() {
        DataQualityService service = mock(DataQualityService.class);
        QualityReportController controller = reportController(service);
        when(service.pageReports(anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new Page<>());

        controller.pageReports(
                1,
                10,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "PROJECT",
                "101,102"
        );

        ArgumentCaptor<QualityProjectVisibility> visibilityCaptor = ArgumentCaptor.forClass(QualityProjectVisibility.class);
        verify(service).pageReports(
                anyInt(),
                anyInt(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                visibilityCaptor.capture()
        );
        QualityProjectVisibility visibility = visibilityCaptor.getValue();
        assertThat(visibility.projectScopeEnforced()).isTrue();
        assertThat(visibility.authorizedProjectIds()).containsExactly(101L, 102L);
    }

    /**
     * 构造规则控制器。
     */
    private DataQualityController controller(DataQualityService service) {
        return new DataQualityController(service, new QualityProjectScopeSupport(), new QualityRuleSuggestionSupport());
    }

    /**
     * 构造报告控制器。
     */
    private QualityReportController reportController(DataQualityService service) {
        return new QualityReportController(service, new QualityProjectScopeSupport());
    }
}
