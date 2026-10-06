package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 清点任务最终提交：对全部已接受扫描去重后逐行比对冻结基准，
 * 生成缺失/错位/状态异常差异，任务进入待复核。requestNo 幂等。
 */
@Data
public class StocktakeSubmitRequest {

    @NotBlank(message = "提交请求流水号不能为空")
    private String requestNo;

    @NotBlank(message = "提交人不能为空")
    private String submittedBy;

    private LocalDateTime submittedAt;
}
