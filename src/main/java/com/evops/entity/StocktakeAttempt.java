package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 清点操作尝试审计：回放/建版本/扫描/提交/复核/解释/确认/关版本/补充等写操作，
 * 每次尝试（含版本冲突、状态不允许的拒绝）都落一行。
 * EFFECTIVE 为最终生效的一次；REJECTED 记录拒绝原因。
 * request_no 稀疏唯一：重试/补传复用流水号时命中同一次尝试，绝不重复执行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_stocktake_attempt")
public class StocktakeAttempt extends BaseEntity {

    private Long roundId;
    private Long taskId;
    private String operation;
    private String requestNo;
    private String operatorName;
    private String decision;
    private String rejectReason;
    private Long effectiveAttemptId;
    private Long bizId;
    private LocalDateTime occurredAt;
}
