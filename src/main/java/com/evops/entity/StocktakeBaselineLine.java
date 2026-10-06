package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 清点冻结基准行：固化陈展记录、文物状态、未还借出、未结修复、在库箱号快照，
 * 只追加。ON_DISPLAY expected=1（应在陈），WITHDRAWN expected=0（不要求在场）。
 */
@Data
@TableName("t_stocktake_baseline_line")
public class StocktakeBaselineLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private Integer lineNo;
    private Long relicId;
    private String relicNo;
    private String relicName;
    private String material;
    private Long hallRelicId;
    private String pointCode;
    private String displayNo;
    private String hallRelicStatus;
    private String hallStatus;
    private Integer expected;
    private String boxNo;
    private String relicStatus;
    private String loanNo;
    private String loanStatus;
    private String openRepairNos;
    private Integer openRepairFlag;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private java.time.LocalDateTime createTime;
}
