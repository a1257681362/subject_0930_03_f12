package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 清点版本：展览内版本号递增，只追加。
 * REPLAY 旧数据台账回放版本（差异自动引用台账解释），
 * LIVE 正式现场清点版本（标题固定为"展览借品清点盘点"）。
 * FROZEN 清点中 → CLOSED 已关闭；rebase 时旧版本置 SUPERSEDED，原文保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_stocktake_round")
public class StocktakeRound extends BaseEntity {

    private String roundNo;
    private Long exhibitionId;
    private Integer versionNo;
    private String kind;
    private String title;
    private String status;
    private String baselineFingerprint;
    private LocalDateTime frozenAt;
    private String generatedBy;
    private Long sourceRoundId;
    private String requestNo;
    private String closedBy;
    private LocalDateTime closedAt;
    private String closeRemark;
}
