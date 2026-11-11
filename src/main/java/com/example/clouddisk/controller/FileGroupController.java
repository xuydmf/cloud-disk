package com.example.clouddisk.controller;

import com.example.clouddisk.common.GroupVO;
import com.example.clouddisk.common.Result;
import com.example.clouddisk.entity.FileGroup;
import com.example.clouddisk.service.AutoGroupService;
import com.example.clouddisk.service.FileGroupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 文件分组接口
 */
@Slf4j
@RestController
@RequestMapping("/api/groups")
public class FileGroupController {

    @Autowired
    private FileGroupService fileGroupService;

    @Autowired
    private AutoGroupService autoGroupService;

    /** 分组列表（含每个分组的文件数） */
    @GetMapping
    public Result<List<GroupVO>> list() {
        return Result.success(fileGroupService.listGroups());
    }

    /** 新建分组 */
    @PostMapping
    public Result<FileGroup> create(@RequestParam String name) {
        return Result.success(fileGroupService.createGroup(name));
    }

    /** 重命名分组 */
    @PutMapping("/{id}")
    public Result<Void> rename(@PathVariable Long id, @RequestParam String name) {
        fileGroupService.renameGroup(id, name);
        return Result.success();
    }

    /**
     * 删除分组。
     *
     * @param deleteFiles false（默认）= 只删分组，组内文件移到「未分组」；
     *                    true = 同时把组内文件移入回收站
     */
    @DeleteMapping("/{id}")
    public Result<Integer> delete(@PathVariable Long id,
                                  @RequestParam(defaultValue = "false") boolean deleteFiles) {
        return Result.success(fileGroupService.deleteGroup(id, deleteFiles));
    }

    // ==================== AI 识别：分组样貌参考图 ====================

    /**
     * 上传/替换该分组的样貌参考图。
     * AI 自动分组时，会把待识别图片和这张参考图一起交给视觉模型比对。
     */
    @PostMapping("/{id}/ref-image")
    public Result<String> uploadRefImage(@PathVariable Long id,
                                         @RequestParam("file") MultipartFile file) {
        try {
            autoGroupService.saveRefImage(id, file);
            return Result.success("参考图已保存");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.warn("保存分组参考图失败 id={}", id, e);
            return Result.error("保存参考图失败：" + e.getMessage());
        }
    }

    /** 清除参考图（清除后该分组不再参与自动比对） */
    @DeleteMapping("/{id}/ref-image")
    public Result<Void> clearRefImage(@PathVariable Long id) {
        autoGroupService.clearRefImage(id);
        return Result.success();
    }

    /** 查看参考图 */
    @GetMapping("/{id}/ref-image")
    public void refImage(@PathVariable Long id, HttpServletResponse response) throws IOException {
        FileGroup group = fileGroupService.getGroupOrNull(id);
        File file = autoGroupService.resolveRefImage(group);
        if (file == null || !file.isFile()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "该分组未设置参考图");
            return;
        }
        response.setContentType("image/jpeg");
        response.setHeader("Cache-Control", "no-store");
        response.setContentLengthLong(file.length());
        try (InputStream in = new FileInputStream(file);
             OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            out.flush();
        }
    }
}
