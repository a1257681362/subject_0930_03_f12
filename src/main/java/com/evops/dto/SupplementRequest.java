package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 补充记录：仅已关闭版本可追加，不重写原清点结果。
 * 可挂任务/差异（可空=对整版本补充）；两名见证人若都填不能为同一人。
 */
@Data
public class SupplementRequest {

    private Long taskId;
    private Long diffId;

    @NotBlank(message = "补充内容不能为空")
    private String content;

    @NotBlank(message = "提交人不能为空")
    private String submittedBy;

    private String witnessA;
    private String witnessB;
}
