package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 清点任务：版本内按展厅一个，冻结时固化展厅快照与基准指纹。
 * PENDING 待清点 → SUBMITTED 已提交待复核 → REVIEWED 已复核；
 * 版本关闭时 → CLOSED，版本被取代时 → SUPERSEDED。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_stocktake_task")
public class StocktakeTask extends BaseEntity {

    private String taskNo;
    private Long roundId;
    private Long exhibitionId;
    private Long hallId;
    private String hallNo;
    private String hallName;
    private String hallStatus;
    private String status;
    private String baselineFingerprint;
    private LocalDateTime frozenAt;
    private Integer baselineCount;
    private Integer scannedCount;
    private Integer missingCount;
    private Integer misplacedCount;
    private Integer abnormalCount;
    private String submittedBy;
    private LocalDateTime submittedAt;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewRemark;
}
