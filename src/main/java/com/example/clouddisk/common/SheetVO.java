package com.example.clouddisk.common;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 单个工作表的可编辑视图
 */
@Data
public class SheetVO implements Serializable {

    private String name;

    /** 返回给前端的行数（含尾部空行，便于编辑时空行也能点） */
    private int rows;

    /** 返回给前端的列数 */
    private int cols;

    /** 稀疏单元格列表 */
    private List<CellVO> cells;

    /** 合并区域：[row1, col1, row2, col2] */
    private List<int[]> merges;

    /** 列宽，单位 px，key=列下标 */
    private List<Integer> colWidths;

    /** 行高，单位 px，key=行下标；缺省用前端默认行高 */
    private List<Integer> rowHeights;

    /** 冻结行数 / 列数 */
    private int frozenRows;
    private int frozenCols;
}
