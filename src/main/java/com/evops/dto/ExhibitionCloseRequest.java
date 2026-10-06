package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 关闭展览：所有清点版本关闭/取代、全部差异解释并确认后才允许。
 */
@Data
public class ExhibitionCloseRequest {

    @NotBlank(message = "关闭操作人不能为空")
    private String closedBy;

    private String remark;
}
