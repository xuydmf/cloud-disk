package com.example.clouddisk.service.impl;

import com.example.clouddisk.common.DuplicateFileException;
import com.example.clouddisk.entity.FileInfo;
import com.example.clouddisk.mapper.FileInfoMapper;
import com.example.clouddisk.service.AutoGroupService;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重复文件校验：同一份内容只允许存在一条记录。
 *
 * 这里盯住三件事：
 * 1. 命中重复 → 抛 {@link DuplicateFileException}，且**不写库**；
 * 2. 命中重复 → 刚落盘的那份（手机一次多选里的重复项）必须被删掉，不能留垃圾；
 * 3. 回收站里的同名内容不算重复（用户删了再传一遍是合理诉求，由 SQL 的 deleted=0 保证）。
 */
class FileServiceImplDuplicateTest {

    @TempDir
    Path tmp;

    private FileInfoMapper fileInfoMapper;
    private FileServiceImpl service;

    @BeforeEach
    void setUp() {
        fileInfoMapper = mock(FileInfoMapper.class);
        AutoGroupService autoGroupService = mock(AutoGroupService.class);
        service = new FileServiceImpl(fileInfoMapper, autoGroupService);
        ReflectionTestUtils.setField(service, "storageRoot", tmp.toString());
        // @Value 注入的字段在纯单元测试里是 null，用反射补上默认值
        ReflectionTestUtils.setField(service, "ffmpegPath", "ffmpeg-not-exists");
        ReflectionTestUtils.setField(service, "forceTranscode", false);
    }

    @Test
    @DisplayName("内容重复：抛出 DuplicateFileException，不落库，也不留物理文件")
    void duplicateContentIsRejectedAndCleanedUp() throws Exception {
        FileInfo exist = new FileInfo();
        exist.setId(7L);
        exist.setFileName("IMG_0001.jpg");
        when(fileInfoMapper.selectLiveByMd5(anyString(), anyLong())).thenReturn(exist);

        MockMultipartFile file = new MockMultipartFile(
                "file", "IMG_0001-copy.jpg", "image/jpeg", tinyJpeg());

        assertThatThrownBy(() -> service.upload(file, null))
                .isInstanceOf(DuplicateFileException.class)
                .hasMessageContaining("IMG_0001.jpg");

        verify(fileInfoMapper, never()).insert(any(FileInfo.class));
        // 只有目录结构，不应残留任何文件
        assertThat(regularFiles(tmp)).isEmpty();
    }

    @Test
    @DisplayName("首次上传：查重落空 → 正常落库，且带上 md5")
    void freshContentIsStored() throws Exception {
        when(fileInfoMapper.selectLiveByMd5(anyString(), anyLong())).thenReturn(null);

        MockMultipartFile file = new MockMultipartFile(
                "file", "IMG_0002.jpg", "image/jpeg", tinyJpeg());

        FileInfo saved = service.upload(file, null);

        ArgumentCaptor<FileInfo> captor = ArgumentCaptor.forClass(FileInfo.class);
        verify(fileInfoMapper).insert(captor.capture());
        FileInfo inserted = captor.getValue();

        // id 由数据库回填，单元测试里为 null，所以直接看实体
        assertThat(inserted.getFileName()).isEqualTo("IMG_0002.jpg");
        assertThat(inserted.getMd5()).hasSize(32);
        assertThat(saved.getFileSize()).isEqualTo(file.getSize());
        // 物理文件应该还在
        assertThat(regularFiles(tmp)).hasSize(1);
    }

    @Test
    @DisplayName("同样内容传两次：第二次被拒，磁盘上只留一份")
    void secondUploadOfSameBytesIsRejected() throws Exception {
        byte[] bytes = tinyJpeg();

        when(fileInfoMapper.selectLiveByMd5(anyString(), anyLong())).thenReturn(null);
        service.upload(new MockMultipartFile("file", "a.jpg", "image/jpeg", bytes), null);
        assertThat(regularFiles(tmp)).hasSize(1);

        // 第二次：库里已经有同一份内容
        FileInfo exist = new FileInfo();
        exist.setId(99L);
        exist.setFileName("a.jpg");
        when(fileInfoMapper.selectLiveByMd5(anyString(), anyLong())).thenReturn(exist);

        assertThatThrownBy(() ->
                service.upload(new MockMultipartFile("file", "a-copy.jpg", "image/jpeg", bytes), null))
                .isInstanceOf(DuplicateFileException.class);

        assertThat(regularFiles(tmp)).hasSize(1);
    }

    /** 1×1 的合法 JPEG，够 ImageIO 识别，也够算 md5 */
    private static byte[] tinyJpeg() {
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46,
                0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xDB, 0x00, 0x43, 0x00, 0x08, 0x06, 0x06, 0x07, 0x06,
                0x05, 0x08, 0x07, 0x07, 0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C, 0x14, 0x0D,
                0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F,
                0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C,
                0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C, 0x30, 0x31, 0x34, 0x34, 0x34,
                0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
                (byte) 0xFF, (byte) 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01,
                0x01, 0x11, 0x00,
                (byte) 0xFF, (byte) 0xC4, 0x00, 0x1F, 0x00, 0x00, 0x01, 0x05, 0x01, 0x01,
                0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
                (byte) 0xFF, (byte) 0xC4, 0x00, 0x14, 0x10, 0x01, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00,
                (byte) 0x37, (byte) 0xFF, (byte) 0xD9
        };
    }

    private static java.util.List<File> regularFiles(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            java.util.List<File> files = new java.util.ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(p -> files.add(p.toFile()));
            return files;
        }
    }
}
