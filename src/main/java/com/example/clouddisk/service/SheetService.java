package com.example.clouddisk.service;

import com.example.clouddisk.common.WorkbookVO;
import com.example.clouddisk.dto.SheetSaveForm;

/**
 * 在线编辑：表格（xlsx/xls）与纯文本文件。
 */
public interface SheetService {

    /** 读取表格为可编辑视图（多 sheet） */
    WorkbookVO readWorkbook(Long fileId);

    /** 增量保存某个 sheet 的改动，返回重算后的最新视图 */
    WorkbookVO saveSheet(Long fileId, SheetSaveForm form);

    /** 读取文本文件内容 */
    String readText(Long fileId);

    /** 覆盖保存文本内容 */
    void saveText(Long fileId, String content);
}
