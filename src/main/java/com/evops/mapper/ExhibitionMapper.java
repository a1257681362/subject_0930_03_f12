package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.Exhibition;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ExhibitionMapper extends BaseMapper<Exhibition> {

    /**
     * 展览行锁：UPDATE 占位使 H2 行级排他锁持有到事务提交，
     * 同一展览的开关幕/建版本等并发操作在此串行。必须在事务内调用。
     */
    @Update("UPDATE t_exhibition SET update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int lockRow(@Param("id") Long id);
}
