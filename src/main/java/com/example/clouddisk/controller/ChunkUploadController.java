package com.example.clouddisk.controller;

import com.example.clouddisk.common.DuplicateFileException;
import com.example.clouddisk.common.Result;
import com.example.clouddisk.dto.ChunkInitForm;
import com.example.clouddisk.entity.FileInfo;
import com.example.clouddisk.service.ChunkUploadService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 大文件分块上传接口（需要登录，由 AuthInterceptor 统一拦截 /api/**）。
 *
 * 前端约定：文件大于阈值时先 init，再并发 POST 各分片，最后 complete。
 */
@RestController
@RequestMapping("/api/files/chunk")
public class ChunkUploadController {

    private final ChunkUploadService chunkUploadService;

    public ChunkUploadController(ChunkUploadService chunkUploadService) {
        this.chunkUploadService = chunkUploadService;
    }

    /** 初始化 / 续传：返回 uploadId 与已收到的分片下标 */
    @PostMapping("/init")
    public Result<Map<String, Object>> init(@RequestBody ChunkInitForm form) {
        try {
            return Result.success(chunkUploadService.init(form));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 上传一个分片（index 从 0 开始，可乱序、可重传） */
    @PostMapping("/{uploadId}")
    public Result<Map<String, Object>> upload(@PathVariable String uploadId,
                                              @RequestParam("index") int index,
                                              @RequestParam("chunk") MultipartFile chunk) {
        try {
            return Result.success(chunkUploadService.saveChunk(uploadId, index, chunk));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 查询已收到的分片（断点续传前对账用） */
    @GetMapping("/{uploadId}")
    public Result<Map<String, Object>> status(@PathVariable String uploadId) {
        try {
            return Result.success(chunkUploadService.status(uploadId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 合并并落库，返回文件记录 */
    @PostMapping("/{uploadId}/complete")
    public Result<FileInfo> complete(@PathVariable String uploadId) {
        try {
            return Result.success(chunkUploadService.complete(uploadId));
        } catch (DuplicateFileException e) {
            // 与普通上传保持一致：重复文件回 code=2，前端按「跳过」处理而不是「失败」
            return Result.error(2, e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 取消上传，清理分片 */
    @DeleteMapping("/{uploadId}")
    public Result<Void> abort(@PathVariable String uploadId) {
        try {
            chunkUploadService.abort(uploadId);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
