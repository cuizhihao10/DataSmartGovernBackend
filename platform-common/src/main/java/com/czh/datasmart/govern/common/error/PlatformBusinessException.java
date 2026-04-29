/**
 * @Author : Cui
 * @Date: 2026/04/25 22:30
 * @Description DataSmart Govern Backend - PlatformBusinessException.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.error;

/**
 * 平台级业务异常。
 *
 * 这个异常用于表达“请求已经进入业务系统，但因为权限、状态、租户范围、幂等冲突等原因无法继续执行”。
 * 它不应该被用于包装所有技术异常，例如数据库连接失败、网络超时、序列化失败等。
 *
 * 使用建议：
 * 1. Controller 不直接拼失败响应，而是让 GlobalExceptionHandler 捕获该异常；
 * 2. Service 层在发现明确业务规则不满足时抛出该异常；
 * 3. errorCode 必须尽量使用 PlatformErrorCode 中已有枚举，避免模块私自发明不可观测的错误码。
 */
public class PlatformBusinessException extends RuntimeException {

    /**
     * 平台统一错误码。
     */
    private final PlatformErrorCode errorCode;

    /**
     * 构造业务异常。
     *
     * @param errorCode 平台统一错误码。
     * @param message 更具体的业务错误说明。
     */
    public PlatformBusinessException(PlatformErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 使用错误码默认说明构造异常。
     */
    public PlatformBusinessException(PlatformErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    public PlatformErrorCode getErrorCode() {
        return errorCode;
    }
}
