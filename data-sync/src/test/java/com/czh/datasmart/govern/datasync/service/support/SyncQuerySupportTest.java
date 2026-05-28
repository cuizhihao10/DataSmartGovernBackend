/**
 * @Author : Cui
 * @Date: 2026/05/23 10:40
 * @Description DataSmart Govern Backend - SyncQuerySupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * `SyncQuerySupport` 的基础行为测试。
 *
 * <p>这组测试不依赖 Spring 容器，也不依赖数据库，只负责验证“分页和文本规整”这类纯函数行为。
 * 这样一来，后续如果有人调整默认页大小、最大页大小或字符串处理规则，单测能第一时间提醒我们。
 */
class SyncQuerySupportTest {

    private final SyncQuerySupport support = new SyncQuerySupport();

    /**
     * 默认分页应该稳定兜底。
     */
    @Test
    void shouldClampPageValuesSafely() {
        Page<Object> page = support.page(0L, 9999L);
        assertEquals(1L, page.getCurrent());
        assertEquals(200L, page.getSize());
    }

    /**
     * 字符串规整应同时兼容空白、默认值和大小写归一。
     */
    @Test
    void shouldNormalizeAndFallbackText() {
        assertEquals("RUNNING", support.normalizeCode(" running "));
        assertEquals("default", support.defaultText(" ", "default"));
        assertNull(support.trimToNull("   "));
    }

    /**
     * 截断规则应该保持简单、可预测。
     */
    @Test
    void shouldTruncateTextPredictably() {
        assertEquals("12345", support.truncate("123456789", 5));
        assertEquals("abc", support.truncate("abc", 5));
    }

    /**
     * actorId 提取应安全处理空上下文。
     */
    @Test
    void shouldExtractActorIdSafely() {
        assertNull(support.actorId(null));
        assertEquals(1001L, support.actorId(new SyncActorContext(7L, 1001L, "OPERATOR", "trace")));
    }
}
