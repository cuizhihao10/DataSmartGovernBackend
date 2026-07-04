/**
 * @Author : Cui
 * @Date: 2026/07/05 01:22
 * @Description DataSmart Govern Backend - AgentRagCommandWorkerClientProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Java agent-runtime 调用 Python RAG Command Worker 的客户端配置。
 *
 * <p>本配置块服务于“Java command outbox -> Python RAG worker -> Java worker receipt”的闭环。
 * 它和普通 `/agent/rag/query` 产品查询入口不同：这里的调用来自后台 outbox dispatcher，
 * 目标是让 `knowledge.rag.query` 命令可以被可靠投递、失败重试、低敏回执和后续 E2E smoke 验证。</p>
 *
 * <p>为什么要单独配置 RAG worker，而不是复用 MCP durable worker 配置：
 * 1. MCP tools/call 通常可能访问外部系统，属于更高风险的副作用边界；
 * 2. RAG 查询是只读知识检索，默认不声明 `sideEffectExecuted=true`，也不要求 worker lease；
 * 3. RAG 的敏感边界集中在 question、answer、chunk text、sourceUri 和 compressedContext，
 *    这些内容不能进入 Java 控制面，而 MCP 的敏感边界更多是 arguments、tool result 和外部 endpoint；
 * 4. 分开配置后，生产环境可以独立灰度 RAG worker、调整超时、切换内部网关或服务网格地址。</p>
 *
 * <p>安全约束：
 * - `baseUrl` 只能指向内网 Python Runtime、内部 gateway 或服务网格地址；
 * - `serviceAccountToken` 必须来自环境变量、Nacos Secret、K8s Secret 或企业密钥系统；
 * - 客户端返回对象和异常信息不得包含 URL、Header、question、answer、token 或响应正文。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.rag-command-worker")
public class AgentRagCommandWorkerClientProperties {

    /**
     * 是否启用 Java -> Python RAG Command Worker 调用。
     *
     * <p>默认关闭是为了保护本地学习环境：很多时候开发者只启动 Java agent-runtime，
     * 如果此开关默认打开，dispatcher 可能因为 Python Runtime 未启动而不断失败重试。
     * 完整 Compose、真实 E2E 或生产环境应显式打开该开关，并配合内部认证和网络隔离。</p>
     */
    private boolean enabled = false;

    /**
     * Python Runtime 内部 base URL。
     *
     * <p>本地常用 `http://localhost:8090`，Compose 内部网络常用 `http://python-ai-runtime:8090`。
     * 生产环境更建议通过内部 gateway、服务网格或服务发现地址访问，而不是把主机和端口硬编码到业务代码。</p>
     */
    private String baseUrl = "http://localhost:8090";

    /**
     * Python RAG Command Worker 内部路由。
     *
     * <p>Python 侧当前同时注册 `/internal/agent/rag/command-worker/run` 和
     * `/api/internal/agent/rag/command-worker/run`。保留配置项可以支持不同网关前缀、灰度版本或路径重写。</p>
     */
    private String runPath = "/internal/agent/rag/command-worker/run";

    /**
     * 连接超时时间，单位毫秒。
     *
     * <p>RAG worker 调用位于后台 dispatcher 链路。如果 Python Runtime 不可达，应尽快释放 dispatcher，
     * 让 outbox 状态机记录失败并按退避策略重试，而不是长时间阻塞 Java 控制面线程。</p>
     */
    private long connectTimeoutMs = 1500;

    /**
     * 读取响应超时时间，单位毫秒。
     *
     * <p>该超时覆盖 Python 路由解析、RAG 检索、可选生成、checkpoint 写入和 receipt 生成。
     * 后续如果 RAG 需要处理很大的知识库或生成较长答案，应优先引入异步 worker 与 artifact writer，
     * 不建议无限拉长同步 HTTP 等待时间。</p>
     */
    private long readTimeoutMs = 30000;

    /**
     * 是否要求 Python worker 自己直接 POST Java receipt。
     *
     * <p>当前推荐保持 false：Java dispatcher 先拿到 Python 低敏响应，再由 Java 侧统一写 receipt。
     * 这样更容易保证 outbox PUBLISHED、receipt index、runtime event projection 和失败重试语义一致。
     * 如果未来某些部署希望 Python 直写 receipt，可以在灰度环境显式开启。</p>
     */
    private boolean postToJava = false;

    /**
     * 服务间认证 Header 名称。
     *
     * <p>默认使用 `Authorization`，并在 `serviceAccountToken` 非空时发送 `Bearer <token>`。
     * 如果客户环境使用 mTLS 旁路身份、自定义网关签名头或服务网格身份头，可以通过该字段替换。</p>
     */
    private String authHeaderName = "Authorization";

    /**
     * 服务账号令牌。
     *
     * <p>这是敏感配置，只能通过环境变量或 Secret 注入。客户端实现不会把它写入日志、异常、runtime event、
     * HTTP 响应或测试断言输出。</p>
     */
    private String serviceAccountToken = "";
}
