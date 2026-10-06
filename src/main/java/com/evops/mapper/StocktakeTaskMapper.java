package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.StocktakeTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface StocktakeTaskMapper extends BaseMapper<StocktakeTask> {

    /** 任务行锁：扫描/提交/复核等并发操作串行到提交 */
    @Update("UPDATE t_stocktake_task SET update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int lockRow(@Param("id") Long id);

    @Select("SELECT NEXT VALUE FOR seq_stocktake_task_no")
    long nextTaskSeq();
}
