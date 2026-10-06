package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 关闭清点版本：全部展厅任务复核通过、全部差异确认后才允许。
 */
@Data
public class RoundCloseRequest {

    @NotBlank(message = "关闭操作人不能为空")
    private String closedBy;

    private String remark;
}
