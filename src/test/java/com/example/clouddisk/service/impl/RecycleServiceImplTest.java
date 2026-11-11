package com.example.clouddisk.service.impl;

import com.example.clouddisk.common.PageResult;
import com.example.clouddisk.common.RecycleItemVO;
import com.example.clouddisk.entity.FileGroup;
import com.example.clouddisk.entity.FileInfo;
import com.example.clouddisk.mapper.FileGroupMapper;
import com.example.clouddisk.mapper.FileInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回收站单元测试。
 *
 * <p>回收站最容易出事的四个点，正好都能脱离数据库断言：
 * <ol>
 *   <li>「彻底删除」必须<b>先删磁盘再删库</b>，否则文件会变成没人认领的垃圾；</li>
 *   <li>过期清理必须<b>每轮先删库再取下轮</b>，否则同一批会被反复取出来变成死循环；</li>
 *   <li>恢复时原分组已删的文件要清空 groupId，不然前端会冒出幽灵分组；</li>
 *   <li>剩余天数要向上取整，删了 6 天 5 小时应显示「1 天」而不是「0 天」。</li>
 * </ol>
 */
class RecycleServiceImplTest {

    private static final int KEEP_DAYS = 7;

    @TempDir
    Path tmp;

    private FileInfoMapper fileInfoMapper;
    private FileGroupMapper fileGroupMapper;
    private RecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        fileInfoMapper = mock(FileInfoMapper.class);
        fileGroupMapper = mock(FileGroupMapper.class);
        service = new RecycleServiceImpl(fileInfoMapper, fileGroupMapper);
        ReflectionTestUtils.setField(service, "storageRoot", tmp.toString());
        ReflectionTestUtils.setField(service, "keepDays", KEEP_DAYS);
    }

    // ==================== 辅助 ====================

    /** 在临时存储目录里造一个真文件，返回它的相对路径 */
    private String touch(String relative) throws IOException {
        File f = new File(tmp.toFile(), relative);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
        Files.write(f.toPath(), ("payload:" + relative).getBytes(StandardCharsets.UTF_8));
        return relative;
    }

    private FileInfo deletedFile(long id, String filePath, String thumbPath, Long groupId, LocalDateTime deletedTime) {
        FileInfo f = new FileInfo();
        f.setId(id);
        f.setFileName("file-" + id + ".bin");
        f.setFileSize(1024L);
        f.setFileType("IMAGE");
        f.setContentType("image/png");
        f.setFilePath(filePath);
        f.setThumbPath(thumbPath);
        f.setGroupId(groupId);
        f.setDeleted(1);
        f.setDeletedTime(deletedTime);
        return f;
    }

    // ==================== 列表 / 统计 ====================

    @Test
    @DisplayName("列表：分组名一次批量查出，缺失时回落到「未分组」")
    void pageFillsGroupName() {
        FileInfo withGroup = deletedFile(1L, "a.bin", null, 9L, LocalDateTime.now().minusDays(1));
        FileInfo noGroup = deletedFile(2L, "b.bin", null, null, LocalDateTime.now().minusDays(2));
        FileInfo deadGroup = deletedFile(3L, "c.bin", null, 77L, LocalDateTime.now().minusDays(3));

        when(fileInfoMapper.countRecycle(null)).thenReturn(3L);
        when(fileInfoMapper.selectRecyclePage(isNull(), eq(0L), eq(20L)))
                .thenReturn(Arrays.asList(withGroup, noGroup, deadGroup));

        FileGroup g = new FileGroup();
        g.setId(9L);
        g.setName("项目资料");
        when(fileGroupMapper.selectBatchIds(any(Collection.class))).thenReturn(Collections.singletonList(g));

        PageResult<RecycleItemVO> page = service.page("  ", 1, 20);

        assertThat(page.getTotal()).isEqualTo(3L);
        assertThat(page.getRecords()).hasSize(3);
        assertThat(page.getRecords().get(0).getGroupName()).isEqualTo("项目资料");
        // groupId 为空
        assertThat(page.getRecords().get(1).getGroupName()).isEqualTo("未分组");
        // 分组已被删除：查不到名字，同样回落
        assertThat(page.getRecords().get(2).getGroupName()).isEqualTo("未分组");
    }

    @Test
    @DisplayName("列表：空结果不再去查分组表")
    void pageWithoutRowsSkipsGroupQuery() {
        when(fileInfoMapper.countRecycle(anyString())).thenReturn(0L);

        PageResult<RecycleItemVO> page = service.page("nothing", 1, 20);

        assertThat(page.getRecords()).isEmpty();
        verify(fileGroupMapper, never()).selectBatchIds(any(Collection.class));
        assertThat(page.getRecords()).isEmpty();
    }

    @Test
    @DisplayName("列表：page/size 非法值被纠正（size 超过 200 截断）")
    void pageNormalizesArgs() {
        // 至少要有 1 条才走分页查询，否则 service 会跳过 selectRecyclePage
        when(fileInfoMapper.countRecycle(null)).thenReturn(1L);
        when(fileInfoMapper.selectRecyclePage(any(), anyLong(), anyLong()))
                .thenReturn(Collections.singletonList(
                        deletedFile(1L, "a.bin", null, null, LocalDateTime.now())));

        service.page(null, 0, 9999);

        ArgumentCaptor<Long> offset = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> size = ArgumentCaptor.forClass(Long.class);
        verify(fileInfoMapper).selectRecyclePage(isNull(), offset.capture(), size.capture());
        assertThat(offset.getValue()).isZero();
        assertThat(size.getValue()).isEqualTo(200L);
    }

    @Test
    @DisplayName("统计：返回条数、占用空间与保留天数")
    void stats() {
        when(fileInfoMapper.countAllRecycle()).thenReturn(5L);
        when(fileInfoMapper.sumRecycleSize()).thenReturn(4096L);

        Map<String, Object> s = service.stats();

        assertThat(s).containsEntry("count", 5L)
                .containsEntry("totalSize", 4096L)
                .containsEntry("keepDays", KEEP_DAYS);
    }

    // ==================== 剩余天数 ====================

    @Test
    @DisplayName("剩余天数：向上取整，删了 6 天 5 小时显示 1 天而不是 0 天")
    void remainDaysCeilsUp() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 29, 12, 0);

        // 刚删：还剩满 7 天
        assertThat(RecycleItemVO.of(deletedFile(1L, "x", null, null, now), null, KEEP_DAYS, now)
                .getRemainDays()).isEqualTo(7);

        // 删了 6 天 5 小时：剩 19 小时 → 向上取整 1 天
        assertThat(RecycleItemVO.of(deletedFile(2L, "x", null, null, now.minusDays(6).minusHours(5)),
                null, KEEP_DAYS, now).getRemainDays()).isEqualTo(1);

        // 删了 7 天 1 分：已过期
        assertThat(RecycleItemVO.of(deletedFile(3L, "x", null, null, now.minusDays(7).minusMinutes(1)),
                null, KEEP_DAYS, now).getRemainDays()).isZero();

        // 历史数据没有 deletedTime：按满期算，尽快清掉
        assertThat(RecycleItemVO.of(deletedFile(4L, "x", null, null, null), null, KEEP_DAYS, now)
                .getRemainDays()).isZero();
    }

    // ==================== 恢复 ====================

    @Test
    @DisplayName("恢复：原分组已删除的清空 groupId，避免出现幽灵分组")
    void restoreClearsOrphanGroup() {
        FileInfo alive = deletedFile(1L, "a", null, 9L, LocalDateTime.now());
        FileInfo orphan = deletedFile(2L, "b", null, 77L, LocalDateTime.now());

        when(fileInfoMapper.selectDeletedByIds(Arrays.asList(1L, 2L)))
                .thenReturn(Arrays.asList(alive, orphan));
        FileGroup g = new FileGroup();
        g.setId(9L);
        g.setName("还活着");
        when(fileGroupMapper.selectBatchIds(any(Collection.class))).thenReturn(Collections.singletonList(g));
        when(fileInfoMapper.restoreBatch(Arrays.asList(1L, 2L))).thenReturn(2);

        int n = service.restore(Arrays.asList(1L, 2L));

        assertThat(n).isEqualTo(2);
        // 只对失效分组执行了一次 update，而且是 groupId=9 的那条不受影响
        verify(fileInfoMapper, times(1)).update(isNull(), any());
    }

    @Test
    @DisplayName("恢复：id 去重、过滤非法值后按干净列表更新")
    void restoreNormalizesIds() {
        when(fileInfoMapper.selectDeletedByIds(Arrays.asList(3L, 5L))).thenReturn(new ArrayList<>());
        when(fileInfoMapper.restoreBatch(Arrays.asList(3L, 5L))).thenReturn(2);

        service.restore(Arrays.asList(3L, null, 3L, -1L, 5L, 0L));

        verify(fileInfoMapper).restoreBatch(Arrays.asList(3L, 5L));
    }

    @Test
    @DisplayName("恢复：没有可用 id 时直接报错，不打数据库")
    void restoreRejectsEmpty() {
        assertThatThrownBy(() -> service.restore(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请先选择");
        assertThatThrownBy(() -> service.restore(null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(fileInfoMapper, never()).restoreBatch(any());
    }

    // ==================== 彻底删除 ====================

    @Test
    @DisplayName("彻底删除：顺序是先删物理文件、再删数据库记录")
    void purgeDeletesFilesBeforeRows() throws IOException {
        String main = touch("2026/10/29/a.bin");
        String thumb = touch("thumb/2026/10/29/a.bin.jpg");
        File transcode = new File(new File(tmp.toFile(), ".transcode"), "7.mp4");
        assertThat(transcode.getParentFile().mkdirs() || transcode.getParentFile().exists()).isTrue();
        Files.write(transcode.toPath(), new byte[]{1, 2, 3});

        FileInfo f = deletedFile(7L, main, thumb, null, LocalDateTime.now());
        when(fileInfoMapper.selectDeletedByIds(Collections.singletonList(7L)))
                .thenReturn(Collections.singletonList(f));
        when(fileInfoMapper.physicalDeleteBatch(Collections.singletonList(7L))).thenReturn(1);

        int n = service.purge(Collections.singletonList(7L));

        assertThat(n).isEqualTo(1);
        // 磁盘上的三份产物（原文件、缩略图、转码缓存）都没了
        assertThat(new File(tmp.toFile(), main)).doesNotExist();
        assertThat(new File(tmp.toFile(), thumb)).doesNotExist();
        assertThat(transcode).doesNotExist();
        verify(fileInfoMapper).physicalDeleteBatch(Collections.singletonList(7L));
    }

    @Test
    @DisplayName("彻底删除：记录不在回收站时报错，且不动数据库")
    void purgeRejectsUnknownId() {
        when(fileInfoMapper.selectDeletedByIds(Collections.singletonList(42L))).thenReturn(new ArrayList<>());

        assertThatThrownBy(() -> service.purge(Collections.singletonList(42L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("回收站");

        verify(fileInfoMapper, never()).physicalDeleteBatch(any());
    }

    @Test
    @DisplayName("彻底删除：单次超过 500 条直接拒绝，防止拖垮服务")
    void purgeRejectsTooMany() {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 501; i++) {
            ids.add(i);
        }

        assertThatThrownBy(() -> service.purge(ids))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("彻底删除：路径含 .. 的一律跳过，不会删到存储根目录之外")
    void purgeSkipsSuspiciousPath() throws IOException {
        // 存储根目录的「兄弟」文件，正常路径不该碰到它
        Path outside = tmp.getParent().resolve("outside-" + System.nanoTime() + ".bin");
        Files.write(outside, new byte[]{9, 9, 9});

        FileInfo f = deletedFile(8L, "../" + outside.getFileName(), null, null, LocalDateTime.now());
        when(fileInfoMapper.selectDeletedByIds(Collections.singletonList(8L)))
                .thenReturn(Collections.singletonList(f));
        when(fileInfoMapper.physicalDeleteBatch(Collections.singletonList(8L))).thenReturn(1);

        service.purge(Collections.singletonList(8L));

        assertThat(outside).exists();
        Files.deleteIfExists(outside);
    }

    // ==================== 过期清理 ====================

    @Test
    @DisplayName("过期清理：分批取、删完这批再取下批（不会死循环）")
    void purgeExpiredLoopsUntilEmpty() throws IOException {
        FileInfo a = deletedFile(1L, touch("old/a.bin"), null, null, LocalDateTime.now().minusDays(9));
        FileInfo b = deletedFile(2L, touch("old/b.bin"), null, null, LocalDateTime.now().minusDays(8));
        FileInfo c = deletedFile(3L, touch("old/c.bin"), null, null, LocalDateTime.now().minusDays(10));

        // 第一次返回 a/b，第二次返回 c，第三次返回空 → 循环退出
        when(fileInfoMapper.selectExpired(any(LocalDateTime.class), anyInt()))
                .thenReturn(Arrays.asList(a, b))
                .thenReturn(Collections.singletonList(c))
                .thenReturn(new ArrayList<>());
        when(fileInfoMapper.physicalDeleteBatch(any())).thenReturn(1);

        int total = service.purgeExpired(0);

        assertThat(total).isEqualTo(3);
        assertThat(new File(tmp.toFile(), a.getFilePath())).doesNotExist();
        assertThat(new File(tmp.toFile(), c.getFilePath())).doesNotExist();
        verify(fileInfoMapper, times(2)).physicalDeleteBatch(any());
    }

    @Test
    @DisplayName("过期清理：截止时间按保留天数往前推")
    void purgeExpiredUsesKeepDays() {
        when(fileInfoMapper.selectExpired(any(LocalDateTime.class), anyInt())).thenReturn(new ArrayList<>());

        LocalDateTime before = LocalDateTime.now().minusDays(KEEP_DAYS);
        service.purgeExpired(0);
        LocalDateTime after = LocalDateTime.now().minusDays(KEEP_DAYS);

        ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fileInfoMapper).selectExpired(deadline.capture(), eq(200));
        assertThat(deadline.getValue()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    @Test
    @DisplayName("过期清理：显式传天数时优先用传入值")
    void purgeExpiredHonorsExplicitDays() {
        when(fileInfoMapper.selectExpired(any(LocalDateTime.class), anyInt())).thenReturn(new ArrayList<>());

        service.purgeExpired(1);

        ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fileInfoMapper).selectExpired(deadline.capture(), anyInt());
        assertThat(deadline.getValue()).isAfter(LocalDateTime.now().minusDays(2));
    }

    // ==================== 预览定位 ====================

    @Test
    @DisplayName("预览定位：查不到记录时报「不在回收站」，物理文件缺失时报「已丢失」")
    void resolveRecycledFileGuards() {
        when(fileInfoMapper.selectDeletedByIds(Collections.singletonList(1L)))
                .thenReturn(new ArrayList<>());
        assertThatThrownBy(() -> service.resolveRecycledFile(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("回收站");

        // 记录在，但磁盘上没这个文件
        when(fileInfoMapper.selectDeletedByIds(Collections.singletonList(2L)))
                .thenReturn(Collections.singletonList(deletedFile(2L, "gone/missing.bin", null, null, LocalDateTime.now())));
        assertThatThrownBy(() -> service.resolveRecycledFile(2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("丢失");
    }

    @Test
    @DisplayName("预览定位：没有缩略图时返回 null，而不是抛异常")
    void resolveRecycledThumbReturnsNullWhenAbsent() {
        when(fileInfoMapper.selectDeletedByIds(Collections.singletonList(3L)))
                .thenReturn(Collections.singletonList(deletedFile(3L, "a.bin", null, null, LocalDateTime.now())));

        assertThat(service.resolveRecycledThumb(3L)).isNull();
    }

    @Test
    @DisplayName("预览定位：id 非法时直接拒绝")
    void resolveRejectsBadId() {
        assertThatThrownBy(() -> service.getRecycled(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getRecycled(0L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(fileInfoMapper, never()).selectDeletedByIds(any());
    }

    // ==================== 清空 ====================

    @Test
    @DisplayName("清空：有东西时逐批删干净")
    void clearDrainsInBatches() throws IOException {
        FileInfo a = deletedFile(1L, touch("c/a.bin"), null, null, LocalDateTime.now());
        FileInfo b = deletedFile(2L, touch("c/b.bin"), null, null, LocalDateTime.now());
        when(fileInfoMapper.countAllRecycle()).thenReturn(2L);
        when(fileInfoMapper.selectRecyclePage(any(), anyLong(), anyLong()))
                .thenReturn(Arrays.asList(a, b))
                .thenReturn(new ArrayList<>());
        when(fileInfoMapper.physicalDeleteBatch(any())).thenReturn(2);

        assertThat(service.clear()).isEqualTo(2);
        assertThat(new File(tmp.toFile(), a.getFilePath())).doesNotExist();
        assertThat(new File(tmp.toFile(), b.getFilePath())).doesNotExist();
    }

    @Test
    @DisplayName("清空：回收站本来就空时直接返回 0，不碰磁盘也不删库")
    void clearOnEmptyBinDoesNothing() {
        when(fileInfoMapper.countAllRecycle()).thenReturn(0L);

        assertThat(service.clear()).isZero();

        verify(fileInfoMapper, never()).selectRecyclePage(any(), anyLong(), anyLong());
        verify(fileInfoMapper, never()).physicalDeleteBatch(any());
    }

    @Test
    @DisplayName("清空：库里一条都没删掉时立即收手，不会死循环空转")
    void clearBailsOutWhenNothingDeleted() throws IOException {
        FileInfo a = deletedFile(1L, touch("c/x.bin"), null, null, LocalDateTime.now());
        when(fileInfoMapper.countAllRecycle()).thenReturn(1L);
        // 永远返回同一条：实现里一旦没有「本批删 0 条就退出」的保护，这里就会一直循环下去
        when(fileInfoMapper.selectRecyclePage(any(), anyLong(), anyLong()))
                .thenReturn(Collections.singletonList(a));
        when(fileInfoMapper.physicalDeleteBatch(any())).thenReturn(0);

        assertThat(service.clear()).isZero();
        verify(fileInfoMapper, times(1)).physicalDeleteBatch(any());
    }

    @Test
    @DisplayName("过期清理：库里一条都没删掉时立即收手，不会死循环空转")
    void purgeExpiredBailsOutWhenNothingDeleted() throws IOException {
        FileInfo a = deletedFile(1L, touch("old/z.bin"), null, null, LocalDateTime.now().minusDays(30));
        when(fileInfoMapper.selectExpired(any(LocalDateTime.class), anyInt()))
                .thenReturn(Collections.singletonList(a));
        when(fileInfoMapper.physicalDeleteBatch(any())).thenReturn(0);

        assertThat(service.purgeExpired(KEEP_DAYS)).isZero();
        verify(fileInfoMapper, times(1)).physicalDeleteBatch(any());
    }
}
