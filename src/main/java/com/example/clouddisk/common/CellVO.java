package com.example.clouddisk.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 表格单元格（稀疏返回，只带非空 / 有样式的格子）。
 * 字段名刻意用短名，减少整页表格的 JSON 体积。
 */
@Data
public class CellVO implements Serializable {

    /** 行下标，0 基 */
    private int r;

    /** 列下标，0 基 */
    private int c;

    /** 显示值：数值/日期已按单元格格式排版；公式单元格放的是计算结果 */
    private String v;

    /** 类型：s=文本 n=数字 b=布尔 e=错误 d=日期 */
    private String t;

    /** 公式原文（不含前导 =），非公式单元格为 null */
    private String f;

    /** 数字格式，如 0.00% / yyyy-mm-dd，用于前端提示 */
    private String fmt;

    /** 加粗 */
    private boolean b;

    /** 背景色 #RRGGBB，无填充为 null */
    private String bg;

    /** 字体色 #RRGGBB */
    private String fg;

    /** 水平对齐 left / center / right，未设置（跟随类型）为 null */
    private String align;
}
