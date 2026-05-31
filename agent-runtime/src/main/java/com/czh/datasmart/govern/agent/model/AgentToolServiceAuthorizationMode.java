/**
 * @Author : Cui
 * @Date: 2026/05/31 23:58
 * @Description DataSmart Govern Backend - AgentToolServiceAuthorizationMode.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

/**
 * Agent 工具服务间授权预检模式。
 *
 * <p>Agent Runtime 未来会代表用户触发 datasource、data-quality、data-sync、task-management 等多个业务服务。
 * 这类“服务账号代表人类 actor 执行动作”的场景，不能只依赖模型计划，也不能只依赖工具目录声明，
 * 必须有一层可以解释、可审计、可灰度的授权预检。</p>
 *
 * <p>这里先把模式拆成枚举，是为了避免后续在业务代码里散落字符串判断：
 * 本地学习环境可以用 LOCAL_PREVIEW 做结构校验，生产环境则应逐步切换到 PERMISSION_ADMIN_EVALUATE，
 * 由 permission-admin 统一判定服务账号、动作、资源范围和审批要求。</p>
 */
public enum AgentToolServiceAuthorizationMode {

    /**
     * 只做本地结构化预览，不发起跨服务 HTTP 调用。
     *
     * <p>该模式适合单元测试、本地学习和 permission-admin 尚未启动的开发环境。
     * 它会检查 tenantId、projectId、actorId、toolCode、requiredActions 等关键上下文字段是否齐全，
     * 但不会声称自己已经获得真实授权，因此返回原因中会明确标记为“本地预览”。</p>
     */
    LOCAL_PREVIEW,

    /**
     * 调用 permission-admin 的 evaluate 接口进行真实服务间授权判定。
     *
     * <p>该模式更接近商业化生产链路：Agent Runtime 以 SERVICE_ACCOUNT 身份请求 permission-admin，
     * permission-admin 根据角色、路由策略、资源类型、动作和数据范围返回 allow/deny。
     * 当前它仍是预检能力，真实执行入口仍必须二次读取最新状态并再次校验。</p>
     */
    PERMISSION_ADMIN_EVALUATE
}
