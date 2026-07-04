/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - PermissionIdentityUserMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.permission.entity.PermissionIdentityUser;
import org.apache.ibatis.annotations.Select;

/**
 * 身份影子记录 Mapper。
 *
 * <p>大部分查询使用 MyBatis-Plus 的 LambdaQueryWrapper 即可；单独暴露 nextActorId() 是因为 actorId
 * 不使用表主键生成，而是作为平台级主体编号进入 gateway、审计、Agent 记忆和业务授权链路，需要一条独立序列。
 */
public interface PermissionIdentityUserMapper extends BaseMapper<PermissionIdentityUser> {

    /**
     * 获取下一个 DataSmart actorId。
     *
     * <p>使用数据库序列而不是 JVM 内存自增，是为了保证多实例部署时不会生成重复 actorId。
     */
    @Select("SELECT nextval('permission_identity_actor_id_seq')")
    Long nextActorId();
}
