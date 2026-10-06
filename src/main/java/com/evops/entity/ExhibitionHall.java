package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 展览-展厅关联：一个展览含多个展厅。每个清点版本冻结各自的展厅任务，
 * 后续挂载的展厅不影响已冻结版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_exhibition_hall")
public class ExhibitionHall extends BaseEntity {

    private Long exhibitionId;
    private Long hallId;
    private String hallNo;
}
