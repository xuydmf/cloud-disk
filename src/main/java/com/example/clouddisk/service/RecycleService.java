package com.example.clouddisk.service;

import com.example.clouddisk.common.PageResult;
import com.example.clouddisk.common.RecycleItemVO;
import com.example.clouddisk.entity.FileInfo;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 回收站业务。
 *
 * <p>删除本身还是「逻辑删除」，只是删除时多记一个时间戳；
 * 本服务的职责是：把回收站里的东西列出来、还原回去、或者彻底删掉（含物理文件）。
 */
public interface RecycleService {

    /** 回收站分页列表（按删除时间倒序） */
    PageResult<RecycleItemVO> page(String keyword, long page, long size);

    /** 回收站概况：{count, totalSize, keepDays} */
    Map<String, Object> stats();

    /**
     * 从回收站恢复。
     *
     * @return 实际恢复条数
     */
    int restore(List<Long> ids);

    /**
     * 彻底删除（不可恢复）：同时删掉物理文件、缩略图、视频转码缓存和数据库记录。
     *
     * @return 实际删除条数
     */
    int purge(List<Long> ids);

    /** 清空回收站（彻底删除全部），返回删除条数 */
    int clear();

    /**
     * 清理超过保留天数的文件（定时任务与启动时调用）。
     *
     * @param keepDays 保留天数，传 <=0 时使用配置值
     * @return 本次彻底删除的条数
     */
    int purgeExpired(int keepDays);

    /**
     * 取回收站里的一条记录（不做逻辑删除过滤）。
     *
     * @throws IllegalArgumentException 记录不存在或不在回收站
     */
    FileInfo getRecycled(Long id);

    /**
     * 定位回收站文件的物理文件（原文件）。
     *
     * @throws IllegalArgumentException 记录不存在、不在回收站，或物理文件已丢失
     */
    File resolveRecycledFile(Long id);

    /**
     * 定位回收站文件的缩略图 / 视频封面，没有则返回 null。
     */
    File resolveRecycledThumb(Long id);
}
