package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 任务复核：所有差异必须有解释；LIVE 差异还须≥1张现场照片与两名不同见证人。
 * 闸门不过则复核被拒（任务停留待复核），不产生状态推进。
 */
@Data
public class StocktakeReviewRequest {

    @NotBlank(message = "复核人不能为空")
    private String reviewedBy;

    private String remark;
}
