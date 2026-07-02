/**
 * @Author : Cui
 * @Date: 2026/07/02 03:15
 * @Description DataSmart Govern Backend - AgentCommandSafetySignals.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * 命令文本安全预检提取出的低敏布尔信号。
 *
 * <p>该对象刻意不保存命令原文、参数、路径或环境变量，只保留 allowlist、危险、写入和联网四类判断。
 * 因此它可以在预检内部安全传递，并用于决定审批和风险级别，而不会扩大命令明文泄露面。
 */
record AgentCommandSafetySignals(
        boolean knownSafe,
        boolean dangerous,
        boolean write,
        boolean network
) {
}
