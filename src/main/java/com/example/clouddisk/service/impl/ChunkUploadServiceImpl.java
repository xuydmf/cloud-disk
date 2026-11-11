package com.example.clouddisk.service.impl;

import com.example.clouddisk.dto.ChunkInitForm;
import com.example.clouddisk.entity.FileInfo;
import com.example.clouddisk.service.ChunkUploadService;
import com.example.clouddisk.service.FileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 分块上传实现。
 *
 * 目录结构：
 *   {storageRoot}/.chunks/{uploadId}/meta.json   本次上传的元数据
 *   {storageRoot}/.chunks/{uploadId}/0.part …  各分片
 *
 * 几个刻意的取舍：
 * 1. 每个分片单独落一个文件，浏览器可以并发上传、失败单独重传，服务端不用加锁。
 * 2. uploadId 由前端 fileKey 派生（MD5），同一文件重复选择就命中同一目录 → 天然断点续传。
 * 3. 合并时边读边写，内存占用与文件大小无关；合并完先校验总字节数再落库。
 */
@Slf4j
@Service
public class ChunkUploadServiceImpl implements ChunkUploadService {

    /** 单文件上限，防止被当成存储炸弹 */
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024 * 1024;

    /** 分片数上限（20GB / 1MB 也才 20480 片） */
    private static final int MAX_CHUNKS = 20000;

    /** 单片上限（与 spring.servlet.multipart.max-file-size 呼应） */
    private static final long MAX_CHUNK_BYTES = 64L * 1024 * 1024;

    /** 只接受这个形状的 uploadId，避免路径穿越 */
    private static final Pattern UPLOAD_ID = Pattern.compile("^[a-f0-9]{32}$");

    private static final String CHUNK_DIR = ".chunks";
    private static final String META_FILE = "meta.json";
    private static final String PART_SUFFIX = ".part";

    private final FileService fileService;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${file.storage.root}")
    private String storageRoot;

    /** 未完成的分片目录保留时长（小时），超时启动时清掉 */
    @Value("${file.chunk.expire-hours:48}")
    private long expireHours;

    public ChunkUploadServiceImpl(FileService fileService) {
        this.fileService = fileService;
    }

    @PostConstruct
    public void init() {
        File root = chunkRoot();
        if (!root.exists() && !root.mkdirs()) {
            log.warn("分块临时目录创建失败：{}", root.getAbsolutePath());
        }
        cleanupExpired();
    }

    // ==================== 初始化 ====================

    @Override
    public Map<String, Object> init(ChunkInitForm form) {
        if (form == null) {
            throw new IllegalArgumentException("参数缺失");
        }
        if (form.getFileName() == null || form.getFileName().trim().isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (form.getFileSize() <= 0) {
            throw new IllegalArgumentException("文件大小不合法");
        }
        if (form.getFileSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("单个文件最大支持 20 GB");
        }
        if (form.getChunkSize() <= 0 || form.getChunkSize() > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("分片大小不合法");
        }
        long expected = (form.getFileSize() + form.getChunkSize() - 1) / form.getChunkSize();
        if (form.getTotalChunks() <= 0 || form.getTotalChunks() > MAX_CHUNKS
                || form.getTotalChunks() != expected) {
            throw new IllegalArgumentException("分片数量与文件大小不匹配");
        }

        String key = form.getFileKey();
        if (key == null || key.trim().isEmpty()) {
            key = form.getFileName() + "-" + form.getFileSize();
        }
        String uploadId = DigestUtils.md5DigestAsHex(key.getBytes(StandardCharsets.UTF_8));

        File dir = chunkDir(uploadId);
        ChunkMeta meta = readMeta(dir);
        // 换了文件（大小/分片数不同）就清掉旧目录重新来过
        if (meta == null || meta.fileSize != form.getFileSize() || meta.totalChunks != form.getTotalChunks()) {
            deleteDir(dir);
            if (!dir.mkdirs()) {
                throw new IllegalStateException("创建分块目录失败");
            }
            meta = new ChunkMeta();
            meta.fileName = form.getFileName();
            meta.fileSize = form.getFileSize();
            meta.chunkSize = form.getChunkSize();
            meta.totalChunks = form.getTotalChunks();
            meta.contentType = form.getContentType();
            meta.groupId = form.getGroupId();
            meta.capturedTime = form.getCapturedTime();
            meta.createdAt = System.currentTimeMillis();
            writeMeta(dir, meta);
            log.info("分块上传初始化 uploadId={} name={} size={} chunks={}",
                    uploadId, meta.fileName, meta.fileSize, meta.totalChunks);
        } else {
            // 分组可能换过，以最新请求为准；拍摄时间同理（有的浏览器刷新后才带上）
            meta.groupId = form.getGroupId();
            if (form.getCapturedTime() != null) {
                meta.capturedTime = form.getCapturedTime();
            }
            writeMeta(dir, meta);
            log.info("分块上传续传 uploadId={} 已收到 {}/{}", uploadId, receivedIndexes(dir, meta).size(), meta.totalChunks);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("uploadId", uploadId);
        result.put("fileName", meta.fileName);
        result.put("fileSize", meta.fileSize);
        result.put("chunkSize", meta.chunkSize);
        result.put("totalChunks", meta.totalChunks);
        result.put("received", receivedIndexes(dir, meta));
        return result;
    }

    // ==================== 分片 ====================

    @Override
    public Map<String, Object> saveChunk(String uploadId, int index, MultipartFile chunk) {
        File dir = requireDir(uploadId);
        ChunkMeta meta = requireMeta(dir);
        if (index < 0 || index >= meta.totalChunks) {
            throw new IllegalArgumentException("分片下标越界：" + index);
        }
        if (chunk == null || chunk.isEmpty()) {
            throw new IllegalArgumentException("分片内容为空");
        }
        if (chunk.getSize() > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("单个分片过大");
        }
        // 除最后一片外，其余分片必须正好等于 chunkSize，避免合并后错位
        boolean last = index == meta.totalChunks - 1;
        long expectedLast = meta.fileSize - (long) meta.chunkSize * (meta.totalChunks - 1);
        if (last ? chunk.getSize() != expectedLast : chunk.getSize() != meta.chunkSize) {
            throw new IllegalArgumentException("分片大小不符：index=" + index + " size=" + chunk.getSize());
        }

        File part = new File(dir, index + PART_SUFFIX);
        File tmp = new File(dir, index + PART_SUFFIX + ".tmp");
        try {
            // 先写 .tmp 再原子改名：并发或中断时不会留下半片被误认为已完成
            chunk.transferTo(tmp);
            Files.move(tmp.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("分片写入失败 uploadId={} index={} {}", uploadId, index, e.getMessage());
            throw new RuntimeException("分片保存失败：" + e.getMessage());
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                log.debug("临时分片清理失败：{}", tmp.getName());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("uploadId", uploadId);
        result.put("index", index);
        result.put("received", receivedIndexes(dir, meta).size());
        result.put("totalChunks", meta.totalChunks);
        return result;
    }

    @Override
    public Map<String, Object> status(String uploadId) {
        File dir = requireDir(uploadId);
        ChunkMeta meta = requireMeta(dir);
        List<Integer> done = receivedIndexes(dir, meta);
        Map<String, Object> result = new HashMap<>();
        result.put("uploadId", uploadId);
        result.put("fileName", meta.fileName);
        result.put("fileSize", meta.fileSize);
        result.put("totalChunks", meta.totalChunks);
        result.put("received", done);
        result.put("completed", done.size() == meta.totalChunks);
        return result;
    }

    // ==================== 合并 ====================

    @Override
    public FileInfo complete(String uploadId) {
        File dir = requireDir(uploadId);
        ChunkMeta meta = requireMeta(dir);
        List<Integer> done = receivedIndexes(dir, meta);
        if (done.size() != meta.totalChunks) {
            throw new IllegalArgumentException("分片未传完：" + done.size() + "/" + meta.totalChunks);
        }

        File merged = new File(dir, "merged" + extensionOf(meta.fileName));
        long total = 0;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(merged), 1 << 20)) {
            byte[] buf = new byte[1 << 20];
            for (int i = 0; i < meta.totalChunks; i++) {
                File part = new File(dir, i + PART_SUFFIX);
                if (!part.isFile()) {
                    throw new IllegalArgumentException("缺少分片：" + i);
                }
                try (InputStream in = new BufferedInputStream(new FileInputStream(part), 1 << 20)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                }
            }
        } catch (IOException e) {
            log.error("分片合并失败 uploadId={}", uploadId, e);
            throw new RuntimeException("合并分片失败：" + e.getMessage());
        }

        if (total != meta.fileSize) {
            deleteQuietly(merged);
            throw new IllegalArgumentException("合并后大小不一致（" + total + " ≠ " + meta.fileSize + "），请重新上传");
        }

        // 合并出来的临时文件已被 adoptFile 移走；无论登记成功（含「内容重复」被拒）
        // 还是失败，分片目录都没有留存价值，立刻清掉，不必等 48 小时过期
        FileInfo info;
        try {
            info = fileService.adoptFile(merged, meta.fileName, meta.contentType, meta.groupId, meta.capturedTime);
        } finally {
            deleteDir(dir);
        }
        log.info("分块上传完成 uploadId={} -> fileId={} bytes={}", uploadId, info.getId(), total);
        return info;
    }

    @Override
    public void abort(String uploadId) {
        File dir = chunkDir(requireValidId(uploadId));
        deleteDir(dir);
        log.info("分块上传已取消 uploadId={}", uploadId);
    }

    // ==================== 内部工具 ====================

    private File chunkRoot() {
        return new File(storageRoot, CHUNK_DIR);
    }

    private File chunkDir(String uploadId) {
        return new File(chunkRoot(), requireValidId(uploadId));
    }

    private String requireValidId(String uploadId) {
        String id = uploadId == null ? "" : uploadId.trim().toLowerCase();
        if (!UPLOAD_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("非法的上传标识");
        }
        return id;
    }

    private File requireDir(String uploadId) {
        File dir = chunkDir(uploadId);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("分块上传不存在或已过期，请重新上传");
        }
        return dir;
    }

    private ChunkMeta requireMeta(File dir) {
        ChunkMeta meta = readMeta(dir);
        if (meta == null) {
            throw new IllegalArgumentException("分块上传不存在或已过期，请重新上传");
        }
        return meta;
    }

    private List<Integer> receivedIndexes(File dir, ChunkMeta meta) {
        List<Integer> list = new ArrayList<>();
        File[] parts = dir.listFiles((d, name) -> name.endsWith(PART_SUFFIX));
        if (parts == null) {
            return list;
        }
        Map<Integer, File> byIndex = new HashMap<>();
        for (File p : parts) {
            String n = p.getName();
            try {
                int idx = Integer.parseInt(n.substring(0, n.length() - PART_SUFFIX.length()));
                if (idx >= 0 && idx < meta.totalChunks && p.isFile() && p.length() > 0) {
                    byIndex.put(idx, p);
                }
            } catch (NumberFormatException ignored) {
                // 非标准命名的文件忽略
            }
        }
        list.addAll(byIndex.keySet());
        list.sort(Comparator.naturalOrder());
        return list;
    }

    private ChunkMeta readMeta(File dir) {
        File f = new File(dir, META_FILE);
        if (!f.isFile()) {
            return null;
        }
        try {
            return json.readValue(f, ChunkMeta.class);
        } catch (Exception e) {
            log.warn("分块元数据读取失败：{}", f.getAbsolutePath());
            return null;
        }
    }

    private void writeMeta(File dir, ChunkMeta meta) {
        File f = new File(dir, META_FILE);
        File tmp = new File(dir, META_FILE + ".tmp");
        try {
            json.writeValue(tmp, meta);
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("写入分块元数据失败：" + e.getMessage());
        }
    }

    /** 启动时清掉长期未完成的分块目录 */
    private void cleanupExpired() {
        File root = chunkRoot();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) {
            return;
        }
        long deadline = System.currentTimeMillis() - expireHours * 3600_000L;
        for (File d : dirs) {
            if (d.lastModified() < deadline) {
                deleteDir(d);
                log.info("清理过期分块目录：{}", d.getName());
            }
        }
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int i = fileName.lastIndexOf('.');
        return i < 0 || i == fileName.length() - 1 ? "" : fileName.substring(i).toLowerCase();
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.stream(files).forEach(this::deleteQuietly);
        }
        if (!dir.delete()) {
            log.warn("分块目录删除失败：{}", dir.getAbsolutePath());
        }
    }

    private void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) {
            log.warn("文件删除失败：{}", f.getAbsolutePath());
        }
    }

    /** 分块元数据（落 meta.json） */
    @Data
    public static class ChunkMeta {
        private String fileName;
        private long fileSize;
        private int chunkSize;
        private int totalChunks;
        private String contentType;
        private Long groupId;
        /** 拍摄时间（毫秒），合并后写进 file_info.captured_time */
        private Long capturedTime;
        private long createdAt;
    }
}
