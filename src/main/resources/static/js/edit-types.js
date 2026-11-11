/* ============================================================
   可在线编辑的文件类型白名单
   —— 与后端 common/EditType 严格保持一致，改这里要同步改后端
   ============================================================ */
(function () {
    'use strict';

    /** 表格：POI 读写。刻意不含 xlsm —— 写回会丢 VBA 宏 */
    const SHEET = ['xlsx', 'xls'];

    /** 纯文本：统一按 UTF-8 处理 */
    const TEXT = [
        'txt', 'md', 'markdown', 'log', 'csv', 'tsv',
        'json', 'xml', 'yml', 'yaml', 'ini', 'conf', 'cfg', 'properties', 'toml', 'env',
        'sql', 'java', 'js', 'mjs', 'ts', 'jsx', 'tsx', 'vue',
        'css', 'scss', 'less', 'html', 'htm',
        'py', 'sh', 'bat', 'ps1', 'go', 'rs', 'c', 'h', 'cpp', 'php', 'rb'
    ];

    function extOf(name) {
        const s = String(name || '');
        const i = s.lastIndexOf('.');
        return i < 0 ? '' : s.slice(i + 1).toLowerCase();
    }

    /** 返回 'SHEET' / 'TEXT' / ''（不支持） */
    function modeOf(name) {
        const e = extOf(name);
        if (SHEET.indexOf(e) >= 0) return 'SHEET';
        if (TEXT.indexOf(e) >= 0) return 'TEXT';
        return '';
    }

    window.EditTypes = {
        extOf: extOf,
        modeOf: modeOf,
        isSheet: (name) => modeOf(name) === 'SHEET',
        isText: (name) => modeOf(name) === 'TEXT'
    };
})();
