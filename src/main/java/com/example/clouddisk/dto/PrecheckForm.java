package com.example.clouddisk.dto;

import lombok.Data;

/**
 * 秒传预检表单。
 *
 * 客户端在上传前问一句「库里有没有这份内容」，命中就直接跳过上传。
 * 图片带 md5（客户端压缩后就在本地算，不耗流量）；视频只带文件名 + 大小。
 */
@Data
public class PrecheckForm {

    /** 即将上传的原始文件名 */
    private String fileName;

    /** 即将上传的字节数（压缩后的） */
    private Long fileSize;

    /** 内容 md5（16 进制小写）。视频可以不传——iOS 每次导出的字节都不一样，算 md5 没意义 */
    private String md5;
}
