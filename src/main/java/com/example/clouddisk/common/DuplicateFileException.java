package com.example.clouddisk.common;

/**
 * 重复文件：库里已经有一份内容完全相同的文件（md5 + 大小一致）。
 *
 * <p>上传流程在落库前做校验，命中就抛这个异常，把「已存在的那份」告诉调用方，
 * 前端据此把它们算作「跳过」而不是「上传失败」。
 */
public class DuplicateFileException extends RuntimeException {

    /** 已存在文件的 id */
    private final Long existingId;

    /** 已存在文件的展示名，方便前端提示「与 xxx 相同」 */
    private final String existingName;

    public DuplicateFileException(Long existingId, String existingName, String incomingName) {
        super("文件已存在：" + (existingName == null ? incomingName : existingName));
        this.existingId = existingId;
        this.existingName = existingName == null ? incomingName : existingName;
    }

    public Long getExistingId() {
        return existingId;
    }

    public String getExistingName() {
        return existingName;
    }
}
