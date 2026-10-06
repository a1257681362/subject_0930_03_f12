package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 扫描行：一条箱号+展签扫码记录，只追加。
 * 箱号/展签即使在系统中查不到也照样收下，提交时判为错位差异。
 */
@Data
@TableName("t_stocktake_scan_line")
public class StocktakeScanLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long batchId;
    private Long taskId;
    private Integer seqNo;
    private String boxNo;
    private String labelNo;
    private String scanPointCode;
    private Integer conditionAbnormal;
    private String note;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private java.time.LocalDateTime createTime;
}
