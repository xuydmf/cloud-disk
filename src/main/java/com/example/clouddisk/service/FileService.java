package com.example.clouddisk.service;

import com.example.clouddisk.common.PageResult;
import com.example.clouddisk.entity.FileInfo;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;

/**
 * 文件业务接口
 */
public interface FileService {

    /**
     * 单文件上传
     *
     * @param groupId 上传到的分组，可为空
     * @param capturedTimeMillis 拍摄时间（毫秒时间戳），可为空 —— 相册按日期分组要用
     */
    FileInfo upload(MultipartFile file, Long groupId, Long capturedTimeMillis);

    /** 单文件上传（不带拍摄时间，等价于 capturedTimeMillis=null） */
    default FileInfo upload(MultipartFile file, Long groupId) {
        return upload(file, groupId, null);
    }

    /**
     * 批量上传
     *
     * @param groupId 上传到的分组，可为空
     */
    List<FileInfo> batchUpload(List<MultipartFile> files, Long groupId);

    /**
     * 把已经落在磁盘上的文件登记为一条文件记录（分块上传合并完成后调用）。
     * 内部会把它移动到正式存储目录，并按类型生成缩略图 / 视频封面。
     *
     * @param source       合并完成的临时文件（调用后原文件不再存在）
     * @param originalName 原始文件名
     * @param contentType  浏览器给的类型，可为空（按扩展名推断）
     * @param groupId      分组，可为空
     * @param capturedTimeMillis 拍摄时间（毫秒时间戳），可为空
     */
    FileInfo adoptFile(File source, String originalName, String contentType, Long groupId, Long capturedTimeMillis);

    default FileInfo adoptFile(File source, String originalName, String contentType, Long groupId) {
        return adoptFile(source, originalName, contentType, groupId, null);
    }

    /**
     * 文件分页列表（支持关键字、类型、分组、日期过滤）
     *
     * @param keyword 文件名关键字（模糊）
     * @param type    类型过滤：IMAGE / VIDEO / OTHER / 传 null 或空表示全部
     * @param groupId 分组过滤：null=全部，0=未分组，>0=指定分组
     * @param date    拍摄日期过滤，形如 2026-11-09；null=不限
     * @param page    页码，从 1 开始
     * @param size    每页条数
     */
    PageResult<FileInfo> pageFiles(String keyword, String type, Long groupId, String date, long page, long size);

    default PageResult<FileInfo> pageFiles(String keyword, String type, Long groupId, long page, long size) {
        return pageFiles(keyword, type, groupId, null, page, size);
    }

    /**
     * 按日期聚合：相册日期条用。
     *
     * @return 每项 {day: '2026-11-09', count: 12, size: 3456789}，按日期倒序
     */
    List<java.util.Map<String, Object>> dateStats(String keyword, String type, Long groupId);

    /**
     * 只取 id 列表（过滤条件与分页列表完全一致）。
     *
     * 批量操作要的是「这一天的所有 id」，一次拿全比分页循环快得多。
     *
     * @param limit 上限，防止误操作把整个库勾上
     */
    List<Long> listIds(String keyword, String type, Long groupId, String date, int limit);

    /**
     * 根据 id 获取文件信息（未找到或已删除抛异常）
     */
    FileInfo getById(Long id);

    /**
     * 逻辑删除
     */
    void delete(Long id);

    /**
     * 批量逻辑删除
     *
     * @return 实际删除条数
     */
    int batchDelete(List<Long> ids);

    /**
     * 按 id 批量查询（过滤已删除，并按传入顺序返回）
     */
    List<FileInfo> listByIds(List<Long> ids);

    /**
     * 重命名
     */
    void rename(Long id, String newName);

    /**
     * 解析文件物理路径
     */
    File resolvePhysicalFile(Long id);

    /**
     * 解析缩略图 / 视频封面物理路径（无则返回 null）
     */
    File resolveThumbFile(Long id);

    /**
     * 解析「预览图」物理路径：长边不超过 1920 的 JPEG，按需生成并缓存。
     *
     * 相册弹窗不该直接吃原图——一张 1200 万像素的照片解出来约 36MB 内存，
     * 手机上连翻几张就够让 Safari 悄悄放弃渲染（表现就是"有时候图片打不开"）。
     * 原图本身小于该尺寸、或生成失败时，直接返回原图兜底。
     */
    File resolvePreviewFile(Long id);

    /**
     * 视频转码为 H.264 MP4（按需 + 缓存），不支持/失败时返回 null
     */
    File transcodeToMp4(Long id);

    /**
     * 判断该视频是否需要转码才能被浏览器播放（如 Apple MOV/HEVC）
     */
    boolean needsTranscode(Long id);

    /**
     * 秒传预检：库里是否已经有这份内容。
     *
     * <p>图片按 md5 + 大小比对（客户端压缩后字节稳定，md5 可靠）；
     * 视频按文件名 + 大小比对（iOS 每次导出都会重写元数据，md5 每次都变，见
     * {@link com.example.clouddisk.mapper.FileInfoMapper#selectLiveByNameAndSize}）。
     *
     * @param fileName 原始文件名（客户端即将上传的名字）
     * @param fileSize 即将上传的字节数
     * @param md5      客户端本地算的 md5，视频可以不传
     * @return 命中的已有记录；没有则返回 null
     */
    FileInfo findExisting(String fileName, long fileSize, String md5);
}
