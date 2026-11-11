package com.example.clouddisk.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 分组展示对象（含文件数量）
 */
@Data
public class GroupVO implements Serializable {
    private Long id;
    private String name;
    private long fileCount;

    /** 是否已设置 AI 识别用的样貌参考图 */
    private boolean hasRefImage;
}
