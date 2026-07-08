/**
 * @Author : Cui
 * @Date: 2026/07/08 19:10
 * @Description DataSmart Govern Backend - GlobalExceptionHandlerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.common;

import com.czh.datasmart.govern.common.api.PlatformApiErrorDetail;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务模块全局异常处理器测试。
 *
 * <p>该测试保护“老 ApiResponse 也要能返回详细错误”的兼容路径。
 * task-management 还没有整体迁移到 {@code PlatformApiResponse}，但前端仍然需要从 data 中读取
 * details、fieldErrors 和 suggestions，否则页面会退回到只展示错误码的旧体验。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * 缺少必填查询参数时，应返回字段级错误明细。
     */
    @Test
    void missingRequestParameterShouldReturnFieldErrorDetail() {
        MissingServletRequestParameterException exception =
                new MissingServletRequestParameterException("projectId", "Long");

        ResponseEntity<ApiResponse<PlatformApiErrorDetail>> response =
                handler.handleMissingServletRequestParameter(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().getMessage()).contains("projectId");
        assertThat(response.getBody().getData()).isNotNull();
        assertThat(response.getBody().getData().fieldErrors())
                .contains(new PlatformApiErrorDetail.FieldErrorDetail("projectId", "不能为空"));
        assertThat(response.getBody().getData().suggestions()).isNotEmpty();
    }

    /**
     * 未知异常应返回低敏模块级详情，而不是把内部异常原文直接暴露给前端。
     */
    @Test
    void unknownExceptionShouldReturnLowSensitivityDetail() {
        ResponseEntity<ApiResponse<PlatformApiErrorDetail>> response =
                handler.handleException(new RuntimeException("password=secret"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getBody().getMessage()).isEqualTo("task-management 内部异常");
        assertThat(response.getBody().getMessage()).doesNotContain("password");
        assertThat(response.getBody().getData()).isNotNull();
        assertThat(response.getBody().getData().details()).anySatisfy(detail ->
                assertThat(detail).contains("task-management 内部异常").doesNotContain("password"));
    }
}
