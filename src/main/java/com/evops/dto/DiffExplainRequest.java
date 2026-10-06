package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 差异解释：人工差异复核前必须填写解释。
 * LIVE 差异还须先上传≥1张现场照片并填两名不同见证人；REPLAY 差异不收见证人。
 */
@Data
public class DiffExplainRequest {

    @NotBlank(message = "差异解释不能为空")
    private String explanation;

    private String witnessA;
    private String witnessB;

    @NotBlank(message = "解释人不能为空")
    private String explainedBy;
}
