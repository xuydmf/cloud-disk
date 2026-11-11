package com.example.clouddisk.common;

import com.example.clouddisk.entity.FileInfo;
import lombok.Data;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 回收站列表项。
 *
 * <p>比 {@link FileInfo} 多出前端真正需要的三个字段：
 * 原分组名（分组可能已被删掉，所以留个兜底文案）、剩余保留天数、以及是否可以恢复。
 */
@Data
public class RecycleItemVO implements Serializable {

    private Long id;
    private String fileName;
    private Long fileSize;
    private String contentType;
    private String fileType;

    /** 缩略图相对路径，没生成过就是 null（前端据此决定是否请求 /thumb） */
    private String thumbPath;

    /** 原分组 id，NULL = 原本未分组 */
    private Long groupId;

    /** 原分组名；分组已不存在时显示「未分组」 */
    private String groupName;

    /** 进入回收站的时间 */
    private LocalDateTime deletedTime;

    /** 上传时间（回收站里也一并展示，方便辨认） */
    private LocalDateTime createTime;

    /** 距离彻底删除还剩几天（0 = 今天就会被清掉） */
    private long remainDays;

    /**
     * 由数据库实体 + 分组名组装。
     *
     * @param info      已删除的文件记录
     * @param groupName 原分组名，可为 null
     * @param keepDays  回收站保留天数
     * @param now       当前时间（统一传入，避免一次批量拼装里时间不一致）
     */
    public static RecycleItemVO of(FileInfo info, String groupName, int keepDays, LocalDateTime now) {
        RecycleItemVO vo = new RecycleItemVO();
        vo.id = info.getId();
        vo.fileName = info.getFileName();
        vo.fileSize = info.getFileSize();
        vo.contentType = info.getContentType();
        vo.fileType = info.getFileType();
        vo.thumbPath = info.getThumbPath();
        vo.groupId = info.getGroupId();
        vo.groupName = (info.getGroupId() == null || groupName == null || groupName.isEmpty())
                ? "未分组" : groupName;
        vo.deletedTime = info.getDeletedTime();
        vo.createTime = info.getCreateTime();
        vo.remainDays = remainDays(info.getDeletedTime(), keepDays, now);
        return vo;
    }

    /**
     * 剩余天数按「向上取整」算：删了 6 天零 5 小时，还剩 0 天多 → 显示 1 天，
     * 而不是四舍五入成 0 让人误以为已经没了。
     */
    private static long remainDays(LocalDateTime deletedTime, int keepDays, LocalDateTime now) {
        if (deletedTime == null) {
            // 历史数据没回填到时间的，按满期算，让它尽快被清理
            return 0;
        }
        long usedSeconds = Duration.between(deletedTime, now).getSeconds();
        long remainSeconds = keepDays * 86400L - usedSeconds;
        if (remainSeconds <= 0) {
            return 0;
        }
        return (remainSeconds + 86399) / 86400;
    }
}
