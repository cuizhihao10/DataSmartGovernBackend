package com.czh.datasmart.govern.datasource.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author : Cui
 * @Date: 2026/07/09 01:28
 * @Description DataSmart Govern Backend - DataSourceAuthorizationActionTest.java
 * @Version:1.0.0
 *
 * 数据源实例授权动作的业务语义测试。
 *
 * <p>数据源授权不是简单的字符串比较：真实产品中通常会把能力分成“看见资源”“使用资源”“管理资源”三层。
 * 其中 MANAGE 是最高权限，天然包含 USE 和 VIEW；USE 表示可以把数据源用于同步、质量扫描、元数据发现等受控流程，
 * 因此也必须包含 VIEW，否则用户能使用却不能在列表中看见候选数据源。</p>
 *
 * <p>这个测试不启动 Spring 容器，只固定枚举本身的规则。这样后续重构授权服务、迁移表结构或接入更复杂的权限中心时，
 * 只要动作层级语义被误改，就能用最小成本被发现。</p>
 */
class DataSourceAuthorizationActionTest {

    /**
     * 验证授权动作列表写入数据库前会被统一成稳定的 CSV。
     *
     * <p>前端、多语言脚本、导入文件可能提交小写、重复值或顺序不同的动作编码。服务层必须在落库前完成大小写归一化、
     * 去重和合法性校验，否则同一组授权可能因为文本顺序不同而难以审计和比较。</p>
     */
    @Test
    void normalizeToCsvShouldDeduplicateAndNormalizeCase() {
        String normalized = DataSourceAuthorizationAction.normalizeToCsv(List.of("view", "USE", "use"));

        assertThat(normalized).isEqualTo("VIEW,USE");
    }

    /**
     * 验证 MANAGE 是最高权限，包含 VIEW 和 USE。
     *
     * <p>这条规则支撑数据源管理页的常见体验：拥有管理权限的项目负责人既能打开授权弹窗，也能查看详情、测试连接和执行元数据发现。</p>
     */
    @Test
    void manageShouldIncludeViewAndUse() {
        assertThat(DataSourceAuthorizationAction.includes("MANAGE", DataSourceAuthorizationAction.VIEW)).isTrue();
        assertThat(DataSourceAuthorizationAction.includes("MANAGE", DataSourceAuthorizationAction.USE)).isTrue();
        assertThat(DataSourceAuthorizationAction.includes("MANAGE", DataSourceAuthorizationAction.MANAGE)).isTrue();
    }

    /**
     * 验证 USE 包含 VIEW，但 VIEW 不反向包含 USE。
     *
     * <p>“可使用”意味着页面至少要能展示该数据源的低敏摘要；但“仅可查看”不能自动升级为可以触达外部数据库，
     * 否则普通查看授权会被误扩大成同步执行能力。</p>
     */
    @Test
    void useShouldIncludeViewButViewShouldNotIncludeUse() {
        assertThat(DataSourceAuthorizationAction.includes("USE", DataSourceAuthorizationAction.VIEW)).isTrue();
        assertThat(DataSourceAuthorizationAction.includes("VIEW", DataSourceAuthorizationAction.USE)).isFalse();
    }

    /**
     * 验证非法动作会被拒绝。
     *
     * <p>授权动作是安全边界字段，不能允许未知字符串静默落库；否则未来权限判断可能因为识别不了旧数据而出现误放行或误拒绝。</p>
     */
    @Test
    void normalizeToCsvShouldRejectUnknownAction() {
        assertThatThrownBy(() -> DataSourceAuthorizationAction.normalizeToCsv(List.of("VIEW", "ROOT")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ROOT");
    }
}
