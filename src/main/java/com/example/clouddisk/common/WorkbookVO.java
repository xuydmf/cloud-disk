package com.example.clouddisk.common;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 整份表格的编辑视图
 */
@Data
public class WorkbookVO implements Serializable {

    private Long fileId;
    private String fileName;

    /** 是否因为文件太大被截断（只返回前 N 行 N 列） */
    private boolean truncated;

    /** 提示信息，例如「文件较大，仅显示前 600 行」 */
    private String notice;

    private List<SheetVO> sheets;
}
