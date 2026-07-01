/**
 * @Author : Cui
 * @Date: 2026/07/01 10:45
 * @Description DataSmartGovernBackend - PlatformModuleKind.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.support;

/**
 * 平台模块运行形态。
 *
 * <p>这个枚举专门用于 observability 的平台闭环诊断视图。它不是业务模块状态，
 * 而是回答“这个目录/模块在商业化部署中应该以什么形态存在”。用户提到
 * data-quality、observability、platform-common 是否都应该有微服务时，最容易混淆的点就是：
 * 有些模块确实应该独立启动，有些模块只应该作为公共契约被其他服务编译依赖。</p>
 *
 * <p>把运行形态显式枚举出来，可以避免后续因为看到一个 Maven module 就机械地创建服务。
 * 商业级微服务拆分应该按职责、生命周期、扩缩容和故障隔离来决定，而不是按目录数量决定。</p>
 */
public enum PlatformModuleKind {

    /**
     * Java/Spring Boot 微服务。
     *
     * <p>该类模块需要独立端口、健康检查、Prometheus 指标、服务发现注册、网关路由和部署单元。
     * 例如 task-management、data-quality、observability 都属于这种形态。</p>
     */
    JAVA_MICROSERVICE,

    /**
     * Python AI Runtime。
     *
     * <p>它也是可部署运行时，但不是 Spring Boot 服务，不使用 /actuator/prometheus。
     * 当前通过 FastAPI 暴露 Agent 规划、诊断和指标端点，仍然需要被 gateway、Prometheus 和 smoke 纳入闭环。</p>
     */
    PYTHON_RUNTIME,

    /**
     * 共享代码库或公共契约包。
     *
     * <p>这类模块不应该启动端口，也不应该注册到 Nacos。它提供统一响应、Header、上下文、错误码、
     * 审计事件等基础契约，被其他微服务依赖。platform-common 就属于这里。</p>
     */
    SHARED_LIBRARY
}
