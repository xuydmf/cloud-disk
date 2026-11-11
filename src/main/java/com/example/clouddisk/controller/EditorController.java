package com.example.clouddisk.controller;

import com.example.clouddisk.common.Result;
import com.example.clouddisk.common.WorkbookVO;
import com.example.clouddisk.dto.SheetSaveForm;
import com.example.clouddisk.dto.TextSaveForm;
import com.example.clouddisk.service.SheetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * 在线编辑接口：表格（xlsx/xls）与纯文本。
 * 需要登录（由 AuthInterceptor 统一拦截 /api/**）。
 */
@RestController
@RequestMapping("/api/files")
public class EditorController {

    private final SheetService sheetService;

    public EditorController(SheetService sheetService) {
        this.sheetService = sheetService;
    }

    /** 读取表格为可编辑视图 */
    @GetMapping("/{id}/sheet")
    public Result<WorkbookVO> readSheet(@PathVariable Long id) {
        try {
            return Result.success(sheetService.readWorkbook(id));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 增量保存某个 sheet 的改动，返回重算后的最新视图 */
    @PutMapping("/{id}/sheet")
    public Result<WorkbookVO> saveSheet(@PathVariable Long id, @RequestBody SheetSaveForm form) {
        try {
            return Result.success(sheetService.saveSheet(id, form));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 读取文本文件 */
    @GetMapping("/{id}/text")
    public Result<Map<String, String>> readText(@PathVariable Long id) {
        try {
            return Result.success(Collections.singletonMap("content", sheetService.readText(id)));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 保存文本文件 */
    @PutMapping("/{id}/text")
    public Result<Void> saveText(@PathVariable Long id, @RequestBody TextSaveForm form) {
        try {
            sheetService.saveText(id, form.getContent());
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
