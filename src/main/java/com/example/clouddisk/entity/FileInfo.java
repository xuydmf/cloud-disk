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
 * 文件信息实体，对应表 file_info
 */
@Data
@TableName("file_info")
public class FileInfo implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 原始文件名 */
    private String fileName;

    /** 存储文件名（uuid + 扩展名） */
    private String storedName;

    /** 相对存储根目录的路径，如 2026/10/27/xxxx.jpg */
    private String filePath;

    /** 缩略图 / 视频封面相对路径 */
    private String thumbPath;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MIME 类型 */
    private String contentType;

    /** 文件大类：IMAGE / VIDEO / OTHER */
    private String fileType;

    /** 所属分组 id，NULL 表示未分组 */
    private Long groupId;

    /** 文件指纹（MD5），可用于秒传 */
    private String md5;

    /** 逻辑删除：0-正常 1-已删（已删的进回收站） */
    private Integer deleted;

    /** AI 识别是否已完成：0-否 1-是（非图片、功能关闭时保持 0） */
    private Integer aiChecked;

    /** AI 识别命中的分组 id；NULL 表示没认出任何已有分组 */
    private Long aiGroupId;

    /** AI 识别结论的可读描述，用于前端提示与排查 */
    private String aiNote;

    /**
     * 删除时间（进入回收站的时间）。
     * 正常文件为 NULL；回收站里的文件用来算「还剩几天被彻底清理」。
     */
    private LocalDateTime deletedTime;

    /**
     * 拍摄时间。
     *
     * <p>相册按「拍摄日期」分组才有意义——用户是拿它和手机相册对着看的。
     * 值来自客户端上传时带的 File.lastModified（iOS 相册给网页的正是这张照片的时间），
     * 老数据、或取不到时保持 NULL，展示与分组一律回退到 {@link #createTime}。
     */
    private LocalDateTime capturedTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
