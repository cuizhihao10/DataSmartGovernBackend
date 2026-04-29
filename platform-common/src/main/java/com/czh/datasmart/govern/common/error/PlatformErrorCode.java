/**
 * @Author : Cui
 * @Date: 2026/04/25 22:30
 * @Description DataSmart Govern Backend - PlatformErrorCode.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.error;

/**
 * 平台级错误码枚举。
 *
 * 错误码的价值不是“把异常换成数字”这么简单，而是给商业化产品提供稳定的错误语言：
 * 1. 前端可以根据错误码做统一提示、跳转登录、展示无权限页；
 * 2. gateway 可以根据错误码判断是否需要熔断、限流、审计或告警；
 * 3. observability 可以统计不同错误类型的出现频率；
 * 4. 客户现场排障时，可以把错误码作为比异常堆栈更稳定的沟通对象。
 *
 * 编码建议：
 * 0 表示成功；
 * 10xxx 表示通用请求问题；
 * 20xxx 表示认证鉴权问题；
 * 30xxx 表示租户、数据范围和合规问题；
 * 40xxx 表示业务状态冲突；
 * 50xxx 表示外部依赖或基础设施问题；
 * 90xxx 表示未知系统异常。
 */
public enum PlatformErrorCode {

    SUCCESS(0, 200, "success"),
    VALIDATION_ERROR(10001, 400, "请求参数校验失败"),
    BAD_REQUEST(10002, 400, "请求格式或参数不合法"),
    NOT_FOUND(10003, 404, "请求的资源不存在"),
    UNAUTHORIZED(20001, 401, "当前请求未认证"),
    FORBIDDEN(20002, 403, "当前身份没有权限执行该操作"),
    TENANT_SCOPE_DENIED(30001, 403, "当前身份不能访问目标租户数据"),
    SENSITIVE_DATA_REQUIRES_APPROVAL(30002, 403, "敏感数据操作需要审批或更高权限"),
    BUSINESS_STATE_CONFLICT(40001, 409, "当前资源状态不允许执行该操作"),
    DUPLICATE_OPERATION(40002, 409, "重复操作或幂等冲突"),
    EXTERNAL_DEPENDENCY_FAILED(50001, 502, "外部依赖调用失败"),
    DEPENDENCY_TIMEOUT(50002, 504, "外部依赖调用超时"),
    INTERNAL_ERROR(90000, 500, "系统内部异常");

    /**
     * 平台统一业务码。
     */
    private final int code;

    /**
     * 建议映射的 HTTP 状态码。
     * 领域模块可以根据场景覆写 HTTP 状态，但默认建议保持这里的映射。
     */
    private final int httpStatus;

    /**
     * 默认错误说明。
     */
    private final String defaultMessage;

    PlatformErrorCode(int code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
