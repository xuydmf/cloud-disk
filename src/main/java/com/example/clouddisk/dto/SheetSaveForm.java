package com.example.clouddisk.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 表格增量保存请求：只提交用户真正改动过的单元格，未改动的由服务端保留原值
 * （这样能规避"显示值被格式化后再反解析"的精度损失）。
 */
@Data
public class SheetSaveForm implements Serializable {

    /** 要保存的工作表名；为空表示第一个 sheet */
    private String sheetName;

    private List<Edit> edits;

    @Data
    public static class Edit implements Serializable {
        private int r;
        private int c;

        /** 新值；null 或空串表示清空该单元格 */
        private String v;

        /**
         * 类型提示：
         * n=按数字写入，b=按布尔写入，s=强制文本，auto/空=由服务端猜
         */
        private String t;
    }
}
