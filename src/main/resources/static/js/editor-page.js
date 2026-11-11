/* ============================================================
   在线编辑页（独立全屏页面）
   用法：editor.html?id=123
   —— 表格走 /sheet 接口，纯文本走 /text 接口
   ============================================================ */
(function () {
    'use strict';

    // ---------------- 基础工具 ----------------
    const $ = (sel) => document.querySelector(sel);

    function toast(msg) {
        const t = $('#toast');
        if (!t) return;
        t.textContent = msg;
        t.hidden = false;
        clearTimeout(toast._timer);
        toast._timer = setTimeout(() => { t.hidden = true; }, 2400);
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"]/g, (c) => (
            { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]
        ));
    }

    /** 统一接口调用：未登录直接回登录页，避免页面停在半死状态 */
    function api(url, opts) {
        return fetch(url, opts).then(async (r) => {
            if (r.status === 401) {
                location.replace('login.html');
                return { code: 401, message: '登录已失效，请重新登录' };
            }
            if (r.status === 403) return { code: 403, message: '没有访问权限' };
            try {
                return await r.json();
            } catch (e) {
                return { code: -1, message: '服务器返回异常' };
            }
        }).catch(() => ({ code: -1, message: '网络异常，请重试' }));
    }

    function colName(i) {
        let s = '';
        let n = i;
        while (n >= 0) {
            s = String.fromCharCode(65 + (n % 26)) + s;
            n = Math.floor(n / 26) - 1;
        }
        return s;
    }

    // ---------------- DOM ----------------
    const $name = $('#edName');
    const $dirty = $('#edDirty');
    const $notice = $('#edNotice');
    const $status = $('#edStatus');
    const $ref = $('#edRef');
    const $tip = $('#edTip');
    const $reload = $('#edReload');
    const $save = $('#edSave');
    const $close = $('#edClose');

    const $tabs = $('#sheetTabs');
    const $sheetView = $('#sheetView');
    const $grid = $('#sheetGrid');
    const $textView = $('#textView');
    const $textArea = $('#textArea');

    const state = {
        mode: null,        // 'SHEET' | 'TEXT'
        file: null,
        dirtySize: 0,
        saving: false
    };

    function setStatus(t) { $status.textContent = t; }

    /** 脏标记：表格显示"改了几处"，文本只说"未保存" */
    function showDirty(n) {
        if (state.mode === 'TEXT') {
            state.dirtySize = n > 0 ? 1 : 0;
            $dirty.textContent = '未保存';
        } else {
            state.dirtySize = n;
            $dirty.textContent = n + ' 处未保存';
        }
        $dirty.hidden = state.dirtySize === 0;
        const mark = state.dirtySize > 0 ? '• ' : '';
        document.title = mark + (state.file ? state.file.fileName : '在线编辑') + ' · 在线编辑';
    }

    // 关页面前拦截未保存（含浏览器刷新/关标签）
    window.addEventListener('beforeunload', (e) => {
        if (state.dirtySize > 0) {
            e.preventDefault();
            e.returnValue = '';
            return '';
        }
    });

    // ============================================================
    // 表格
    // ============================================================
    const S = {
        wb: null,
        index: 0,
        dirty: new Map()   // "r:c" -> { r, c, v, t }
    };

    function renderTabs() {
        const sheets = (S.wb && S.wb.sheets) || [];
        if (sheets.length <= 1) {
            $tabs.hidden = true;
            $tabs.innerHTML = '';
            return;
        }
        $tabs.hidden = false;
        $tabs.innerHTML = sheets.map((s, i) =>
            `<button class="ed-tab${i === S.index ? ' active' : ''}" type="button" data-i="${i}">${esc(s.name)}</button>`
        ).join('');
    }

    async function switchSheet(i) {
        if (i === S.index) return;
        if (S.dirty.size) {
            const ok = await uiConfirm({
                icon: 'save',
                eyebrow: 'Unsaved',
                title: '放弃未保存的修改？',
                sub: `当前工作表有 ${S.dirty.size} 处修改未保存`,
                note: '切换工作表后，这些修改<strong>将丢失</strong>。建议先保存再切换。',
                okText: '放弃并切换'
            });
            if (!ok) return;
        }
        S.dirty.clear();
        showDirty(0);
        S.index = i;
        renderTabs();
        renderSheet();
    }

    function renderSheet() {
        const sh = S.wb && S.wb.sheets[S.index];
        if (!sh) {
            $grid.innerHTML = '<tbody><tr><td class="ed-grid-empty">没有工作表</td></tr></tbody>';
            return;
        }

        // 单元格索引 + 合并信息
        const idx = new Map();
        (sh.cells || []).forEach((c) => idx.set(c.r + ':' + c.c, c));

        const skip = new Set();       // 被合并覆盖的格子（不输出 td，避免错位）
        const span = new Map();       // 合并区左上角 -> { rs, cs }
        (sh.merges || []).forEach((m) => {
            const r1 = m[0], c1 = m[1];
            const r2 = Math.min(m[2], sh.rows - 1);
            const c2 = Math.min(m[3], sh.cols - 1);
            if (r2 < r1 || c2 < c1) return;
            span.set(r1 + ':' + c1, { rs: r2 - r1 + 1, cs: c2 - c1 + 1 });
            for (let r = r1; r <= r2; r++) {
                for (let c = c1; c <= c2; c++) {
                    if (r !== r1 || c !== c1) skip.add(r + ':' + c);
                }
            }
        });

        // 铺满可视区域：右侧/下方补空白行列，避免大片留白（Excel 的观感）
        const ROW_H = 26;
        const HEAD_H = 24;      // 列头高度
        const MIN_COL = 92;
        const availW = $sheetView.clientWidth || 0;
        const availH = ($sheetView.clientHeight || 0) - HEAD_H;

        const widths = [];
        let totalW = 46;        // 行号列
        for (let c = 0; c < sh.cols; c++) {
            const w = (sh.colWidths && sh.colWidths[c]) || MIN_COL;
            widths.push(w);
            totalW += w;
        }
        if (availW > 0) {
            while (totalW < availW && widths.length < sh.cols + 60) {
                widths.push(MIN_COL);
                totalW += MIN_COL;
            }
            // 最后一列吸收余量，避免为几十像素多出一条横向滚动条
            if (widths.length > sh.cols) {
                const last = widths.length - 1;
                widths[last] = Math.max(60, widths[last] - (totalW - availW));
                totalW = availW;
            }
        }
        const colCount = widths.length;

        let totalH = HEAD_H;
        for (let r = 0; r < sh.rows; r++) {
            totalH += (sh.rowHeights && sh.rowHeights[r] > 0 ? sh.rowHeights[r] : ROW_H);
        }
        let rowCount = sh.rows;
        if (availH > 0) {
            while (totalH + ROW_H <= availH && rowCount < 600) {
                totalH += ROW_H;
                rowCount++;
            }
        }

        let cols = '<colgroup><col style="width:46px">';
        for (let c = 0; c < colCount; c++) cols += `<col style="width:${widths[c]}px">`;
        cols += '</colgroup>';

        let head = '<thead><tr><th class="rh"></th>';
        for (let c = 0; c < colCount; c++) head += `<th>${colName(c)}</th>`;
        head += '</tr></thead>';

        let body = '<tbody>';
        for (let r = 0; r < rowCount; r++) {
            const h = (sh.rowHeights && sh.rowHeights[r] > 0) ? ` style="height:${sh.rowHeights[r]}px"` : '';
            body += `<tr${h}><th class="rh">${r + 1}</th>`;
            for (let c = 0; c < colCount; c++) {
                const k = r + ':' + c;
                if (skip.has(k)) continue;
                body += cellHtml(r, c, idx.get(k), span.get(k));
            }
            body += '</tr>';
        }
        body += '</tbody>';

        // 重绘会重置滚动位置，这里保持住
        const scroller = $sheetView;
        const sx = scroller.scrollLeft;
        const sy = scroller.scrollTop;
        $grid.innerHTML = cols + head + body;
        scroller.scrollLeft = sx;
        scroller.scrollTop = sy;

        $ref.hidden = false;
        $ref.textContent = 'A1';
        setStatus(`${sh.rows} 行 × ${sh.cols} 列 · ${(sh.cells || []).length} 个非空单元格`);
    }

    function cellHtml(r, c, cell, span) {
        const v = cell ? (cell.v == null ? '' : cell.v) : '';
        let cls = '';
        let style = '';
        let data = '';

        if (cell) {
            if (cell.b) cls += ' bold';
            if (cell.t === 'n' || cell.t === 'd') cls += ' num';
            if (cell.t === 'e') cls += ' err';
            if (cell.align) cls += ' ' + cell.align;
            if (cell.bg) style += `background:${cell.bg};`;
            if (cell.fg) style += `color:${cell.fg};`;
            if (cell.t) data += ` data-t="${esc(cell.t)}"`;
            if (cell.f) data += ` data-f="${esc(cell.f)}"`;
        }

        let size = '';
        if (span) {
            if (span.rs > 1) size += ` rowspan="${span.rs}"`;
            if (span.cs > 1) size += ` colspan="${span.cs}"`;
        }

        return `<td data-r="${r}" data-c="${c}" data-val="${esc(v)}" data-base="${esc(v)}" contenteditable="true"`
            + ` spellcheck="false"${data}${size}`
            + (cls ? ` class="${cls.trim()}"` : '')
            + (style ? ` style="${style}"` : '')
            + `>${esc(v)}</td>`;
    }

    function tdAt(r, c) {
        return $grid.querySelector(`td[data-r="${r}"][data-c="${c}"]`);
    }

    function selectAll(el) {
        try {
            const range = document.createRange();
            range.selectNodeContents(el);
            const sel = window.getSelection();
            sel.removeAllRanges();
            sel.addRange(range);
        } catch (e) { /* 忽略 */ }
    }

    /** 把 td 当前内容提交到 dirty 表；quiet=true 时不动 DOM（输入过程中调用） */
    function commitCell(td, quiet) {
        const r = Number(td.dataset.r);
        const c = Number(td.dataset.c);
        const raw = (td.textContent || '').replace(/[\r\n]+/g, ' ').trim();
        // data-base 是"文件里的原值"，只用来判断是否改动过（data-val 是显示值，会随公式切换）
        const baseline = td.dataset.f ? ('=' + td.dataset.f) : (td.dataset.base || '');

        if (raw === baseline) {
            if (S.dirty.delete(r + ':' + c)) {
                td.classList.remove('dirty');
                showDirty(S.dirty.size);
            }
            if (!quiet && td.dataset.f) td.textContent = td.dataset.val || '';
            return;
        }

        let t = 'auto';
        if (raw.length > 1 && raw.charAt(0) === '=') {
            t = 'auto';
        } else if (td.dataset.t === 'n') {
            t = 'n';
        } else if (td.dataset.t === 'b') {
            t = 'b';
        } else if (td.dataset.t) {
            t = 's';
        }

        S.dirty.set(r + ':' + c, { r: r, c: c, v: raw, t: t });
        td.classList.add('dirty');
        if (td.dataset.f && raw.length > 1 && raw.charAt(0) === '=') {
            td.dataset.f = raw.slice(1);
        } else if (td.dataset.f) {
            delete td.dataset.f;
        }
        showDirty(S.dirty.size);
    }

    function moveCell(td, dr, dc) {
        const r = Number(td.dataset.r) + dr;
        const c = Number(td.dataset.c) + dc;
        const next = tdAt(r, c) || tdAt(Number(td.dataset.r), c) || tdAt(r, Number(td.dataset.c));
        if (next) {
            next.focus();
            selectAll(next);
        }
    }

    $grid.addEventListener('focusin', (e) => {
        const td = e.target.closest('td[contenteditable]');
        if (!td) return;
        $ref.textContent = colName(Number(td.dataset.c)) + (Number(td.dataset.r) + 1);
        if (td.dataset.f) {
            td.textContent = '=' + td.dataset.f;
            selectAll(td);
        }
    });

    $grid.addEventListener('focusout', (e) => {
        const td = e.target.closest('td[contenteditable]');
        if (!td) return;
        commitCell(td);
    });

    // 输入即记录改动：这样人在单元格里直接按 Ctrl+S 也能存下当前这格
    $grid.addEventListener('input', (e) => {
        const td = e.target.closest('td[contenteditable]');
        if (!td) return;
        commitCell(td, true);
    });

    $grid.addEventListener('keydown', (e) => {
        const td = e.target.closest('td[contenteditable]');
        if (!td) return;

        if (e.key === 'Enter' && !e.altKey) {
            e.preventDefault();
            commitCell(td);
            moveCell(td, e.shiftKey ? -1 : 1, 0);
            return;
        }
        if (e.key === 'Tab') {
            e.preventDefault();
            commitCell(td);
            moveCell(td, 0, e.shiftKey ? -1 : 1);
            return;
        }
        if (e.key === 'Escape') {
            e.preventDefault();
            commitCell(td);
            td.blur();
        }
    });

    // 粘贴只取纯文本，避免把 HTML 带进单元格
    $grid.addEventListener('paste', (e) => {
        const td = e.target.closest('td[contenteditable]');
        if (!td) return;
        const text = (e.clipboardData || window.clipboardData).getData('text');
        if (text == null) return;
        e.preventDefault();
        document.execCommand('insertText', false, text.replace(/[\r\n]+/g, ' '));
    });

    $tabs.addEventListener('click', (e) => {
        const btn = e.target.closest('.ed-tab');
        if (btn) switchSheet(Number(btn.dataset.i));
    });

    // 窗口尺寸变化后重算"铺满"的行列；正在编辑或有未保存改动时先不动，避免打断输入
    let resizeTimer;
    window.addEventListener('resize', () => {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(() => {
            if (state.mode !== 'SHEET' || !S.wb || state.saving || S.dirty.size) return;
            const ae = document.activeElement;
            if (ae && ae.closest && ae.closest('#sheetGrid td')) return;
            renderSheet();
        }, 220);
    });

    async function loadSheet() {
        $sheetView.hidden = false;
        $textView.hidden = true;
        $reload.hidden = false;
        $tip.textContent = 'Enter 下一行 · Tab 右移 · 单元格内以 = 开头即为公式 · Ctrl+S 保存';
        $grid.innerHTML = '<tbody><tr><td class="ed-grid-empty">加载中…</td></tr></tbody>';
        setStatus('加载中…');

        const res = await api(`/api/files/${state.file.id}/sheet`);
        if (res.code !== 0) {
            $grid.innerHTML = `<tbody><tr><td class="ed-grid-empty">${esc(res.message || '打开失败')}</td></tr></tbody>`;
            setStatus('打开失败');
            return;
        }
        S.wb = res.data;
        S.index = 0;
        S.dirty.clear();
        showDirty(0);
        if (S.wb.notice) {
            $notice.textContent = S.wb.notice;
            $notice.hidden = false;
        }
        renderTabs();
        renderSheet();
    }

    async function saveSheet() {
        if (state.saving || !S.wb) return;
        const edits = Array.from(S.dirty.values());
        if (!edits.length) { toast('没有需要保存的改动'); return; }

        state.saving = true;
        setStatus('保存中…');
        try {
            const res = await api(`/api/files/${state.file.id}/sheet`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sheetName: S.wb.sheets[S.index].name, edits: edits })
            });
            if (res.code !== 0) {
                setStatus(res.message || '保存失败');
                toast(res.message || '保存失败');
                return;
            }
            S.wb = res.data;
            S.dirty.clear();
            if (S.index >= S.wb.sheets.length) S.index = 0;
            if (S.wb.notice) {
                $notice.textContent = S.wb.notice;
                $notice.hidden = false;
            }
            renderTabs();
            renderSheet();
            showDirty(0);
            setStatus('已保存 · ' + new Date().toLocaleTimeString());
            toast(`已保存 ${edits.length} 处修改`);
        } finally {
            state.saving = false;
        }
    }

    // ============================================================
    // 文本
    // ============================================================
    function updateTextStat() {
        const s = $textArea.value;
        setStatus(`${s ? s.split('\n').length : 0} 行 · ${s.length} 字符`);
    }

    async function loadText() {
        $sheetView.hidden = true;
        $textView.hidden = false;
        $tabs.hidden = true;
        $ref.hidden = true;
        $reload.hidden = false;
        $tip.textContent = 'Tab 插入缩进 · Ctrl+S 保存';
        $textArea.value = '';
        setStatus('加载中…');

        const res = await api(`/api/files/${state.file.id}/text`);
        if (res.code !== 0) {
            setStatus(res.message || '打开失败');
            toast(res.message || '打开失败');
            return;
        }
        $textArea.value = res.data.content || '';
        updateTextStat();
        showDirty(0);
    }

    $textArea.addEventListener('input', () => {
        showDirty(1);
        updateTextStat();
    });

    $textArea.addEventListener('keydown', (e) => {
        if (e.key === 'Tab') {
            e.preventDefault();
            document.execCommand('insertText', false, '    ');
        }
    });

    async function saveText() {
        if (state.saving) return;
        state.saving = true;
        setStatus('保存中…');
        try {
            const res = await api(`/api/files/${state.file.id}/text`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ content: $textArea.value })
            });
            if (res.code !== 0) {
                setStatus(res.message || '保存失败');
                toast(res.message || '保存失败');
                return;
            }
            showDirty(0);
            updateTextStat();
            setStatus('已保存 · ' + new Date().toLocaleTimeString());
            toast('已保存');
        } finally {
            state.saving = false;
        }
    }

    // ============================================================
    // 顶部操作 / 快捷键
    // ============================================================
    $save.addEventListener('click', () => {
        if (state.mode === 'SHEET') saveSheet(); else saveText();
    });

    $reload.addEventListener('click', async () => {
        if (state.dirtySize > 0) {
            const ok = await uiConfirm({
                icon: 'save',
                eyebrow: 'Unsaved',
                title: '重新载入？',
                sub: `${state.dirtySize} 处修改未保存`,
                note: '重新载入会<strong>丢弃</strong>这些修改。',
                okText: '丢弃并载入'
            });
            if (!ok) return;
        }
        if (state.mode === 'SHEET') loadSheet(); else loadText();
    });

    async function requestClose() {
        if (state.dirtySize > 0) {
            const ok = await uiConfirm({
                icon: 'save',
                eyebrow: 'Unsaved',
                title: '未保存就关闭？',
                sub: `${state.dirtySize} 处修改未保存`,
                note: '关闭后这些修改<strong>将丢失</strong>。建议先保存（Ctrl + S）。',
                okText: '放弃并关闭'
            });
            if (!ok) return;
        }
        state.dirtySize = 0;
        // 由本页打开的窗口才能用 close()，否则退回云盘
        window.close();
        setTimeout(() => {
            if (!document.hidden) location.href = 'index.html';
        }, 260);
    }

    $close.addEventListener('click', requestClose);

    document.addEventListener('keydown', (e) => {
        if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
            e.preventDefault();
            if (state.mode === 'SHEET') saveSheet(); else saveText();
        }
    });

    // ============================================================
    // 启动
    // ============================================================
    (async function boot() {
        const id = Number(new URLSearchParams(location.search).get('id'));
        if (!id) {
            $grid.innerHTML = '<tbody><tr><td class="ed-grid-empty">缺少文件 id，请从云盘里点「编辑」进入</td></tr></tbody>';
            setStatus('参数错误');
            return;
        }

        const res = await api(`/api/files/${id}`);
        if (res.code !== 0 || !res.data) {
            $grid.innerHTML = `<tbody><tr><td class="ed-grid-empty">${esc(res.message || '文件不存在或已删除')}</td></tr></tbody>`;
            setStatus('打开失败');
            return;
        }

        const file = res.data;
        const mode = window.EditTypes ? window.EditTypes.modeOf(file.fileName) : '';
        if (!mode) {
            $grid.innerHTML = '<tbody><tr><td class="ed-grid-empty">这个文件类型不支持在线编辑，请下载后用本地软件打开</td></tr></tbody>';
            setStatus('不支持的类型');
            return;
        }

        state.file = file;
        state.mode = mode;
        $name.textContent = file.fileName;
        $name.title = file.fileName;
        document.title = file.fileName + ' · 在线编辑';

        if (mode === 'SHEET') await loadSheet();
        else await loadText();
    })();
})();
