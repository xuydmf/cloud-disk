package com.example.clouddisk.service;

import com.example.clouddisk.dto.ChunkInitForm;
import com.example.clouddisk.entity.FileInfo;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 大文件分块上传。
 *
 * 流程：init（拿 uploadId + 已收到的分片） → 多次 saveChunk → complete（合并 + 落库）。
 * 分片以独立文件存放在 storageRoot/.chunks/{uploadId}/ 下，可并发上传，也可断点续传。
 */
public interface ChunkUploadService {

    /** 初始化（幂等）：返回 uploadId、分片总数、已收到的分片下标 */
    Map<String, Object> init(ChunkInitForm form);

    /** 上传单个分片（index 从 0 开始，可乱序、可重传） */
    Map<String, Object> saveChunk(String uploadId, int index, MultipartFile chunk);

    /** 查询已收到的分片 */
    Map<String, Object> status(String uploadId);

    /** 合并分片并登记为文件记录 */
    FileInfo complete(String uploadId);

    /** 放弃这次上传，清理已落盘的分片 */
    void abort(String uploadId);
}
