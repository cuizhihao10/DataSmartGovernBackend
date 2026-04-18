package com.czh.datasmart.govern.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.task.entity.Task;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:12
 * @Description DataSmart Govern Backend - TaskMapper.java
 * @Version:1.0.0
 *
 * 任务主表 Mapper。
 * 当前继承 BaseMapper 即可满足模块现阶段的大部分读写需求。
 * 这样做的好处是：
 * 1. 基础增删改查不需要重复写样板 SQL。
 * 2. Service 层仍然可以围绕 Mapper 构建明确业务规则。
 * 3. 后续如果出现复杂检索、统计报表，再在这个接口上扩展自定义方法即可。
 */
@Mapper
public interface TaskMapper extends BaseMapper<Task> {
}
