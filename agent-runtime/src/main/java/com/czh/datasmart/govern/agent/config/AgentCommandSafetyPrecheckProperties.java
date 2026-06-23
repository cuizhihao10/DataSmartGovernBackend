/**
 * @Author : Cui
 * @Date: 2026-06-23 01:37
 * @Description DataSmart Govern Backend - AgentCommandSafetyPrecheckProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 命令安全预检配置。
 *
 * <p>本配置专门服务“模型或外部协议提出要执行一条命令”这一高风险场景。它和
 * {@link AgentRuntimeProperties.ToolSandboxProperties} 的职责不同：</p>
 *
 * <p>1. ToolSandboxProperties 面向 DataSmart 已注册工具，主要检查工具目录、targetService、参数体量、
 * 幂等重试和审批事实；</p>
 * <p>2. 本配置面向 shell/程序运行类能力，重点检查命令文本、危险片段、工作目录、路径越界、联网、
 * 输出体量和超时预算；</p>
 * <p>3. 二者最终都会服务同一条商业化 Agent 主链路：模型只能提出意图，真实副作用必须经过 Java Host、
 * permission-admin、outbox、worker receipt 和审计事件证明。</p>
 *
 * <p>为什么独立成配置类：AgentRuntimeProperties 已经承载模型路由、工具目录和工具沙箱等大量配置。
 * 如果继续把 command 安全策略塞进去，主配置类会快速膨胀，违反当前项目“解耦、单文件尽量控制在
 * 500 行以内”的约束。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.command-safety-precheck")
public class AgentCommandSafetyPrecheckProperties {

    /**
     * 是否启用命令安全预检。
     *
     * <p>生产环境建议保持 true。若关闭，本次预检接口不会给出“允许执行”的结论，只会返回
     * POLICY_DISABLED 类问题码，提醒调用方该环境没有形成可采信的命令安全事实。</p>
     */
    private Boolean enabled = true;

    /**
     * 是否要求请求必须显式传入 workspaceRoot。
     *
     * <p>命令执行与普通 HTTP 工具最大的区别是它会接触文件系统。没有 workspaceRoot，控制面无法判断
     * workingDirectory 和 referencedPaths 是否越界，也无法证明命令只在 Agent 工作区内运行。</p>
     */
    private Boolean workspaceRootRequired = true;

    /**
     * 单条命令允许的最大字符数。
     *
     * <p>过长命令通常意味着调用方把脚本正文、SQL、prompt、base64 文件或敏感上下文直接塞进命令行。
     * 商业化 Agent 应改用 artifactReference 或受控脚本文件引用，而不是把大段正文放进命令参数。</p>
     */
    private Integer maxCommandChars = 4096;

    /**
     * 默认命令超时时间，单位秒。
     *
     * <p>调用方不传 timeoutSeconds 时使用该值。默认值应短而保守，避免一次 Agent 命令长时间占用
     * worker、容器、CPU、文件句柄或下游网络连接。</p>
     */
    private Integer defaultTimeoutSeconds = 30;

    /**
     * 命令允许的最大超时时间，单位秒。
     *
     * <p>超过该值的请求不会按原值执行，预检响应会返回被裁剪后的 normalizedTimeoutSeconds。
     * 真实 worker 仍应再次强制使用服务端上限，不能信任客户端自报。</p>
     */
    private Integer maxTimeoutSeconds = 120;

    /**
     * 默认输出字节上限。
     *
     * <p>stdout/stderr 可能包含 SQL、样本数据、文件内容、token、异常栈或内部路径。控制输出体量不仅是
     * 性能保护，也是低敏保护。</p>
     */
    private Integer defaultOutputByteLimit = 16 * 1024;

    /**
     * 单次命令允许返回的最大输出字节数。
     *
     * <p>预检只返回归一化上限，不返回真实输出。真实执行器必须按该值裁剪 stdout/stderr，并把完整输出
     * 如有必要写入受控 artifact，而不是直接进入 timeline 或前端响应。</p>
     */
    private Integer maxOutputByteLimit = 64 * 1024;

    /**
     * 写文件、改状态或产生持久副作用的命令是否必须人工审批。
     *
     * <p>默认 true。即使命令本身看起来安全，只要调用方声明 writeRequested=true，或者命令命中常见写动作，
     * 都应该进入 Human-in-the-loop 流程。</p>
     */
    private Boolean approvalRequiredForWrite = true;

    /**
     * 联网命令是否必须人工审批。
     *
     * <p>默认 true。网络访问可能导致数据外传、供应链下载、访问内网端点或触发第三方副作用，因此不能由
     * 模型自动决定。</p>
     */
    private Boolean approvalRequiredForNetwork = true;

    /**
     * 未命中安全命令 allowlist 的命令是否必须人工审批。
     *
     * <p>默认 true。这样可以把未知命令从“默认可执行”变为“默认需要人确认”，避免安全策略追不上工具生态。</p>
     */
    private Boolean approvalRequiredForUnknownCommand = true;

    /**
     * 允许作为低风险候选的命令前缀。
     *
     * <p>这里只是控制面第一层 allowlist，不代表真实执行器可以直接放行。真实执行仍要检查 workspace、
     * 路径、联网、写入、输出、超时、租户权限和审批事实。</p>
     */
    private List<String> safeCommandPrefixes = new ArrayList<>(List.of(
            "pwd",
            "ls",
            "dir",
            "cat",
            "type",
            "rg",
            "grep",
            "findstr",
            "head",
            "tail",
            "wc",
            "git status",
            "git diff",
            "git log",
            "mvn -v",
            "mvn test",
            "java -version"
    ));

    /**
     * 直接阻断的危险命令片段。
     *
     * <p>这些片段通常代表删除、格式化、系统关机、注册表修改、权限放大、管道执行远程脚本等高危行为。
     * 命中后即使携带 approvalFactId，也只返回 BLOCKED，因为这类命令不应进入普通 Agent worker。</p>
     */
    private List<String> dangerousCommandFragments = new ArrayList<>(List.of(
            "rm -rf",
            "del /s",
            "rmdir /s",
            "format ",
            "shutdown",
            "reboot",
            "diskpart",
            "mkfs",
            "dd if=",
            "reg delete",
            "chmod 777",
            "chown -r",
            "icacls ",
            "takeown ",
            "curl | sh",
            "wget | sh",
            "invoke-expression",
            " iwr ",
            "iex "
    ));

    /**
     * 需要识别为“联网倾向”的命令片段。
     *
     * <p>命中后不一定永久阻断，但默认需要人工审批或网络权限事实。后续可以继续接入 egress allowlist、
     * 代理、域名分类和下载大小限制。</p>
     */
    private List<String> networkCommandFragments = new ArrayList<>(List.of(
            "curl ",
            "wget ",
            "invoke-webrequest",
            " iwr ",
            "git clone",
            "git fetch",
            "git pull",
            "npm install",
            "pnpm install",
            "yarn install",
            "pip install",
            "uv pip install",
            "docker pull"
    ));

    /**
     * 常见写入或状态变更命令片段。
     *
     * <p>命中这些片段时，预检会把命令提升为 writeRequested 风险，即使请求体没有显式声明写入。</p>
     */
    private List<String> writeCommandFragments = new ArrayList<>(List.of(
            "touch ",
            "mkdir ",
            "new-item",
            "set-content",
            "out-file",
            "tee ",
            "sed -i",
            "echo ",
            "cp ",
            "copy ",
            "mv ",
            "move ",
            "git add",
            "git commit",
            "npm run build",
            "mvn package"
    ));

    /**
     * 需要阻断的路径片段。
     *
     * <p>这些片段代表系统目录、凭据目录、配置目录、VCS 内部目录或本机工具缓存目录。预检响应只返回分类，
     * 不返回真实路径值。</p>
     */
    private List<String> blockedPathFragments = new ArrayList<>(List.of(
            "/etc",
            "/root",
            "/var/lib",
            "/proc",
            "/sys",
            "c:\\windows",
            "c:\\program files",
            "\\.ssh",
            "/.ssh",
            "\\.aws",
            "/.aws",
            "\\.kube",
            "/.kube",
            "\\.codex",
            "/.codex",
            "\\.git",
            "/.git",
            "\\.m2",
            "/.m2",
            "\\.npm",
            "/.npm"
    ));
}
