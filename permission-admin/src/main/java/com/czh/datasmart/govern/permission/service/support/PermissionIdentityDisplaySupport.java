/**
 * @Author : Cui
 * @Date: 2026/07/10
 * @Description DataSmart Govern Backend - PermissionIdentityDisplaySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.permission.entity.PermissionIdentityUser;
import com.czh.datasmart.govern.permission.mapper.PermissionIdentityUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves internal actor IDs to low-sensitive usernames for console views.
 *
 * <p>Actor IDs remain the stable authorization and audit keys, but they are not suitable user-facing labels. This
 * component centralizes the conversion to {@code permission_identity_user.username} and loads a whole page in one
 * query, avoiding both duplicated fallback rules and N+1 identity lookups across project and approval screens.</p>
 */
@Component
@RequiredArgsConstructor
public class PermissionIdentityDisplaySupport {

    private final PermissionIdentityUserMapper identityUserMapper;

    /**
     * Loads usernames for the requested actor IDs.
     *
     * <p>Disabled identities are intentionally retained in the result because historical approvals must continue to
     * show who applied or reviewed them after an account is disabled. Missing shadow identities are simply omitted so
     * callers can render a neutral "user not found" label without exposing a raw Actor ID.</p>
     */
    public Map<Long, String> usernames(Collection<Long> actorIds) {
        Set<Long> normalizedActorIds = actorIds == null
                ? Set.of()
                : actorIds.stream()
                .filter(Objects::nonNull)
                .filter(actorId -> actorId > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalizedActorIds.isEmpty()) {
            return Map.of();
        }

        List<PermissionIdentityUser> identities = identityUserMapper.selectList(
                new LambdaQueryWrapper<PermissionIdentityUser>()
                        .select(PermissionIdentityUser::getActorId, PermissionIdentityUser::getUsername)
                        .in(PermissionIdentityUser::getActorId, normalizedActorIds));
        if (identities == null || identities.isEmpty()) {
            return Map.of();
        }
        return identities.stream()
                .filter(identity -> identity.getActorId() != null)
                .filter(identity -> identity.getUsername() != null && !identity.getUsername().isBlank())
                .collect(Collectors.toMap(
                        PermissionIdentityUser::getActorId,
                        identity -> identity.getUsername().trim(),
                        (left, right) -> left));
    }

    /**
     * Resolves one username for a detail response while preserving the same lookup semantics as page queries.
     */
    public String username(Long actorId) {
        return usernames(List.of(actorId == null ? -1L : actorId)).get(actorId);
    }
}
