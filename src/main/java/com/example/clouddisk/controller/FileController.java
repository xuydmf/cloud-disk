package com.example.clouddisk.controller;

import com.example.clouddisk.common.FileType;
import com.example.clouddisk.common.PageResult;
import com.example.clouddisk.common.Result;
import com.example.clouddisk.dto.PrecheckForm;
import com.example.clouddisk.entity.FileInfo;
import com.example.clouddisk.service.FileGroupService;
import com.example.clouddisk.service.FileService;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 文件管理 REST 接口
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
public class FileController {

    @Autowired
    private FileService fileService;

    @Autowired
    private FileGroupService fileGroupService;

    @Autowired
    private com.example.clouddisk.service.AutoGroupService autoGroupService;

    /**
     * 单文件上传（可指定分组）
     *
     * @param capturedTime 拍摄时间（毫秒时间戳），客户端从 File.lastModified 取；
     *                     相册按日期分组要用，取不到就不传
     */
    @PostMapping("/upload")
    public Result<FileInfo> upload(@RequestParam("file") MultipartFile file,
                                   @RequestParam(required = false) Long groupId,
                                   @RequestParam(required = false) Long capturedTime) {
        return Result.success(fileService.upload(file, groupId, capturedTime));
    }

    /** 批量上传（可指定分组） */
    @PostMapping("/batch-upload")
    public Result<java.util.List<FileInfo>> batchUpload(@RequestParam("files") MultipartFile[] files,
                                                        @RequestParam(required = false) Long groupId) {
        return Result.success(fileService.batchUpload(java.util.Arrays.asList(files), groupId));
    }

    /**
     * 秒传预检：上传前先问一句「库里有没有这份内容」，命中就不必再传字节。
     *
     * 判定规则与上传时的查重一致（见 FileService#findExisting），
     * 图片靠客户端本地算的 md5，视频靠「文件名 + 大小」。
     */
    @PostMapping("/precheck")
    public Result<Map<String, Object>> precheck(@RequestBody PrecheckForm form) {
        FileInfo exist = fileService.findExisting(form.getFileName(),
                form.getFileSize() == null ? 0L : form.getFileSize(), form.getMd5());
        Map<String, Object> data = new HashMap<>();
        data.put("exists", exist != null);
        if (exist != null) {
            data.put("id", exist.getId());
            data.put("name", exist.getFileName());
        }
        return Result.success(data);
    }

    /** 文件分页列表（默认每页 20 条，可按分组、拍摄日期过滤） */
    @GetMapping
    public Result<PageResult<FileInfo>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) Long groupId,
                                             @RequestParam(required = false) String date,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        return Result.success(fileService.pageFiles(keyword, type, groupId, date, page, size));
    }

    /**
     * 按日期聚合（相册顶部的日期条）。
     *
     * 返回 [{day:'2026-11-09', count:12, size:3456789}, …]，按日期倒序；
     * 日期口径与列表过滤一致：有拍摄时间用拍摄时间，否则用上传时间。
     */
    @GetMapping("/dates")
    public Result<List<Map<String, Object>>> dates(@RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String type,
                                                   @RequestParam(required = false) Long groupId) {
        return Result.success(fileService.dateStats(keyword, type, groupId));
    }

    /**
     * 只取 id 列表，过滤条件与列表一致 —— 「全选这天」用。
     */
    @GetMapping("/ids")
    public Result<List<Long>> ids(@RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String type,
                                  @RequestParam(required = false) Long groupId,
                                  @RequestParam(required = false) String date,
                                  @RequestParam(defaultValue = "500") int limit) {
        return Result.success(fileService.listIds(keyword, type, groupId, date, limit));
    }

    /** 把文件分配到分组；groupId 为空或 0 表示移出到未分组 */
    @PutMapping("/{id}/group")
    public Result<Void> assignGroup(@PathVariable Long id,
                                    @RequestParam(required = false) Long groupId) {
        fileGroupService.assignGroup(id, groupId);
        return Result.success();
    }

    /** 文件详情 */
    @GetMapping("/{id}")
    public Result<FileInfo> get(@PathVariable Long id) {
        return Result.success(fileService.getById(id));
    }

    /** 删除（逻辑删除） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        fileService.delete(id);
        return Result.success();
    }

    /** 批量移动到分组；groupId 为空或 0 表示移到未分组 */
    @PostMapping("/batch/group")
    public Result<Integer> batchAssignGroup(@RequestParam(required = false) Long groupId,
                                            @RequestBody List<Long> ids) {
        return Result.success(fileGroupService.batchAssignGroup(ids, groupId));
    }

    /** 批量删除（逻辑删除） */
    @PostMapping("/batch/delete")
    public Result<Integer> batchDelete(@RequestBody List<Long> ids) {
        return Result.success(fileService.batchDelete(ids));
    }

    /** 批量下载：打包成 zip 流式输出 */
    @GetMapping("/batch/download")
    public void batchDownload(@RequestParam("ids") String ids, HttpServletResponse response) throws IOException {
        List<Long> idList = parseIds(ids);
        if (idList.isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "请先选择文件");
            return;
        }
        List<FileInfo> files = fileService.listByIds(idList);
        if (files.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "没有可下载的文件");
            return;
        }

        String zipName = "cloud-disk-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip";
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encodeFileName(zipName) + "\"; filename*=UTF-8''" + encodeFileName(zipName));
        response.setHeader("X-File-Count", String.valueOf(files.size()));

        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8)) {
            for (FileInfo info : files) {
                File physical;
                try {
                    physical = fileService.resolvePhysicalFile(info.getId());
                } catch (Exception e) {
                    // 单个文件丢失不影响其余文件打包
                    log.warn("批量下载跳过缺失文件 id={}", info.getId());
                    continue;
                }
                ZipEntry entry = new ZipEntry(uniqueEntryName(info.getFileName(), usedNames));
                entry.setTime(physical.lastModified());
                zos.putNextEntry(entry);
                try (InputStream in = new FileInputStream(physical)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        zos.write(buffer, 0, read);
                    }
                }
                zos.closeEntry();
            }
            zos.finish();
            zos.flush();
        }
    }

    /** 重命名 */
    @PutMapping("/{id}/rename")
    public Result<Void> rename(@PathVariable Long id, @RequestParam String name) {
        fileService.rename(id, name);
        return Result.success();
    }

    /**
     * 对单张图片重新跑一次 AI 识别自动分组。
     * 适合「先上传了图、后来才给分组设参考图」的场景。
     */
    @PostMapping("/{id}/auto-group")
    public Result<String> autoGroup(@PathVariable Long id) {
        if (!autoGroupService.isAvailable()) {
            return Result.error("智能识别未启用：请先在配置中填写 file.auto-group.api-key");
        }
        FileInfo info = fileService.getById(id);
        if (!FileType.IMAGE.name().equals(info.getFileType())) {
            return Result.error("只有图片支持智能识别");
        }
        File physical = fileService.resolvePhysicalFile(id);
        autoGroupService.recognizeAsyncById(info, physical);
        return Result.success("已提交识别，稍后刷新查看结果");
    }

    /** 在线预览原文件（图片直接显示，支持 Range） */
    @GetMapping("/{id}/raw")
    public void raw(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        FileInfo info = fileService.getById(id);
        File file = fileService.resolvePhysicalFile(id);
        response.setHeader("Content-Disposition", "inline; filename=\"" + encodeFileName(info.getFileName()) + "\"");
        streamFile(file, safeContentType(info.getContentType()), request, response);
    }

    /**
     * 相册预览图：长边 ≤1920 的 JPEG（首次访问时生成并缓存）。
     *
     * 之前弹窗直接用 /raw 显示原图，一张 1200 万像素照片解出来约 36MB 内存，
     * 手机上连翻几张，Safari 就会悄悄放弃解码——表现就是"有时候图片打不开"。
     * 这里不设 Range：预览图只有几百 KB，整取更省事。
     */
    @GetMapping("/{id}/preview")
    public void preview(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        File file = fileService.resolvePreviewFile(id);
        if (file == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        // 内容是按 id 固化的，可以放心让浏览器长期缓存；返回原图时沿用原类型
        boolean generated = file.getName().toLowerCase().endsWith(".jpg");
        String contentType = generated ? "image/jpeg" : safeContentType(fileService.getById(id).getContentType());
        response.setHeader("Cache-Control", "private, max-age=604800");
        streamFile(file, contentType, request, response);
    }

    /** 缩略图 / 视频封面（支持 Range） */
    @GetMapping("/{id}/thumb")
    public void thumb(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        File thumb = fileService.resolveThumbFile(id);
        // 有缩略图就一定是本项目生成的 JPEG；回退到原图时必须报原图自己的类型，
        // 否则一张 PNG / HEIC 会被标成 image/jpeg（浏览器只能靠嗅探猜）
        String contentType = "image/jpeg";
        if (thumb == null) {
            // 无缩略图时回退到原图（仅图片有意义）
            FileInfo info = fileService.getById(id);
            if (FileType.IMAGE.name().equals(info.getFileType())) {
                thumb = fileService.resolvePhysicalFile(id);
                contentType = safeContentType(info.getContentType());
            } else {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        }
        streamFile(thumb, contentType, request, response);
    }

    /** 视频播放：自动选择原生流或转码后的 H.264 MP4，支持 Range 分段（可拖拽进度） */
    @GetMapping("/{id}/play")
    public void play(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        FileInfo info = fileService.getById(id);
        File file = fileService.resolvePhysicalFile(id);
        String contentType = safeContentType(info.getContentType());

        // 需要转码的格式（如 Apple MOV/HEVC）优先输出 MP4
        if (fileService.needsTranscode(id)) {
            File mp4 = fileService.transcodeToMp4(id);
            if (mp4 != null && mp4.exists()) {
                file = mp4;
                contentType = "video/mp4";
            }
        }
        response.setHeader("Content-Disposition", "inline; filename=\"" + encodeFileName(info.getFileName()) + "\"");
        streamFile(file, contentType, request, response);
    }

    /** 下载（附件形式） */
    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        FileInfo info = fileService.getById(id);
        File file = fileService.resolvePhysicalFile(id);
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encodeFileName(info.getFileName()) + "\"; filename*=UTF-8''" + encodeFileName(info.getFileName()));
        streamFile(file, safeContentType(info.getContentType()), request, response);
    }

    // ---------------------- 批量操作工具 ----------------------

    /** 解析 "1,2,3" 形式的 id 串（非法片段直接忽略） */
    private List<Long> parseIds(String raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(t));
            } catch (NumberFormatException ignored) {
                // 跳过非法 id
            }
        }
        return ids;
    }

    /** zip 内重名文件追加 (1)(2)... 后缀 */
    private String uniqueEntryName(String originalName, Set<String> used) {
        String name = (originalName == null || originalName.trim().isEmpty()) ? "unnamed" : originalName.trim();
        name = name.replaceAll("[/\\\\]", "_");
        if (used.add(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 1;
        while (true) {
            String candidate = base + "(" + i + ")" + ext;
            if (used.add(candidate)) {
                return candidate;
            }
            i++;
        }
    }

    // ---------------------- 流式输出（支持 HTTP Range） ----------------------

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
            return "download";
        }
    }
}
