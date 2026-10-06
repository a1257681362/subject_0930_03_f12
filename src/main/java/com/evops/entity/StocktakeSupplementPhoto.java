package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 补充记录照片：一行一张，只追加，复用 t_photo_file 内容寻址。
 */
@Data
@TableName("t_stocktake_supplement_photo")
public class StocktakeSupplementPhoto {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long supplementId;
    private Long fileId;
    private String contentHash;
    private String photoName;
    private String uploadedBy;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
