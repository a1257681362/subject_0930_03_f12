package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.StocktakeRound;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface StocktakeRoundMapper extends BaseMapper<StocktakeRound> {

    /** 版本行锁，保证同展览版本号递增与 rebase 串行 */
    @Update("UPDATE t_stocktake_round SET update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int lockRow(@Param("id") Long id);

    @Select("SELECT NEXT VALUE FOR seq_stocktake_round_no")
    long nextRoundSeq();
}
