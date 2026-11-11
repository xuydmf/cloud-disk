package com.example.clouddisk.service;

import com.example.clouddisk.common.GroupVO;
import com.example.clouddisk.entity.FileGroup;

import java.util.List;

/**
 * 文件分组业务
 */
public interface FileGroupService {

    /** 分组列表（含文件数量） */
    List<GroupVO> listGroups();

    /** 新建分组 */
    FileGroup createGroup(String name);

    /** 重命名分组 */
    void renameGroup(Long id, String name);

    /**
     * 删除分组。
     *
     * @param deleteFiles true = 同时把组内文件移入回收站；
     *                    false = 只删分组，组内文件移到「未分组」
     * @return 受影响的文件数（移入回收站或移出分组的条数）
     */
    int deleteGroup(Long id, boolean deleteFiles);

    /** 把文件分配到分组；groupId 为空或 <=0 表示移出到未分组 */
    void assignGroup(Long fileId, Long groupId);

    /**
     * 批量把文件分配到分组；groupId 为空或 <=0 表示移出到未分组
     *
     * @return 实际更新条数
     */
    int batchAssignGroup(List<Long> ids, Long groupId);

    /** 按 id 取分组，不存在或已删除返回 null（不抛异常） */
    FileGroup getGroupOrNull(Long id);
}
