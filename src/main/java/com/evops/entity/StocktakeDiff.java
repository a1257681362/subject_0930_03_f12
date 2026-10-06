package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 清点差异行：缺失 MISSING / 错位 MISPLACED / 状态异常 STATUS_ABNORMAL。
 * 结果字段（类型/原因/期望与发现值）提交后不可变；
 * 解释/见证人/确认字段仅任务 SUBMITTED 复核期可写，REVIEWED 后不可变。
 * LIVE 差异 photo_required=1（须≥1现场照片+两名不同见证人）；
 * REPLAY 自动解释差异 photo_required=0、auto_explained=1。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_stocktake_diff")
public class StocktakeDiff extends BaseEntity {

    private Long taskId;
    private Long roundId;
    private Integer diffNo;
    private String diffType;
    private Long relicId;
    private String relicNo;
    private String relicName;
    private Long baselineLineId;
    private String expectedBoxNo;
    private String expectedLabelNo;
    private String expectedPointCode;
    private String foundBoxNo;
    private String foundLabelNo;
    private String foundPointCode;
    private Long foundHallId;
    private String foundHallNo;
    private String reasonCode;
    private String reasonDetail;
    private Integer photoRequired;
    private String witnessA;
    private String witnessB;
    private Integer autoExplained;
    private String explanation;
    private String explanationSource;
    private String sourceRef;
    private String explainedBy;
    private LocalDateTime explainedAt;
    private String confirmStatus;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
}
