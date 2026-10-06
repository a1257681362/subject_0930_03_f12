package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 旧数据台账回放：用现有修复/借还/陈展/箱号旧数据回放重建清点版本（REPLAY），
 * requestNo 幂等——重复提交返回同一版本与差异，绝不二次执行。
 * 回放差异自动引用台账解释；无台账依据的差异保留为无解释，阻断复核。
 */
@Data
public class StocktakeReplayRequest {

    @NotBlank(message = "回放请求流水号不能为空")
    private String requestNo;

    @NotBlank(message = "回放操作人不能为空")
    private String replayedBy;

    private String title;
}
