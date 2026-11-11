package com.example.clouddisk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.clouddisk.common.DuplicateFileException;
import com.example.clouddisk.common.FileType;
import com.example.clouddisk.common.PageResult;
import com.example.clouddisk.entity.FileInfo;
import com.example.clouddisk.mapper.FileInfoMapper;
import com.example.clouddisk.service.AutoGroupService;
import com.example.clouddisk.service.FileService;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 文件业务实现
 */
@Slf4j
@Service
public class FileServiceImpl implements FileService {

    /** 缩略图长边 */
    private static final int THUMB_MAX_EDGE = 320;
    /**
     * 原图长边不超过这个值就不再另存缩略图，列表直接展示原图。
     * 手机截图、聊天转发图普遍在这个范围内，省掉一次大图解码 + 重编码。
     */
    private static final int THUMB_SKIP_EDGE = 640;

    /**
     * 预览图长边。1920 足够铺满手机和绝大多数笔记本屏幕，
     * 而解码内存只有 1200 万像素原图的约 1/9。
     */
    private static final int PREVIEW_MAX_EDGE = 1920;

    /** 预览图按需生成时的并发保护：同一张图被两个请求同时点开，不能写同一个文件 */
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> previewLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${file.storage.root}")
    private String storageRoot;

    @Value("${file.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${file.ffmpeg.force-transcode:false}")
    private boolean forceTranscode;

    private final FileInfoMapper fileInfoMapper;
    private final AutoGroupService autoGroupService;
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool();

    /**
     * 批量落库的并行线程池。
     * 20 张图串行做「全文 MD5 + 大图解码出缩略图」会排队到 10 秒以上，
     * 并行后整体耗时接近单张大图的处理时间。
     */
    private final ExecutorService batchExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            r -> {
                Thread t = new Thread(r, "batch-upload");
                t.setDaemon(true);
                return t;
            });

    /**
     * 预览图预热队列：**单线程 + LIFO（后进先出）**。
     *
     * 为什么单线程：一张 12MP 照片解码要 40MB 左右内存，20 张并发解码能直接把堆顶爆；
     * 而这张图不挡任何请求，慢一点没关系，所以宁可排队。
     *
     * 为什么 LIFO：相册列表是「最新在前」，用户传完 20 张马上点开看的是**最后传的那张**。
     * 用 FIFO 的话它排在队尾，正好是最后才预热到的那张 —— 等于白忙。
     */
    private final java.util.concurrent.LinkedBlockingDeque<Long> previewWarmQueue =
            new java.util.concurrent.LinkedBlockingDeque<>();
    private boolean previewWarmStarted = false;

    public FileServiceImpl(FileInfoMapper fileInfoMapper, AutoGroupService autoGroupService) {
        this.fileInfoMapper = fileInfoMapper;
        this.autoGroupService = autoGroupService;
    }

    @PostConstruct
    public void init() {
        new File(storageRoot).mkdirs();
        new File(storageRoot, ".transcode").mkdirs();
        // ImageIO 默认会把解码出来的原始像素缓存到临时文件里：一张 4000×3000 的照片
        // 就是 36MB 的磁盘写入，50 张并发上传时全耗在这上面。关掉它，解码直接在内存里做。
        javax.imageio.ImageIO.setUseCache(false);
    }

    // ---------------------- 上传 ----------------------

    @Override
    public FileInfo upload(MultipartFile file, Long groupId, Long capturedTimeMillis) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        long t0 = System.currentTimeMillis();
        try {
            String originalName = sanitizeFileName(file.getOriginalFilename());
            String ext = getExtension(originalName);
            String storedName = UUID.randomUUID().toString().replace("-", "") + ext;
            String relativeDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            File dir = new File(storageRoot, relativeDir);
            dir.mkdirs();
            File target = new File(dir, storedName);
            file.transferTo(target);
            long t1 = System.currentTimeMillis();

            FileInfo info = register(target, originalName, file.getContentType(),
                    relativeDir + "/" + storedName, file.getSize(), groupId, capturedTimeMillis);
            if (log.isDebugEnabled()) {
                log.debug("[upload] total={}ms write={}ms file={} size={}",
                        System.currentTimeMillis() - t0, t1 - t0, originalName, file.getSize());
            }
            return info;
        } catch (IOException e) {
            log.error("文件上传失败", e);
            throw new RuntimeException("文件上传失败：" + e.getMessage());
        }
    }

    /**
     * 分块上传合并后的登记：把临时文件搬进正式目录，再复用同一套登记逻辑
     * （md5、类型识别、缩略图、视频封面）。
     */
    @Override
    public FileInfo adoptFile(File source, String originalName, String contentType, Long groupId,
                              Long capturedTimeMillis) {
        if (source == null || !source.isFile()) {
            throw new IllegalArgumentException("合并后的文件不存在");
        }
        try {
            String name = sanitizeFileName(originalName);
            String ext = getExtension(name);
            String storedName = UUID.randomUUID().toString().replace("-", "") + ext;
            String relativeDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            File dir = new File(storageRoot, relativeDir);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("创建存储目录失败：" + dir.getAbsolutePath());
            }
            File target = new File(dir, storedName);
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFail) {
                // 临时目录与存储目录不在同一个卷时 move 会失败，退化为流拷贝
                log.warn("临时文件移动失败，改为拷贝：{}", moveFail.getMessage());
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(source.toPath());
            }
            log.info("分块上传合并完成 {} -> {} ({} 字节)", name, relativeDir + "/" + storedName, target.length());
            return register(target, name, contentType, relativeDir + "/" + storedName, target.length(),
                    groupId, capturedTimeMillis);
        } catch (IOException e) {
            log.error("保存合并后的文件失败", e);
            throw new RuntimeException("保存文件失败：" + e.getMessage());
        }
    }

    /** 统一的落库：md5 查重、类型识别、缩略图 / 视频封面 */
    private FileInfo register(File target, String originalName, String contentType,
                              String filePath, long size, Long groupId, Long capturedTimeMillis) {
        long t0 = System.currentTimeMillis();

        FileInfo info = new FileInfo();
        info.setFileName(originalName);
        info.setStoredName(target.getName());
        info.setFilePath(filePath);
        info.setFileSize(size);
        info.setContentType(contentType);
        info.setFileType(detectType(contentType, getExtension(originalName)).name());
        info.setMd5(computeMd5(target));
        info.setDeleted(0);
        info.setGroupId(groupId != null && groupId > 0 ? groupId : null);
        info.setCapturedTime(saneCapturedTime(capturedTimeMillis));

        String relativeDir = filePath.contains("/")
                ? filePath.substring(0, filePath.lastIndexOf('/'))
                : "";
        boolean isImage = FileType.IMAGE.name().equals(info.getFileType());
        if (isImage) {
            info.setThumbPath(generateImageThumb(target, relativeDir, target.getName()));
        }
        long t1 = System.currentTimeMillis();

        // 查重与落库必须在同一把锁里：同一张图并发上传（手机连点、多选里混了张重复的）
        // 两个请求会同时通过「库里没有」的判断，最后落两条一模一样的记录。
        synchronized (lockFor(info.getMd5(), size)) {
            if (info.getMd5() != null) {
                FileInfo exist = fileInfoMapper.selectLiveByMd5(info.getMd5(), size);
                if (exist != null) {
                    // 已经有一份了：把刚写进来的这份连同缩略图一起删掉，不留垃圾
                    discardQuietly(target, info.getThumbPath());
                    throw new DuplicateFileException(exist.getId(), exist.getFileName(), originalName);
                }
            }
            // 视频兜底：iOS 每次导出都会重写容器元数据，同一段视频的 md5 每次都不同，
            // 只按 md5 判重等于没判重（实测同一段 62MB 视频在库里躺了 6 份）。
            // 同名同大小几乎只可能是同一份 —— 相机文件名 IMG_XXXX.mov 天然不重复。
            if (FileType.VIDEO.name().equals(info.getFileType())) {
                synchronized (lockFor("name:" + originalName, size)) {
                    FileInfo same = fileInfoMapper.selectLiveByNameAndSize(originalName, size);
                    if (same != null) {
                        discardQuietly(target, info.getThumbPath());
                        throw new DuplicateFileException(same.getId(), same.getFileName(), originalName);
                    }
                }
            }
            fileInfoMapper.insert(info);
        }
        long t2 = System.currentTimeMillis();

        // 图片：交给 AI 判断是否该归入某个已有分组。
        // 异步执行，识别失败/超时都不会影响上传结果。
        if (isImage) {
            autoGroupService.submitAsync(info, target, groupId);
            // 顺手把浏览用的 1920 预览图生成好，用户点开相册时就不用现场等 1 秒
            warmPreviewAsync(info, target);
        }

        // 视频：异步生成封面（ffmpeg 可用时）
        if (FileType.VIDEO.name().equals(info.getFileType())) {
            generateVideoPosterAsync(info, target, relativeDir, target.getName());
        }

        if (log.isDebugEnabled()) {
            log.debug("[register] total={}ms md5+thumb={}ms check+insert={}ms file={}",
                    t2 - t0, t1 - t0, t2 - t1, originalName);
        }
        return info;
    }

    /**
     * 秒传预检。
     *
     * 这一层只是「省流量」：命中就告诉客户端别传了。**真正拦重复的还是 register()**，
     * 因为预检和上传之间总有一个时间窗（别的设备可能刚好传了同一份），
     * 所以两处的判定规则必须一致，否则会出现「预检说没有、传上来被判重」的错位。
     */
    @Override
    public FileInfo findExisting(String fileName, long fileSize, String md5) {
        if (fileName == null || fileName.trim().isEmpty() || fileSize <= 0) {
            return null;
        }
        String name = sanitizeFileName(fileName);
        if (md5 != null && !md5.isEmpty()) {
            FileInfo byMd5 = fileInfoMapper.selectLiveByMd5(md5, fileSize);
            if (byMd5 != null) {
                return byMd5;
            }
        }
        // 视频：客户端不算 md5（算了也对不上，iOS 每次导出字节都不同），按「文件名 + 大小」兜底
        if (isVideoExt(getExtension(name))) {
            return fileInfoMapper.selectLiveByNameAndSize(name, fileSize);
        }
        return null;
    }

    @Override
    public List<FileInfo> batchUpload(List<MultipartFile> files, Long groupId) {
        List<FileInfo> result = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return result;
        }
        // 前端已改成逐张并发上传，这个接口主要留给脚本 / 旧调用方。
        // 仍然并行处理：串行时每个文件都要全文读一遍算 MD5、再把大图解码成缩略图，
        // 20 张就能排到 10 秒以上，用户看到的是「进度条满了但一直不出来」。
        List<Future<FileInfo>> futures = new ArrayList<>(files.size());
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) {
                futures.add(null);
                continue;
            }
            futures.add(batchExecutor.submit(() -> upload(f, groupId)));
        }
        for (int i = 0; i < futures.size(); i++) {
            Future<FileInfo> fu = futures.get(i);
            if (fu == null) {
                continue;
            }
            try {
                result.add(fu.get());
            } catch (Exception e) {
                log.warn("批量上传中单个文件失败: {}", files.get(i).getOriginalFilename(), e);
            }
        }
        return result;
    }

    // ---------------------- 查询 / 管理 ----------------------

    @Override
    public PageResult<FileInfo> pageFiles(String keyword, String type, Long groupId, String date, long page, long size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20;
        }
        if (size > 200) {
            size = 200;
        }
        Page<FileInfo> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<FileInfo> qw = filterWrapper(keyword, type, groupId, date);
        qw.orderByDesc(FileInfo::getCreateTime);
        Page<FileInfo> result = fileInfoMapper.selectPage(pageParam, qw);
        return PageResult.of(result.getRecords(), result.getTotal(), page, size);
    }

    @Override
    public List<Long> listIds(String keyword, String type, Long groupId, String date, int limit) {
        int cap = limit <= 0 ? 500 : Math.min(limit, 2000);
        LambdaQueryWrapper<FileInfo> qw = filterWrapper(keyword, type, groupId, date);
        qw.select(FileInfo::getId);
        qw.orderByDesc(FileInfo::getCreateTime);
        qw.last("LIMIT " + cap);
        List<FileInfo> rows = fileInfoMapper.selectList(qw);
        List<Long> ids = new ArrayList<>(rows.size());
        for (FileInfo r : rows) {
            if (r.getId() != null) {
                ids.add(r.getId());
            }
        }
        return ids;
    }

    /**
     * 列表 / 日期聚合 / 取 id 三处共用的过滤条件。
     * 必须共用，否则「日期条上显示 N 项、点进去却不是 N 项」这种错位迟早出现。
     */
    private LambdaQueryWrapper<FileInfo> filterWrapper(String keyword, String type, Long groupId, String date) {
        LambdaQueryWrapper<FileInfo> qw = new LambdaQueryWrapper<>();
        qw.eq(FileInfo::getDeleted, 0);
        if (keyword != null && !keyword.trim().isEmpty()) {
            qw.like(FileInfo::getFileName, keyword.trim());
        }
        if (type != null && !type.trim().isEmpty()) {
            qw.eq(FileInfo::getFileType, type.trim().toUpperCase());
        }
        if (groupId != null) {
            if (groupId == 0L) {
                qw.isNull(FileInfo::getGroupId);
            } else {
                qw.eq(FileInfo::getGroupId, groupId);
            }
        }
        String day = normalizeDate(date);
        if (day != null) {
            // 与日期条共用同一套「有拍摄时间用拍摄时间，没有就用上传时间」的口径，
            // 否则点某一天看到的内容会和那天的计数对不上
            qw.apply("DATE_FORMAT(IFNULL(captured_time, create_time), '%Y-%m-%d') = {0}", day);
        }
        return qw;
    }

    @Override
    public List<java.util.Map<String, Object>> dateStats(String keyword, String type, Long groupId) {
        boolean unGrouped = groupId != null && groupId == 0L;
        // 注意别写成 `a && b ? null : type.trim()`：条件为假时仍然会走到 type.trim()，
        // type 为 null 就直接 NPE。
        String kw = (keyword == null || keyword.trim().isEmpty()) ? null : keyword.trim();
        String tp = (type == null || type.trim().isEmpty()) ? null : type.trim().toUpperCase();
        List<java.util.Map<String, Object>> rows =
                fileInfoMapper.selectDateStats(kw, tp, unGrouped ? null : groupId, unGrouped);
        // 统一成对外的字段名（day / count / size），不让 SQL 别名泄漏到接口上
        List<java.util.Map<String, Object>> out = new ArrayList<>(rows.size());
        for (java.util.Map<String, Object> r : rows) {
            java.util.Map<String, Object> m = new HashMap<>(4);
            Object day = r.get("day");
            m.put("day", day == null ? null : String.valueOf(day));
            m.put("count", r.get("cnt") == null ? 0L : ((Number) r.get("cnt")).longValue());
            m.put("size", r.get("bytes") == null ? 0L : ((Number) r.get("bytes")).longValue());
            out.add(m);
        }
        return out;
    }

    /** 只接受 yyyy-MM-dd，其余一律当作「不按日期过滤」，避免拼接出怪 SQL */
    private String normalizeDate(String date) {
        if (date == null || date.trim().isEmpty()) {
            return null;
        }
        String d = date.trim();
        return d.matches("\\d{4}-\\d{2}-\\d{2}") ? d : null;
    }

    /**
     * 拍摄时间兜底校验。
     *
     * 客户端的 File.lastModified 来自设备时钟，可能为 0、也可能是「1970」或未来时间。
     * 太离谱的宁可不要（回退到上传时间），否则相册里会冒出 1902 年、2087 年这样的日期组。
     */
    private java.time.LocalDateTime saneCapturedTime(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return null;
        }
        long min = 946684800000L;                                  // 2000-01-01
        long max = System.currentTimeMillis() + 2L * 24 * 3600 * 1000; // 允许 2 天的时钟误差
        if (epochMillis < min || epochMillis > max) {
            return null;
        }
        return java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis), java.time.ZoneId.systemDefault());
    }

    @Override
    public FileInfo getById(Long id) {
        FileInfo info = fileInfoMapper.selectById(id);
        if (info == null || (info.getDeleted() != null && info.getDeleted() == 1)) {
            throw new IllegalArgumentException("文件不存在或已删除");
        }
        return info;
    }

    @Override
    public void delete(Long id) {
        // 校验存在（已删除的会抛异常）
        getById(id);
        // 逻辑删除 = 移入回收站：记下删除时间，过期后由定时任务彻底清理
        fileInfoMapper.moveToRecycle(id, LocalDateTime.now());
    }

    @Override
    public int batchDelete(List<Long> ids) {
        List<Long> safeIds = normalizeIds(ids);
        return fileInfoMapper.moveToRecycleBatch(safeIds, LocalDateTime.now());
    }

    @Override
    public List<FileInfo> listByIds(List<Long> ids) {
        List<Long> safeIds = normalizeIds(ids);
        LambdaQueryWrapper<FileInfo> qw = new LambdaQueryWrapper<>();
        qw.in(FileInfo::getId, safeIds).eq(FileInfo::getDeleted, 0);
        List<FileInfo> found = fileInfoMapper.selectList(qw);
        // selectList 不保证顺序，按调用方传入顺序重排（批量下载 zip 依赖这个顺序）
        Map<Long, FileInfo> byId = new HashMap<>();
        for (FileInfo f : found) {
            byId.put(f.getId(), f);
        }
        List<FileInfo> result = new ArrayList<>(found.size());
        for (Long id : safeIds) {
            FileInfo f = byId.get(id);
            if (f != null) {
                result.add(f);
            }
        }
        return result;
    }

    /** 批量操作入参校验：去空、去非法、限流 */
    private List<Long> normalizeIds(List<Long> ids) {
        List<Long> result = new ArrayList<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id != null && id > 0) {
                    result.add(id);
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("请先选择文件");
        }
        if (result.size() > 500) {
            throw new IllegalArgumentException("单次最多操作 500 个文件");
        }
        return result;
    }

    @Override
    public void rename(Long id, String newName) {
        if (newName == null || newName.trim().isEmpty()) {
            throw new IllegalArgumentException("新文件名不能为空");
        }
        FileInfo info = getById(id);
        info.setFileName(sanitizeFileName(newName.trim()));
        fileInfoMapper.updateById(info);
    }

    // ---------------------- 物理文件解析 ----------------------

    @Override
    public File resolvePhysicalFile(Long id) {
        FileInfo info = getById(id);
        File f = new File(storageRoot, info.getFilePath());
        if (!f.exists()) {
            throw new IllegalArgumentException("文件物理路径不存在");
        }
        return f;
    }

    @Override
    public File resolveThumbFile(Long id) {
        FileInfo info = getById(id);
        if (info.getThumbPath() == null || info.getThumbPath().isEmpty()) {
            return null;
        }
        File f = new File(storageRoot, info.getThumbPath());
        return f.exists() ? f : null;
    }

    @Override
    public File resolvePreviewFile(Long id) {
        FileInfo info = getById(id);
        if (!FileType.IMAGE.name().equals(info.getFileType())) {
            return null;
        }
        File src = new File(storageRoot, info.getFilePath());
        if (!src.exists()) {
            return null;
        }
        // 原图本身就不大：没必要多一次解码 + 重编码，直接给原图
        int[] dim = readImageSize(src);
        if (dim != null && Math.max(dim[0], dim[1]) <= PREVIEW_MAX_EDGE) {
            return src;
        }
        File preview = buildPreview(info, src);
        return preview != null ? preview : src;
    }

    /**
     * 生成（或复用）预览图：`.preview/{id}.jpg`，长边 1920。
     *
     * 抽出来是因为上传完就要**预热**——不预热的话，用户第一次点开某张照片时
     * 现场生成要 0.5~1.1 秒（12MP 全图解码），表现就是「翻到下一张卡一下」。
     *
     * @return 预览图文件；无需生成（原图够小）或生成失败时返回 null
     */
    private File buildPreview(FileInfo info, File src) {
        File dir = new File(storageRoot, ".preview");
        File out = new File(dir, info.getId() + ".jpg");
        if (out.exists() && out.length() > 0) {
            return out;
        }
        Object lock = previewLocks.computeIfAbsent(info.getId(), k -> new Object());
        synchronized (lock) {
            if (out.exists() && out.length() > 0) {
                return out;
            }
            dir.mkdirs();
            // 先写临时文件再改名：两个请求撞上时不会有人读到半张图。
            // 注意扩展名必须是 .jpg —— Thumbnailator 的 outputFormat 会强制改后缀，
            // 写成 134.tmp 的话磁盘上会出现 134.tmp.jpg，后面的 rename 必然失败。
            File tmp = new File(dir, info.getId() + ".tmp.jpg");
            try {
                Thumbnails.of(src)
                        .size(PREVIEW_MAX_EDGE, PREVIEW_MAX_EDGE)
                        .keepAspectRatio(true)
                        .outputQuality(0.86f)
                        .outputFormat("jpg")
                        .toFile(tmp);
                if (!tmp.renameTo(out)) {
                    // Windows 上目标已存在时 renameTo 会失败，此时删掉半成品改用已有文件
                    tmp.delete();
                }
                return out.exists() ? out : null;
            } catch (Exception e) {
                // 解码失败（少见格式、文件损坏）：退回原图，让浏览器自己试
                log.warn("预览图生成失败，回退原图 id={}: {}", info.getId(), e.getMessage());
                tmp.delete();
                return null;
            }
        }
    }

    /**
     * 上传后顺手把预览图生成好（后台单线程，LIFO）。
     *
     * 之所以敢放在后台：上传响应不等它，用户上传完到点开第一张通常有几十秒的间隔。
     * 生成失败只记 debug，不影响任何业务。
     */
    private void warmPreviewAsync(FileInfo info, File src) {
        if (info == null || info.getId() == null) {
            return;
        }
        previewWarmQueue.push(info.getId());   // push = 放到队头，后上传的先预热
        startPreviewWarmWorker();
    }

    private synchronized void startPreviewWarmWorker() {
        if (previewWarmStarted) {
            return;
        }
        previewWarmStarted = true;
        Thread t = new Thread(() -> {
            for (;;) {
                Long id;
                try {
                    id = previewWarmQueue.take();
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    warmOnePreview(id);
                } catch (Exception e) {
                    log.debug("预览图预热失败 id={}: {}", id, e.getMessage());
                }
            }
        }, "preview-warm");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void warmOnePreview(Long id) {
        FileInfo info;
        try {
            info = getById(id);
        } catch (Exception e) {
            return;    // 已经删了
        }
        if (!FileType.IMAGE.name().equals(info.getFileType())) {
            return;
        }
        File src = new File(storageRoot, info.getFilePath());
        if (!src.exists()) {
            return;
        }
        int[] dim = readImageSize(src);
        if (dim != null && Math.max(dim[0], dim[1]) <= PREVIEW_MAX_EDGE) {
            return;    // 原图够小，浏览时直接用原图
        }
        long t0 = System.currentTimeMillis();
        File out = buildPreview(info, src);
        if (out != null && log.isDebugEnabled()) {
            log.debug("[preview] {}ms id={} ({}x{})", System.currentTimeMillis() - t0, id,
                    dim == null ? 0 : dim[0], dim == null ? 0 : dim[1]);
        }
    }

    // ---------------------- 视频转码 ----------------------

    @Override
    public boolean needsTranscode(Long id) {
        FileInfo info = getById(id);
        return needsTranscode(info.getContentType(), info.getFileName());
    }

    @Override
    public File transcodeToMp4(Long id) {
        FileInfo info = getById(id);
        if (!FileType.VIDEO.name().equals(info.getFileType())) {
            return null;
        }
        if (!isFfmpegAvailable()) {
            log.warn("未配置可用的 ffmpeg，无法转码视频");
            return null;
        }
        File src = new File(storageRoot, info.getFilePath());
        if (!src.exists()) {
            return null;
        }
        File outDir = new File(storageRoot, ".transcode");
        outDir.mkdirs();
        File out = new File(outDir, id + ".mp4");
        if (out.exists() && out.length() > 0) {
            return out;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath, "-y", "-i", src.getAbsolutePath(),
                    "-c:v", "libx264", "-profile:v", "high", "-level", "4.0",
                    "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k",
                    "-movflags", "+faststart",
                    out.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int code = p.waitFor();
            if (code == 0 && out.exists() && out.length() > 0) {
                return out;
            }
            log.error("ffmpeg 转码失败，退出码 {}", code);
            return null;
        } catch (Exception e) {
            log.error("ffmpeg 转码异常", e);
            return null;
        }
    }

    // ---------------------- 私有工具 ----------------------

    private FileType detectType(String contentType, String ext) {
        if (contentType != null) {
            if (contentType.startsWith("image/")) {
                return FileType.IMAGE;
            }
            if (contentType.startsWith("video/")) {
                return FileType.VIDEO;
            }
        }
        String lower = ext.toLowerCase();
        if (isImageExt(lower)) {
            return FileType.IMAGE;
        }
        if (isVideoExt(lower)) {
            return FileType.VIDEO;
        }
        return FileType.OTHER;
    }

    private boolean isImageExt(String lower) {
        return lower.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|svg|heic|heif)$");
    }

    private boolean isVideoExt(String lower) {
        return lower.matches(".*\\.(mp4|mov|m4v|avi|wmv|flv|mkv|webm|3gp|mpg|mpeg|ts)$");
    }

    /**
     * 判断该视频是否需要转码为 H.264 MP4 才能被浏览器播放
     */
    private boolean needsTranscode(String contentType, String fileName) {
        if (forceTranscode) {
            return true;
        }
        String lower = (fileName == null ? "" : fileName.toLowerCase());
        String ext = getExtension(lower);
        // 浏览器原生可播放的封装：mp4(通常 H.264)、webm、ogg
        if (ext.equals(".mp4") || ext.equals(".webm") || ext.equals(".ogv")) {
            return false;
        }
        // Apple 手机视频(MOV/HEVC)、以及 avi/wmv/flv/mkv/3gp/m4v 等需要转码
        return isVideoExt(lower) || (contentType != null && contentType.equals("video/quicktime"));
    }

    private String generateImageThumb(File src, String relativeDir, String storedName) {
        try {
            int[] dim = readImageSize(src);
            // 原图本来就不大（手机截图、聊天转发的小图）：直接让 /thumb 回退到原图，
            // 省掉一次「解码 + 缩放 + 重编码」。这一步在批量上传时省下的是成倍的时间。
            if (dim != null && Math.max(dim[0], dim[1]) <= THUMB_SKIP_EDGE) {
                return null;
            }
            String thumbName = "thumb_" + storedName + ".jpg";
            File dir = new File(storageRoot, relativeDir);
            File out = new File(dir, thumbName);
            long t0 = System.currentTimeMillis();

            // 快路：抽样解码 + 自己缩放。12MP 照片缩到 320 只需解 1/16 的像素，
            // 实测 225ms → 152ms（同一张 4032×3024 的 JPEG）；批量 20 张就是几秒钟。
            if (writeScaledJpeg(src, out, THUMB_MAX_EDGE, 0.82f)) {
                if (log.isDebugEnabled()) {
                    log.debug("[thumb] {}ms {} ({}x{})", System.currentTimeMillis() - t0,
                            src.getName(), dim == null ? 0 : dim[0], dim == null ? 0 : dim[1]);
                }
                return relativeDir + "/" + thumbName;
            }

            // 兜底：解码器不支持抽样（少见编码 / 特殊色彩空间）时仍用 Thumbnailator，
            // 行为与改动前完全一致。
            Thumbnails.of(src)
                    .size(THUMB_MAX_EDGE, THUMB_MAX_EDGE)
                    .keepAspectRatio(true)
                    .outputQuality(0.82f)
                    .outputFormat("jpg")
                    .toFile(out);
            return relativeDir + "/" + thumbName;
        } catch (IOException e) {
            log.warn("图片缩略图生成失败: {}", src.getName(), e);
            return null;
        }
    }

    /**
     * 解码 → 缩放 → 写 JPEG，全过程只占目标尺寸所需的那点像素。
     *
     * 原来 Thumbnailator 直接对着文件读，等于先把 4032×3024（≈3600 万字节的像素数组）
     * 整张解出来，再缩到 320——解码本身占了缩略图耗时的绝大部分。
     * 这里用 {@link ImageReadParam#setSourceSubsampling} 让解码器每隔 N 个像素取一个样本，
     * 解码量按 1/4、1/16 递减，出来的图再缩到目标尺寸肉眼没有区别。
     *
     * @return 写成功返回 true；解不了（没有 reader，例如 HEIC）返回 false，由调用方兜底
     */
    private boolean writeScaledJpeg(File src, File out, int maxEdge, float quality) {
        try {
            BufferedImage decoded = readSubsampled(src, maxEdge);
            if (decoded == null) {
                return false;
            }
            int w = decoded.getWidth();
            int h = decoded.getHeight();
            if (w <= 0 || h <= 0) {
                return false;
            }
            double ratio = Math.min(1.0, (double) maxEdge / Math.max(w, h));
            int tw = Math.max(1, (int) Math.round(w * ratio));
            int th = Math.max(1, (int) Math.round(h * ratio));

            // 缩得太狠（比如 1/13）时一步插值会出摩尔纹：先对半缩，缩到 2 倍以内再收尾。
            // 半尺寸图上的卷积很便宜，级数也就 log2 次。
            BufferedImage cur = flattenOnWhite(decoded);
            while (cur.getWidth() > tw * 2 && cur.getHeight() > th * 2) {
                cur = scaleTo(cur, Math.max(1, cur.getWidth() / 2), Math.max(1, cur.getHeight() / 2));
            }
            if (cur.getWidth() != tw || cur.getHeight() != th) {
                cur = scaleTo(cur, tw, th);
            }
            writeJpeg(cur, out, quality);
            return true;
        } catch (Exception e) {
            // 半截文件绝不能留在磁盘上：缩略图路径是按名字拼出来的，留着就会被当成成品
            out.delete();
            log.debug("抽样缩放失败，回退 Thumbnailator: {} / {}", src.getName(), e.getMessage());
            return false;
        }
    }

    /** 按目标长边决定抽样倍数：只要「再抽一倍」后仍不小于 2 倍目标，就继续加倍 */
    private BufferedImage readSubsampled(File f, int targetEdge) {
        ImageReader reader = null;
        try (ImageInputStream iis = ImageIO.createImageInputStream(f)) {
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) {
                return null;
            }
            reader = it.next();
            reader.setInput(iis, true, true);
            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            int factor = 1;
            while (w / (factor * 2) >= targetEdge * 2 && h / (factor * 2) >= targetEdge * 2) {
                factor *= 2;
            }
            ImageReadParam param = reader.getDefaultReadParam();
            if (factor > 1) {
                param.setSourceSubsampling(factor, factor, 0, 0);
            }
            return reader.read(0, param);
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    /** JPEG 没有 alpha：透明 PNG 直接写会出黑底，先铺白再画 */
    private BufferedImage flattenOnWhite(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private BufferedImage scaleTo(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private void writeJpeg(BufferedImage img, File out, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /**
     * 只读图片尺寸，不解码像素。
     * ImageReader 读的是文件头，比整图 decode 快两个数量级；
     * HEIC / WEBP 等没有内置 reader 的格式返回 null，交给后续逻辑兜底。
     */
    private int[] readImageSize(File f) {
        try (javax.imageio.stream.ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(f)) {
            if (iis == null) {
                return null;
            }
            java.util.Iterator<javax.imageio.ImageReader> it = javax.imageio.ImageIO.getImageReaders(iis);
            if (!it.hasNext()) {
                return null;
            }
            javax.imageio.ImageReader reader = it.next();
            try {
                reader.setInput(iis, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void generateVideoPosterAsync(FileInfo info, File src, String relativeDir, String storedName) {
        asyncExecutor.submit(() -> {
            if (!isFfmpegAvailable()) {
                return;
            }
            try {
                String posterName = "poster_" + storedName + ".jpg";
                File dir = new File(storageRoot, relativeDir);
                File out = new File(dir, posterName);
                ProcessBuilder pb = new ProcessBuilder(
                        ffmpegPath, "-y", "-i", src.getAbsolutePath(),
                        "-ss", "00:00:01", "-vframes", "1", "-q:v", "2",
                        out.getAbsolutePath());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                int code = p.waitFor();
                if (code == 0 && out.exists()) {
                    FileInfo update = new FileInfo();
                    update.setId(info.getId());
                    update.setThumbPath(relativeDir + "/" + posterName);
                    fileInfoMapper.updateById(update);
                    log.info("视频封面生成成功: {}", info.getId());
                }
            } catch (Exception e) {
                log.warn("视频封面生成失败: {}", info.getId(), e);
            }
        });
    }

    private boolean isFfmpegAvailable() {
        if (ffmpegPath == null || ffmpegPath.trim().isEmpty()) {
            return false;
        }
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String getExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx).toLowerCase();
    }

    private String sanitizeFileName(String name) {
        if (name == null) {
            return "unnamed";
        }
        // 去掉路径分隔，防止目录穿越
        return name.replaceAll("[/\\\\]", "_").trim();
    }

    private String computeMd5(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int len;
            while ((len = raf.read(buf)) != -1) {
                md.update(buf, 0, len);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 分段锁：按内容指纹分到 64 把锁的一把，避免全站上传被一把锁串起来 */
    private static final Object[] LOCKS = new Object[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) {
            LOCKS[i] = new Object();
        }
    }

    private static Object lockFor(String md5, long size) {
        String key = (md5 == null ? "null" : md5) + size;
        return LOCKS[(key.hashCode() & 0x7fffffff) % LOCKS.length];
    }

    /** 重复文件被拒时，把刚落盘的文件和缩略图删掉；删不掉只记日志，不影响给用户的结论 */
    private void discardQuietly(File file, String thumbRelativePath) {
        try {
            if (file != null && file.isFile() && !file.delete()) {
                log.warn("重复文件清理失败（将在下次手工清理时处理）：{}", file.getAbsolutePath());
            }
            if (thumbRelativePath != null && !thumbRelativePath.isEmpty()) {
                File thumb = new File(storageRoot, thumbRelativePath);
                if (thumb.isFile()) {
                    thumb.delete();
                }
            }
        } catch (Exception e) {
            log.warn("重复文件清理异常：{}", e.getMessage());
        }
    }
}
