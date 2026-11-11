package com.example.clouddisk.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 分块上传初始化参数。
 *
 * fileKey 由前端拼成「文件名-大小-最后修改时间」，服务端用它派生 uploadId，
 * 同一个文件重复选择时会命中同一个 uploadId，从而复用已经上传的分片（断点续传）。
 */
@Data
public class ChunkInitForm implements Serializable {

    /** 原始文件名（含扩展名） */
    private String fileName;

    /** 文件总字节数 */
    private long fileSize;

    /** 每片字节数 */
    private int chunkSize;

    /** 分片总数 */
    private int totalChunks;

    /** 前端生成的稳定标识，用于断点续传 */
    private String fileKey;

    /** 浏览器给的类型，可为空 */
    private String contentType;

    /** 目标分组，可为空 */
    private Long groupId;

    /** 拍摄时间（毫秒时间戳），可为空；相册按日期分组要用 */
    private Long capturedTime;
}
