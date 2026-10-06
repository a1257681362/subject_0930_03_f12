package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 展览：包含多个展厅，是展览借品清点的组织主体。
 * PLANNED 筹备中 → OPEN 开放中 → CLOSED 已关闭；
 * 清点版本全部关闭前不能关闭展览。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_exhibition")
public class Exhibition extends BaseEntity {

    private String exhibitionNo;
    private String name;
    private String theme;
    private String venue;
    private LocalDate startDate;
    private LocalDate endDate;
    private String curator;
    private String status;
    private String closedBy;
    private LocalDateTime closedAt;
    private String closeRemark;
}
