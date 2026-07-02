/**
 * @Author : Cui
 * @Date: 2026/07/02 03:30
 * @Description DataSmart Govern Backend - AgentSkillPublicationDraftValidator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.skill;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Skill 发布草稿的语法与低敏内容校验器。
 *
 * <p>发布生命周期服务负责状态推进和持久化，本类负责进入生命周期前的纯输入校验。Skill code 使用稳定的
 * 小写标识格式；displayName/description 等可进入 Manifest、审计和市场展示的文本只允许低敏摘要，
 * 禁止包含 prompt、SQL、URL、凭据、工具参数或模型输出。
 *
 * <p>关键词检查是控制面保守门禁，不是完整 DLP。生产上传制品、发布审批和对象存储入口仍需更强扫描。
 */
final class AgentSkillPublicationDraftValidator {

    private static final Pattern SKILL_CODE_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9_.-]{2,158}$");

    private static final List<String> SENSITIVE_TEXT_MARKERS = List.of(
            "api_key", "apikey", "access_key", "secret", "password", "passwd", "bearer ",
            "token=", "jdbc:", "http://", "https://", "select *", "insert into", "update ",
            "delete from", "drop table", "prompt:", "system prompt", "model output"
    );

    private AgentSkillPublicationDraftValidator() {
    }

    static void validateSkillCode(String skillCode) {
        if (!isValidSkillCode(skillCode)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "skillCode 只能包含小写字母、数字、点、下划线和短横线，且长度需在 3-159 之间");
        }
    }

    static boolean isValidSkillCode(String skillCode) {
        return skillCode != null && SKILL_CODE_PATTERN.matcher(skillCode).matches();
    }

    static void validateLowSensitiveText(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : SENSITIVE_TEXT_MARKERS) {
            if (lower.contains(marker)) {
                throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        fieldName + " 只能填写低敏摘要，不能包含 prompt、SQL、URL、凭据、工具参数或样本数据");
            }
        }
    }
}
