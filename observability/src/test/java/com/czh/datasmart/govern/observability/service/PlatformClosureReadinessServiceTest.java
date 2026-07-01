/**
 * @Author : Cui
 * @Date: 2026/07/01 10:50
 * @Description DataSmartGovernBackend - PlatformClosureReadinessServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.service;

import com.czh.datasmart.govern.observability.controller.dto.PlatformClosureModuleView;
import com.czh.datasmart.govern.observability.controller.dto.PlatformClosureReadinessResponse;
import com.czh.datasmart.govern.observability.support.PlatformModuleKind;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台闭环 readiness 服务测试。
 *
 * <p>这组测试不是为了验证静态字符串本身，而是锁定几个关键架构判断：
 * 1. data-quality 与 observability 必须被视为可部署微服务；
 * 2. platform-common 必须被视为共享库，不能被误纳入“缺失微服务”；
 * 3. 当前收敛阶段不能再靠人工记忆判断模块是否闭环，应该由 observability API 输出统一视图。</p>
 */
class PlatformClosureReadinessServiceTest {

    private final PlatformClosureReadinessService service = new PlatformClosureReadinessService();

    @Test
    void shouldTreatDataQualityAndObservabilityAsDeployableMicroservices() {
        PlatformClosureReadinessResponse response = service.buildClosureReadiness();
        Map<String, PlatformClosureModuleView> modules = response.modules().stream()
                .collect(Collectors.toMap(PlatformClosureModuleView::moduleCode, Function.identity()));

        PlatformClosureModuleView dataQuality = modules.get("data-quality");
        assertThat(dataQuality).isNotNull();
        assertThat(dataQuality.moduleKind()).isEqualTo(PlatformModuleKind.JAVA_MICROSERVICE);
        assertThat(dataQuality.deployableRuntime()).isTrue();
        assertThat(dataQuality.defaultPort()).isEqualTo(8083);
        assertThat(dataQuality.gatewayPrefix()).isEqualTo("/api/quality/**");

        PlatformClosureModuleView observability = modules.get("observability");
        assertThat(observability).isNotNull();
        assertThat(observability.moduleKind()).isEqualTo(PlatformModuleKind.JAVA_MICROSERVICE);
        assertThat(observability.deployableRuntime()).isTrue();
        assertThat(observability.defaultPort()).isEqualTo(8084);
        assertThat(observability.gatewayPrefix()).isEqualTo("/api/observability/**");
    }

    @Test
    void shouldKeepPlatformCommonAsSharedLibraryInsteadOfMissingService() {
        PlatformClosureReadinessResponse response = service.buildClosureReadiness();
        Map<String, PlatformClosureModuleView> modules = response.modules().stream()
                .collect(Collectors.toMap(PlatformClosureModuleView::moduleCode, Function.identity()));

        PlatformClosureModuleView platformCommon = modules.get("platform-common");
        assertThat(platformCommon).isNotNull();
        assertThat(platformCommon.moduleKind()).isEqualTo(PlatformModuleKind.SHARED_LIBRARY);
        assertThat(platformCommon.deployableRuntime()).isFalse();
        assertThat(platformCommon.defaultPort()).isNull();
        assertThat(platformCommon.expectedServiceName()).isNull();
        assertThat(platformCommon.operationalNotes())
                .anySatisfy(note -> assertThat(note).contains("不应该被做成微服务"));

        assertThat(response.platformCommonShouldBeMicroservice()).isFalse();
        assertThat(response.missingMicroservices()).isEmpty();
    }

    @Test
    void shouldExposeCurrentClosureCountsForProjectConvergence() {
        PlatformClosureReadinessResponse response = service.buildClosureReadiness();

        assertThat(response.assessmentVersion()).isEqualTo("platform-closure-readiness.v1");
        assertThat(response.expectedJavaMicroserviceCount()).isEqualTo(8);
        assertThat(response.deployableRuntimeCount()).isEqualTo(9);
        assertThat(response.sharedLibraryCount()).isEqualTo(1);
        assertThat(response.wiredRuntimeCount()).isEqualTo(9);
        assertThat(response.recommendedNextActions())
                .anySatisfy(action -> assertThat(action).contains("服务健康聚合"));
    }
}
