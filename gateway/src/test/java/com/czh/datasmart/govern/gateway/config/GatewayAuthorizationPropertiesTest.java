/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - GatewayAuthorizationPropertiesTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway 路由授权元数据的安全基线测试。
 *
 * <p>application.yml 可以覆盖整份 route-metadata。该测试模拟一个只保留旧版通用 Agent 规则的环境，
 * 证明配置绑定完成后，专业事实路径仍会被插入通用规则之前，不会退化成普通 POST CREATE 或绕过授权过滤器。</p>
 */
class GatewayAuthorizationPropertiesTest {

    /**
     * 浏览器使用的 RAG 与事件补偿入口必须拥有精确动作语义，并排在通用 Agent 规则之前。
     */
    @Test
    void shouldDescribeRagAndRuntimeEventHttpRoutesBeforeGenericAgentRoute() {
        GatewayAuthorizationProperties properties = new GatewayAuthorizationProperties();

        List<String> paths = properties.getRouteMetadata().stream()
                .map(GatewayAuthorizationProperties.RouteAuthorizationMetadata::getPathPattern)
                .toList();

        assertThat(paths)
                .contains("/api/agent/rag/query", "/api/agent/rag/diagnostics",
                        "/api/agent/langgraph/checkpoints/latest", "/api/agent/langgraph/checkpoints/events",
                        "/api/agent/events/replay", "/api/agent/events/control");
        assertThat(paths.indexOf("/api/agent/rag/query"))
                .isLessThan(paths.indexOf("/api/agent/**"));
        assertThat(paths.indexOf("/api/agent/events/replay"))
                .isLessThan(paths.indexOf("/api/agent/**"));
        assertThat(paths.indexOf("/api/agent/langgraph/checkpoints/latest"))
                .isLessThan(paths.indexOf("/api/agent/**"));
    }

    /** REST replay/control 必须进入 Python Runtime，不能被 Java agent-runtime 通配路由吞掉。 */
    @Test
    void applicationRoutesRuntimeEventReplayAndControlToPython() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"))
                .replace("\r\n", "\n");

        int eventRoute = yaml.indexOf("id: python-ai-runtime-events-http");
        int genericAgentRoute = yaml.indexOf("- id: agent-runtime\n");
        assertThat(eventRoute).isGreaterThanOrEqualTo(0).isLessThan(genericAgentRoute);
        assertThat(yaml)
                .contains("Path=/api/agent/events/replay,/api/agent/events/control")
                .contains("RewritePath=/api/agent/events/(?<segment>.*), /agent/events/$\\{segment}");
    }

    /** LangGraph 低敏查询必须进入 Python checkpointer，不能落入 Java Agent 通配路由。 */
    @Test
    void applicationRoutesLangGraphCheckpointReadsToPython() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"))
                .replace("\r\n", "\n");

        int checkpointRoute = yaml.indexOf("id: python-ai-runtime-langgraph-checkpoints");
        int genericAgentRoute = yaml.indexOf("- id: agent-runtime\n");
        assertThat(checkpointRoute).isGreaterThanOrEqualTo(0).isLessThan(genericAgentRoute);
        assertThat(yaml)
                .contains("Path=/api/agent/langgraph/checkpoints/latest,/api/agent/langgraph/checkpoints/events")
                .contains("RewritePath=/api/agent/langgraph/checkpoints/(?<segment>.*), /agent/langgraph/checkpoints/$\\{segment}");
    }

    /**
     * 旧配置覆盖 route-metadata 时，专业事实路由仍必须拥有独立的读取/登记动作。
     */
    @Test
    void shouldRestoreSpecialistFactRouteBeforeGenericAgentRoute() {
        GatewayAuthorizationProperties properties = new GatewayAuthorizationProperties();
        GatewayAuthorizationProperties.RouteAuthorizationMetadata genericRoute =
                GatewayAuthorizationProperties.route(
                        "/api/agent/**", "AI_RUNTIME", "通用 Agent 规则");
        properties.setRouteMetadata(new ArrayList<>(List.of(genericRoute)));

        properties.ensureSpecialistTurnFactsRouteMetadata();

        assertThat(properties.getRouteMetadata())
                .extracting(GatewayAuthorizationProperties.RouteAuthorizationMetadata::getPathPattern)
                .containsExactly(
                        "/api/agent/specialist-turn-facts/**",
                        "/api/agent/**");
        GatewayAuthorizationProperties.RouteAuthorizationMetadata specialistRoute =
                properties.getRouteMetadata().getFirst();
        assertThat(specialistRoute.getMethodActions())
                .containsEntry("GET", "VIEW")
                .containsEntry("POST", "EXECUTE");
    }

    /** 已经显式配置事实路由时不重复插入，避免配置顺序和动作解释产生歧义。 */
    @Test
    void shouldKeepOneExplicitSpecialistFactRoute() {
        GatewayAuthorizationProperties properties = new GatewayAuthorizationProperties();
        properties.ensureSpecialistTurnFactsRouteMetadata();
        int routeCount = (int) properties.getRouteMetadata().stream()
                .filter(route -> "/api/agent/specialist-turn-facts/**".equals(route.getPathPattern()))
                .count();

        assertThat(routeCount).isEqualTo(1);
    }

    /**
     * 即使部署文件误把专业事实路由配置成普通 REST 动作，绑定后的安全目录也必须纠正它。
     *
     * <p>这项保护避免“配置文件里存在同路径”被误认为“配置正确”。专业事实 POST 是受信服务
     * EXECUTE，不允许因为环境复制旧配置而退化成 CREATE。</p>
     */
    @Test
    void shouldReplaceUnsafeExplicitSpecialistFactRouteMetadata() {
        GatewayAuthorizationProperties properties = new GatewayAuthorizationProperties();
        properties.setRouteMetadata(new ArrayList<>(List.of(
                GatewayAuthorizationProperties.route(
                        "/api/agent/specialist-turn-facts/**", "SYSTEM_SETTING", "错误覆盖",
                        GatewayAuthorizationProperties.defaultMethodActions()),
                GatewayAuthorizationProperties.route(
                        "/api/agent/**", "AI_RUNTIME", "通用 Agent 规则"))));

        properties.ensureSpecialistTurnFactsRouteMetadata();

        GatewayAuthorizationProperties.RouteAuthorizationMetadata specialistRoute =
                properties.getRouteMetadata().getFirst();
        assertThat(specialistRoute.getResourceType()).isEqualTo("AI_RUNTIME");
        assertThat(specialistRoute.getMethodActions())
                .containsEntry("GET", "VIEW")
                .containsEntry("POST", "EXECUTE");
        assertThat(properties.getRouteMetadata()).hasSize(2);
    }

    /** 专业事实登记根路径必须出现在本地默认服务账号守卫目录中，普通主体不能进入。 */
    @Test
    void shouldProtectSpecialistFactRegistrationAsInternalServiceEndpoint() {
        GatewayAuthorizationProperties properties = new GatewayAuthorizationProperties();

        assertThat(properties.getInternalServiceEndpoints())
                .anySatisfy(endpoint -> {
                    if ("/api/agent/specialist-turn-facts".equals(endpoint.getPathPattern())) {
                        assertThat(endpoint.getAllowedActorRoles()).containsExactly("SERVICE_ACCOUNT");
                        assertThat(endpoint.getAllowedActorTypes()).containsExactly("SERVICE_ACCOUNT");
                    }
                });
    }
}
