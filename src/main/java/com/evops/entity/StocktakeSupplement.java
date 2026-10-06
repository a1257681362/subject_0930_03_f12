package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 清点补充记录：任务/版本/展览关闭后唯一允许的新增，只追加。
 * 可针对某任务/某条差异，也可只挂版本（对整版本补充）；
 * 两名见证人若都填写则不能为同一人。原清点结果永不被补充记录改写。
 */
@Data
@TableName("t_stocktake_supplement")
public class StocktakeSupplement {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roundId;
    private Long taskId;
    private Long diffId;
    private String content;
    private String submittedBy;
    private LocalDateTime submittedAt;
    private String witnessA;
    private String witnessB;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
