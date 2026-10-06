package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 一条扫描记录：箱号+展签号（未知也照样收下，提交时判错位）；
 * conditionAbnormal=1 时必须填现场异常说明。
 */
@Data
public class ScanItemDto {

    @NotBlank(message = "箱号不能为空")
    private String boxNo;

    @NotBlank(message = "展签号不能为空")
    private String labelNo;

    private String scanPointCode;

    private Boolean conditionAbnormal;

    private String note;
}
