package com.example.clouddisk.common;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 判断哪些文件可以在线编辑，以及用哪种编辑器。
 */
public final class EditType {

    /** 表格：用 POI 读写。刻意不含 xlsm —— 写回会丢 VBA 宏 */
    private static final Set<String> SHEET = new HashSet<>(Arrays.asList("xlsx", "xls"));

    /** 纯文本：用文本编辑器，全部按 UTF-8 处理 */
    private static final Set<String> TEXT = new HashSet<>(Arrays.asList(
            "txt", "md", "markdown", "log", "csv", "tsv",
            "json", "xml", "yml", "yaml", "ini", "conf", "cfg", "properties", "toml", "env",
            "sql", "java", "js", "mjs", "ts", "jsx", "tsx", "vue",
            "css", "scss", "less", "html", "htm",
            "py", "sh", "bat", "ps1", "go", "rs", "c", "h", "cpp", "php", "rb"
    ));

    private EditType() {
    }

    public static String ext(String fileName) {
        if (fileName == null) {
            return "";
        }
        int i = fileName.lastIndexOf('.');
        if (i < 0 || i == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** SHEET / TEXT / 空字符串（不支持编辑） */
    public static String modeOf(String fileName) {
        String e = ext(fileName);
        if (SHEET.contains(e)) {
            return "SHEET";
        }
        if (TEXT.contains(e)) {
            return "TEXT";
        }
        return "";
    }

    public static boolean isSheet(String fileName) {
        return "SHEET".equals(modeOf(fileName));
    }

    public static boolean isText(String fileName) {
        return "TEXT".equals(modeOf(fileName));
    }
}
