package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 扫描批次提交：requestNo 幂等。提交前重算基准指纹，
 * 清点期间借还/陈展/箱位发生变化时报版本冲突且不写入任何扫描数据。
 */
@Data
public class ScanSubmitRequest {

    @NotBlank(message = "扫描请求流水号不能为空")
    private String requestNo;

    @NotBlank(message = "扫描人不能为空")
    private String scannedBy;

    private LocalDateTime scannedAt;

    /** 批次默认点位（扫描行可覆盖） */
    private String scanPointCode;

    private String remark;

    @NotEmpty(message = "扫描记录不能为空")
    @Valid
    private List<ScanItemDto> scans;
}
