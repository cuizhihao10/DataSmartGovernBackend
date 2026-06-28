/**
 * @Author : Cui
 * @Date: 2026/06/29 00:09
 * @Description DataSmart Govern Backend - DatasourceCapabilitySnapshotEnvelope.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource;

import lombok.Data;

/**
 * datasource-management capability snapshot 响应 envelope 的本地解析模型。
 *
 * <p>这里没有直接依赖 datasource-management 模块里的 ApiResponse 类，是为了保持微服务边界清晰。
 * data-sync 只关心跨服务 JSON 契约中稳定存在的 code/message/data 三个字段；如果把对方模块的 Java 类
 * 直接引入本模块，两个服务会在编译层产生不必要耦合，后续独立部署、独立版本演进和契约测试都会变困难。</p>
 */
@Data
public class DatasourceCapabilitySnapshotEnvelope {

    /**
     * 业务结果码，datasource-management 当前约定 0 表示成功。
     */
    private Integer code;

    /**
     * 人类可读提示信息。
     *
     * <p>客户端不会把该字段原样拼进异常细节中，因为远端 message 未来可能包含更具体的排障描述。
     * 为了坚持低敏原则，data-sync 只根据 code/data 判断成功与否，失败时返回本模块自己的标准化错误。</p>
     */
    private String message;

    /**
     * 低敏能力快照主体。
     */
    private DatasourceCapabilitySnapshotView data;
}
