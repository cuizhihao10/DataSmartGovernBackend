/**
 * @Author : Cui
 * @Date: 2026/06/29 12:52
 * @Description DataSmart Govern Backend - DatasourceRunOnceEnvelope.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.runonce;

import lombok.Getter;
import lombok.Setter;

/**
 * datasource-management run-once 响应 envelope 的 data-sync 本地镜像。
 *
 * <p>datasource-management 当前使用模块内的 {@code ApiResponse<T>} 返回
 * {@code code/message/data} 三段结构。data-sync 不直接依赖对方模块的 Java 类，
 * 只在本模块定义一个字段同名的解析镜像，这样两个微服务可以独立编译、独立部署、独立演进。
 * 后续如果 run-once 从 HTTP JSON 升级为 gRPC、Kafka command 或 service mesh sidecar，
 * 只需要替换 {@link HttpDatasourceRunOnceClient}，不会把 datasource-management 内部类型扩散到 data-sync。</p>
 *
 * <p>安全说明：虽然响应主体已经是低敏摘要，本 envelope 仍然不使用 Lombok {@code @Data}，
 * 避免未来有人在响应里加入更敏感字段后被默认 {@code toString()} 打到日志。</p>
 */
@Getter
@Setter
public class DatasourceRunOnceEnvelope {

    /**
     * 远端业务结果码。datasource-management 当前约定 0 表示成功。
     */
    private Integer code;

    /**
     * 远端人类可读提示。
     *
     * <p>data-sync 不会把该字段原样拼接到业务异常中，因为远端 message 未来可能携带更具体的排障上下文。
     * 本模块只根据 code/data 判断成功与否，失败时返回 data-sync 自己标准化、低敏的错误描述。</p>
     */
    private String message;

    /**
     * 单批执行低敏结果。
     */
    private DatasourceRunOnceResponse data;
}
