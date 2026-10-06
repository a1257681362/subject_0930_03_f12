package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 扫描批次头：工作人员一次提交的箱号+展签扫描，request_no 幂等，只追加。
 * rebase 时旧批次搬运到新版本任务，carried_from_batch_id 指向原批次。
 */
@Data
@TableName("t_stocktake_scan_batch")
public class StocktakeScanBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private String requestNo;
    private String scannedBy;
    private LocalDateTime scannedAt;
    private String scanPointCode;
    private String remark;
    private Long carriedFromBatchId;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
