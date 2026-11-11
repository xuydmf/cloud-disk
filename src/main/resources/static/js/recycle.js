/* 回收站页面逻辑：列表 / 搜索 / 恢复 / 彻底删除 / 清空 */
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

const PAGE_SIZE = 20;

let keyword = '';
let currentPage = 1;
let totalCount = 0;
let totalPages = 1;
let records = [];
const selected = new Set();
let keepDays = 7;

const ICONS = {
    image: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2.5"/><circle cx="8.5" cy="9.5" r="1.6"/><path d="m21 16-4.6-4.6L7 21"/></svg>',
    video: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="6" width="13" height="12" rx="2.5"/><path d="m15.5 11 5.5-3v8l-5.5-3"/></svg>',
    file: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M13.5 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8.5L13.5 3Z"/><path d="M13.5 3v5.5H19"/></svg>'
};

// ---------------- 工具 ----------------
function toast(msg) {
    const t = $('#toast');
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toast._timer);
    toast._timer = setTimeout(() => { t.hidden = true; }, 2400);
}

function formatSize(bytes) {
    if (bytes == null) return '-';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    let v = bytes;
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return v.toFixed(i === 0 ? 0 : 1) + ' ' + units[i];
}

function formatTime(ts) {
    if (!ts) return '-';
    const d = new Date(ts);
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function goLogin() {
    location.replace('login.html');
}

function api(url, opts) {
    return fetch(url, opts).then(async (r) => {
        if (r.status === 401) { goLogin(); return { code: 401, message: '登录已失效' }; }
        if (r.status === 403) { return { code: 403, message: '没有访问权限' }; }
        try {
            return await r.json();
        } catch (e) {
            return { code: -1, message: '服务器返回异常' };
        }
    }).catch(() => ({ code: -1, message: '网络异常，请重试' }));
}

function postJson(url, ids) {
    return api(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(ids)
    });
}

// ---------------- 登录用户 ----------------
async function loadCurrentUser() {
    const res = await api('/api/auth/current');
    if (res.code !== 0 || !res.data) { goLogin(); return null; }
    const u = res.data;
    if ($('#railUser')) $('#railUser').hidden = false;
    if ($('#userAvatar')) $('#userAvatar').textContent = String(u.displayName || u.username || '?').slice(0, 1).toUpperCase();
    if ($('#userName')) $('#userName').textContent = u.displayName || u.username;
    if ($('#userRole')) $('#userRole').textContent = u.role === 'ADMIN' ? '管理员' : '普通用户';
    if ($('#adminLink')) $('#adminLink').hidden = u.role !== 'ADMIN';
    return u;
}

async function doLogout() {
    try { await fetch('/api/auth/logout', { method: 'POST' }); } catch (e) { /* 忽略 */ }
    goLogin();
}

// ---------------- 列表 ----------------
async function loadList() {
    let url = `/api/recycle?page=${currentPage}&size=${PAGE_SIZE}`;
    if (keyword) url += '&keyword=' + encodeURIComponent(keyword);
    const res = await api(url);
    if (res.code !== 0) { toast(res.message || '加载失败'); return; }
    const p = res.data || {};
    totalCount = p.total || 0;
    totalPages = p.pages || 0;
    currentPage = p.page || 1;
    records = p.records || [];
    renderRows();
    renderPagination();
    syncSelection();
}

async function loadStats() {
    const res = await api('/api/recycle/stats');
    if (res.code !== 0) return;
    const d = res.data || {};
    keepDays = d.keepDays || 7;
    if ($('#statTotal')) $('#statTotal').textContent = d.count != null ? d.count : '—';
    if ($('#statSize')) $('#statSize').textContent = formatSize(d.totalSize || 0);
    if ($('#statKeep')) $('#statKeep').textContent = keepDays + ' 天';
    if ($('#recycleTip')) {
        $('#recycleTip').innerHTML = `删除的文件会先放到这里，<b>${keepDays} 天</b>内可以恢复；`
            + '超过期限将自动彻底删除，无法找回。';
    }
}

function renderRows() {
    const box = $('#rRows');
    const empty = $('#empty');
    box.innerHTML = '';
    if (!records.length) {
        empty.hidden = false;
        empty.querySelector('p').textContent = keyword ? '没有匹配的文件' : '回收站是空的';
        empty.querySelector('small').textContent = keyword
            ? '试试别的关键字'
            : '在工作台删除的文件会出现在这里';
        return;
    }
    empty.hidden = true;
    records.forEach((f) => box.appendChild(renderRow(f)));
}

function renderRow(f) {
    const row = document.createElement('div');
    row.className = 'rrow';
    row.dataset.id = f.id;

    // 勾选框
    const check = document.createElement('span');
    check.className = 'rc-check';
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = selected.has(f.id);
    cb.setAttribute('aria-label', '选择 ' + f.fileName);
    cb.onchange = () => {
        if (cb.checked) selected.add(f.id); else selected.delete(f.id);
        syncSelection();
    };
    check.appendChild(cb);

    // 名称 + 缩略图
    const nameCell = document.createElement('div');
    nameCell.className = 'rc-name';
    const thumb = document.createElement('span');
    thumb.className = 'rc-thumb';
    if (f.thumbPath && (f.fileType === 'IMAGE' || f.fileType === 'VIDEO')) {
        const img = document.createElement('img');
        img.src = `/api/recycle/${f.id}/thumb`;
        img.loading = 'lazy';
        img.alt = f.fileName;
        img.onerror = () => { thumb.innerHTML = extMarkup(f); };
        thumb.appendChild(img);
        thumb.classList.add('clickable');
        thumb.title = '点击预览';
        thumb.onclick = () => previewThumb(f);
    } else {
        thumb.innerHTML = extMarkup(f);
    }
    const nameText = document.createElement('span');
    nameText.className = 'rc-nametext';
    nameText.innerHTML = `<span class="rc-file" title="${escapeHtml(f.fileName)}">${escapeHtml(f.fileName)}</span>`
        + `<span class="rc-sub">${escapeHtml(extOf(f.fileName))}</span>`;
    nameCell.appendChild(thumb);
    nameCell.appendChild(nameText);

    // 大小 · 原分组
    const meta = document.createElement('div');
    meta.className = 'rc-meta';
    meta.innerHTML = `<span>${formatSize(f.fileSize)}</span><span class="rc-group">${escapeHtml(f.groupName || '未分组')}</span>`;

    // 删除时间
    const timeCell = document.createElement('div');
    timeCell.className = 'rc-time';
    timeCell.textContent = formatTime(f.deletedTime);

    // 剩余天数
    const expire = document.createElement('div');
    const d = Number(f.remainDays || 0);
    expire.className = 'rc-expire-v' + (d <= 1 ? ' urgent' : (d <= 3 ? ' warn' : ''));
    expire.textContent = d <= 0 ? '即将清理' : d + ' 天';
    expire.title = d <= 0 ? '下次清理时将被彻底删除' : `还剩 ${d} 天被彻底删除`;

    // 操作
    const acts = document.createElement('div');
    acts.className = 'rc-acts';
    acts.appendChild(mkBtn('恢复', () => restoreOne(f), false));
    acts.appendChild(mkBtn('彻底删除', () => purgeOne(f), true));

    row.appendChild(check);
    row.appendChild(nameCell);
    row.appendChild(meta);
    row.appendChild(timeCell);
    row.appendChild(expire);
    row.appendChild(acts);
    return row;
}

function extOf(name) {
    if (!name || name.lastIndexOf('.') < 0) return 'FILE';
    return name.slice(name.lastIndexOf('.') + 1).toUpperCase().slice(0, 5);
}

function extMarkup(f) {
    const ico = f.fileType === 'IMAGE' ? ICONS.image : (f.fileType === 'VIDEO' ? ICONS.video : ICONS.file);
    return '<span class="rc-ph">' + ico + '</span>';
}

function mkBtn(label, fn, danger) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'rc-btn' + (danger ? ' danger' : '');
    b.textContent = label;
    b.onclick = fn;
    return b;
}

function previewThumb(f) {
    $('#imagePreview').src = `/api/recycle/${f.id}/preview`;
    $('#imgName').textContent = f.fileName;
    $('#imageModal').hidden = false;
}

// ---------------- 分页 ----------------
function renderPagination() {
    const el = $('#pagination');
    el.innerHTML = '';
    if (totalPages <= 1) return;

    el.appendChild(pageBtn('‹', currentPage - 1, currentPage <= 1, '上一页'));
    pageWindow().forEach((n) => {
        if (n === '...') {
            const s = document.createElement('span');
            s.className = 'page-gap';
            s.textContent = '…';
            el.appendChild(s);
        } else {
            el.appendChild(pageBtn(String(n), n, false, '第 ' + n + ' 页', n === currentPage));
        }
    });
    el.appendChild(pageBtn('›', currentPage + 1, currentPage >= totalPages, '下一页'));
}

function pageBtn(text, target, disabled, title, active) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'page-btn' + (active ? ' active' : '');
    b.textContent = text;
    b.disabled = !!disabled;
    if (title) b.title = title;
    if (!disabled && !active) b.onclick = () => goPage(target);
    return b;
}

function pageWindow() {
    const out = [];
    const total = totalPages;
    const cur = currentPage;
    if (total <= 7) {
        for (let i = 1; i <= total; i++) out.push(i);
        return out;
    }
    out.push(1);
    if (cur > 3) out.push('...');
    for (let i = Math.max(2, cur - 1); i <= Math.min(total - 1, cur + 1); i++) out.push(i);
    if (cur < total - 2) out.push('...');
    out.push(total);
    return out;
}

function goPage(n) {
    if (n < 1 || n > totalPages) return;
    currentPage = n;
    clearSelection();
    loadList();
}

// ---------------- 选择 ----------------
function syncSelection() {
    const n = selected.size;
    document.body.classList.toggle('selecting', n > 0);
    const bar = $('#batchBar');
    if (bar) bar.hidden = n === 0;
    if ($('#batchCount')) $('#batchCount').textContent = n;
    const all = $('#checkAll');
    if (all) {
        all.checked = n > 0 && n === records.length;
        all.indeterminate = n > 0 && n < records.length;
    }
}

function clearSelection() {
    if (!selected.size) return;
    selected.clear();
    $$('#rRows .rrow input[type=checkbox]').forEach((cb) => { cb.checked = false; });
    syncSelection();
}

// ---------------- 操作 ----------------
async function restoreOne(f) {
    const res = await api(`/api/recycle/${f.id}/restore`, { method: 'POST' });
    if (res.code === 0) {
        toast(`已恢复「${f.fileName}」`);
        selected.delete(f.id);
        refresh();
    } else {
        toast(res.message || '恢复失败');
    }
}

async function purgeOne(f) {
    const ok = await uiConfirm({
        icon: 'trash',
        danger: true,
        eyebrow: 'Danger',
        title: '彻底删除此文件？',
        sub: f.fileName + (f.fileSize ? ' · ' + formatSize(f.fileSize) : ''),
        note: '此操作<strong>不可撤销</strong>，文件将被永久从磁盘移除，回收站中不再保留。',
        okText: '彻底删除'
    });
    if (!ok) return;
    const res = await api(`/api/recycle/${f.id}`, { method: 'DELETE' });
    if (res.code === 0) {
        toast('已彻底删除');
        selected.delete(f.id);
        refresh();
    } else {
        toast(res.message || '删除失败');
    }
}

async function batchRestore() {
    const ids = Array.from(selected);
    if (!ids.length) return;
    const res = await postJson('/api/recycle/restore', ids);
    if (res.code === 0) {
        toast(`已恢复 ${res.data != null ? res.data : ids.length} 个文件`);
        clearSelection();
        refresh();
    } else {
        toast(res.message || '恢复失败');
    }
}

async function batchPurge() {
    const ids = Array.from(selected);
    if (!ids.length) return;

    const idSet = new Set(ids);
    const picked = records.filter((r) => idSet.has(r.id));
    const totalBytes = picked.length === ids.length
        ? picked.reduce((s, r) => s + (r.fileSize || 0), 0)
        : 0;

    const ok = await uiConfirm({
        icon: 'trash',
        danger: true,
        eyebrow: 'Danger · ' + ids.length + ' items',
        title: `彻底删除选中的 ${ids.length} 个文件？`,
        sub: totalBytes > 0 ? '合计 ' + formatSize(totalBytes) : '',
        note: '此操作<strong>不可撤销</strong>，这些文件将被永久从磁盘移除。',
        okText: '彻底删除'
    });
    if (!ok) return;

    const res = await postJson('/api/recycle/purge', ids);
    if (res.code === 0) {
        toast(`已彻底删除 ${res.data != null ? res.data : ids.length} 个文件`);
        clearSelection();
        refresh();
    } else {
        toast(res.message || '删除失败');
    }
}

async function clearAll() {
    if (!totalCount) { toast('回收站已经是空的'); return; }
    const ok = await uiConfirm({
        icon: 'trash',
        danger: true,
        eyebrow: 'Danger',
        title: '清空回收站？',
        sub: `共 ${totalCount} 个文件`,
        note: `回收站内<strong>全部 ${totalCount} 个文件</strong>将被永久删除，且无法恢复。`,
        okText: '清空回收站'
    });
    if (!ok) return;
    const res = await api('/api/recycle', { method: 'DELETE' });
    if (res.code === 0) {
        toast(`已清空回收站（${res.data != null ? res.data : 0} 个文件）`);
        clearSelection();
        currentPage = 1;
        refresh();
    } else {
        toast(res.message || '清空失败');
    }
}

/** 操作后刷新：如果当前页被删空了就回退一页，避免停在空白页 */
function refresh() {
    loadStats();
    if (records.length === 1 && currentPage > 1) {
        currentPage -= 1;
    }
    loadList();
}

// ---------------- 事件 ----------------
$('#searchInput').oninput = (() => {
    let timer;
    return (e) => {
        clearTimeout(timer);
        timer = setTimeout(() => {
            keyword = e.target.value.trim();
            currentPage = 1;
            clearSelection();
            loadList();
        }, 300);
    };
})();

$('#checkAll').onchange = (e) => {
    if (e.target.checked) {
        records.forEach((f) => selected.add(f.id));
        $$('#rRows .rrow input[type=checkbox]').forEach((cb) => { cb.checked = true; });
    } else {
        selected.clear();
        $$('#rRows .rrow input[type=checkbox]').forEach((cb) => { cb.checked = false; });
    }
    syncSelection();
};

$('#batchRestore').onclick = batchRestore;
$('#batchPurge').onclick = batchPurge;
$('#batchClear').onclick = clearSelection;
$('#clearBtn').onclick = clearAll;

$$('[data-close]').forEach((el) => {
    el.onclick = () => {
        const id = el.dataset.close;
        const m = $('#' + id);
        if (m) m.hidden = true;
        if (id === 'imageModal') $('#imagePreview').src = '';
    };
});

const railToggle = $('#railToggle');
if (railToggle) railToggle.onclick = () => document.body.classList.toggle('rail-open');
const railMask = $('#railMask');
if (railMask) railMask.onclick = () => document.body.classList.remove('rail-open');
const logoutBtn = $('#logoutBtn');
if (logoutBtn) logoutBtn.onclick = doLogout;

document.addEventListener('keydown', (e) => {
    const tag = (e.target.tagName || '').toLowerCase();
    const typing = tag === 'input' || tag === 'textarea' || tag === 'select';
    if (e.key === '/' && !typing) {
        e.preventDefault();
        $('#searchInput').focus();
        return;
    }
    if (e.key === 'Escape') {
        document.body.classList.remove('rail-open');
        const m = $('#imageModal');
        if (m && !m.hidden) {
            m.hidden = true;
            $('#imagePreview').src = '';
        } else {
            clearSelection();
        }
        if (typing) $('#searchInput').blur();
    }
});

// ---------------- 启动 ----------------
(async function boot() {
    const me = await loadCurrentUser();
    if (!me) return;
    await loadStats();
    await loadList();
})();
