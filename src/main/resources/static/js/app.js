const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

let currentType = '';
let currentKeyword = '';
let currentGroupId = null;   // null=全部, 0=未分组, >0=指定分组
let currentDate = null;      // null=不限日期，否则 'YYYY-MM-DD'（拍摄日期，没有则用上传日期）
let dateList = [];           // 日期条数据：[{day, count, size}]，按日期倒序
let groupsCache = [];
let currentPage = 1;
/** 可选每页条数。服务端上限 200（/api/files 会把它夹到 200），这里别给更大的值 */
const PAGE_SIZES = [10, 20, 50, 100, 200];
/** 每页条数：可在分页栏里改，选择记在本地 */
let PAGE_SIZE = (() => {
    try {
        const saved = Number(localStorage.getItem('cd_page_size'));
        return PAGE_SIZES.indexOf(saved) >= 0 ? saved : 20;
    } catch (e) {
        return 20;
    }
})();
let totalPages = 1;
let totalCount = 0;
let lastPageCount = 0;
let lastRecords = [];
const selected = new Set();   // 当前勾选的文件 id
let currentView = localStorage.getItem('cd_view') === 'list' ? 'list' : 'grid';
(() => {
    const v = new URLSearchParams(location.search).get('view');
    if (v === 'grid' || v === 'list') currentView = v;
})();

// ---------------- SVG 图标 ----------------
const ICONS = {
    eye: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.9-6.5 10-6.5S22 12 22 12s-3.9 6.5-10 6.5S2 12 2 12Z"/><circle cx="12" cy="12" r="2.3"/></svg>',
    play: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M7.5 5.2a1 1 0 0 1 1.5-.87l9 6.8a1 1 0 0 1 0 1.74l-9 6.8a1 1 0 0 1-1.5-.87V5.2Z"/></svg>',
    download: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v11"/><path d="m7.5 10.5 4.5 4.5 4.5-4.5"/><path d="M4.5 20h15"/></svg>',
    edit: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M12.5 20h8"/><path d="M16.7 3.9a2.1 2.1 0 0 1 3 3L7.3 19.3l-4 1 1-4L16.7 3.9Z"/></svg>',
    editor: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.85" stroke-linecap="round" stroke-linejoin="round"><rect x="2.6" y="4.2" width="11.6" height="11.6" rx="1.8"/><path d="M2.6 9.3h11.6M7.4 9.3v6.5"/><path d="M20.5 7.3a1.9 1.9 0 0 0-2.7-2.7l-4.6 4.6-.55 3.25 3.25-.55 4.6-4.6Z"/></svg>',
    trash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6.5h16"/><path d="M9 6.5V4.4h6v2.1"/><path d="M6.4 6.5 7.3 20h9.4l.9-13.5"/></svg>',
    image: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2.5"/><circle cx="8.5" cy="9.5" r="1.6"/><path d="m21 16-4.6-4.6L7 21"/></svg>',
    video: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="6" width="13" height="12" rx="2.5"/><path d="m15.5 11 5.5-3v8l-5.5-3"/></svg>',
    folder: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M3 8.2A2.2 2.2 0 0 1 5.2 6h3.4l1.8 2h8.4A2.2 2.2 0 0 1 21 10.2v7.6A2.2 2.2 0 0 1 18.8 20H5.2A2.2 2.2 0 0 1 3 17.8V8.2Z"/></svg>',
    inbox: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M3.5 13.5h4l1.2 2.2h6.6l1.2-2.2h4"/><path d="M5.3 5.4 3.5 13.5v4.3A2.2 2.2 0 0 0 5.7 20h12.6a2.2 2.2 0 0 0 2.2-2.2v-4.3L18.7 5.4A1.6 1.6 0 0 0 17.1 4.2H6.9a1.6 1.6 0 0 0-1.6 1.2Z"/></svg>',
    file: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M13.5 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8.5L13.5 3Z"/><path d="M13.5 3v5.5H19"/></svg>',
    plus: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>',
    camera: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4.5 8.6h2.4l1.3-2.1h6.6l1.3 2.1h2.4a1.9 1.9 0 0 1 1.9 1.9v7a1.9 1.9 0 0 1-1.9 1.9H4.5a1.9 1.9 0 0 1-1.9-1.9v-7a1.9 1.9 0 0 1 1.9-1.9Z"/><circle cx="12" cy="13.4" r="2.8"/></svg>',
    sparkle: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M11 3.6l1.6 4.3 4.3 1.6-4.3 1.6L11 15.4 9.4 11.1 5.1 9.5l4.3-1.6L11 3.6Z"/><path d="M18 14.6l.75 2 2 .75-2 .75-.75 2-.75-2-2-.75 2-.75.75-2Z"/></svg>',
    close: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="m6 6 12 12M18 6 6 18"/></svg>'
};

// ---------------- 工具 ----------------
/** 轻提示。opts.sticky=true 时常驻，返回的函数用来手动收起。 */
let toastSeq = 0;
function toast(msg, opts) {
    const t = $('#toast');
    const sticky = !!(opts && opts.sticky);
    const seq = ++toastSeq;
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toast._timer);
    const hide = () => {
        if (seq !== toastSeq) return;   // 已经有更新的提示了，别把它一起收掉
        t.hidden = true;
    };
    if (!sticky) toast._timer = setTimeout(hide, 2200);
    return hide;
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
    if (!ts) return '';
    const d = new Date(ts);
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function extOf(name) {
    if (!name || name.lastIndexOf('.') < 0) return 'FILE';
    return name.slice(name.lastIndexOf('.') + 1).toUpperCase().slice(0, 5);
}

// 无缩略图时的占位：视频给播放面，其他给文件图标
function fallbackMarkup(type) {
    if (type === 'VIDEO') {
        return '<span class="ph-play"><svg viewBox="0 0 24 24" fill="currentColor">'
             + '<path d="M9 7.6a1 1 0 0 1 1.52-.85l6.2 4.4a1 1 0 0 1 0 1.7l-6.2 4.4A1 1 0 0 1 9 16.4V7.6Z"/>'
             + '</svg></span>';
    }
    if (type === 'IMAGE') return '<span class="ph">' + ICONS.image + '</span>';
    return '<span class="ph">' + ICONS.file + '</span>';
}

// 统一的接口调用：会话失效（HTTP 401）直接回登录页，避免页面停在半死状态
function goLogin() {
    location.replace('login.html');
}

function api(url, opts) {
    return fetch(url, opts).then(async (r) => {
        if (r.status === 401) {
            goLogin();
            return { code: 401, message: '登录已失效，请重新登录' };
        }
        if (r.status === 403) {
            return { code: 403, message: '没有访问权限' };
        }
        try {
            return await r.json();
        } catch (e) {
            return { code: -1, message: '服务器返回异常' };
        }
    }).catch(() => ({ code: -1, message: '网络异常，请重试' }));
}

// ---------------- 登录用户 ----------------
let currentUser = null;

function initialOf(name) {
    const s = String(name || '?').trim();
    return s ? s.slice(0, 1).toUpperCase() : '?';
}

async function loadCurrentUser() {
    const res = await api('/api/auth/current');
    if (res.code !== 0 || !res.data) {
        goLogin();
        return null;
    }
    currentUser = res.data;
    const box = $('#railUser');
    if (box) box.hidden = false;
    if ($('#userAvatar')) $('#userAvatar').textContent = initialOf(currentUser.displayName || currentUser.username);
    if ($('#userName')) $('#userName').textContent = currentUser.displayName || currentUser.username;
    if ($('#userRole')) $('#userRole').textContent = currentUser.role === 'ADMIN' ? '管理员' : '普通用户';
    if ($('#adminLink')) $('#adminLink').hidden = currentUser.role !== 'ADMIN';
    return currentUser;
}

async function doLogout() {
    try {
        await fetch('/api/auth/logout', { method: 'POST' });
    } catch (e) { /* 忽略：本地会话照样丢弃 */ }
    goLogin();
}

// ---------------- 视图模式 ----------------
function applyView() {
    document.body.dataset.view = currentView;
    const grid = $('#grid');
    if (grid) grid.classList.toggle('list', currentView === 'list');
    const g = $('#viewGrid');
    const l = $('#viewList');
    if (g) g.classList.toggle('active', currentView === 'grid');
    if (l) l.classList.toggle('active', currentView === 'list');
}

function setView(v) {
    if (v === currentView) return;
    currentView = v;
    try { localStorage.setItem('cd_view', v); } catch (e) { /* 隐私模式忽略 */ }
    applyView();
}

// ---------------- 标题 / 统计 ----------------
function currentViewName() {
    const typeMap = { IMAGE: '图片', VIDEO: '视频', OTHER: '其他文件' };
    let base = currentType ? (typeMap[currentType] || '全部文件') : '全部文件';
    if (currentGroupId === 0) {
        base += ' · 未分组';
    } else if (currentGroupId > 0) {
        const g = groupsCache.find((x) => x.id === currentGroupId);
        base += ' · ' + (g ? g.name : '分组');
    }
    if (currentDate) {
        base += ' · ' + formatDayShort(currentDate);
    }
    return base;
}

function updateTitle() {
    const name = currentViewName();
    const t = $('#viewTitle');
    const c = $('#crumbName');
    if (t) t.textContent = name;
    if (c) c.textContent = name;
}

function renderStats() {
    const a = $('#statTotal');
    const b = $('#statPage');
    const c = $('#statGroups');
    if (a) a.textContent = totalCount;
    if (b) b.textContent = lastPageCount;
    if (c) c.textContent = groupsCache.length;
}

// ---------------- 列表（分页） ----------------
async function loadFiles() {
    let url = `/api/files?page=${currentPage}&size=${PAGE_SIZE}`;
    if (currentType) url += '&type=' + encodeURIComponent(currentType);
    if (currentKeyword) url += '&keyword=' + encodeURIComponent(currentKeyword);
    if (currentGroupId !== null) url += '&groupId=' + currentGroupId;
    if (currentDate) url += '&date=' + encodeURIComponent(currentDate);
    const res = await api(url);
    if (res.code !== 0) { toast(res.message || '加载失败'); return; }
    const p = res.data || {};
    totalCount = p.total || 0;
    totalPages = p.pages || 0;
    currentPage = p.page || 1;
    renderGrid(p.records || []);
    renderPagination();
    updateTitle();
    renderStats();
    loadDateBar();   // 日期条和列表用同一套过滤条件，跟着一起刷新
}

// ---------------- 日期（相册按天分组） ----------------
/** 这一条记录归到哪一天：优先拍摄时间，没有就用上传时间 */
function dayKeyOf(f) {
    const raw = f.capturedTime || f.createTime || '';
    return String(raw).slice(0, 10);
}

function todayKey() {
    const d = new Date();
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** '2026-11-09' → { y, m, d, week }，不走 Date 解析字符串，避免时区把它挪一天 */
function parseDay(key) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(key || '');
    if (!m) return null;
    const y = Number(m[1]);
    const mo = Number(m[2]);
    const d = Number(m[3]);
    const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][new Date(y, mo - 1, d).getDay()];
    return { y: y, m: mo, d: d, week: week };
}

/** 标题/胶囊用的短标签：今天 / 昨天 / 11-09 */
function formatDayShort(key) {
    if (!key) return '';
    const today = todayKey();
    if (key === today) return '今天';
    const dt = new Date();
    dt.setDate(dt.getDate() - 1);
    const p = (n) => String(n).padStart(2, '0');
    const yest = `${dt.getFullYear()}-${p(dt.getMonth() + 1)}-${p(dt.getDate())}`;
    if (key === yest) return '昨天';
    const info = parseDay(key);
    return info ? `${info.m}-${String(info.d).padStart(2, '0')}` : key;
}

/** 分组头用的长标签：今天 · 周日 / 2026年11月9日 · 周日 */
function formatDayLong(key) {
    const info = parseDay(key);
    if (!info) return key || '未知日期';
    const short = formatDayShort(key);
    const isTodayOrYest = short === '今天' || short === '昨天';
    const datePart = isTodayOrYest
        ? short
        : `${info.y}年${info.m}月${info.d}日`;
    return `${datePart} · ${info.week}`;
}

function dayStat(key) {
    return dateList.find((d) => d.day === key) || null;
}

/** 拉日期聚合，渲染顶部那条日期条 */
async function loadDateBar() {
    let url = '/api/files/dates';
    const qs = [];
    if (currentType) qs.push('type=' + encodeURIComponent(currentType));
    if (currentKeyword) qs.push('keyword=' + encodeURIComponent(currentKeyword));
    if (currentGroupId !== null) qs.push('groupId=' + currentGroupId);
    if (qs.length) url += '?' + qs.join('&');
    const res = await api(url);
    if (res.code !== 0) return;
    dateList = (res.data || []).map((d) => ({
        day: d.day,
        count: Number(d.count || 0),
        size: Number(d.size || 0)
    }));
    // 选中的那天可能已经空了（删光了 / 换了筛选条件），退回「全部」
    if (currentDate && !dateList.some((d) => d.day === currentDate)) {
        currentDate = null;
        currentPage = 1;
        loadFiles();
        return;
    }
    renderDayBar();
    refreshDayHeads();
}

/**
 * 网格里的分组头先画出来了（列表接口先返回，日期聚合后到），这里把计数补正。
 * 不重画整个网格，避免图片重新加载闪一下。
 */
function refreshDayHeads() {
    document.querySelectorAll('.day-head').forEach((head) => {
        const stat = dayStat(head.dataset.day);
        const n = head.querySelector('.day-head-n');
        if (stat && n) {
            n.textContent = `${stat.count} 项 · ${formatSize(stat.size)}`;
        }
    });
}

function renderDayBar() {
    const el = $('#dayBar');
    if (!el) return;
    if (dateList.length <= 1) {
        // 只有一天时这条没有信息量，藏起来，别占手机屏幕
        el.hidden = true;
        el.innerHTML = '';
        return;
    }
    el.hidden = false;
    el.innerHTML = '';

    const total = dateList.reduce((s, d) => s + d.count, 0);
    const all = document.createElement('button');
    all.type = 'button';
    all.className = 'day-chip' + (currentDate ? '' : ' active');
    all.innerHTML = `<b>全部</b><span>${total}</span>`;
    all.onclick = () => pickDate(null);
    el.appendChild(all);

    dateList.forEach((d) => {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'day-chip' + (currentDate === d.day ? ' active' : '');
        b.innerHTML = `<b>${formatDayShort(d.day)}</b><span>${d.count}</span>`;
        b.title = `${formatDayLong(d.day)} · ${d.count} 项 · ${formatSize(d.size)}`;
        b.onclick = () => pickDate(d.day);
        el.appendChild(b);
    });
}

function pickDate(day) {
    currentDate = (day && day !== currentDate) ? day : null;
    currentPage = 1;
    clearSelection();
    loadFiles();
    // 让选中的胶囊滚进可视区（手机上这条是横向滚动的）
    setTimeout(() => {
        const active = document.querySelector('#dayBar .day-chip.active');
        const bar = $('#dayBar');
        if (active && bar) {
            bar.scrollLeft = Math.max(0, active.offsetLeft - 12);
        }
    }, 0);
}

// ---------------- 分页控件 ----------------
/**
 * 分页栏只有两个锚点：左边「状态」，右边「控件」。
 * 「每页条数」是设置项，和翻页按钮同属右侧控件，所以包进 .page-tools 一起靠右——
 * 以前它作为第三个兄弟节点夹在中间，用 space-between 会飘在正中，看着像第三个对等的东西。
 */
function renderPagination() {
    const el = $('#pagination');
    if (!el) return;
    el.innerHTML = '';
    if (!totalCount) return;

    const info = document.createElement('div');
    info.className = 'page-info';
    info.textContent = `共 ${totalCount} 条 · 第 ${currentPage}/${totalPages} 页`;
    el.appendChild(info);

    const tools = document.createElement('div');
    tools.className = 'page-tools';

    const nav = document.createElement('div');
    nav.className = 'page-nav';

    nav.appendChild(pageBtn('上一页', currentPage <= 1, false, () => goPage(currentPage - 1)));

    // 手机上只列 3 个页码：这样按钮可以放到 44px 的触摸尺寸还排得下（列 5 个就挤了）
    const narrow = window.matchMedia && window.matchMedia('(max-width: 560px)').matches;
    pageWindow(currentPage, totalPages, narrow ? 3 : 5).forEach((n) => {
        if (n === '...') {
            const span = document.createElement('span');
            span.className = 'page-ellipsis';
            span.textContent = '…';
            nav.appendChild(span);
        } else {
            nav.appendChild(pageBtn(String(n), false, n === currentPage, () => goPage(n)));
        }
    });

    nav.appendChild(pageBtn('下一页', currentPage >= totalPages, false, () => goPage(currentPage + 1)));

    // 顺序：翻页在前、每页条数在后（最后的才是「显示设置」，紧贴右边界）
    tools.appendChild(nav);
    tools.appendChild(buildPageSize());
    el.appendChild(tools);
}

/** 每页条数选择：手机上用原生 select，点开就是系统滚轮，比自绘下拉省事也好点 */
function buildPageSize() {
    const wrap = document.createElement('label');
    wrap.className = 'page-size';
    const cap = document.createElement('span');
    cap.textContent = '每页';
    const sel = document.createElement('select');
    sel.setAttribute('aria-label', '每页显示条数');
    PAGE_SIZES.forEach((n) => {
        const o = document.createElement('option');
        o.value = String(n);
        o.textContent = String(n);
        if (n === PAGE_SIZE) o.selected = true;
        sel.appendChild(o);
    });
    sel.onchange = () => {
        const n = Number(sel.value);
        if (!n || n === PAGE_SIZE) return;
        PAGE_SIZE = n;
        try { localStorage.setItem('cd_page_size', String(n)); } catch (e) { /* 隐私模式忽略 */ }
        // 页码要回到第 1 页：原来在第 5 页、改成每页 100 条后根本没有第 5 页了
        currentPage = 1;
        clearSelection();
        loadFiles();
        toast(`每页 ${n} 条`);
    };
    wrap.appendChild(cap);
    wrap.appendChild(sel);
    return wrap;
}

function pageBtn(text, disabled, active, onClick) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'page-btn' + (active ? ' active' : '');
    b.textContent = text;
    if (disabled) b.disabled = true;
    b.onclick = onClick;
    return b;
}

function goPage(n) {
    if (n < 1 || n > totalPages || n === currentPage) return;
    currentPage = n;
    clearSelection();
    loadFiles();
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

function pageWindow(cur, total, span) {
    if (total <= span + 2) {
        return Array.from({ length: total }, (_, i) => i + 1);
    }
    const arr = [1];
    let start = Math.max(2, cur - 1);
    let end = Math.min(total - 1, cur + 1);
    if (start > 2) arr.push('...');
    for (let i = start; i <= end; i++) arr.push(i);
    if (end < total - 1) arr.push('...');
    arr.push(total);
    return arr;
}

function renderGrid(list) {
    const grid = $('#grid');
    const empty = $('#empty');
    grid.innerHTML = '';
    lastPageCount = list.length;
    lastRecords = list;
    if (!list.length) {
        empty.hidden = false;
        syncSelection();
        return;
    }
    empty.hidden = true;
    // 按日期插入分组头（全宽一行），一眼看出哪天传了哪些。
    // 跨天时同一个日期只出一行；同一天内保持服务端给的顺序（上传时间倒序）。
    let lastDay = null;
    list.forEach((f) => {
        const day = dayKeyOf(f);
        if (day !== lastDay) {
            grid.appendChild(renderDayHead(day));
            lastDay = day;
        }
        grid.appendChild(renderCard(f));
    });
    syncSelection();
}

/** 网格里的日期分组头：今天 · 周日 · 19 项 · 78.4 MB */
function renderDayHead(day) {
    const head = document.createElement('div');
    head.className = 'day-head';
    head.dataset.day = day;
    const stat = dayStat(day);
    // 计数优先用「那天全库有多少」（日期条的聚合），只有本页时退回同页张数
    const count = stat ? stat.count : lastRecords.filter((f) => dayKeyOf(f) === day).length;
    const size = stat ? stat.size : null;
    head.innerHTML = `<span class="day-head-t">${formatDayLong(day)}</span>`
        + `<span class="day-head-n">${count} 项${size != null ? ' · ' + formatSize(size) : ''}</span>`;
    return head;
}

function renderCard(f) {
    const card = document.createElement('div');
    card.className = 'card';
    card.dataset.id = f.id;

    const type = f.fileType;

    const thumb = document.createElement('div');
    thumb.className = 'card-thumb';

    const badge = document.createElement('span');
    badge.className = 'badge';
    badge.textContent = extOf(f.fileName);

    if (type === 'IMAGE' || type === 'VIDEO') {
        const img = document.createElement('img');
        img.src = mediaUrl(`/api/files/${f.id}/thumb`);
        img.loading = 'lazy';
        img.alt = f.fileName;
        img.onerror = () => {
            thumb.innerHTML = fallbackMarkup(type);
            thumb.appendChild(badge);
        };
        thumb.appendChild(img);
        thumb.title = type === 'IMAGE' ? '点击预览' : '点击播放';
        thumb.onclick = () => {
            // 已进入选择态时，点击卡片主体改为切换勾选，避免误触预览
            if (selected.size > 0) { toggleOne(f.id); return; }
            type === 'IMAGE' ? previewImage(f.id) : playVideo(f.id);
        };
    } else {
        thumb.innerHTML = fallbackMarkup(type);
        const canEdit = canEditFile(f);
        if (canEdit) thumb.classList.add('editable');
        thumb.title = canEdit ? '点击在线编辑' : '';
        thumb.onclick = () => {
            if (selected.size > 0) { toggleOne(f.id); return; }
            if (canEdit) openFileEditor(f);
        };
    }
    thumb.appendChild(badge);

    // 勾选框
    const pick = document.createElement('label');
    pick.className = 'pick' + (selected.has(f.id) ? ' checked' : '');
    pick.title = '选择';
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = selected.has(f.id);
    cb.setAttribute('aria-label', '选择 ' + f.fileName);
    cb.onchange = () => {
        if (cb.checked) selected.add(f.id); else selected.delete(f.id);
        pick.classList.toggle('checked', cb.checked);
        syncSelection();
    };
    pick.appendChild(cb);

    const body = document.createElement('div');
    body.className = 'card-body';
    // AI 自动分组成功的标记：只在「确实由 AI 改写了分组」时显示，
    // 未命中的不显示，避免每张图都挂一条无用信息
    const aiHit = f.aiGroupId && f.groupId && Number(f.aiGroupId) === Number(f.groupId);
    const aiHtml = aiHit
        ? `<div class="card-ai" title="${escapeHtml(f.aiNote || '')}">${ICONS.sparkle}<span>AI 自动分组</span></div>`
        : '';
    body.innerHTML = `<div class="card-name" title="${escapeHtml(f.fileName)}">${escapeHtml(f.fileName)}</div>${aiHtml}
                      <div class="card-meta">${formatSize(f.fileSize)} · ${formatTime(f.createTime)}</div>`;

    const actions = document.createElement('div');
    actions.className = 'card-actions';
    if (type === 'IMAGE') {
        actions.appendChild(mkBtn('预览', () => previewImage(f.id), false, ICONS.eye));
    }
    if (type === 'VIDEO') {
        actions.appendChild(mkBtn('播放', () => playVideo(f.id), false, ICONS.play));
    }
    if (canEditFile(f)) {
        actions.appendChild(mkBtn('编辑', () => openFileEditor(f), false, ICONS.editor));
    }
    actions.appendChild(mkBtn('分组', () => openGroupModal(f), false, ICONS.folder));
    actions.appendChild(mkBtn('下载', () => downloadFile(f.id), false, ICONS.download));
    actions.appendChild(mkBtn('重命名', () => renameFile(f), false, ICONS.edit));
    actions.appendChild(mkBtn('删除', () => deleteFile(f), true, ICONS.trash));

    card.appendChild(pick);
    card.appendChild(thumb);
    card.appendChild(body);
    card.appendChild(actions);
    return card;
}

// ---------------- 批量选择 ----------------
function syncSelection() {
    const n = selected.size;
    document.body.classList.toggle('selecting', n > 0);
    const bar = $('#batchBar');
    if (bar) bar.hidden = n === 0;
    const cnt = $('#batchCount');
    if (cnt) cnt.textContent = n;
    const all = $('#batchAll');
    if (all) {
        // 选了某一天时，这个按钮变成「全选这天」（跨页）
        const dayTotal = currentDate ? ((dayStat(currentDate) || {}).count || 0) : 0;
        const picked = currentDate ? selected.size >= dayTotal && dayTotal > 0
            : (n > 0 && n === lastPageCount);
        const label = picked ? '取消全选' : (currentDate ? '全选这天' : '全选本页');
        const span = all.querySelector('span');
        if (span) span.textContent = label;
        all.title = label;
    }
}

function applySelectionToDom() {
    $$('#grid .card').forEach((card) => {
        const id = Number(card.dataset.id);
        const cb = card.querySelector('.pick input');
        const pick = card.querySelector('.pick');
        if (!cb || !pick) return;
        const on = selected.has(id);
        cb.checked = on;
        pick.classList.toggle('checked', on);
    });
}

function toggleOne(id) {
    if (selected.has(id)) selected.delete(id); else selected.add(id);
    applySelectionToDom();
    syncSelection();
}

function clearSelection() {
    if (selected.size === 0) return;
    selected.clear();
    applySelectionToDom();
    syncSelection();
}

function toggleSelectAll() {
    // 选中了某一天时，「全选」= 全选那一天（跨页、走服务端取 id），否则还是全选本页
    if (currentDate) {
        selectWholeDay();
        return;
    }
    if (lastPageCount === 0) return;
    if (selected.size === lastPageCount) {
        selected.clear();
    } else {
        lastRecords.forEach((f) => selected.add(f.id));
    }
    applySelectionToDom();
    syncSelection();
}

/** 全选当前日期下的所有文件（不受分页限制） */
async function selectWholeDay() {
    const stat = dayStat(currentDate);
    const total = stat ? stat.count : 0;
    if (total > 0 && selected.size === total) {
        clearSelection();
        return;
    }
    const qs = [];
    if (currentType) qs.push('type=' + encodeURIComponent(currentType));
    if (currentKeyword) qs.push('keyword=' + encodeURIComponent(currentKeyword));
    if (currentGroupId !== null) qs.push('groupId=' + currentGroupId);
    qs.push('date=' + encodeURIComponent(currentDate));
    const res = await api('/api/files/ids?' + qs.join('&'));
    if (res.code !== 0) { toast(res.message || '加载失败'); return; }
    const ids = res.data || [];
    if (!ids.length) { toast('这天没有文件'); return; }
    selected.clear();
    ids.forEach((id) => selected.add(id));
    applySelectionToDom();
    syncSelection();
    toast(`已选中 ${formatDayShort(currentDate)} 的 ${ids.length} 项`);
}

function mkBtn(label, fn, danger, icon) {
    const b = document.createElement('button');
    b.type = 'button';
    if (danger) b.className = 'danger';
    b.title = label;
    b.setAttribute('aria-label', label);
    if (icon) {
        const ico = document.createElement('span');
        ico.className = 'ico';
        ico.innerHTML = icon;
        b.appendChild(ico);
    }
    const lbl = document.createElement('span');
    lbl.className = 'lbl';
    lbl.textContent = label;
    b.appendChild(lbl);
    b.onclick = fn;
    return b;
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// ---------------- 预览 / 播放 / 下载 ----------------
let galleryList = [];    // 当前页的图片集合（保持列表顺序）
let galleryIndex = -1;
let galleryDir = 0;      // 1=向后，-1=向前，0=首次打开（不播动画）

function imageRecordsOf(records) {
    return (records || []).filter((f) => f.fileType === 'IMAGE');
}

function previewImage(id) {
    galleryList = imageRecordsOf(lastRecords);
    galleryIndex = galleryList.findIndex((f) => f.id === id);
    $('#imageModal').hidden = false;

    if (galleryIndex < 0) {
        // 兜底：不在当前页集合内时退化为单图浏览
        galleryList = [];
        galleryDir = 0;
        showImage({ id: id, fileName: '' }, 0);
        renderGalleryMeta(null);
        return;
    }
    renderGallery(galleryIndex, 0);
}

function renderGallery(index, dir) {
    const file = galleryList[index];
    if (!file) return;
    galleryIndex = index;
    galleryDir = dir;
    renderGalleryMeta(file);
    showImage(file, 0);
}

// 每次换图 +1，用来丢弃上一张图迟到的 onload/onerror
let imgToken = 0;
let galFront = null;            // 当前可见的那一层
/**
 * 预解码缓存：url → Promise<HTMLImageElement|null>。
 *
 * 只把 <img> new 出来是不够的——浏览器只缓存了网络响应，解码后的位图并没有留住，
 * 真正上屏时还要再解一次。decode() 之后这张位图是热的，切上来几乎不花时间。
 */
const preloaded = new Map();
const PRELOAD_MAX = 8;

function preloadImage(url) {
    if (preloaded.has(url)) return preloaded.get(url);
    const im = new Image();
    const p = new Promise((resolve) => {
        im.onload = () => {
            const ok = () => resolve(im);
            if (im.decode) im.decode().then(ok, ok); else ok();
        };
        im.onerror = () => resolve(null);
    });
    im.src = url;
    preloaded.set(url, p);
    if (preloaded.size > PRELOAD_MAX) {
        // 只保留最近的几张：解码后的位图一张就是 10~20MB，不设上限手机上会撑爆
        const keys = Array.from(preloaded.keys());
        for (let i = 0; i < keys.length - PRELOAD_MAX; i++) preloaded.delete(keys[i]);
    }
    return p;
}

/**
 * 把一张图塞进预览弹窗。
 *
 * 三级回退：/preview（长边 1920，解码内存只有原图的 ~1/9）→ /raw 原图 → 提示重试。
 * 以前直接用 /raw，1200 万像素的照片解出来约 36MB，手机上连翻几张 Safari 会悄悄
 * 放弃解码——黑屏、什么都不显示，也没有任何提示，就是"有时候图片打不开"。
 *
 * 换图的做法：解到**隐藏的那一层**，等 decode() 完成再交换可见性。
 * 直接换同一个 <img> 的 src，会把解码压进"正在绘制"的那一帧里，手机上就是明显的顿一下。
 */
function showImage(file, attempt) {
    const a = $('#imagePreview');
    const b = $('#imagePreviewB');
    const state = $('#imgState');
    const text = $('#imgStateText');
    const stage = $('#galleryStage');
    if (!a) return;

    const urls = [mediaUrl(`/api/files/${file.id}/preview`), mediaUrl(`/api/files/${file.id}/raw`)];
    const url = urls[Math.min(attempt, urls.length - 1)];
    const token = ++imgToken;

    if (attempt === 0) {
        if (stage) stage.dataset.fileId = file.id;
        a.alt = file.fileName || '预览';
        state.hidden = false;
        state.dataset.mode = 'loading';
        text.textContent = '正在载入…';
    }

    const back = (galFront === a) ? b : a;
    if (!back) return;
    let committed = false;

    const commit = () => {
        if (committed || token !== imgToken) return;
        committed = true;
        back.hidden = false;
        back.classList.remove('gal-next', 'gal-prev');
        if (galleryDir) {
            void back.offsetWidth;      // 强制重排以重启动画
            back.classList.add(galleryDir > 0 ? 'gal-next' : 'gal-prev');
            galleryDir = 0;
        }
        if (galFront && galFront !== back) {
            galFront.hidden = true;
            galFront.classList.remove('gal-next', 'gal-prev');
        }
        galFront = back;
        state.hidden = true;
        state.dataset.mode = '';
        prefetchNeighbours();
    };

    back.onerror = () => {
        if (token !== imgToken) return;
        if (attempt + 1 < urls.length) {
            showImage(file, attempt + 1);
            return;
        }
        state.hidden = false;
        state.dataset.mode = 'error';
        text.textContent = '这张图加载失败，点一下重试';
    };

    back.src = url;
    if (back.decode) {
        back.decode().then(commit, () => { /* 失败交给 onerror */ });
    } else {
        back.onload = commit;
    }
}

/** 预取左右各一张（连带预解码）：手机上翻图时少一次白等 */
function prefetchNeighbours() {
    [-1, 1].forEach((d) => {
        const f = galleryList[galleryIndex + d];
        if (f) preloadImage(mediaUrl(`/api/files/${f.id}/preview`));
    });
}

function renderGalleryMeta(file) {
    const nameEl = $('#imgName');
    const countEl = $('#imgCount');
    const total = galleryList.length;
    const multi = total > 1 && galleryIndex >= 0;

    if (nameEl) nameEl.textContent = file ? file.fileName : '';
    if (countEl) countEl.textContent = multi ? `${galleryIndex + 1} / ${total}` : '';

    const prev = $('#imgPrev');
    const next = $('#imgNext');
    if (prev) prev.disabled = galleryIndex <= 0;
    if (next) next.disabled = !multi || galleryIndex >= total - 1;
}

function stepImage(delta) {
    const target = galleryIndex + delta;
    if (target < 0 || target >= galleryList.length) return;
    renderGallery(target, delta);
}

function playVideo(id) {
    const v = $('#videoPlayer');
    const state = $('#videoState');
    if (state) state.hidden = true;
    v.dataset.downloadUrl = mediaUrl(`/api/files/${id}/download`);
    v.src = mediaUrl(`/api/files/${id}/play`);
    v.load();
    $('#videoModal').hidden = false;
    fitVideoBox();
}

/**
 * 让播放器占满屏幕但不超过视频本身的比例。
 *
 * 不这么做的话，<video> 在元数据没解析出来时会停在 300×150 的默认尺寸，
 * iOS 上尤其明显——这就是"视频窗口非常小"。CSS 里已经给了兜底舞台尺寸，
 * 这里在拿到真实分辨率后再收一次边，避免竖屏视频留两条大黑边。
 */
function fitVideoBox() {
    const box = document.querySelector('.modal-video');
    const v = $('#videoPlayer');
    if (!box || !v) return;
    const w = v.videoWidth;
    const h = v.videoHeight;
    if (!w || !h) {
        box.style.width = '';
        box.style.height = '';
        return;
    }
    const maxW = Math.min(window.innerWidth * 0.92, 1180);
    const maxH = Math.min(window.innerHeight * 0.88, 720);
    // 放大上限 2 倍：再大就是糊的，不如留边
    const k = Math.min(maxW / w, maxH / h, 2);
    box.style.width = Math.max(240, Math.round(w * k)) + 'px';
    box.style.height = Math.max(150, Math.round(h * k)) + 'px';
}

function downloadFile(id) {
    window.open(mediaUrl(`/api/files/${id}/download`), '_blank');
}

// ---------------- 在线编辑（独立全屏页 editor.html） ----------------
/** 可编辑判定：图片视频走预览播放，其余按扩展名判断（白名单在 edit-types.js） */
function canEditFile(f) {
    if (!f || f.fileType === 'IMAGE' || f.fileType === 'VIDEO') return false;
    if (!window.EditTypes) return false;
    return !!window.EditTypes.modeOf(f.fileName);
}

/** 新开标签页全屏打开编辑器，让用户能像 Excel 一样整页操作 */
function openFileEditor(f) {
    const url = 'editor.html?id=' + encodeURIComponent(f.id);
    // 独立 App 里是新开窗口不可用，直接在本 WebView 里打开（编辑器有「返回云盘」链接）
    if (window.APP_IS_NATIVE) {
        location.href = url;
        return;
    }
    const w = window.open(url, '_blank');
    if (!w) toast('浏览器拦截了新标签页，请允许弹出窗口后重试');
}

async function deleteFile(f) {
    const ok = await uiConfirm({
        icon: 'trash',
        eyebrow: 'Delete',
        title: '删除此文件？',
        sub: f.fileName,
        note: '文件会移入<strong>回收站</strong>，7 天内可随时恢复。',
        okText: '移入回收站'
    });
    if (!ok) return;
    const res = await api(`/api/files/${f.id}`, { method: 'DELETE' });
    if (res.code === 0) {
        toast('已移入回收站');
        if (selected.delete(f.id)) syncSelection();
        loadFiles();
        loadGroups();
        loadRecycleBadge();
    } else {
        toast(res.message || '删除失败');
    }
}

async function renameFile(f) {
    // 与原行为一致：整个文件名（含扩展名）都可改，不做拆分
    const name = await uiPrompt({
        icon: 'pencil',
        eyebrow: 'Rename',
        title: '重命名文件',
        sub: f.fileName,
        label: '新文件名',
        value: f.fileName,
        placeholder: '输入新文件名',
        hint: '包含扩展名，例如 报告.pdf',
        maxlength: 200,
        validate: (v) => (/[/\\:*?"<>|]/.test(v) ? '文件名不能包含 \\ / : * ? " < > |' : '')
    });
    if (name == null || name === f.fileName) return;
    const res = await api(`/api/files/${f.id}/rename?name=${encodeURIComponent(name)}`, { method: 'PUT' });
    if (res.code === 0) { toast('已重命名'); loadFiles(); }
    else toast(res.message || '重命名失败');
}

// ---------------- 上传 ----------------
// 手机端批量传大图的几个关键点：
//   1) 边读元信息边上传。手机上读一张照片的 size/type 都要问一次系统相册，
//      50 张能卡好几秒；等全部读完再开始传，用户看到的就是「点了确定没反应」。
//      现在先亮出进度面板，读一张就往队列里丢一张，上传线程立刻开跑。
//   2) 一个文件一个请求。以前把所有 ≤8MB 的文件塞进同一个 multipart，20 张 4MB 照片
//      就是一个 80MB 的 POST —— 手机浏览器光把它拼进内存就要卡好几秒，
//      而且一张失败整批重来、没有任何续传粒度。
//   3) 小文件并发 4 条流水线跑。服务端一边收一边处理，总耗时从「上传 + 处理」相加
//      变成两者取大值（内网 Wi-Fi 下差距非常明显）。大文件仍串行，避免分片把上行打满。
//   4) 每个文件独立超时 + 重试，单张出错不影响其他张；重复文件（服务端 code=2）算「跳过」。
//   5) 进度按「已完成张数 + 已传字节」双指标反馈。部分移动浏览器不触发
//      xhr.upload.onprogress，光有百分比会一直卡在 1%，有张数在动用户就知道没死。
const SIMPLE_THRESHOLD = 8 * 1024 * 1024;   // 8MB 以下整体直传
const SIMPLE_CONCURRENCY = 4;               // 直传并发数（局域网/家庭 Wi-Fi 下 4 条明显快于 3 条）
const SIMPLE_TIMEOUT = 120000;              // 单文件超时 120 秒
const SIMPLE_RETRY = 2;                     // 单文件失败重试次数

const CHUNK_SIZE = 5 * 1024 * 1024;
const CHUNK_CONCURRENCY = 3;
const CHUNK_RETRY = 3;

// ---- 大图压缩：手机端「上传慢」真正的大头在字节数，不在协议 ----
// 一张 iPhone 12MP 照片导出后约 2.5~4MB，20 张就是 50~80MB。上行按家用 Wi-Fi 常见的
// 3MB/s 算，光发出去就要 20 秒以上；服务端还要把每张 4000×3000 解成缩略图。
// 缩到长边 2560（列表缩略图 320、相册预览 1920 都够用）体积只剩 1/4~1/6，
// 上传时间同比缩短，服务端解码也轻了一个数量级。
//
// 三条保险：#1 解不出来的（安卓上的 HEIC 等）直接用原图；#2 省不到 30% 也用原图；
// #3 拖拽区里的「原图」开关能一键关掉，代价就是回到慢的路径。
const SHRINK_MAX_EDGE = 2560;
const SHRINK_QUALITY = 0.86;
const SHRINK_MIN_BYTES = 900 * 1024;    // 小于 900KB 压不出什么，不如不压
const SHRINK_MIN_GAIN = 0.3;            // 压缩后不够省 30% 就放弃
const SHRINK_EXTS = /\.(jpe?g|png|heic|heif|webp|bmp|tiff?)$/i;

let uploadOriginals = false;
try { uploadOriginals = localStorage.getItem('cd_upload_original') === '1'; } catch (e) { /* 隐私模式 */ }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 解码一张图。
 *
 * 优先走 <img>：Chrome / Safari 都在解码线程里解，主线程只付一次 drawImage。
 * 实测同一张 4032×3024 的 JPEG，缩到 2560 再编码：<img> 路线约 336ms，
 * createImageBitmap(整图) 路线约 490ms（多出来的是一次 48MB 位图的内存分配）。
 * 所以 createImageBitmap 只当兜底（个别格式 / 老版本 WebView）。
 */
async function decodeImage(file) {
    const viaImg = await decodeViaImg(file);
    if (viaImg) return viaImg;
    if (typeof createImageBitmap === 'function') {
        try {
            const bmp = await createImageBitmap(file);
            if (bmp) return { img: bmp, release: () => { try { bmp.close(); } catch (e) { /* 忽略 */ } } };
        } catch (e) { /* 落到下面的 null */ }
    }
    return null;
}

function decodeViaImg(file) {
    return new Promise((resolve) => {
        const url = URL.createObjectURL(file);
        const img = new Image();
        img.onload = () => resolve({ img, release: () => URL.revokeObjectURL(url) });
        img.onerror = () => { URL.revokeObjectURL(url); resolve(null); };
        img.src = url;
    });
}

function canvasToBlob(canvas, type, quality) {
    return new Promise((resolve) => {
        if (canvas.toBlob) {
            canvas.toBlob((b) => resolve(b), type, quality);
            return;
        }
        try {
            const bin = atob(canvas.toDataURL(type, quality).split(',')[1]);
            const arr = new Uint8Array(bin.length);
            for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
            resolve(new Blob([arr], { type }));
        } catch (e) {
            resolve(null);
        }
    });
}

/** 统一成 .jpg 文件名：HEIC/PNG 压完都是 JPEG，名字跟着改，服务端才不会按后缀判错类型 */
function jpegName(name) {
    const base = String(name || 'image').replace(/\.[^./\\]+$/, '');
    return (base || 'image') + '.jpg';
}

// ---- 秒传用的 MD5 ----
// 服务端判重存的是 MD5，而 crypto.subtle 只给 SHA 系列（不提供 MD5），
// 所以这里内嵌一份 RFC 1321 的实现。只在图片上跑，输入是压完的图（几百 KB），
// 耗时几毫秒；视频不跑（iOS 每次导出的字节都不一样，算出来必然对不上）。
const MD5_S = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
const MD5_K = (() => {
    const k = new Uint32Array(64);
    for (let i = 0; i < 64; i++) k[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) >>> 0;
    return k;
})();

function md5Bytes(bytes) {
    const len = bytes.length;
    const total = (((len + 9) + 63) >> 6) << 6;   // 补 0x80 + 补零 + 8 字节长度，凑 64 的整数倍
    const buf = new Uint8Array(total);
    buf.set(bytes);
    buf[len] = 0x80;
    const dv = new DataView(buf.buffer);
    const bitLen = len * 8;
    dv.setUint32(total - 8, bitLen >>> 0, true);
    dv.setUint32(total - 4, Math.floor(bitLen / 4294967296), true);

    let a0 = 0x67452301;
    let b0 = 0xefcdab89;
    let c0 = 0x98badcfe;
    let d0 = 0x10325476;
    const M = new Uint32Array(16);

    for (let off = 0; off < total; off += 64) {
        for (let i = 0; i < 16; i++) M[i] = dv.getUint32(off + i * 4, true);
        let A = a0;
        let B = b0;
        let C = c0;
        let D = d0;
        for (let i = 0; i < 64; i++) {
            let F;
            let g;
            if (i < 16) {
                F = (B & C) | (~B & D);
                g = i;
            } else if (i < 32) {
                F = (D & B) | (~D & C);
                g = (5 * i + 1) & 15;
            } else if (i < 48) {
                F = B ^ C ^ D;
                g = (3 * i + 5) & 15;
            } else {
                F = C ^ (B | ~D);
                g = (7 * i) & 15;
            }
            const tmp = D;
            D = C;
            C = B;
            const sum = (A + F + MD5_K[i] + M[g]) >>> 0;
            const s = MD5_S[(i >> 4) * 4 + (i & 3)];
            B = (B + (((sum << s) | (sum >>> (32 - s))) >>> 0)) >>> 0;
            A = tmp;
        }
        a0 = (a0 + A) >>> 0;
        b0 = (b0 + B) >>> 0;
        c0 = (c0 + C) >>> 0;
        d0 = (d0 + D) >>> 0;
    }

    const out = new DataView(new ArrayBuffer(16));
    out.setUint32(0, a0, true);
    out.setUint32(4, b0, true);
    out.setUint32(8, c0, true);
    out.setUint32(12, d0, true);
    let hex = '';
    for (let i = 0; i < 16; i++) hex += out.getUint8(i).toString(16).padStart(2, '0');
    return hex;
}

/** 读 Blob 的字节：优先 arrayBuffer（Safari 14+），老 WebView 退回 FileReader */
function blobBytes(blob) {
    if (blob.arrayBuffer) return blob.arrayBuffer().then((b) => new Uint8Array(b));
    return new Promise((resolve, reject) => {
        const fr = new FileReader();
        fr.onload = () => resolve(new Uint8Array(fr.result));
        fr.onerror = () => reject(fr.error || new Error('读取文件失败'));
        fr.readAsArrayBuffer(blob);
    });
}

const VIDEO_EXTS = /\.(mp4|mov|m4v|avi|wmv|flv|mkv|webm|3gp|mpg|mpeg|ts)$/i;

/**
 * 拍摄时间（毫秒）。iOS 相册交给网页的 File.lastModified 就是这张照片的时间，
 * 压缩后的 File 也把 lastModified 带过去了，所以两条路都取得到。
 *
 * 设备时钟可能抽风（0 / 1970 / 未来），这里只是粗筛，服务端还会再挡一次；
 * 拿根数据线从「文件」App 传进来的文件 lastModified 通常是拷贝时间，那也没办法，只能按它算。
 */
function capturedTimeOf(file) {
    const t = file && file.lastModified;
    if (!t || t < 946684800000 || t > Date.now() + 2 * 86400000) return 0;
    return t;
}

/** 这个 job 是图片 / 视频 / 其他？秒传只对前两类有意义 */
function kindOf(job) {
    const type = (job.type || '').toLowerCase();
    if (/^video\//.test(type) || VIDEO_EXTS.test(job.name || '')) return 'video';
    if (/^image\//.test(type) || SHRINK_EXTS.test(job.name || '')) return 'image';
    return 'other';
}

/**
 * 秒传预检：上传前先问一句「库里有没有」，命中就直接跳过，一个字节都不发。
 *
 * 图片带本地算的 md5（算的就是即将发出去的那份字节，服务端算出来一模一样）；
 * 视频不带 md5 —— iOS 每次导出都会重写容器元数据，同一段视频每次 md5 都不同，
 * 服务端那边按「文件名 + 大小」判（见 FileInfoMapper#selectLiveByNameAndSize）。
 *
 * 预检失败（断网、接口异常）就当没命中，照常上传 —— 秒传是省流量，不能变成拦路虎。
 */
async function precheckExisting(job) {
    const kind = kindOf(job);
    if (kind === 'other') return null;   // PDF / zip 之类没有秒传，别白跑一次请求
    try {
        const body = { fileName: job.name, fileSize: job.size };
        if (kind === 'image') body.md5 = md5Bytes(await blobBytes(job.blob));
        const res = await api('/api/files/precheck', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        if (res && res.code === 0 && res.data && res.data.exists) return res.data;
    } catch (e) { /* 忽略，走正常上传 */ }
    return null;
}

/**
 * 压缩并发闸门：同时只解 2 张。
 * 一张 12MP 照片解码后约 48MB 位图，4 条上传流水线一起解就是 200MB 上下 ——
 * iPhone 上 Safari 会因此直接 reload 整个页面（用户看到的就是「传到一半白屏」）。
 * 2 张既不会把内存顶到上限，也够喂满上行带宽。
 */
const SHRINK_CONCURRENCY = 2;
let shrinkBusy = 0;
const shrinkQueue = [];

function shrinkSlot() {
    if (shrinkBusy < SHRINK_CONCURRENCY) {
        shrinkBusy += 1;
        return Promise.resolve();
    }
    return new Promise((resolve) => shrinkQueue.push(resolve));
}

function releaseShrinkSlot() {
    const next = shrinkQueue.shift();
    if (next) next();          // 名额直接转交给排队的那一个，busy 不变
    else shrinkBusy -= 1;
}

/**
 * 把一张大图缩到长边 SHRINK_MAX_EDGE。压不动 / 不划算返回 null，调用方用原图。
 * HEIC 也走这条路：iOS 17.4 起相册不再无条件把 HEIC 转成 JPEG，直接给网页原图，
 * 而服务端 ImageIO 解不了 HEIC —— 缩一次顺带把「Chrome 里图片裂开」也修了。
 */
async function shrinkImage(file) {
    if (uploadOriginals || !file) return null;
    if (file.size < SHRINK_MIN_BYTES) return null;
    const name = file.name || '';
    const type = (file.type || '').toLowerCase();
    if (!/^image\//.test(type) && !SHRINK_EXTS.test(name)) return null;
    // 动图 / 矢量压了就废了
    if (/gif|svg/.test(type) || /\.(gif|svg)$/i.test(name)) return null;

    let src = null;
    try {
        src = await decodeImage(file);
        if (!src) return null;
        const w = src.img.width || src.img.naturalWidth || 0;
        const h = src.img.height || src.img.naturalHeight || 0;
        if (!w || !h) return null;

        const long = Math.max(w, h);
        // 尺寸本来就不大、体积也不夸张：压了也省不下，白烧 CPU
        if (long <= SHRINK_MAX_EDGE && file.size < SHRINK_MIN_BYTES * 2) return null;

        const ratio = Math.min(1, SHRINK_MAX_EDGE / long);
        const tw = Math.max(1, Math.round(w * ratio));
        const th = Math.max(1, Math.round(h * ratio));

        const canvas = document.createElement('canvas');
        canvas.width = tw;
        canvas.height = th;
        const g = canvas.getContext('2d');
        if (!g) return null;
        g.imageSmoothingEnabled = true;
        g.imageSmoothingQuality = 'high';
        g.drawImage(src.img, 0, 0, tw, th);

        const blob = await canvasToBlob(canvas, 'image/jpeg', SHRINK_QUALITY);
        if (!blob) return null;
        if (blob.size > file.size * (1 - SHRINK_MIN_GAIN)) return null;
        return new File([blob], jpegName(name), {
            type: 'image/jpeg',
            lastModified: file.lastModified || Date.now()
        });
    } catch (e) {
        return null;
    } finally {
        if (src) src.release();
    }
}

/** 剩余时长：只在预计还要等 1 秒以上时展示，避免数字乱跳 */
function formatDuration(sec) {
    const s = Math.round(sec);
    if (s < 60) return s + 's';
    const m = Math.floor(s / 60);
    const r = s % 60;
    return m + 'm' + (r ? r + 's' : '');
}

/**
 * 上传各阶段的耗时埋点。
 *
 * 「选完照片要等 10 秒」这句抱怨里其实混着三段完全不同的时间：
 *   ① 点「照片/视频」→ change 触发：系统相册在导出/转码文件，网页一行 JS 都还没跑；
 *   ② change → 第一条上传请求发出：这一段才是本项目该负责的；
 *   ③ 上传本身：字节数 ÷ 上行带宽 + 服务端处理。
 * 不分开量就只能靠猜。地址栏带 ?perf=1（或 localStorage.cd_perf=1）时启用，
 * 结束后弹一条 toast 并在服务端日志里留一行 [picker] perf ...，手机上不用抄数字。
 * 不启用时所有调用都是空操作，不影响正常上传。
 */
const PERF = (() => {
    let on = false;
    try {
        on = new URLSearchParams(location.search).has('perf') ||
            localStorage.getItem('cd_perf') === '1';
    } catch (e) { on = false; }
    return { on, pick: 0, change: 0, first: 0, end: 0, count: 0 };
})();

function perfStart(count) {
    if (!PERF.on) return;
    PERF.count = count;
}

/**
 * 每次点开相册就清零。
 * 注意：不能在 doUpload 里清 —— 那时候 change 早就触发过了（change 标记会被抹掉，
 * 报出来就成了「点按钮→首包」混着用户挑图的时间，看不出页面自己的开销）。
 */
function perfReset() {
    if (!PERF.on) return;
    PERF.change = 0;
    PERF.first = 0;
    PERF.end = 0;
    PERF.count = 0;
}

/** 只记第一次：第一张开始传的时刻才是用户感知的「开始上传」 */
function perfMark(key) {
    if (!PERF.on || PERF[key]) return;
    PERF[key] = performance.now();
}

function perfReport(extra) {
    if (!PERF.on) return;
    PERF.end = performance.now();
    const seg = [];
    const add = (label, a, b) => {
        if (a && b && b >= a) seg.push(label + ' ' + Math.round(b - a) + 'ms');
    };
    // 「点按钮→change」里混着用户翻相册、勾选的时间，不是页面开销，标签要写清楚
    add('点按钮→change(含挑图)', PERF.pick, PERF.change);
    add('change→首包', PERF.change || PERF.pick, PERF.first);
    add('首包→完成', PERF.first, PERF.end);
    add('总计', PERF.pick, PERF.end);
    const line = seg.join(' · ') + ` · ${PERF.count} 个 · ${extra || ''}`;
    console.log('[perf] ' + line);
    toast('耗时：' + line);
    try {
        fetch('/api/debug/picker', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: 'perf ' + line
        }).catch(() => {});
    } catch (e) { /* 埋点失败不影响业务 */ }
    perfReset();
}

/**
 * 上传调度。
 *
 * 为什么允许「边传边选」：iPhone 上每张照片在交给网页之前，系统要花约 230ms 做
 * HEIC→JPEG 转码和拷贝（实测 5 张比 1 张多 919ms）。这段耗时发生在相册关闭之前，
 * 网页完全无法干预。它唯一能被"利用"的地方就是——让它和上传同时进行。
 *
 * 于是选第二批次时不再被挡回去，而是排队：用户在传第一批的同时去相册挑第二批，
 * 系统的准备时间就被上传时间吸收掉了。第一批传完，第二批立刻起跑，中间不需要再等。
 */
let uploadBusy = false;
const uploadQueue = [];

/** 选择器是否还开着。开着的时候不能动 input.value，否则部分 WebView 会丢掉待交付的文件 */
let pickerOpen = false;

function openPicker(input) {
    const el = typeof input === 'string' ? $(input) : input;
    if (!el) return;

    // 原生壳（Capacitor）里的「照片/视频」走系统 PHPicker：
    // 点「添加」相册立刻关，文件由 App 在后台取回；
    // 网页的 <input type="file"> 则要等 iOS 把整批照片导出完才关窗（10 张 2~3 秒）。
    if (el.id === 'mediaInput' && window.NativeShell && window.NativeShell.canPick()) {
        return openMediaSheet();
    }

    pickerOpen = true;
    if (PERF.on) {
        perfReset();
        PERF.pick = performance.now();
    }
    // 兜底：用户把相册挂着不管，标志位不能永远为真
    setTimeout(() => { pickerOpen = false; }, 180000);
    el.click();
}

/**
 * 原生壳里点「照片/视频」：先让用户选「照片」还是一段视频。
 *
 * 照片走 PHPicker（pickImages）：点「添加」相册立刻关，原生缩到 2560 再交给我们；
 * 视频仍走系统相册（chooseFromGallery）：IONCAMR 会在关窗前把视频导出，所以视频
 * 这一段还是要等——但日常传照片不再等。
 */
function openMediaSheet() {
    const sheet = $('#mediaSheet');
    if (!sheet) return;
    sheet.hidden = false;
}

async function pickFromNative(kind) {
    const isVideo = kind === 'video';
    const pick = isVideo ? window.NativeShell.pickVideos : window.NativeShell.pickPhotos;
    const hide = toast(isVideo ? '正在从相册导入视频，请稍候…' : '正在从相册导入照片，请稍候…', { sticky: true });
    let files = null;
    try {
        files = await pick();
    } catch (err) {
        const msg = (err && err.message) || String(err || '');
        if (/denied access to photos/i.test(msg)) {
            // pickImages 要求「全部照片」权限；用户选了「有限访问」时插件会拒绝
            toast('请在 设置 → 隐私与安全性 → 照片 里把「云盘」改成「全部照片」');
            return;
        }
        if (!/cancel/i.test(msg)) toast((isVideo ? '视频' : '照片') + '导入失败：' + (msg || '未知错误'));
        return;
    } finally {
        hide();
    }
    if (files && files.length) await uploadFiles(files);
}

/**
 * 选完文件后的确认面板：按拍摄日期分组列一遍，让你先看清「这次到底选了什么」。
 *
 * 为什么不做成「全部检查完再传」：要判断一张图在不在库里，必须先用压缩后的字节算 md5，
 * 20 张就是 3~5 秒干等 —— 那等于把刚省下来的等待又加回去。所以这里只做零等待的事：
 *
 *   · 按日期分组、逐条列出（马上可见）
 *   · 大文件（≥8MB，基本都是视频）用「文件名 + 大小」问一次服务端，命中当场标灰并取消勾选
 *   · 图片的命中情况在上传过程中回填（秒传本来就只花几十毫秒）
 *
 * @return Promise<File[]> 勾选要上传的文件；取消返回空数组
 */
function confirmUploadBatch(files) {
    const sheet = $('#uploadSheet');
    const listEl = $('#sheetList');
    const titleEl = $('#sheetTitle');
    const subEl = $('#sheetSub');
    const startBtn = $('#sheetStart');
    const cancelBtn = $('#sheetCancel');
    if (!sheet || !listEl) return Promise.resolve(files);

    // 按拍摄日期分组（没有就用现在），新的在前；同一天里保持选中顺序
    const rows = files.map((f, i) => {
        const t = capturedTimeOf(f);
        const day = t ? new Date(t) : new Date();
        const key = `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`;
        return { f: f, idx: i, day: key, checked: true, exists: false };
    });
    rows.sort((a, b) => (a.day === b.day ? 0 : (a.day < b.day ? 1 : -1)));

    listEl.innerHTML = '';
    let lastDay = null;
    const rowEls = [];
    rows.forEach((r) => {
        if (r.day !== lastDay) {
            const head = document.createElement('div');
            head.className = 'sheet-day';
            head.innerHTML = `<span>${formatDayLong(r.day)}</span><em></em>`;
            listEl.appendChild(head);
            r.head = head;
            lastDay = r.day;
        }
        const row = document.createElement('label');
        row.className = 'sheet-row';
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.checked = true;
        cb.onchange = () => {
            r.checked = cb.checked;
            refresh();
        };
        const name = document.createElement('span');
        name.className = 'sheet-name';
        name.textContent = r.f.name || '文件';
        const meta = document.createElement('span');
        meta.className = 'sheet-meta';
        meta.textContent = r.f.size ? formatSize(r.f.size) : '—';
        const state = document.createElement('span');
        state.className = 'sheet-state';
        state.textContent = '';
        row.appendChild(cb);
        row.appendChild(name);
        row.appendChild(meta);
        row.appendChild(state);
        listEl.appendChild(row);
        r.el = row;
        r.cb = cb;
        r.stateEl = state;
        rowEls.push(r);
    });

    const dayCount = () => {
        const m = {};
        rows.forEach((r) => { m[r.day] = (m[r.day] || 0) + 1; });
        return m;
    };
    const counts = dayCount();
    const heads = {};
    rows.forEach((r) => { if (r.head) heads[r.day] = r.head; });
    Object.keys(heads).forEach((d) => {
        heads[d].querySelector('em').textContent = `${counts[d]} 项`;
    });

    function refresh() {
        const picked = rows.filter((r) => r.checked && !r.exists);
        const existsN = rows.filter((r) => r.exists).length;
        const title = `${rows.length} 项`;
        titleEl.textContent = '本次选择 · ' + title;
        const totalSize = rows.reduce((s, r) => s + (r.f.size || 0), 0);
        const unknownSize = rows.some((r) => !r.f.size);
        subEl.textContent = existsN
            ? `其中 ${existsN} 项已在库里，已替你取消勾选`
            : `${unknownSize ? '大小待读取' : formatSize(totalSize)} · 点「开始上传」立即开始`;
        startBtn.textContent = picked.length ? `开始上传 ${picked.length} 项` : '没有可上传的';
        startBtn.disabled = picked.length === 0;
    }
    refresh();

    // 大文件：先问一句「库里有没有」。
    // 只问视频：判定靠「文件名 + 大小」，不用读内容，几十毫秒就能标出来；
    // 大图不能这么问（图片的判定指纹是压缩后的 md5，sha 不出来就不能提前判，硬算反而多花几百毫秒）。
    rows.filter((r) => r.f.size >= SIMPLE_THRESHOLD
        && kindOf({ name: r.f.name, type: r.f.type }) === 'video').forEach(async (r) => {
        r.stateEl.textContent = '检查中…';
        const hit = await precheckExisting({
            blob: r.f, name: r.f.name || 'file', size: r.f.size, type: r.f.type || ''
        });
        if (hit) {
            r.exists = true;
            r.checked = false;
            r.cb.checked = false;
            r.cb.disabled = true;
            r.el.classList.add('is-exists');
            r.stateEl.textContent = '已在库里';
        } else {
            r.stateEl.textContent = '';
        }
        refresh();
    });

    return new Promise((resolve) => {
        let settled = false;
        const done = (picked) => {
            if (settled) return;
            settled = true;
            document.removeEventListener('keydown', onKey);
            sheet.hidden = true;
            resolve(picked);
        };
        const onKey = (e) => { if (e.key === 'Escape') done([]); };
        document.addEventListener('keydown', onKey);

        startBtn.onclick = () => done(rows.filter((r) => r.checked && !r.exists).map((r) => r.f));
        cancelBtn.onclick = () => done([]);
        // 遮罩和右上角 X 走 data-close（closeModal 只负责隐藏），这里补上 Promise 收尾
        sheet.querySelectorAll('[data-close]').forEach((el) => {
            el.addEventListener('click', () => done([]));
        });
        sheet.hidden = false;
    });
}

function uploadFiles(fileList) {
    const files = Array.from(fileList || []);
    if (!files.length) return Promise.resolve();

    // 单个小文件直接传（少一次点击）；多个文件、或者单个大文件，先摆出清单确认
    const needConfirm = files.length > 1 || files.some((f) => f.size >= SIMPLE_THRESHOLD);
    if (!needConfirm) {
        return enqueueUpload(files);
    }
    return confirmUploadBatch(files).then((picked) => {
        if (!picked.length) {
            toast('已取消上传');
            return Promise.resolve();
        }
        return enqueueUpload(picked);
    });
}

/** 真正的入队与上传：正在传就排队，传完自动接下一批 */
function enqueueUpload(fileList) {
    const files = Array.from(fileList || []);
    if (!files.length) return Promise.resolve();

    if (uploadBusy) {
        uploadQueue.push(files);
        toast(`已排队 ${files.length} 个，本批传完自动开始`);
        return Promise.resolve();
    }

    uploadBusy = true;
    return doUpload(files).finally(() => {
        uploadBusy = false;
        // 本批结束才清空 input：提前清会让部分安卓 WebView 释放 File 底层句柄，
        // 上传到一半的文件会突然读不出来。清空是为了下次还能选同一个文件。
        // 用户正开着选择器挑下一批时不能动它。
        if (!pickerOpen) {
            ['#fileInput', '#mediaInput'].forEach((sel) => {
                const inp = $(sel);
                if (inp) inp.value = '';
            });
        }
        const next = uploadQueue.shift();
        if (next) enqueueUpload(next);
    });
}

async function doUpload(fileList) {
    if (!fileList || !fileList.length) return;
    const files = Array.from(fileList);
    if (!files.length) return;

    // 关键：先把进度面板画出来，再去碰文件。
    // 手机上读一张照片的 size / type 都要问一次系统相册，50 张就是几秒，
    // 以前是先读完全部元信息才建面板 —— 用户看到的就是「点了确定，屏幕一动不动」。
    const ctx = createUploadProgress(files.length);
    ctx.paint(true);
    await sleep(0);

    const st = ctx.st;
    let hasImage = false;

    // 边读边传：每读完一张就丢进队列，上传线程立刻开跑，
    // 不必等 50 张全部读完。总耗时不变，但第一张的反馈从「几秒后」变成「立刻」。
    const queue = [];
    let readerDone = false;

    const reader = (async () => {
        // 每读 2 张就让一次主线程。读 size / type 在安卓上是一次跨进程查询，
        // 攒着读 6 张就是几百毫秒不松手，页面滑动、按钮反馈全被卡住。
        const SLICE = 2;
        for (let i = 0; i < files.length; i++) {
            const f = files[i];
            const meta = { f, size: f.size, type: f.type || '', idx: ctx.nextIndex() };
            if (/^image\//.test(meta.type)) hasImage = true;
            if (!meta.type && SHRINK_EXTS.test(f.name || '')) hasImage = true;
            ctx.addTotal(meta.size);
            queue.push(meta);
            st.read = i + 1;
            if ((i + 1) % SLICE === 0) {
                ctx.paint(true);
                await sleep(0);
            }
        }
        readerDone = true;
        ctx.paint(true);
    })();

    // 取一件待传：队列空了就等读线程继续喂，读完且空了才算收工
    const take = async () => {
        for (;;) {
            if (queue.length) return queue.shift();
            if (readerDone) return null;
            await sleep(30);
        }
    };

    // 大文件串行：分片上传内部已经有 3 条并发，再多路叠加会把手机上行打满、CPU 也跟不上
    let bigChain = Promise.resolve();
    const runBig = (task) => {
        const run = bigChain.then(task, task);
        bigChain = run.then(() => {}, () => {});
        return run;
    };

    /**
     * 上传前处理：大图先缩一遍。
     * 放在 worker 里而不是读线程里，是为了让「压缩」和「上传」真正并行 ——
     * 一张压完立刻发出去，网络在跑的时候 CPU 压下一张，互不等待。
     */
    const prepare = async (item) => {
        // 原生相册的视频：几百 MB 的东西一旦读进 JS 就是灾难，原样带着路径交给原生上传
        if (item.f && item.f.nativePath) {
            return {
                nativePath: item.f.nativePath,
                name: item.f.name || 'video',
                size: item.f.size || 0,
                type: item.f.type || '',
                lastModified: item.f.lastModified || 0
            };
        }

        // 照片（PHPicker pickImages）：原生已经按长边 2560 / q86 缩好，
        // 这里只把字节读进来，不再用 canvas 压第二次。
        if (item.f && item.f.precompressed) {
            st.label = '读取 ' + (item.f.name || '');
            ctx.paint();
            const blob = await window.NativeShell.readFile(item.f.webPath);
            const file = new File([blob], item.f.name || 'image.jpg', {
                type: 'image/jpeg',
                lastModified: item.f.lastModified || Date.now()
            });
            if (file.size !== item.size) {
                ctx.addTotal(file.size - item.size);
                item.size = file.size;
            }
            item.f = file;
            return { blob: file, name: file.name, size: file.size, type: 'image/jpeg', precompressed: true };
        }

        // 原生相册的照片：轮到它时才读进内存。一次只读一张，
        // 而不是把整批 20 张先变成 60MB Blob 压在 WKWebView 里。
        if (item.f && item.f.nativePhoto) {
            st.label = '读取 ' + (item.f.name || '');
            ctx.paint();
            const blob = await window.NativeShell.readFile(item.f.webPath);
            const file = new File([blob], item.f.name || 'image.jpg', {
                type: item.f.type || blob.type || 'image/jpeg',
                lastModified: item.f.lastModified || Date.now()
            });
            if (file.size !== item.size) {
                ctx.addTotal(file.size - item.size);
                item.size = file.size;
            }
            item.f = file;
        }

        const job = { blob: item.f, name: item.f.name || 'file', size: item.f.size || item.size, type: item.type };
        if (!/^image\//.test(item.type) && !SHRINK_EXTS.test(item.f.name || '')) return job;
        st.label = '压缩 ' + (item.f.name || '');
        ctx.paint();
        await shrinkSlot();
        let small = null;
        try {
            small = await shrinkImage(item.f);
        } finally {
            releaseShrinkSlot();
        }
        if (!small) {
            st.label = '';
            return job;
        }
        // 总量跟着改成压缩后的字节：进度条按「张数」走，但已传/总量得说真话
        ctx.addTotal(small.size - item.size);
        return { blob: small, name: small.name, size: small.size, type: 'image/jpeg', shrunk: item.size };
    };

    const worker = async () => {
        for (;;) {
            const item = await take();
            if (!item) return;
            let job = null;
            try {
                // 压缩可能改变大小，所以先压再决定走直传还是分片：
                // 一张 20MB 的图缩完只有 1MB，就该走直传，而不是切四个分片
                job = await prepare(item);

                // 秒传：库里已经有了就直接跳过，一个字节都不发
                const hit = await precheckExisting(job);
                if (hit) {
                    st.skipped += 1;
                    ctx.fail(item.idx, job, null, '秒传');
                    st.label = '';
                    ctx.paint(true);
                    continue;
                }

                if (job.nativePath) {
                    // 原生上传也走大文件串行链：FileTransfer 的进度事件是全局的，
                    // 同时发两路就分不清进度属于谁了
                    await runBig(() => uploadNativeWithRetry(item.idx, job, ctx));
                } else if (job.size <= SIMPLE_THRESHOLD) {
                    await uploadSimpleWithRetry(item.idx, job, ctx);
                } else {
                    await runBig(() => uploadOneChunked(job, item.idx, ctx));
                }
                st.done += 1;
            } catch (e) {
                // 进度按「实际发出去的字节」算：跳过重复的那张也压过了，
                // 用压缩后的 size，进度条才不会出现「已传 > 总量」的怪数字
                const info = job || { name: item.f.name, size: item.size };
                if (e && e.duplicate) {
                    st.skipped += 1;
                    ctx.fail(item.idx, info, null, e.message);
                } else {
                    ctx.fail(item.idx, info, e);
                }
            }
            st.label = '';
            ctx.paint(true);
        }
    };

    perfStart(files.length);
    try {
        // 读线程和上传线程同时开跑：不能先 await reader，否则又变回「全部读完才开始传」
        const pool = Promise.all(
            Array.from({ length: Math.min(SIMPLE_CONCURRENCY, files.length) }, worker));
        await Promise.all([reader, pool]);
        ctx.paint(true);
        // 让 100% 停一下再收起，否则进度条一闪而过，用户不知道到底传完了没有
        if (st.count) await sleep(420);
    } finally {
        ctx.hide();
    }

    const bits = [];
    if (st.done > 0) bits.push(`成功 ${st.done} 个`);
    if (st.skipped > 0) bits.push(`跳过重复 ${st.skipped} 个`);
    if (st.failed > 0) bits.push(`失败 ${st.failed} 个`);

    if (st.failed > 0) {
        const first = ctx.firstFailure();
        toast(`${bits.join('，')}${first ? '（' + first + '）' : ''}`);
    } else if (st.skipped > 0 && st.done === 0) {
        const names = ctx.duplicateNames();
        toast(`所选文件都已存在，已跳过 ${st.skipped} 个${names ? '：' + names : ''}`);
    } else if (bits.length) {
        toast(`已上传 ${st.done} 个文件${st.skipped ? `，跳过重复 ${st.skipped} 个` : ''}` +
            (hasImage && st.done ? '，正在智能识别图片…' : ''));
    }
    perfReport(`成功 ${st.done} · 跳过 ${st.skipped} · 失败 ${st.failed} · 发出 ${formatSize(ctx.sentBytes())}`);

    if (st.done > 0 || st.skipped > 0) {
        loadFiles();
        loadGroups();
    }

    // AI 识别在服务端异步执行，此时列表里还是原分组。
    // 等一会儿再刷一次，让「自动归组」的结果自己浮现出来。
    if (hasImage && st.done > 0) {
        setTimeout(() => { loadFiles(); loadGroups(); }, 7000);
        setTimeout(() => { loadFiles(); loadGroups(); }, 16000);
    }
}

/** 上传进度面板：张数 + 字节总量 + 实时速度 + 剩余时间 */
function createUploadProgress(totalCount) {
    const prog = $('#uploadProgress');
    const fill = $('#barFill');
    const text = $('#progressText');
    const note = $('#progressSpeed');

    let totalBytes = 0;
    let seq = 0;
    let sentBytes = 0;              // 真正发出去并被服务端收下的字节（跳过的不算，秒传更不算）
    const perFile = new Map();

    // count = 本次选中的总张数（一开始就知道），read = 已读到元信息的张数
    const st = { count: totalCount, done: 0, failed: 0, skipped: 0, read: 0, label: '' };
    const failures = [];
    const duplicates = [];
    const samples = [];
    let lastPaint = 0;

    prog.hidden = false;
    fill.style.width = '1%';
    text.textContent = totalCount > 1 ? `正在读取 0/${totalCount} 个文件…` : '准备上传…';
    if (note) note.textContent = '';

    const loadedBytes = () => {
        let s = 0;
        perFile.forEach((v) => { s += v; });
        return s;
    };

    // 近 4 秒滑动窗口的速度。用总平均值会把早期的突发抹平，
    // 用户看到的「速度」就跟当下手感对不上。
    const speedNow = (cur) => {
        const now = Date.now();
        samples.push({ t: now, v: cur });
        while (samples.length > 1 && now - samples[0].t > 4000) samples.shift();
        const first = samples[0];
        const dt = (now - first.t) / 1000;
        return dt < 0.5 ? 0 : Math.max(0, (cur - first.v) / dt);
    };

    const paint = (force) => {
        const now = Date.now();
        // 手机上每 100ms 重排一次 DOM 是白烧电量，180ms 肉眼已看不出跳
        if (!force && now - lastPaint < 180) return;
        lastPaint = now;

        const cur = loadedBytes();
        // 读元信息的阶段按「已读张数」走，进入上传后按「已处理张数」走
        const processed = st.done + st.failed + st.skipped;
        const pct = totalBytes <= 0
            ? Math.floor((st.read / Math.max(1, st.count)) * 100)
            : Math.min(100, Math.floor((processed / Math.max(1, st.count)) * 100));
        fill.style.width = Math.max(1, pct) + '%';

        const bits = [`${processed}/${st.count} 个文件`];
        if (totalBytes > 0) {
            bits.push(`${formatSize(cur)} / ${formatSize(totalBytes)}`);
        } else {
            bits.push(`已读取 ${st.read}/${st.count}`);
        }
        const sp = speedNow(cur);
        if (sp > 0) {
            bits.push(formatSize(sp) + '/s');
            const rest = (totalBytes - cur) / sp;
            if (rest > 1) bits.push('剩 ' + formatDuration(rest));
        }
        text.textContent = bits.join(' · ');
        if (note) note.textContent = st.label || '';
    };

    return {
        st,
        perFile,
        paint,
        nextIndex: () => seq++,
        /** 本轮真正上传成功的字节数（进度面板里的「已传」还含被跳过的，别拿它当流量统计） */
        sentBytes: () => sentBytes,
        addSent(bytes) {
            sentBytes += bytes || 0;
        },
        addTotal(bytes) {
            totalBytes += bytes || 0;
        },
        fail(index, file, err, message) {
            // 失败的、跳过的都要计入进度，否则进度条永远停在 99%，看着像卡死
            perFile.set(index, file.size);
            if (!err) {
                // 重复文件：计数由调用方（worker）负责，这里只留痕给最后的提示用
                duplicates.push(`${file.name}${message ? '（' + message + '）' : ''}`);
                return;
            }
            st.failed += 1;
            failures.push(`${file.name}：${(err && err.message) || '上传失败'}`);
        },
        firstFailure: () => failures[0],
        duplicateNames: () => duplicates.slice(0, 3).join('、'),
        hide() {
            prog.hidden = true;
            fill.style.width = '0%';
            if (note) note.textContent = '';
        }
    };
}

/**
 * 单文件直传：用 XHR 而不是 fetch，因为只有它能拿到浏览器原生上传进度。
 * job = { blob, name, size }：压缩过的图 blob 是新的 Blob，不一定还有 file.name，
 * 所以文件名一律显式带上。
 */
function uploadSimple(index, job, ctx) {
    return new Promise((resolve, reject) => {
        const fd = new FormData();
        fd.append('file', job.blob, job.name);
        if (currentGroupId !== null && currentGroupId > 0) fd.append('groupId', currentGroupId);
        // 相册按日期分组要用：没有它就只能按上传时间归组
        const captured = capturedTimeOf(job.blob);
        if (captured) fd.append('capturedTime', String(captured));

        const xhr = new XMLHttpRequest();
        let settled = false;
        const settle = (fn, val) => { if (!settled) { settled = true; fn(val); } };

        xhr.open('POST', '/api/files/upload');
        xhr.timeout = SIMPLE_TIMEOUT;
        xhr.upload.onprogress = (e) => {
            if (!e.lengthComputable) return;
            ctx.perFile.set(index, Math.min(e.loaded, job.size));
            ctx.paint();
        };
        xhr.onload = () => {
            if (xhr.status === 401) {
                goLogin();
                settle(reject, new Error('登录已失效'));
                return;
            }
            let res = null;
            try { res = JSON.parse(xhr.responseText); } catch (e) { res = null; }
            if (res && res.code === 0) {
                ctx.perFile.set(index, job.size);
                ctx.addSent(job.size);
                settle(resolve, res.data);
                return;
            }
            // code=2 是服务端的「这份内容库里已经有了」，不是失败：标记出来让上层跳过
            if (res && res.code === 2) {
                const err = new Error(res.message || '文件已存在');
                err.duplicate = true;
                ctx.perFile.set(index, job.size);
                settle(reject, err);
                return;
            }
            settle(reject, new Error((res && res.message) || `上传失败（HTTP ${xhr.status}）`));
        };
        xhr.onerror = () => settle(reject, new Error('网络中断'));
        xhr.ontimeout = () => settle(reject, new Error('上传超时'));
        xhr.onabort = () => settle(reject, new Error('上传被中断'));
        perfMark('first');
        xhr.send(fd);
    });
}

/** 单文件失败自动重试；登录失效、重复文件不重试（重试也不会有不同结果） */
async function uploadSimpleWithRetry(index, job, ctx) {
    let lastErr = null;
    for (let attempt = 0; attempt <= SIMPLE_RETRY; attempt++) {
        try {
            return await uploadSimple(index, job, ctx);
        } catch (e) {
            lastErr = e;
            if (/登录已失效/.test(e.message) || e.duplicate) throw e;
            ctx.perFile.set(index, 0);
            ctx.paint(true);
            if (attempt < SIMPLE_RETRY) await sleep(500 * (attempt + 1));
        }
    }
    throw lastErr || new Error('上传失败');
}

/**
 * 原生直传（Capacitor FileTransfer）：从原生文件路径直接读磁盘发，
 * 不经过 WebView 内存——相册里的视频就靠这条路。
 *
 * 接口和网页直传完全一样：multipart 字段 `file`，groupId / capturedTime 放在查询参数里
 * （服务端 @RequestParam 两者都认），所以服务端一行都不用改。
 */
function uploadNative(index, job, ctx) {
    return new Promise((resolve, reject) => {
        const params = {};
        if (currentGroupId !== null && currentGroupId > 0) params.groupId = String(currentGroupId);
        const captured = capturedTimeOf(job);
        if (captured) params.capturedTime = String(captured);

        const onProgress = (bytes, total) => {
            const size = (total && total > 0) ? total : job.size;
            if (!size) return;
            ctx.perFile.set(index, Math.min(bytes, size));
            ctx.paint();
        };

        window.NativeShell.upload({
            url: '/api/files/upload',
            path: job.nativePath,
            params: params,
            mimeType: job.type || ''
        }, onProgress).then((res) => {
            if (res.status === '401') {
                goLogin();
                return reject(new Error('登录已失效'));
            }
            let body = null;
            try { body = JSON.parse(res.body); } catch (e) { body = null; }
            if (body && body.code === 0) {
                ctx.perFile.set(index, job.size);
                ctx.addSent(job.size);
                return resolve(body.data);
            }
            // 和网页直传一致：code=2 是「库里已有」，上层按跳过处理
            if (body && body.code === 2) {
                const err = new Error(body.message || '文件已存在');
                err.duplicate = true;
                ctx.perFile.set(index, job.size);
                return reject(err);
            }
            reject(new Error((body && body.message) || ('上传失败（HTTP ' + (res.status || '?') + '）')));
        }, reject);
    });
}

/** 原生直传的重试语义与网页直传保持一致 */
async function uploadNativeWithRetry(index, job, ctx) {
    let lastErr = null;
    for (let attempt = 0; attempt <= SIMPLE_RETRY; attempt++) {
        try {
            return await uploadNative(index, job, ctx);
        } catch (e) {
            lastErr = e;
            if (/登录已失效/.test(e.message) || e.duplicate) throw e;
            ctx.perFile.set(index, 0);
            ctx.paint(true);
            if (attempt < SIMPLE_RETRY) await sleep(500 * (attempt + 1));
        }
    }
    throw lastErr || new Error('上传失败');
}

/** 大文件：init → 并发传分片（跳过已传的）→ complete */
async function uploadOneChunked(job, index, ctx) {
    const file = job.blob;
    const fileName = job.name;
    const totalChunks = Math.max(1, Math.ceil(file.size / CHUNK_SIZE));
    const fileKey = `${fileName}-${file.size}-${file.lastModified || 0}`;
    ctx.st.label = `分片：${fileName}`;
    ctx.paint(true);

    const init = await api('/api/files/chunk/init', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            fileName: fileName,
            fileSize: file.size,
            chunkSize: CHUNK_SIZE,
            totalChunks: totalChunks,
            fileKey: fileKey,
            contentType: file.type || '',
            groupId: (currentGroupId !== null && currentGroupId > 0) ? currentGroupId : null,
            capturedTime: capturedTimeOf(file) || null
        })
    });
    if (init.code !== 0) throw new Error(init.message || '初始化分块上传失败');

    const uploadId = init.data.uploadId;
    const received = new Set(init.data.received || []);

    // 续传：已经传完的分片直接计入进度
    let got = 0;
    received.forEach((i) => {
        got += Math.max(0, Math.min(CHUNK_SIZE, file.size - i * CHUNK_SIZE));
    });
    ctx.perFile.set(index, got);
    ctx.paint(true);

    const pending = [];
    for (let i = 0; i < totalChunks; i++) if (!received.has(i)) pending.push(i);

    let cursor = 0;
    const worker = async () => {
        while (cursor < pending.length) {
            const ci = pending[cursor++];
            const start = ci * CHUNK_SIZE;
            const blob = file.slice(start, Math.min(start + CHUNK_SIZE, file.size));
            await putChunk(uploadId, ci, blob);
            got += blob.size;
            ctx.perFile.set(index, got);
            ctx.paint();
        }
    };
    const workers = Math.min(CHUNK_CONCURRENCY, Math.max(1, pending.length));
    await Promise.all(Array.from({ length: workers }, worker));

    const fin = await api(`/api/files/chunk/${uploadId}/complete`, { method: 'POST' });
    if (fin.code === 2) {
        // 合并完才发现内容重复：服务端已经把合并出来的文件删掉了，这里按「跳过」上报
        const err = new Error(fin.message || '文件已存在');
        err.duplicate = true;
        throw err;
    }
    if (fin.code !== 0) throw new Error(fin.message || '合并分块失败');

    ctx.perFile.set(index, file.size);
    ctx.addSent(file.size);
}

/** 单片上传，失败重试（网络抖一下不至于整个大文件重传） */
async function putChunk(uploadId, index, blob) {
    let lastErr = null;
    for (let attempt = 0; attempt < CHUNK_RETRY; attempt++) {
        try {
            const fd = new FormData();
            fd.append('chunk', blob, 'part');
            const res = await fetch(`/api/files/chunk/${uploadId}?index=${index}`, { method: 'POST', body: fd });
            if (res.status === 401) { goLogin(); throw new Error('登录已失效，请重新登录'); }
            let data = null;
            try { data = await res.json(); } catch (e) { data = null; }
            if (data && data.code === 0) return;
            lastErr = new Error((data && data.message) || `分片 ${index} 上传失败`);
            // 业务性错误（比如分片大小不符）重试也没用，直接抛出
            if (data && data.code !== 0 && !/网络|超时|失败/.test(data.message || '')) throw lastErr;
        } catch (e) {
            lastErr = e;
        }
        await new Promise((r) => setTimeout(r, 400 * (attempt + 1)));
    }
    throw lastErr || new Error(`分片 ${index} 上传失败`);
}

// ---------------- 分组 ----------------
async function loadGroups() {
    const res = await api('/api/groups');
    if (res.code !== 0) return;
    groupsCache = res.data || [];
    renderGroups();
    updateTitle();
    renderStats();
}

/** 侧边栏「回收站」后面的条数徽标：有东西才显示，0 就藏起来 */
async function loadRecycleBadge() {
    const badge = $('#recycleBadge');
    if (!badge) return;
    const res = await api('/api/recycle/stats');
    if (res.code !== 0) return;
    const n = (res.data && res.data.count) || 0;
    badge.textContent = n > 99 ? '99+' : String(n);
    badge.hidden = n === 0;
}

function renderGroups() {
    const bar = $('#groupsBar');
    if (!bar) return;
    bar.innerHTML = '';
    bar.appendChild(groupChip('所有分组', null, false));
    bar.appendChild(groupChip('未分组', 0, false));
    groupsCache.forEach((g) => bar.appendChild(groupChip(g.name, g.id, true, g.fileCount, g.hasRefImage)));

    const add = document.createElement('button');
    add.type = 'button';
    add.className = 'group-add';
    add.innerHTML = ICONS.plus + '<span>新建分组</span>';
    add.onclick = createGroup;
    bar.appendChild(add);
}

function groupChip(label, gid, deletable, count, hasRef) {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'group-chip' + (currentGroupId === gid ? ' active' : '');

    const ico = document.createElement('span');
    ico.className = 'gico';
    ico.innerHTML = gid === 0 ? ICONS.inbox : ICONS.folder;
    chip.appendChild(ico);

    const txt = document.createElement('span');
    txt.className = 'glabel';
    txt.textContent = label;
    chip.appendChild(txt);

    if (count != null) {
        const c = document.createElement('span');
        c.className = 'gcount';
        c.textContent = count;
        chip.appendChild(c);
    }

    chip.onclick = () => {
        currentGroupId = gid;
        currentDate = null;
        currentPage = 1;
        clearSelection();
        renderGroups();
        loadFiles();
        document.body.classList.remove('rail-open');
    };

    // AI 识别用的样貌参考图：有图时图标点亮，一眼看出哪些分组会参与自动比对
    if (deletable) {
        const ref = document.createElement('span');
        ref.className = 'ref' + (hasRef ? ' has' : '');
        ref.innerHTML = ICONS.camera;
        ref.title = hasRef ? '已设置样貌参考图（点击管理）' : '设置样貌参考图，让 AI 自动分组';
        ref.onclick = (e) => { e.stopPropagation(); setGroupRefImage(gid, label, !!hasRef); };
        chip.appendChild(ref);
    }

    if (deletable) {
        const del = document.createElement('span');
        del.className = 'del';
        del.innerHTML = ICONS.close;
        del.title = '删除分组';
        del.onclick = (e) => { e.stopPropagation(); removeGroup(gid); };
        chip.appendChild(del);
    }
    return chip;
}

async function createGroup() {
    const name = await uiPrompt({
        icon: 'folderPlus',
        eyebrow: 'New group',
        title: '新建分组',
        sub: '分组用于归档文件，可随时改名或删除',
        label: '分组名称',
        value: '',
        placeholder: '例如：合同文档',
        maxlength: 40,
        hint: '最多 40 个字符',
        okText: '创建',
        validate: (v) => {
            // 同名校验在本地先拦一次，省一次往返
            const dup = groupsCache.some((g) => g.name === v);
            return dup ? '已有同名分组' : '';
        }
    });
    if (name == null) return;
    const res = await api('/api/groups?name=' + encodeURIComponent(name), { method: 'POST' });
    if (res.code === 0) { toast('已创建分组'); loadGroups(); }
    else toast(res.message || '创建失败');
}

async function removeGroup(id) {
    const g = groupsCache.find((x) => x.id === id);
    const name = g ? g.name : id;
    const count = g && typeof g.fileCount === 'number' ? g.fileCount : 0;

    // 空分组没什么可选的，直接走一句轻确认
    let choice;
    if (!count) {
        const ok = await uiConfirm({
            icon: 'trash',
            eyebrow: 'Delete group',
            title: `删除分组「${name}」？`,
            sub: '该分组内没有文件',
            okText: '删除分组'
        });
        choice = ok ? 'plain' : null;
    } else {
        // 有文件时必须让用户明确二选一：
        //   主按钮 = 连文件一起删（进回收站），次要按钮 = 只删分组
        const r = await uiConfirm({
            icon: 'trash',
            eyebrow: 'Delete group',
            title: `删除分组「${name}」？`,
            sub: `该分组下有 ${count} 个文件，请选择如何处理`,
            note: '「同时删除文件」会把 ' + count + ' 个文件移入<strong>回收站</strong>，7 天内可恢复；' +
                  '「仅删除分组」则保留文件，移到<strong>「未分组」</strong>。',
            danger: true,
            altText: '仅删除分组',
            okText: '同时删除文件'
        });
        if (r === false) return;          // 取消 / Esc
        choice = r === true ? 'withFiles' : 'plain';
    }
    if (!choice) return;

    const url = '/api/groups/' + id + (choice === 'withFiles' ? '?deleteFiles=true' : '');
    const res = await api(url, { method: 'DELETE' });
    if (res.code === 0) {
        const n = typeof res.data === 'number' ? res.data : 0;
        toast(choice === 'withFiles'
            ? (n ? `已删除分组，${n} 个文件移入回收站` : '已删除分组')
            : (n ? `已删除分组，${n} 个文件移到未分组` : '已删除分组'));
        if (currentGroupId === id) currentGroupId = null;
        loadGroups();
        loadFiles();
    } else {
        toast(res.message || '删除失败');
    }
}

// ---------------- 分组样貌参考图（AI 自动分组用） ----------------

/** 唤起系统文件选择框，返回选中的图片文件；取消返回 null */
function pickImageFile() {
    return new Promise((resolve) => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*';
        input.style.position = 'fixed';
        input.style.left = '-9999px';
        document.body.appendChild(input);

        let settled = false;
        function done(v) {
            if (settled) return;
            settled = true;
            if (document.body.contains(input)) document.body.removeChild(input);
            resolve(v);
        }
        input.onchange = () => done(input.files && input.files[0] ? input.files[0] : null);
        // 用户点「取消」时不会触发 change，靠窗口重新获得焦点兜底
        window.addEventListener('focus', () => {
            setTimeout(() => {
                if (!settled && (!input.files || !input.files.length)) done(null);
            }, 500);
        }, { once: true });

        input.click();
    });
}

/**
 * 设置 / 更换 / 清除某个分组的样貌参考图。
 * 这张图不用于展示，只在 AI 识别时作为「这个人长什么样」的依据。
 */
async function setGroupRefImage(gid, name, hasRef) {
    if (hasRef) {
        const r = await uiConfirm({
            icon: 'userCheck',
            eyebrow: 'AI reference',
            title: `「${name}」的样貌参考图`,
            sub: '自动分组时，AI 用它判断照片里的人是不是这一组的成员',
            note: '建议用<strong>正面、清晰、只有一个人</strong>的人像照。更换后对新上传的图片生效。',
            altText: '清除参考图',
            okText: '更换参考图'
        });
        if (r === false) return;
        if (r === 'alt') {
            const res = await api('/api/groups/' + gid + '/ref-image', { method: 'DELETE' });
            if (res.code === 0) { toast('已清除参考图，该分组不再参与自动识别'); loadGroups(); }
            else toast(res.message || '清除失败');
            return;
        }
    }

    const file = await pickImageFile();
    if (!file) return;
    if (file.size > 10 * 1024 * 1024) {
        toast('参考图请控制在 10MB 以内');
        return;
    }

    const fd = new FormData();
    fd.append('file', file);
    const res = await api('/api/groups/' + gid + '/ref-image', { method: 'POST', body: fd });
    if (res.code === 0) {
        toast('参考图已保存，之后上传的照片会自动比对');
        loadGroups();
    } else {
        toast(res.message || '保存失败');
    }
}

let groupPickHandler = null;
/** 打开分组选择器；currentGid 用于标记「当前」分组，onPick 接收选中的 groupId */function openGroupPicker(currentGid, subtitle, onPick) {
    groupPickHandler = onPick;
    const list = $('#groupModalList');
    const sub = $('#groupModalFile');
    if (sub) sub.textContent = subtitle;
    list.innerHTML = '';
    list.appendChild(groupItem('未分组', 0, currentGid === 0));
    groupsCache.forEach((g) => list.appendChild(groupItem(g.name, g.id, currentGid === g.id)));
    $('#groupModal').hidden = false;
}

function groupItem(name, gid, isCurrent) {
    const item = document.createElement('div');
    item.className = 'group-item' + (isCurrent ? ' current' : '');
    item.innerHTML = `<span>${escapeHtml(name)}</span><span class="cnt">${isCurrent ? '当前' : ''}</span>`;
    item.onclick = () => { if (groupPickHandler) groupPickHandler(gid); };
    return item;
}

function openGroupModal(file) {
    openGroupPicker(file.groupId ? file.groupId : 0, file.fileName, (gid) => assignToGroup(file, gid));
}

async function assignToGroup(file, gid) {
    const res = await api(`/api/files/${file.id}/group?groupId=${gid}`, { method: 'PUT' });
    if (res.code === 0) {
        toast('已移动到分组');
        $('#groupModal').hidden = true;
        loadGroups();
        loadFiles();
    } else {
        toast(res.message || '操作失败');
    }
}

// ---------------- 批量操作 ----------------
function selectedIds() {
    return Array.from(selected);
}

function postJson(url, ids) {
    return api(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(ids)
    });
}

function openBatchGroupModal() {
    if (selected.size === 0) return;
    openGroupPicker(null, `已选 ${selected.size} 项`, (gid) => batchAssignGroup(gid));
}

async function batchAssignGroup(gid) {
    const ids = selectedIds();
    if (!ids.length) return;
    const res = await postJson(`/api/files/batch/group?groupId=${gid}`, ids);
    if (res.code === 0) {
        toast(`已把 ${res.data != null ? res.data : ids.length} 个文件移入分组`);
        $('#groupModal').hidden = true;
        clearSelection();
        loadGroups();
        loadFiles();
    } else {
        toast(res.message || '操作失败');
    }
}

async function batchDeleteSelected() {
    const ids = selectedIds();
    if (!ids.length) return;

    // 从当前页缓存里算总大小；跨页选中时算不全就不显示，别给个错的数
    const idSet = new Set(ids);
    const picked = lastRecords.filter((r) => idSet.has(r.id));
    const totalBytes = picked.length === ids.length
        ? picked.reduce((s, r) => s + (r.fileSize || 0), 0)
        : 0;

    const ok = await uiConfirm({
        icon: 'trash',
        eyebrow: 'Delete · ' + ids.length + ' items',
        title: `删除选中的 ${ids.length} 个文件？`,
        sub: totalBytes > 0 ? '合计 ' + formatSize(totalBytes) : '',
        note: '这些文件会移入<strong>回收站</strong>，7 天内可随时恢复。',
        okText: '移入回收站'
    });
    if (!ok) return;

    const res = await postJson('/api/files/batch/delete', ids);
    if (res.code === 0) {
        toast(`已把 ${res.data != null ? res.data : ids.length} 个文件移入回收站`);
        clearSelection();
        loadGroups();
        loadFiles();
        loadRecycleBadge();
    } else {
        toast(res.message || '删除失败');
    }
}

function batchDownloadSelected() {
    const ids = selectedIds();
    if (!ids.length) return;
    if (ids.length > 300) { toast('单次最多打包 300 个文件'); return; }
    // 交给浏览器下载，服务端流式打包为 zip
    window.open(mediaUrl('/api/files/batch/download?ids=' + ids.join(',')), '_blank');
    toast(`正在打包 ${ids.length} 个文件…`);
}

// ---------------- 弹窗 ----------------
function closeModal(id) {
    const m = $('#' + id);
    if (!m) return;
    m.hidden = true;
    if (id === 'videoModal') {
        const v = $('#videoPlayer');
        v.pause();
        // 用 removeAttribute + load 而不是 src=''：后者会解析成当前页面地址再发一次请求，
        // 白白多一个 404，还会触发一次 error 事件
        v.removeAttribute('src');
        v.load();
        const box = document.querySelector('.modal-video');
        if (box) { box.style.width = ''; box.style.height = ''; }
        const state = $('#videoState');
        if (state) state.hidden = true;
    }
    if (id === 'imageModal') {
        // 让还在解码/加载的那次回合作废：否则关掉之后它还会把图层重新显示出来，
        // 下次打开会先闪一下上一张。
        imgToken++;
        [$('#imagePreview'), $('#imagePreviewB')].forEach((img) => {
            if (!img) return;
            // 先摘掉回调再清空 src，否则清空本身会触发一次 onerror，把状态面板点亮
            img.onload = null;
            img.onerror = null;
            img.removeAttribute('src');
            img.hidden = true;
            img.classList.remove('gal-next', 'gal-prev');
        });
        galFront = null;
        galleryList = [];
        galleryIndex = -1;
        galleryDir = 0;
        const state = $('#imgState');
        if (state) { state.hidden = true; state.dataset.mode = ''; }
    }
}

$$('[data-close]').forEach((el) => {
    el.onclick = () => closeModal(el.dataset.close);
});

// ---------------- 事件绑定 ----------------
$('#pickBtn').onclick = () => openPicker('#fileInput');

/**
 * 选完文件后，这个回调必须「立刻返回」。
 *
 * 原因：系统相册 / 文件选择器是宿主（浏览器）的一个独立页面，JS 的 change 回调跑在
 * 主线程上。只要回调还没返回、主线程还在忙，宿主就没有机会把那个页面收起来——
 * 用户看到的就是「点了确定，相册僵在那儿好几秒」。
 *
 * 以前这里同步进入 uploadFiles：建进度面板、改 DOM、逐张读 size/type、
 * 甚至开始发 HTTP 请求，全挤在同一个同步栈里。选 50 张时，这些活儿足够把
 * 相册关闭动画压住。
 *
 * 现在这里只有三件事：复位标志位、取一个 FileList 引用、丢到下一个宏任务。
 * 不遍历 FileList（`slice` 也要逐个取元素）、不读 name/size/type、不碰 DOM、
 * 不发请求。原来的 350ms 空窗也去掉了——那是拿用户的时间去猜宿主什么时候收完，
 * 而我们真正需要让出的只是「当前这一帧」。
 *
 * 不在回调里清空 input.value：部分 Android WebView 会同步释放每个 File 背后的
 * ContentResolver 权限，50 个文件就是一串同步 IPC，等于又把活儿干回来了。
 * 清空挪到本批上传结束时做（见 uploadFiles 的 finally）。
 */
function bindPicker(input) {
    if (!input) return;
    input.onchange = () => {
        pickerOpen = false;
        perfMark('change');
        const picked = input.files;   // 只拿引用，元素一个都不取
        if (!picked || !picked.length) return;
        setTimeout(() => uploadFiles(picked), 0);
    };
    // Safari 16.4+ 会发 cancel，用来把标志位复位，否则「点取消」之后一直以为选择器还开着
    input.addEventListener('cancel', () => { pickerOpen = false; });
}

bindPicker($('#fileInput'));
bindPicker($('#mediaInput'));

/** 「原图 / 压缩」开关：默认压缩（快），要原图就点一下，选择记在本地 */
function renderOriginalToggle() {
    const btn = $('#origToggle');
    if (!btn) return;
    btn.setAttribute('aria-pressed', uploadOriginals ? 'true' : 'false');
    btn.textContent = uploadOriginals
        ? '正在上传原图 · 点此改为自动压缩（更快）'
        : '大图自动压到 2560px 再传 · 点此改为上传原图';
}

const origToggle = $('#origToggle');
if (origToggle) {
    renderOriginalToggle();
    origToggle.onclick = (e) => {
        // 拖拽区整块也是「选择文件」的点击区，这个按钮不能顺带把相册也叫起来
        e.stopPropagation();
        uploadOriginals = !uploadOriginals;
        try { localStorage.setItem('cd_upload_original', uploadOriginals ? '1' : '0'); } catch (err) { /* 隐私模式 */ }
        renderOriginalToggle();
        toast(uploadOriginals ? '已改为上传原图（更慢，但保留原始分辨率）'
            : '已开启自动压缩：长边 2560，通常快 3~5 倍');
    };
}

// 手机 / 平板上多给一个「照片/视频」入口：带 accept 的 input 会直达系统相册，
// 不必经过「文件浏览」那一层——选几十张照片时，这一步省下的是实打实的等待。
if (window.matchMedia && window.matchMedia('(pointer: coarse)').matches) {
    const mediaBtn = $('#pickMediaBtn');
    if (mediaBtn) {
        mediaBtn.style.display = '';
        mediaBtn.onclick = () => openPicker('#mediaInput');
    }
}

// 原生壳的「照片 / 视频」选择面板（浏览器里不会出现）
const pickPhotosBtn = $('#mediaPickPhotos');
if (pickPhotosBtn) {
    pickPhotosBtn.onclick = () => {
        closeModal('mediaSheet');
        pickFromNative('photo');
    };
}
const pickVideosBtn = $('#mediaPickVideos');
if (pickVideosBtn) {
    pickVideosBtn.onclick = () => {
        closeModal('mediaSheet');
        pickFromNative('video');
    };
}

$('#viewGrid').onclick = () => setView('grid');
$('#viewList').onclick = () => setView('list');

$('#filters').addEventListener('click', (e) => {
    const btn = e.target.closest('.chip');
    if (!btn) return;
    $$('#filters .chip').forEach((c) => c.classList.remove('active'));
    btn.classList.add('active');
    currentType = btn.dataset.type;
    currentDate = null;   // 换了类型，某一天可能一张都没有，退回「全部」
    currentPage = 1;
    clearSelection();
    loadFiles();
    document.body.classList.remove('rail-open');
});

let searchTimer;
$('#searchInput').oninput = (e) => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
        currentKeyword = e.target.value.trim();
        currentDate = null;
        currentPage = 1;
        clearSelection();
        loadFiles();
    }, 300);
};

// 批量操作
$('#batchAll').onclick = toggleSelectAll;
$('#batchGroup').onclick = openBatchGroupModal;
$('#batchDownload').onclick = batchDownloadSelected;
$('#batchDelete').onclick = batchDeleteSelected;
$('#batchClear').onclick = clearSelection;

// 图片画廊：箭头切换（换图与动画都在 showImage 里处理，两层交叉切换）
$('#imgPrev').onclick = () => stepImage(-1);
$('#imgNext').onclick = () => stepImage(1);

const galleryStageEl = $('#galleryStage');

// 加载态面板：只有失败时才可以点，点一下重新走一遍回退链
$('#imgState').onclick = () => {
    const state = $('#imgState');
    if (state.dataset.mode !== 'error') return;
    const id = galleryStageEl && galleryStageEl.dataset.fileId;
    if (!id) return;
    const file = galleryList.find((f) => String(f.id) === String(id)) || { id: id, fileName: '' };
    showImage(file, 0);
};

// 视频：拿到真实分辨率后再收一次弹窗尺寸；解码失败就给下载入口
const videoEl = $('#videoPlayer');
videoEl.addEventListener('loadedmetadata', fitVideoBox);
videoEl.addEventListener('error', () => {
    if (!videoEl.getAttribute('src')) return;   // 关闭时清空 src 也会触发，忽略
    const state = $('#videoState');
    if (state) {
        state.hidden = false;
        state.dataset.mode = 'error';
        $('#videoStateText').textContent = '这个视频浏览器播不了，点这里下载';
        state.onclick = () => {
            if (videoEl.dataset.downloadUrl) window.open(videoEl.dataset.downloadUrl, '_blank');
        };
    }
});

// 横竖屏切换 / 窗口尺寸变化时重新适配
window.addEventListener('resize', () => {
    if ($('#videoModal') && !$('#videoModal').hidden) fitVideoBox();
});

// 触屏 / 鼠标拖拽左右滑动
let swipeX = null;
let swipeY = null;
let swipeId = null;
let swipeCaptured = false;   // 是否已经在这次拖拽里捕获了指针
let swipeMoved = false;      // 是否确认过是横向拖拽（不是点按）
// 绑在舞台上而不是那张 <img> 上：换图时可见的层会在两层之间切换，
// 绑到图片上的话，切过去之后手势就失灵了
const swipeTarget = galleryStageEl || $('#imagePreview');

function resetSwipe() {
    swipeX = null;
    swipeY = null;
    swipeId = null;
    swipeCaptured = false;
    swipeMoved = false;
}

swipeTarget.addEventListener('pointerdown', (e) => {
    // 只跟左键 / 单指；按在按钮上的手势不算翻页（关闭键要能正常收到 click）
    if (e.button !== 0) return;
    if (e.target && e.target.closest && e.target.closest('button')) return;
    swipeX = e.clientX;
    swipeY = e.clientY;
    swipeId = e.pointerId;
    swipeCaptured = false;
    swipeMoved = false;
});

// 只有确认是横向拖拽之后才捕获指针。
// 以前 pointerdown 就 setPointerCapture，pointerup 和随后的 click 会被改派给舞台，
// 舞台里那几个 data-close 按钮（右上角 X）永远收不到 click —— 点了没反应。
// 点按本身不需要捕获，所以延后到移动超过阈值再捕获，既保留滑动手势，也不影响点击。
swipeTarget.addEventListener('pointermove', (e) => {
    if (swipeX === null || e.pointerId !== swipeId || swipeCaptured) return;
    const dx = e.clientX - swipeX;
    const dy = e.clientY - swipeY;
    if (Math.abs(dx) < 10 || Math.abs(dx) < Math.abs(dy)) return;
    try { swipeTarget.setPointerCapture(e.pointerId); } catch (err) { /* 忽略 */ }
    swipeCaptured = true;
    swipeMoved = true;
});

swipeTarget.addEventListener('pointerup', (e) => {
    if (swipeX === null || e.pointerId !== swipeId) return;
    const dx = e.clientX - swipeX;
    const dy = e.clientY - swipeY;
    const dragged = swipeMoved && Math.abs(dx) >= 48 && Math.abs(dx) >= Math.abs(dy) * 1.2;
    resetSwipe();
    if (dragged) stepImage(dx < 0 ? 1 : -1);
});
swipeTarget.addEventListener('pointercancel', resetSwipe);
// 已捕获时 pointerup 一定会回到舞台，别让 pointerleave 把这次拖拽提前取消
swipeTarget.addEventListener('pointerleave', () => { if (!swipeCaptured) resetSwipe(); });

// 拖拽
const dz = $('#dropzone');
['dragenter', 'dragover'].forEach((ev) => dz.addEventListener(ev, (e) => { e.preventDefault(); dz.classList.add('drag'); }));
['dragleave', 'drop'].forEach((ev) => dz.addEventListener(ev, (e) => { e.preventDefault(); dz.classList.remove('drag'); }));
dz.addEventListener('drop', (e) => { uploadFiles(e.dataTransfer.files); });
dz.onclick = () => openPicker('#fileInput');

// 移动端导航
const railToggle = $('#railToggle');
if (railToggle) railToggle.onclick = () => document.body.classList.toggle('rail-open');
const railMask = $('#railMask');
if (railMask) railMask.onclick = () => document.body.classList.remove('rail-open');

// 退出登录
const logoutBtn = $('#logoutBtn');
if (logoutBtn) logoutBtn.onclick = doLogout;

// 键盘：/ 聚焦搜索，Esc 关闭
document.addEventListener('keydown', (e) => {
    const tag = (e.target.tagName || '').toLowerCase();
    const typing = tag === 'input' || tag === 'textarea' || tag === 'select';

    if (e.key === '/' && !typing) {
        e.preventDefault();
        $('#searchInput').focus();
        return;
    }

    // 图片预览打开时，左右方向键切换
    const imageOpen = $('#imageModal') && !$('#imageModal').hidden;
    if (imageOpen && !typing && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
        e.preventDefault();
        stepImage(e.key === 'ArrowRight' ? 1 : -1);
        return;
    }

    if (e.key === 'Escape') {
        document.body.classList.remove('rail-open');
        let closedModal = false;
        ['imageModal', 'videoModal', 'groupModal'].forEach((id) => {
            const m = $('#' + id);
            if (m && !m.hidden) { closeModal(id); closedModal = true; }
        });
        if (!closedModal) clearSelection();
        if (typing) $('#searchInput').blur();
    }
});

// 初始化：先确认登录态，再拉数据（未登录会被重定向到登录页）
(async function boot() {
    const me = await loadCurrentUser();
    if (!me) return;
    applyView();
    loadGroups();
    loadFiles();
    loadRecycleBadge();
})();
