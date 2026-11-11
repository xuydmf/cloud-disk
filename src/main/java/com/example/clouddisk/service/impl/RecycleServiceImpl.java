package com.example.clouddisk.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.clouddisk.common.PageResult;
import com.example.clouddisk.common.RecycleItemVO;
import com.example.clouddisk.entity.FileGroup;
import com.example.clouddisk.entity.FileInfo;
import com.example.clouddisk.mapper.FileGroupMapper;
import com.example.clouddisk.mapper.FileInfoMapper;
import com.example.clouddisk.service.RecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 回收站实现。
 *
 * <p>几个刻意的取舍：
 * <ol>
 *   <li>「删除」仍然只改标记（deleted=1 + deleted_time），文件一秒进回收站，不做磁盘搬运，
 *       2 GB 的视频也不会卡住界面。</li>
 *   <li>「彻底删除」才动磁盘：先删文件、再删记录。顺序不能反——反了的话删除记录后
 *       物理文件就成了没人认领的垃圾，再也找不到。</li>
 *   <li>物理文件删失败（被占用等）只记 warn，不阻断流程：数据库记录照样删掉，
 *       否则用户会一直看到删不掉的僵尸条目。残留文件由后续清理兜底。</li>
 * </ol>
 */
@Slf4j
@Service
public class RecycleServiceImpl implements RecycleService {

    /** 单次批量操作上限，与文件服务的批量操作保持一致 */
    private static final int MAX_BATCH = 500;

    /** 过期清理时每批查多少条（防止一次把几十万条读进内存） */
    private static final int EXPIRE_SCAN_BATCH = 200;

    @Value("${file.storage.root}")
    private String storageRoot;

    /** 回收站保留天数，默认 7 天 */
    @Value("${file.recycle.keep-days:7}")
    private int keepDays;

    private final FileInfoMapper fileInfoMapper;
    private final FileGroupMapper fileGroupMapper;

    public RecycleServiceImpl(FileInfoMapper fileInfoMapper, FileGroupMapper fileGroupMapper) {
        this.fileInfoMapper = fileInfoMapper;
        this.fileGroupMapper = fileGroupMapper;
    }

    // ==================== 查询 ====================

    @Override
    public PageResult<RecycleItemVO> page(String keyword, long page, long size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20;
        }
        if (size > 200) {
            size = 200;
        }
        String kw = (keyword == null || keyword.trim().isEmpty()) ? null : keyword.trim();

        long total = fileInfoMapper.countRecycle(kw);
        List<RecycleItemVO> records = new ArrayList<>();
        if (total > 0) {
            List<FileInfo> rows = fileInfoMapper.selectRecyclePage(kw, (page - 1) * size, size);
            Map<Long, String> groupNames = groupNameMap(rows);
            LocalDateTime now = LocalDateTime.now();
            for (FileInfo f : rows) {
                records.add(RecycleItemVO.of(f, groupNames.get(f.getGroupId()), keepDays, now));
            }
        }
        return PageResult.of(records, total, page, size);
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> m = new HashMap<>();
        m.put("count", fileInfoMapper.countAllRecycle());
        m.put("totalSize", fileInfoMapper.sumRecycleSize());
        m.put("keepDays", keepDays);
        return m;
    }

    /** 一次把用到的分组名查出来，避免每行一次查询 */
    private Map<Long, String> groupNameMap(List<FileInfo> rows) {
        Set<Long> ids = rows.stream()
                .map(FileInfo::getGroupId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        List<FileGroup> groups = fileGroupMapper.selectBatchIds(ids);
        Map<Long, String> map = new HashMap<>();
        for (FileGroup g : groups) {
            map.put(g.getId(), g.getName());
        }
        return map;
    }

    // ==================== 预览用的文件定位 ====================

    @Override
    public FileInfo getRecycled(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("文件不存在");
        }
        // 这里不能用 selectById：逻辑删除插件会自动追加 deleted=0，回收站里的东西一条都查不到
        List<FileInfo> rows = fileInfoMapper.selectDeletedByIds(Collections.singletonList(id));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("文件不在回收站，或已被清理");
        }
        return rows.get(0);
    }

    @Override
    public File resolveRecycledFile(Long id) {
        FileInfo info = getRecycled(id);
        File file = resolve(info.getFilePath());
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("文件内容已丢失，可能已被清理");
        }
        return file;
    }

    @Override
    public File resolveRecycledThumb(Long id) {
        FileInfo info = getRecycled(id);
        File thumb = resolve(info.getThumbPath());
        return (thumb != null && thumb.exists()) ? thumb : null;
    }

    // ==================== 恢复 ====================

    @Override
    public int restore(List<Long> ids) {
        List<Long> safe = normalizeIds(ids);
        List<FileInfo> targets = fileInfoMapper.selectDeletedByIds(safe);

        // 原分组若已被删除，恢复后 group_id 会指向一个不存在的分组，前端显示成空分组。
        // 这里把这类文件的 groupId 清空，让它回到「未分组」，避免出现幽灵分组。
        Set<Long> groupIds = targets.stream()
                .map(FileInfo::getGroupId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        int orphan = 0;
        if (!groupIds.isEmpty()) {
            Set<Long> aliveIds = fileGroupMapper.selectBatchIds(groupIds).stream()
                    .map(FileGroup::getId).collect(Collectors.toSet());
            for (FileInfo f : targets) {
                if (f.getGroupId() != null && f.getGroupId() > 0 && !aliveIds.contains(f.getGroupId())) {
                    // 用列名而非 Lambda 更新：这里不需要实体元数据，也不依赖 MyBatis-Plus 的 lambda 缓存
                    fileInfoMapper.update(null, new UpdateWrapper<FileInfo>()
                            .eq("id", f.getId())
                            .set("group_id", null));
                    orphan++;
                }
            }
        }
        int n = fileInfoMapper.restoreBatch(safe);
        log.info("回收站恢复 {} 个文件（其中 {} 个原分组已失效，已归入未分组）", n, orphan);
        return n;
    }

    // ==================== 彻底删除 ====================

    @Override
    public int purge(List<Long> ids) {
        List<Long> safe = normalizeIds(ids);
        List<FileInfo> targets = fileInfoMapper.selectDeletedByIds(safe);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("文件不在回收站，或已被清理");
        }
        // 先删磁盘，再删记录：反过来的话文件就成了没人认领的垃圾
        for (FileInfo f : targets) {
            deleteStorageFiles(f);
        }
        int n = fileInfoMapper.physicalDeleteBatch(safe);
        log.info("回收站彻底删除 {} 个文件", n);
        return n;
    }

    @Override
    public int clear() {
        if (fileInfoMapper.countAllRecycle() == 0) {
            return 0;
        }
        int deleted = 0;
        // 分批取，避免一次性把几十万条读进内存
        while (true) {
            List<FileInfo> batch = fileInfoMapper.selectRecyclePage(null, 0, EXPIRE_SCAN_BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (FileInfo f : batch) {
                // 单个文件的磁盘清理出错不能拦住整批：记录照删，残留文件下次再清
                try {
                    deleteStorageFiles(f);
                } catch (RuntimeException e) {
                    log.warn("回收站清空：id={} 的磁盘文件处理失败，继续处理其余记录", f.getId(), e);
                }
            }
            int n = fileInfoMapper.physicalDeleteBatch(
                    batch.stream().map(FileInfo::getId).collect(Collectors.toList()));
            deleted += n;
            // 一条都没删掉说明这些记录已经不在了（并发清理等），再取还是同一批 → 直接收手，别空转
            if (n <= 0) {
                log.warn("回收站清空提前结束：本批 {} 条记录未能删除", batch.size());
                break;
            }
        }
        log.info("回收站已清空，共彻底删除 {} 个文件", deleted);
        return deleted;
    }

    @Override
    public int purgeExpired(int days) {
        int useDays = days > 0 ? days : keepDays;
        LocalDateTime deadline = LocalDateTime.now().minusDays(useDays);
        int total = 0;
        // 每轮取出 200 条超期记录 → 删物理文件 → 从库里删掉这批，再取下轮。
        // 必须先删库再取下轮，否则同一条会被反复取出，死循环。
        while (true) {
            List<FileInfo> batch = fileInfoMapper.selectExpired(deadline, EXPIRE_SCAN_BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (FileInfo f : batch) {
                // 单个文件的磁盘清理出错不能拦住整批：记录照删，残留文件下次再清
                try {
                    deleteStorageFiles(f);
                } catch (RuntimeException e) {
                    log.warn("回收站清理：id={} 的磁盘文件处理失败，继续处理其余记录", f.getId(), e);
                }
            }
            List<Long> ids = batch.stream().map(FileInfo::getId).collect(Collectors.toList());
            int n = fileInfoMapper.physicalDeleteBatch(ids);
            if (n <= 0) {
                // 库里一条都没动，下一轮还会捞出同一批 → 停手，避免空转
                log.warn("回收站清理提前结束：本批 {} 条记录未能删除", ids.size());
                break;
            }
            total += ids.size();
        }
        if (total > 0) {
            log.info("回收站自动清理：{} 天前删除的 {} 个文件已彻底删除", useDays, total);
        }
        return total;
    }

    /**
     * 删除一条记录在磁盘上的所有产物：
     * 原文件、缩略图/视频封面、以及按 id 缓存的转码结果。
     * 任何一个失败都只记日志——记录该删还是要删，不能因为一个被占用的文件卡住整个流程。
     */
    private void deleteStorageFiles(FileInfo f) {
        deleteQuietly(resolve(f.getFilePath()));
        deleteQuietly(resolve(f.getThumbPath()));
        if (f.getId() != null) {
            deleteQuietly(new File(new File(storageRoot, ".transcode"), f.getId() + ".mp4"));
        }
    }

    private File resolve(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            return null;
        }
        String normalized = relativePath.replace('\\', '/');
        // 防御：不允许跳出存储根目录
        if (normalized.contains("..")) {
            log.warn("跳过可疑路径：{}", relativePath);
            return null;
        }
        return new File(storageRoot, normalized);
    }

    private void deleteQuietly(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (!f.delete()) {
            log.warn("物理文件删除失败（可能被占用）：{}", f.getAbsolutePath());
        }
    }

    // ==================== 工具 ====================

    private List<Long> normalizeIds(List<Long> ids) {
        List<Long> result = new ArrayList<>();
        if (ids != null) {
            Set<Long> seen = new HashSet<>();
            for (Long id : ids) {
                if (id != null && id > 0 && seen.add(id)) {
                    result.add(id);
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("请先选择文件");
        }
        if (result.size() > MAX_BATCH) {
            throw new IllegalArgumentException("单次最多操作 " + MAX_BATCH + " 个文件");
        }
        return result;
    }
}
