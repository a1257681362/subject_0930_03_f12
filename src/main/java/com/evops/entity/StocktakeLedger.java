package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 清点台账：版本生成/回放/扫描/版本冲突/提交/复核/解释/确认/关闭/补充等全部
 * 动作只追加留痕，关联展览/版本/任务/展厅，清点全过程可追溯。
 */
@Data
@TableName("t_stocktake_ledger")
public class StocktakeLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long exhibitionId;
    private Long roundId;
    private Long taskId;
    private Long hallId;
    private String action;
    private String bizNo;
    private String summary;
    private LocalDateTime occurredAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
