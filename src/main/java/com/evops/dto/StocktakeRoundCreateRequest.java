package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 生成正式现场清点版本（LIVE，标题"展览借品清点盘点"）：
 * 按展厅冻结基准生成任务。fromRoundId 非空时为重建版本（rebase）：
 * 旧版本置 SUPERSEDED 且原文保留，已接受扫描批次搬运到新版本任务。
 */
@Data
public class StocktakeRoundCreateRequest {

    @NotBlank(message = "生成人不能为空")
    private String generatedBy;

    private String title;

    /** rebase 来源版本 id；可空 */
    private Long fromRoundId;

    /** 请求流水号（rebase/建版本幂等，可空） */
    private String requestNo;
}
