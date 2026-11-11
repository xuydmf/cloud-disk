package com.example.clouddisk.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件分组实体，对应表 file_group
 */
@Data
@TableName("file_group")
public class FileGroup implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分组名称 */
    private String name;

    /** 排序值，越小越靠前 */
    private Integer sort;

    /** 逻辑删除：0-正常 1-已删 */
    private Integer deleted;

    /**
     * 样貌参考图相对路径（相对 file.storage.root）。
     * AI 自动分组靠它判断待识别图片里的人物是否属于本组；NULL 表示未设置。
     */
    private String refImagePath;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
