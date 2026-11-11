package com.example.clouddisk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.clouddisk.common.GroupVO;
import com.example.clouddisk.entity.FileGroup;
import com.example.clouddisk.entity.FileInfo;
import com.example.clouddisk.mapper.FileGroupMapper;
import com.example.clouddisk.mapper.FileInfoMapper;
import com.example.clouddisk.service.AutoGroupService;
import com.example.clouddisk.service.FileGroupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class FileGroupServiceImpl implements FileGroupService {

    private final FileGroupMapper groupMapper;
    private final FileInfoMapper fileInfoMapper;
    private final AutoGroupService autoGroupService;

    public FileGroupServiceImpl(FileGroupMapper groupMapper, FileInfoMapper fileInfoMapper,
                                AutoGroupService autoGroupService) {
        this.groupMapper = groupMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.autoGroupService = autoGroupService;
    }

    @Override
    public List<GroupVO> listGroups() {
        LambdaQueryWrapper<FileGroup> qw = new LambdaQueryWrapper<>();
        qw.eq(FileGroup::getDeleted, 0)
                .orderByAsc(FileGroup::getSort)
                .orderByAsc(FileGroup::getId);
        List<FileGroup> groups = groupMapper.selectList(qw);
        List<GroupVO> result = new ArrayList<>();
        for (FileGroup g : groups) {
            GroupVO vo = new GroupVO();
            vo.setId(g.getId());
            vo.setName(g.getName());
            vo.setFileCount(fileInfoMapper.selectCount(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getDeleted, 0)
                    .eq(FileInfo::getGroupId, g.getId())));
            vo.setHasRefImage(g.getRefImagePath() != null && !g.getRefImagePath().trim().isEmpty());
            result.add(vo);
        }
        return result;
    }

    @Override
    public FileGroup createGroup(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("分组名称不能为空");
        }
        FileGroup g = new FileGroup();
        g.setName(name.trim());
        g.setSort(0);
        g.setDeleted(0);
        groupMapper.insert(g);
        return g;
    }

    @Override
    public void renameGroup(Long id, String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("分组名称不能为空");
        }
        FileGroup g = getGroup(id);
        g.setName(name.trim());
        groupMapper.updateById(g);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteGroup(Long id, boolean deleteFiles) {
        getGroup(id);
        // 分组没了，它的样貌参考图也没有意义，顺手清掉磁盘文件
        autoGroupService.purgeRefImageQuietly(id);
        int affected;
        if (deleteFiles) {
            // 用户明确选择「同时删除组内文件」：走与单文件删除一致的软删除，
            // 文件进回收站而不是直接抹掉磁盘，误删还能 7 天内恢复
            List<FileInfo> members = fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getGroupId, id)
                    .eq(FileInfo::getDeleted, 0));
            if (!members.isEmpty()) {
                LocalDateTime now = LocalDateTime.now();
                for (FileInfo f : members) {
                    fileInfoMapper.moveToRecycle(f.getId(), now);
                }
            }
            affected = members.size();
            log.info("删除分组 {}：组内 {} 个文件已移入回收站", id, affected);
        } else {
            // 只删分组：组内文件移到未分组（group_id 置空必须用 UpdateWrapper，updateById 会忽略 null）
            affected = fileInfoMapper.update(null, new LambdaUpdateWrapper<FileInfo>()
                    .eq(FileInfo::getGroupId, id)
                    .eq(FileInfo::getDeleted, 0)
                    .set(FileInfo::getGroupId, null));
            log.info("删除分组 {}：{} 个文件已移到未分组", id, affected);
        }
        // 逻辑删除：MyBatis-Plus 会自动把 deleteById 改写为 UPDATE ... SET deleted=1
        groupMapper.deleteById(id);
        return affected;
    }

    @Override
    public void assignGroup(Long fileId, Long groupId) {
        FileInfo info = fileInfoMapper.selectById(fileId);
        if (info == null || (info.getDeleted() != null && info.getDeleted() == 1)) {
            throw new IllegalArgumentException("文件不存在或已删除");
        }
        if (groupId == null || groupId <= 0) {
            // 移出分组：置空
            fileInfoMapper.update(null, new LambdaUpdateWrapper<FileInfo>()
                    .eq(FileInfo::getId, fileId)
                    .set(FileInfo::getGroupId, null));
            return;
        }
        getGroup(groupId);
        FileInfo update = new FileInfo();
        update.setId(fileId);
        update.setGroupId(groupId);
        fileInfoMapper.updateById(update);
    }

    @Override
    public int batchAssignGroup(List<Long> ids, Long groupId) {
        List<Long> safeIds = normalizeIds(ids);
        boolean toNone = (groupId == null || groupId <= 0);
        if (!toNone) {
            // 分组必须存在，避免把文件挂到不存在的分组上
            getGroup(groupId);
        }
        LambdaUpdateWrapper<FileInfo> uw = new LambdaUpdateWrapper<FileInfo>()
                .in(FileInfo::getId, safeIds)
                .eq(FileInfo::getDeleted, 0);
        // 置空 group_id 必须走 UpdateWrapper，updateById 会忽略 null
        uw.set(FileInfo::getGroupId, toNone ? null : groupId);
        return fileInfoMapper.update(null, uw);
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
    public FileGroup getGroupOrNull(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        FileGroup g = groupMapper.selectById(id);
        if (g == null || (g.getDeleted() != null && g.getDeleted() == 1)) {
            return null;
        }
        return g;
    }

    private FileGroup getGroup(Long id) {
        FileGroup g = groupMapper.selectById(id);
        if (g == null || (g.getDeleted() != null && g.getDeleted() == 1)) {
            throw new IllegalArgumentException("分组不存在");
        }
        return g;
    }
}
