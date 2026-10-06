package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 差异确认：复核期逐条确认；版本关闭要求全部差异 CONFIRMED。
 */
@Data
public class DiffConfirmRequest {

    @NotBlank(message = "确认人不能为空")
    private String confirmedBy;
}
