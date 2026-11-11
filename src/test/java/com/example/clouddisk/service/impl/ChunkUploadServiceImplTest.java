package com.example.clouddisk.service.impl;

import com.example.clouddisk.dto.ChunkInitForm;
import com.example.clouddisk.entity.FileInfo;
import com.example.clouddisk.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分块上传单元测试：不启动 Spring 容器、不连数据库，直接测合并 / 续传 / 校验逻辑。
 *
 * 分块上传最容易出问题的地方是「合并错位」「断点续传命中不了」「路径穿越」，
 * 这三类都能在这里精确断言，比跑一遍完整应用更快也更稳。
 */
class ChunkUploadServiceImplTest {

    private static final int MB = 1024 * 1024;

    @TempDir
    Path tmp;

    private FileService fileService;
    private ChunkUploadServiceImpl service;

    /** 被测内容：3MB + 777 字节，除不尽，正好覆盖「末片更短」 */
    private byte[] payload;

    @BeforeEach
    void setUp() {
        payload = new byte[3 * MB + 777];
        new Random(20261029L).nextBytes(payload);

        fileService = mock(FileService.class);
        service = new ChunkUploadServiceImpl(fileService);
        ReflectionTestUtils.setField(service, "storageRoot", tmp.toString());
        ReflectionTestUtils.setField(service, "expireHours", 48L);
        service.init();
    }

    // ==================== 正常流程 ====================

    @Test
    @DisplayName("乱序上传四个分片，合并后内容与原始文件完全一致")
    void outOfOrderChunksMergeIntoSameBytes() {
        Map<String, Object> init = service.init(form("demo.bin", payload.length, MB, "group-key-1"));
        String uploadId = (String) init.get("uploadId");

        assertThat(uploadId).matches("^[a-f0-9]{32}$");
        assertThat(init.get("received")).isEqualTo(Collections.emptyList());
        assertThat(init.get("totalChunks")).isEqualTo(4);

        // 乱序：2、0、3、1
        for (int index : new int[]{2, 0, 3, 1}) {
            service.saveChunk(uploadId, index, part(index));
        }

        Map<String, Object> status = service.status(uploadId);
        assertThat(status.get("received")).isEqualTo(Arrays.asList(0, 1, 2, 3));
        assertThat(status.get("completed")).isEqualTo(true);

        FileInfo merged = complete(uploadId);
        assertThat(merged.getFileSize()).isEqualTo((long) payload.length);
        assertThat(merged.getMd5()).isEqualTo(md5(payload));
        assertThat(merged.getFileName()).isEqualTo("demo.bin");

        // 分块目录用完即删，不占空间
        assertThat(new File(tmp.toFile(), ".chunks/" + uploadId)).doesNotExist();
    }

    @Test
    @DisplayName("重复上传同一分片是幂等的，不会打乱已收列表")
    void reuploadSameChunkIsIdempotent() {
        String uploadId = (String) service.init(form("dup.bin", payload.length, MB, "dup-key")).get("uploadId");

        service.saveChunk(uploadId, 0, part(0));
        service.saveChunk(uploadId, 0, part(0));
        Map<String, Object> second = service.saveChunk(uploadId, 1, part(1));

        assertThat(second.get("received")).isEqualTo(2);
        assertThat(service.status(uploadId).get("received")).isEqualTo(Arrays.asList(0, 1));
    }

    @Test
    @DisplayName("未传完时 complete 被拒绝，补传后可正常完成")
    void completeRejectsUntilAllChunksArrive() {
        String uploadId = (String) service.init(form("half.bin", payload.length, MB, "half-key")).get("uploadId");
        service.saveChunk(uploadId, 0, part(0));
        service.saveChunk(uploadId, 1, part(1));

        assertThatThrownBy(() -> service.complete(uploadId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分片未传完");

        service.saveChunk(uploadId, 2, part(2));
        service.saveChunk(uploadId, 3, part(3));
        assertThat(complete(uploadId).getMd5()).isEqualTo(md5(payload));
    }

    @Test
    @DisplayName("同一 fileKey 重复 init 命中同一 uploadId，并返回已收分片")
    void resumeKeepsReceivedChunks() {
        ChunkInitForm f = form("resume.bin", payload.length, MB, "resume-key");
        String uploadId = (String) service.init(f).get("uploadId");
        service.saveChunk(uploadId, 0, part(0));
        service.saveChunk(uploadId, 1, part(1));

        // 模拟刷新页面后重新选择同一个文件
        Map<String, Object> again = service.init(form("resume.bin", payload.length, MB, "resume-key"));

        assertThat(again.get("uploadId")).isEqualTo(uploadId);
        assertThat(again.get("received")).isEqualTo(Arrays.asList(0, 1));

        service.saveChunk(uploadId, 2, part(2));
        service.saveChunk(uploadId, 3, part(3));
        assertThat(complete(uploadId).getMd5()).isEqualTo(md5(payload));
    }

    @Test
    @DisplayName("同一 fileKey 但文件大小变了，旧分片作废重来")
    void changedFileResetChunkDir() {
        String first = (String) service.init(form("same.bin", payload.length, MB, "same-key")).get("uploadId");
        service.saveChunk(first, 0, new MockMultipartFile("chunk", "0.part", null, new byte[MB]));

        // 文件被改过（大小不同）→ 分片数也不同
        Map<String, Object> second = service.init(form("same.bin", 2 * MB, MB, "same-key"));

        assertThat(second.get("uploadId")).isEqualTo(first);
        assertThat(second.get("received")).isEqualTo(Collections.emptyList());
    }

    @Test
    @DisplayName("分组 id 会随最后一次 init 更新到落库参数里")
    void groupIdFollowsLatestInit() {
        String uploadId = (String) service.init(form("g.bin", payload.length, MB, "g-key", 7L)).get("uploadId");
        service.init(form("g.bin", payload.length, MB, "g-key", 9L));

        for (int i = 0; i < 4; i++) {
            service.saveChunk(uploadId, i, part(i));
        }
        complete(uploadId);

        ArgumentCaptor<Long> groupId = ArgumentCaptor.forClass(Long.class);
        verify(fileService).adoptFile(any(File.class), anyString(), any(), groupId.capture());
        assertThat(groupId.getValue()).isEqualTo(9L);
    }

    @Test
    @DisplayName("abort 清掉分块目录，之后 status 提示已过期")
    void abortRemovesChunkDir() {
        String uploadId = (String) service.init(form("abort.bin", payload.length, MB, "abort-key")).get("uploadId");
        service.saveChunk(uploadId, 0, part(0));
        assertThat(new File(tmp.toFile(), ".chunks/" + uploadId)).isDirectory();

        service.abort(uploadId);

        assertThat(new File(tmp.toFile(), ".chunks/" + uploadId)).doesNotExist();
        assertThatThrownBy(() -> service.status(uploadId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已过期");
    }

    // ==================== 参数与安全校验 ====================

    @Test
    @DisplayName("非法参数在 init 阶段就被挡住")
    void initValidatesParams() {
        assertThatThrownBy(() -> service.init(null)).hasMessageContaining("参数缺失");
        assertThatThrownBy(() -> service.init(form(" ", 100, MB, "k"))).hasMessageContaining("文件名");
        assertThatThrownBy(() -> service.init(form("a.bin", 0, MB, "k"))).hasMessageContaining("大小不合法");
        assertThatThrownBy(() -> service.init(form("a.bin", 21L * 1024 * MB, MB, "k")))
                .hasMessageContaining("20 GB");
        assertThatThrownBy(() -> service.init(form("a.bin", 100, 0, "k"))).hasMessageContaining("分片大小");
        assertThatThrownBy(() -> service.init(form("a.bin", 100, 128 * MB, "k"))).hasMessageContaining("分片大小");

        // 分片数写成 3，但 100 字节按 1MB 切只能是 1 片
        ChunkInitForm bad = form("a.bin", 100, MB, "k");
        bad.setTotalChunks(3);
        assertThatThrownBy(() -> service.init(bad)).hasMessageContaining("分片数量");
    }

    @Test
    @DisplayName("分片大小不符、下标越界、空分片都被拒绝")
    void saveChunkValidatesEachPart() {
        String uploadId = (String) service.init(form("v.bin", payload.length, MB, "v-key")).get("uploadId");

        // 非末片必须正好 chunkSize
        assertThatThrownBy(() -> service.saveChunk(uploadId, 0,
                new MockMultipartFile("chunk", "0.part", null, new byte[MB - 1])))
                .hasMessageContaining("分片大小不符");

        // 末片允许更短，但必须正好是剩余字节（777）
        assertThatThrownBy(() -> service.saveChunk(uploadId, 3,
                new MockMultipartFile("chunk", "3.part", null, new byte[1000])))
                .hasMessageContaining("分片大小不符");
        service.saveChunk(uploadId, 3, part(3));

        assertThatThrownBy(() -> service.saveChunk(uploadId, 4,
                new MockMultipartFile("chunk", "4.part", null, new byte[10])))
                .hasMessageContaining("越界");
        assertThatThrownBy(() -> service.saveChunk(uploadId, -1,
                new MockMultipartFile("chunk", "x.part", null, new byte[10])))
                .hasMessageContaining("越界");
        assertThatThrownBy(() -> service.saveChunk(uploadId, 1,
                new MockMultipartFile("chunk", "1.part", null, new byte[0])))
                .hasMessageContaining("内容为空");
    }

    @Test
    @DisplayName("uploadId 只接受 32 位 hex，挡住路径穿越")
    void uploadIdRejectsPathTraversal() {
        String zzz = "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz";
        for (String bad : new String[]{"../etc/passwd", "..%2F..%2Fetc", "", "ABC", zzz, null}) {
            assertThatThrownBy(() -> service.status(bad))
                    .as("uploadId=%s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.abort("../../tmp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法的上传标识");
    }

    @Test
    @DisplayName("不存在的 uploadId 给出可读提示")
    void unknownUploadIdGivesFriendlyError() {
        assertThatThrownBy(() -> service.status("0123456789abcdef0123456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或已过期");
    }

    // ==================== 内部工具 ====================

    private ChunkInitForm form(String name, long size, int chunkSize, String key) {
        return form(name, size, chunkSize, key, null);
    }

    private ChunkInitForm form(String name, long size, int chunkSize, String key, Long groupId) {
        ChunkInitForm f = new ChunkInitForm();
        f.setFileName(name);
        f.setFileSize(size);
        f.setChunkSize(chunkSize);
        // chunkSize 非法时不去算分片数，交给被测代码先报「分片大小不合法」
        f.setTotalChunks(chunkSize > 0 ? (int) ((size + chunkSize - 1) / chunkSize) : 1);
        f.setFileKey(key);
        f.setContentType("application/octet-stream");
        f.setGroupId(groupId);
        return f;
    }

    /** 取出第 index 片（最后一片可能不足 chunkSize） */
    private MockMultipartFile part(int index) {
        int from = index * MB;
        int to = Math.min(payload.length, from + MB);
        return new MockMultipartFile("chunk", index + ".part", "application/octet-stream",
                Arrays.copyOfRange(payload, from, to));
    }

    /** 走 complete，并在 adoptFile 被调用的瞬间校验合并文件的真实内容 */
    private FileInfo complete(String uploadId) {
        when(fileService.adoptFile(any(File.class), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    File source = (File) inv.getArgument(0);
                    FileInfo info = new FileInfo();
                    info.setId(1L);
                    info.setFileName(inv.getArgument(1));
                    info.setContentType(inv.getArgument(2));
                    info.setGroupId(inv.getArgument(3));
                    info.setFileSize(source.length());
                    info.setMd5(md5(Files.readAllBytes(source.toPath())));
                    return info;
                });
        return service.complete(uploadId);
    }

    private static String md5(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
