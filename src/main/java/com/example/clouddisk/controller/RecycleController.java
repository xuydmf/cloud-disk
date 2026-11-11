package com.example.clouddisk.controller;

import com.example.clouddisk.common.FileType;
import com.example.clouddisk.common.PageResult;
import com.example.clouddisk.common.RecycleItemVO;
import com.example.clouddisk.common.Result;
import com.example.clouddisk.entity.FileInfo;
import com.example.clouddisk.service.RecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 回收站接口。
 *
 * <p>文件「删除」后先落到这里，{@code file.recycle.keep-days}（默认 7 天）内可以恢复；
 * 超过保留期由定时任务彻底删除（同时删磁盘文件）。
 */
@Slf4j
@RestController
@RequestMapping("/api/recycle")
public class RecycleController {

    private final RecycleService recycleService;

    public RecycleController(RecycleService recycleService) {
        this.recycleService = recycleService;
    }

    /** 回收站列表（分页 + 文件名搜索） */
    @GetMapping
    public Result<PageResult<RecycleItemVO>> list(@RequestParam(required = false) String keyword,
                                                  @RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long size) {
        return Result.success(recycleService.page(keyword, page, size));
    }

    /** 回收站概况：条数 / 占用空间 / 保留天数 */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(recycleService.stats());
    }

    /** 恢复单个文件 */
    @PostMapping("/{id}/restore")
    public Result<Integer> restoreOne(@PathVariable Long id) {
        return Result.success(recycleService.restore(Collections.singletonList(id)));
    }

    /** 批量恢复（body 为 id 数组） */
    @PostMapping("/restore")
    public Result<Integer> restore(@RequestBody List<Long> ids) {
        return Result.success(recycleService.restore(ids));
    }

    /** 彻底删除单个文件（不可恢复） */
    @DeleteMapping("/{id}")
    public Result<Integer> purgeOne(@PathVariable Long id) {
        return Result.success(recycleService.purge(Collections.singletonList(id)));
    }

    /** 批量彻底删除（body 为 id 数组） */
    @PostMapping("/purge")
    public Result<Integer> purge(@RequestBody List<Long> ids) {
        return Result.success(recycleService.purge(ids));
    }

    /** 清空回收站（彻底删除全部） */
    @DeleteMapping
    public Result<Integer> clear() {
        return Result.success(recycleService.clear());
    }

    // ---------------------- 预览（回收站里的文件也要能看） ----------------------

    /**
     * 缩略图 / 视频封面。
     *
     * <p>回收站里的文件已经被逻辑删除，走 /api/files/{id}/thumb 会被逻辑删除插件过滤掉，
     * 所以这里另开一套只认「已删除记录」的接口。
     */
    @GetMapping("/{id}/thumb")
    public void thumb(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        FileInfo info;
        File thumb;
        try {
            info = recycleService.getRecycled(id);
            thumb = recycleService.resolveRecycledThumb(id);
        } catch (IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
            return;
        }
        if (thumb == null) {
            // 没有缩略图：图片回退到原图，其余类型（视频/文档）没有封面就不给了
            if (FileType.IMAGE.name().equals(info.getFileType())) {
                try {
                    thumb = recycleService.resolveRecycledFile(id);
                } catch (IllegalArgumentException e) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
                    return;
                }
            } else {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "该文件没有缩略图");
                return;
            }
        }
        // 缩略图是本地生成的派生文件，内容变了必然换了文件，长缓存可以放心用
        response.setHeader("Cache-Control", "public, max-age=86400");
        streamFile(thumb, "image/jpeg", request, response);
    }

    /** 预览原文件（图片 / 视频 / PDF 直接在浏览器里看，支持 Range 拖拽进度） */
    @GetMapping("/{id}/preview")
    public void preview(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        FileInfo info;
        File file;
        try {
            info = recycleService.getRecycled(id);
            file = recycleService.resolveRecycledFile(id);
        } catch (IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
            return;
        }
        response.setHeader("Content-Disposition", "inline; filename=\"" + encodeFileName(info.getFileName()) + "\"");
        streamFile(file, safeContentType(info.getContentType()), request, response);
    }

    // ---------------------- 流式输出（支持 HTTP Range） ----------------------

    /** 与 FileController 的实现保持一致：支持 Range，便于视频在回收站里也能拖动进度 */
    private void streamFile(File file, String contentType, HttpServletRequest request, HttpServletResponse response) throws IOException {
        long fileLength = file.length();
        response.setContentType(contentType);
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("ETag", "\"" + file.length() + "-" + file.lastModified() + "\"");
        response.setDateHeader("Last-Modified", file.lastModified());

        long start = 0;
        long end = fileLength - 1;
        boolean isRange = false;

        String rangeHeader = request.getHeader("Range");
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String range = rangeHeader.substring("bytes=".length());
            String[] parts = range.split("-");
            try {
                if (!parts[0].isEmpty()) {
                    start = Long.parseLong(parts[0]);
                }
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    end = Long.parseLong(parts[1]);
                }
            } catch (NumberFormatException e) {
                log.warn("解析 Range 头失败: {}", rangeHeader);
            }
            if (start > end || start >= fileLength) {
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader("Content-Range", "bytes */" + fileLength);
                return;
            }
            end = Math.min(end, fileLength - 1);
            isRange = true;
        }

        long contentLength = end - start + 1;
        if (isRange) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }
        response.setHeader("Content-Length", String.valueOf(contentLength));

        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             OutputStream out = response.getOutputStream()) {
            raf.seek(start);
            byte[] buffer = new byte[8192];
            long remaining = contentLength;
            int read;
            while (remaining > 0 && (read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        }
    }

    private String safeContentType(String contentType) {
        return (contentType == null || contentType.isEmpty()) ? "application/octet-stream" : contentType;
    }

    private String encodeFileName(String name) {
        try {
            return URLEncoder.encode(name, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            return "file";
        }
    }
}
