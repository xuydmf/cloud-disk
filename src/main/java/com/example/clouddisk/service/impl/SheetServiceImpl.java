package com.example.clouddisk.service.impl;

import com.example.clouddisk.common.CellVO;
import com.example.clouddisk.common.EditType;
import com.example.clouddisk.common.SheetVO;
import com.example.clouddisk.common.WorkbookVO;
import com.example.clouddisk.dto.SheetSaveForm;
import com.example.clouddisk.entity.FileInfo;
import com.example.clouddisk.mapper.FileInfoMapper;
import com.example.clouddisk.service.FileService;
import com.example.clouddisk.service.SheetService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.PaneInformation;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 表格 / 文本文件的在线编辑实现。
 *
 * 设计要点：
 * 1. 读取时把公式求值成显示值，同时带回公式原文，前端聚焦时显示公式、失焦时显示结果。
 * 2. 保存只提交「用户真正改过的格子」（增量），避免"显示值再反解析"造成精度损失。
 * 3. 写回前先把原文件备份到 .history/{id}/，最多留 5 份，误操作可找回。
 * 4. 写回后置 forceFormulaRecalculation，Excel 打开时重算；接口同时返回重算后的视图。
 */
@Slf4j
@Service
public class SheetServiceImpl implements SheetService {

    /** 单次最多返回的行列，超出则截断并提示（浏览器渲染 td 的成本很高） */
    private static final int MAX_ROWS = 600;
    private static final int MAX_COLS = 40;

    /** 读取时尾部多留的空行，方便用户往下补内容 */
    private static final int TAIL_ROWS = 20;

    /** 文本文件上限 */
    private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;

    /** 判断"看起来像数字"：用于空单元格的自动类型推断 */
    private static final Pattern NUMERIC = Pattern.compile("^-?(\\d{1,15})(\\.\\d+)?$|^-?\\.\\d+$");

    private final FileService fileService;
    private final FileInfoMapper fileInfoMapper;

    @Value("${file.storage.root}")
    private String storageRoot;

    public SheetServiceImpl(FileService fileService, FileInfoMapper fileInfoMapper) {
        this.fileService = fileService;
        this.fileInfoMapper = fileInfoMapper;
    }

    // ==================== 表格：读取 ====================

    @Override
    public WorkbookVO readWorkbook(Long fileId) {
        FileInfo info = requireSheet(fileId);
        File file = fileService.resolvePhysicalFile(fileId);

        try (Workbook wb = WorkbookFactory.create(file)) {
            return buildView(info, wb);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("读取表格失败 id={} {}", fileId, e.getMessage());
            throw new IllegalArgumentException("表格解析失败：" + e.getMessage());
        }
    }

    private WorkbookVO buildView(FileInfo info, Workbook wb) {
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();

        WorkbookVO vo = new WorkbookVO();
        vo.setFileId(info.getId());
        vo.setFileName(info.getFileName());

        List<SheetVO> sheets = new ArrayList<>();
        boolean truncated = false;
        StringBuilder notice = new StringBuilder();

        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet sheet = wb.getSheetAt(i);
            if (sheet == null) {
                continue;
            }
            SheetVO sv = readSheet(sheet, formatter, evaluator, wb);
            // 统计是否被截断
            int realRows = sheet.getLastRowNum() + 1;
            int realCols = 0;
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row != null) {
                    realCols = Math.max(realCols, row.getLastCellNum());
                }
            }
            if (realRows > MAX_ROWS || realCols > MAX_COLS) {
                truncated = true;
            }
            sheets.add(sv);
        }

        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("这个文件里没有可显示的工作表");
        }
        if (truncated) {
            notice.append("文件较大，仅显示前 ").append(MAX_ROWS)
                  .append(" 行 × ").append(MAX_COLS)
                  .append(" 列；完整内容请下载后用 Excel 打开。");
        }
        vo.setSheets(sheets);
        vo.setTruncated(truncated);
        vo.setNotice(notice.length() == 0 ? null : notice.toString());
        return vo;
    }

    private SheetVO readSheet(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator, Workbook wb) {
        SheetVO sv = new SheetVO();
        sv.setName(sheet.getSheetName());

        int lastRow = sheet.getLastRowNum();
        int dataRows = lastRow + 1;
        int dataCols = 0;
        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row != null) {
                dataCols = Math.max(dataCols, row.getLastCellNum());
            }
        }

        int rows = Math.min(Math.max(dataRows + TAIL_ROWS, 30), MAX_ROWS);
        int cols = Math.min(Math.max(dataCols + 2, 8), MAX_COLS);
        sv.setRows(rows);
        sv.setCols(cols);

        // 列宽
        List<Integer> widths = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            int px;
            try {
                px = Math.round(sheet.getColumnWidth(c) / 256f * 7f) + 5;
            } catch (Exception e) {
                px = 92;
            }
            widths.add(clamp(px, 42, 420));
        }
        sv.setColWidths(widths);

        // 行高（只有和默认行高不同的才返回）
        float defaultRowHeight = sheet.getDefaultRowHeightInPoints();
        List<Integer> heights = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            Row row = sheet.getRow(r);
            int px = 0;
            if (row != null) {
                float pt = row.getHeightInPoints();
                if (pt > 0 && Math.abs(pt - defaultRowHeight) > 0.05f) {
                    px = Math.round(pt * 96f / 72f);
                }
            }
            heights.add(clamp(px, 0, 200));
        }
        sv.setRowHeights(heights);

        // 合并
        List<int[]> merges = new ArrayList<>();
        for (CellRangeAddress m : sheet.getMergedRegions()) {
            if (m.getFirstRow() < rows && m.getFirstColumn() < cols) {
                merges.add(new int[]{m.getFirstRow(), m.getFirstColumn(), m.getLastRow(), m.getLastColumn()});
            }
        }
        sv.setMerges(merges);

        // 冻结
        PaneInformation pane = sheet.getPaneInformation();
        if (pane != null && pane.isFreezePane()) {
            sv.setFrozenRows(Math.min(pane.getVerticalSplitPosition(), rows));
            sv.setFrozenCols(Math.min(pane.getHorizontalSplitPosition(), cols));
        }

        // 单元格（稀疏）
        List<CellVO> cells = new ArrayList<>();
        for (int r = 0; r < Math.min(dataRows, rows); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (cell == null || cell.getColumnIndex() >= cols) {
                    continue;
                }
                CellVO cv = toCellVO(cell, formatter, evaluator, wb);
                if (isEmpty(cv)) {
                    continue;
                }
                cells.add(cv);
            }
        }
        sv.setCells(cells);
        return sv;
    }

    private CellVO toCellVO(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator, Workbook wb) {
        CellVO cv = new CellVO();
        cv.setR(cell.getRowIndex());
        cv.setC(cell.getColumnIndex());

        CellType type = cell.getCellType();
        String formula = null;
        if (type == CellType.FORMULA) {
            try {
                formula = cell.getCellFormula();
            } catch (Exception ignored) {
                formula = null;
            }
            type = cell.getCachedFormulaResultType();
        }
        cv.setF(formula);

        String display;
        try {
            display = formatter.formatCellValue(cell, evaluator);
        } catch (Exception e) {
            // 不支持的函数 / 循环引用等，退化为缓存值
            try {
                display = formatter.formatCellValue(cell);
            } catch (Exception ignored) {
                display = "";
            }
        }
        cv.setV(display == null ? "" : display);

        switch (type) {
            case NUMERIC:
                cv.setT(DateUtil.isCellDateFormatted(cell) ? "d" : "n");
                break;
            case BOOLEAN:
                cv.setT("b");
                break;
            case ERROR:
                cv.setT("e");
                break;
            default:
                cv.setT("s");
        }

        readStyle(cell, cv, wb);
        return cv;
    }

    private void readStyle(Cell cell, CellVO cv, Workbook wb) {
        try {
            CellStyle style = cell.getCellStyle();
            if (style == null) {
                return;
            }
            if (style.getFillPattern() != FillPatternType.NO_FILL) {
                cv.setBg(hex(style.getFillForegroundColorColor()));
            }
            // POI 5 起 CellStyle 不再暴露 getFont()，要经由 Workbook 取
            Font font = wb.getFontAt(style.getFontIndexAsInt());
            if (font != null) {
                cv.setB(font.getBold());
                if (font instanceof XSSFFont) {
                    cv.setFg(hex(((XSSFFont) font).getXSSFColor()));
                }
            }
            String fmt = style.getDataFormatString();
            if (fmt != null && !"General".equalsIgnoreCase(fmt) && !"@".equals(fmt)) {
                cv.setFmt(fmt);
            }
            HorizontalAlignment align = style.getAlignment();
            if (align == HorizontalAlignment.CENTER || align == HorizontalAlignment.CENTER_SELECTION) {
                cv.setAlign("center");
            } else if (align == HorizontalAlignment.RIGHT) {
                cv.setAlign("right");
            } else if (align == HorizontalAlignment.LEFT) {
                cv.setAlign("left");
            }
        } catch (Exception ignored) {
            // 样式读取失败不影响内容
        }
    }

    /** 只认 RGB/ARGB 明确给出的颜色；主题色（theme）可能取不到，返回 null 让前端用默认 */
    private String hex(org.apache.poi.ss.usermodel.Color color) {
        if (color instanceof XSSFColor) {
            byte[] rgb = ((XSSFColor) color).getRGB();
            if (rgb != null && rgb.length >= 3) {
                return String.format("#%02X%02X%02X", rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF);
            }
        }
        return null;
    }

    private boolean isEmpty(CellVO cv) {
        boolean noText = cv.getV() == null || cv.getV().isEmpty();
        boolean noLook = cv.getBg() == null && cv.getFg() == null && !cv.isB() && cv.getAlign() == null;
        return noText && noLook && cv.getF() == null;
    }

    // ==================== 表格：保存 ====================

    @Override
    public WorkbookVO saveSheet(Long fileId, SheetSaveForm form) {
        FileInfo info = requireSheet(fileId);
        File file = fileService.resolvePhysicalFile(fileId);
        if (form == null || form.getEdits() == null || form.getEdits().isEmpty()) {
            return readWorkbook(fileId);
        }

        // 先写临时文件再替换：POI 读 xlsx 时会持有原文件的 zip 句柄，
        // 直接覆盖原文件在 Windows 上可能失败或写出半截内容。
        File tmp = new File(file.getParentFile(), file.getName() + ".editing");
        try {
            backup(fileId, file);

            try (Workbook wb = WorkbookFactory.create(file)) {
                Sheet sheet = resolveSheet(wb, form.getSheetName());
                for (SheetSaveForm.Edit edit : form.getEdits()) {
                    if (edit == null || edit.getR() < 0 || edit.getC() < 0) {
                        continue;
                    }
                    applyEdit(sheet, edit);
                }
                wb.setForceFormulaRecalculation(true);
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    wb.write(out);
                }
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);

            refreshMeta(fileId, file);
            log.info("表格已保存在线编辑 id={} sheet={} edits={}", fileId,
                    form.getSheetName(), form.getEdits().size());
            // 重新打开取重算后的结果
            return readWorkbook(fileId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("保存表格失败 id={}", fileId, e);
            throw new IllegalArgumentException("保存失败：" + e.getMessage());
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                log.warn("清理临时文件失败：{}", tmp.getAbsolutePath());
            }
        }
    }

    private Sheet resolveSheet(Workbook wb, String name) {
        if (name == null || name.trim().isEmpty()) {
            Sheet s = wb.getSheetAt(0);
            if (s == null) {
                throw new IllegalArgumentException("这个文件里没有工作表");
            }
            return s;
        }
        Sheet s = wb.getSheet(name);
        if (s == null) {
            throw new IllegalArgumentException("工作表不存在：" + name);
        }
        return s;
    }

    private void applyEdit(Sheet sheet, SheetSaveForm.Edit edit) {
        Row row = sheet.getRow(edit.getR());
        if (row == null) {
            row = sheet.createRow(edit.getR());
        }
        Cell cell = row.getCell(edit.getC());
        if (cell == null) {
            cell = row.createCell(edit.getC());
        }

        String value = edit.getV();
        if (value == null || value.isEmpty()) {
            cell.setBlank();
            return;
        }
        // 以 = 开头按公式写入
        if (value.length() > 1 && value.charAt(0) == '=') {
            cell.setCellFormula(value.substring(1));
            return;
        }
        String hint = edit.getT();
        if ("b".equalsIgnoreCase(hint)) {
            cell.setCellValue(Boolean.parseBoolean(value));
            return;
        }
        if ("n".equalsIgnoreCase(hint) || (hint == null || "auto".equalsIgnoreCase(hint))) {
            String trimmed = value.trim().replace(",", "");
            if (NUMERIC.matcher(trimmed).matches()) {
                try {
                    cell.setCellValue(Double.parseDouble(trimmed));
                    return;
                } catch (NumberFormatException ignored) {
                    // 落到下面的文本分支
                }
            }
            if ("n".equalsIgnoreCase(hint)) {
                // 明确要求数字但解析不了，仍按文本存，避免抛异常打断整批保存
                cell.setCellValue(value);
                return;
            }
        }
        cell.setCellValue(value);
    }

    // ==================== 文本 ====================

    @Override
    public String readText(Long fileId) {
        FileInfo info = fileService.getById(fileId);
        if (!EditType.isText(info.getFileName())) {
            throw new IllegalArgumentException("该文件不支持在线编辑");
        }
        File file = fileService.resolvePhysicalFile(fileId);
        if (file.length() > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("文件超过 2 MB，请下载后用本地编辑器修改");
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("读取失败：" + e.getMessage());
        }
    }

    @Override
    public void saveText(Long fileId, String content) {
        FileInfo info = fileService.getById(fileId);
        if (!EditType.isText(info.getFileName())) {
            throw new IllegalArgumentException("该文件不支持在线编辑");
        }
        String body = content == null ? "" : content;
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("内容超过 2 MB，请下载后用本地编辑器修改");
        }
        File file = fileService.resolvePhysicalFile(fileId);
        try {
            backup(fileId, file);
            Files.write(file.toPath(), body.getBytes(StandardCharsets.UTF_8));
            refreshMeta(fileId, file);
            log.info("文本已保存在线编辑 id={} bytes={}", fileId, file.length());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("保存失败：" + e.getMessage());
        }
    }

    // ==================== 内部工具 ====================

    private FileInfo requireSheet(Long fileId) {
        FileInfo info = fileService.getById(fileId);
        if (!EditType.isSheet(info.getFileName())) {
            throw new IllegalArgumentException("该文件不是可编辑的表格（仅支持 xlsx / xls）");
        }
        return info;
    }

    /** 保存前备份，最多保留最近 5 份 */
    private void backup(Long fileId, File src) throws Exception {
        if (!src.isFile()) {
            throw new IllegalArgumentException("文件不存在");
        }
        File dir = new File(storageRoot, ".history/" + fileId);
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("备份目录创建失败：{}", dir.getAbsolutePath());
            return;
        }
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        File dst = new File(dir, ts + "." + EditType.ext(src.getName()));
        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);

        File[] all = dir.listFiles();
        if (all != null && all.length > 5) {
            Arrays.sort(all, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < all.length - 5; i++) {
                if (!all[i].delete()) {
                    log.warn("清理旧备份失败：{}", all[i].getName());
                }
            }
        }
    }

    /** 同步文件大小、MD5、更新时间 */
    private void refreshMeta(Long fileId, File file) {
        try (InputStream in = new FileInputStream(file)) {
            FileInfo upd = new FileInfo();
            upd.setId(fileId);
            upd.setFileSize(file.length());
            upd.setMd5(DigestUtils.md5DigestAsHex(in));
            fileInfoMapper.updateById(upd);
        } catch (Exception e) {
            log.warn("更新文件元数据失败 id={} {}", fileId, e.getMessage());
        }
    }

    private int clamp(int v, int lo, int hi) {
        if (v < lo) {
            return lo;
        }
        return Math.min(v, hi);
    }
}
