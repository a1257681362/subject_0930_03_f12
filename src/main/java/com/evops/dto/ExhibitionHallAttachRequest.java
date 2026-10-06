package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 展览挂载展厅：展厅须存在且 ACTIVE；已挂载的展厅重复挂载被拒绝。
 * 每个清点版本各自冻结展厅任务，后续挂载不影响已冻结版本。
 */
@Data
public class ExhibitionHallAttachRequest {

    @NotEmpty(message = "展厅 id 列表不能为空")
    private List<Long> hallIds;
}
