package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * 展览建单：编号/名称必填，初始为 PLANNED 筹备中，开放后才能生成清点版本。
 */
@Data
public class ExhibitionCreateRequest {

    @NotBlank(message = "展览编号不能为空")
    private String exhibitionNo;

    @NotBlank(message = "展览名称不能为空")
    private String name;

    private String theme;
    private String venue;
    private LocalDate startDate;
    private LocalDate endDate;
    private String curator;
}
