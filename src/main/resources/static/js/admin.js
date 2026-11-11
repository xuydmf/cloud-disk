/* ============================================================
   用户管理后台
   ============================================================ */
(function () {
    'use strict';

    const $ = (s) => document.querySelector(s);
    const ICON = {
        edit: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M12.5 20h8"/><path d="M16.7 3.9a2.1 2.1 0 0 1 3 3L7.3 19.3l-4 1 1-4L16.7 3.9Z"/></svg>',
        key: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="15.5" r="3.5"/><path d="m11 13 8-8"/><path d="m16.5 7.5 2 2"/><path d="m14 10 2 2"/></svg>',
        power: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4v8"/><path d="M7.8 6.3a7 7 0 1 0 8.4 0"/></svg>',
        trash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6.5h16"/><path d="M9 6.5V4.4h6v2.1"/><path d="M6.4 6.5 7.3 20h9.4l.9-13.5"/></svg>'
    };

    let USERS = [];
    let ME = null;
    let editingId = null;      // null = 新增
    let resettingId = null;
    let searchTimer = null;

    // ---------------- 通用 ----------------

    function toast(msg) {
        const t = $('#toast');
        t.textContent = msg;
        t.hidden = false;
        clearTimeout(toast._t);
        toast._t = setTimeout(() => { t.hidden = true; }, 2200);
    }

    function goLogin() {
        location.replace('login.html');
    }

    async function api(url, opts) {
        try {
            const r = await fetch(url, opts);
            if (r.status === 401) { goLogin(); return { code: 401, message: '登录已失效' }; }
            if (r.status === 403) { return { code: 403, message: '需要管理员权限' }; }
            return await r.json();
        } catch (e) {
            return { code: -1, message: '网络异常，请重试' };
        }
    }

    function postJson(url, method, body) {
        return api(url, {
            method: method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body || {})
        });
    }

    function escapeHtml(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => (
            { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
        ));
    }

    function fmtTime(v) {
        if (!v) return '—';
        const d = new Date(v);
        if (isNaN(d.getTime())) return '—';
        const p = (n) => String(n).padStart(2, '0');
        return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
    }

    function initial(name) {
        const s = String(name || '?').trim();
        return s ? s.slice(0, 1).toUpperCase() : '?';
    }

    // ---------------- 当前登录用户 ----------------

    async function loadMe() {
        const res = await api('/api/auth/current');
        if (res.code !== 0 || !res.data) { goLogin(); return null; }
        ME = res.data;

        $('#railUser').hidden = false;
        $('#userAvatar').textContent = initial(ME.displayName || ME.username);
        $('#userName').textContent = ME.displayName || ME.username;
        $('#userRole').textContent = ME.role === 'ADMIN' ? '管理员' : '普通用户';
        document.title = '用户管理 · 云盘';
        return ME;
    }

    // ---------------- 列表 ----------------

    async function loadUsers(keyword) {
        const q = keyword ? '?keyword=' + encodeURIComponent(keyword) : '';
        const res = await api('/api/admin/users' + q);
        if (res.code !== 0) {
            $('#uRows').innerHTML = `<div class="utable-state">${escapeHtml(res.message || '加载失败')}</div>`;
            return;
        }
        USERS = res.data || [];
        renderStats();
        renderRows();
    }

    function renderStats() {
        $('#statTotal').textContent = USERS.length;
        $('#statAdmin').textContent = USERS.filter((u) => u.role === 'ADMIN').length;
        $('#statOff').textContent = USERS.filter((u) => !u.enabled).length;
    }

    function renderRows() {
        const box = $('#uRows');
        if (!USERS.length) {
            box.innerHTML = '<div class="utable-state">没有匹配的用户</div>';
            return;
        }

        box.innerHTML = USERS.map((u) => {
            const isSelf = ME && u.id === ME.id;
            const cls = ['urow'];
            if (isSelf) cls.push('self');
            if (!u.enabled) cls.push('off');

            const roleBadge = u.role === 'ADMIN'
                ? '<span class="ubadge admin">管理员</span>'
                : '<span class="ubadge">普通用户</span>';
            const stateBadge = u.enabled
                ? ''
                : '<span class="ubadge off">已禁用</span>';
            const managedBadge = u.managed
                ? '<span class="ubadge managed">配置托管</span>'
                : '';

            const delDisabled = isSelf || u.managed;
            const toggleDisabled = isSelf;

            return `<div class="${cls.join(' ')}" data-id="${u.id}">
                <div class="u-id${u.enabled ? '' : ' off'}">
                    <span class="u-ava">${escapeHtml(initial(u.displayName || u.username))}</span>
                    <span class="u-name">
                        <strong>${escapeHtml(u.displayName || u.username)}${isSelf ? ' · 自己' : ''}</strong>
                        <em>${escapeHtml(u.username)}</em>
                    </span>
                </div>
                <div class="u-badge-cell">${roleBadge}${stateBadge}${managedBadge}</div>
                <div class="utime">${fmtTime(u.lastLoginTime)}</div>
                <div class="uacts">
                    <button class="icon-btn" data-act="edit" title="编辑用户">${ICON.edit}</button>
                    <button class="icon-btn" data-act="pwd" title="${u.managed ? '密码由配置文件托管' : '重置密码'}"
                            ${u.managed ? 'disabled' : ''}>${ICON.key}</button>
                    <button class="icon-btn" data-act="toggle" title="${u.enabled ? '禁用登录' : '启用登录'}"
                            ${toggleDisabled ? 'disabled' : ''}>${ICON.power}</button>
                    <button class="icon-btn danger" data-act="del" title="删除用户"
                            ${delDisabled ? 'disabled' : ''}>${ICON.trash}</button>
                </div>
            </div>`;
        }).join('');
    }

    function userOf(id) {
        return USERS.find((u) => String(u.id) === String(id));
    }

    // 行内操作（事件委托）
    $('#uRows').addEventListener('click', (e) => {
        const btn = e.target.closest('[data-act]');
        if (!btn || btn.disabled) return;
        const row = btn.closest('.urow');
        const user = userOf(row.dataset.id);
        if (!user) return;

        const act = btn.dataset.act;
        if (act === 'edit') openEdit(user);
        else if (act === 'pwd') openReset(user);
        else if (act === 'toggle') toggleEnabled(user);
        else if (act === 'del') removeUser(user);
    });

    // ---------------- 新增 / 编辑 ----------------

    function clearFormError() { $('#userFormError').hidden = true; }

    function openFormError(msg) {
        $('#userFormError').textContent = msg;
        $('#userFormError').hidden = false;
    }

    function openCreate() {
        editingId = null;
        $('#userModalTitle').textContent = '新增用户';
        $('#userModalSub').textContent = '创建后即可用该账号登录云盘';
        $('#fUsername').value = '';
        $('#fUsername').disabled = false;
        $('#fDisplayName').value = '';
        $('#fPassword').value = '';
        $('#fPassword').type = 'password';
        $('#fPwdField').hidden = false;
        $('#fRole').value = 'USER';
        $('#fRole').disabled = false;
        $('#fEnabled').checked = true;
        clearFormError();
        $('#userModal').hidden = false;
        setTimeout(() => $('#fUsername').focus(), 30);
    }

    function openEdit(user) {
        editingId = user.id;
        $('#userModalTitle').textContent = '编辑用户';
        $('#userModalSub').textContent = user.managed
            ? '该账号由 application.yml 托管，用户名 / 角色不可修改'
            : '@' + user.username;
        $('#fUsername').value = user.username;
        $('#fUsername').disabled = !!user.managed;
        $('#fDisplayName').value = user.displayName || '';
        $('#fPassword').value = '';
        $('#fPwdField').hidden = true;    // 编辑时改密码走「重置密码」
        $('#fRole').value = user.role;
        $('#fRole').disabled = !!user.managed;
        $('#fEnabled').checked = !!user.enabled;
        clearFormError();
        $('#userModal').hidden = false;
        setTimeout(() => $('#fDisplayName').focus(), 30);
    }

    async function saveUser() {
        const isNew = editingId == null;
        const payload = {
            username: $('#fUsername').value.trim(),
            displayName: $('#fDisplayName').value.trim(),
            role: $('#fRole').value,
            enabled: $('#fEnabled').checked
        };
        if (isNew) payload.password = $('#fPassword').value;

        if (!payload.username) { openFormError('请输入用户名'); return; }
        if (isNew) {
            if (!payload.password) { openFormError('请设置初始密码'); return; }
            if (payload.password.length < 6) { openFormError('密码至少 6 位'); return; }
        }

        const btn = $('#userSaveBtn');
        btn.disabled = true;
        try {
            const res = isNew
                ? await postJson('/api/admin/users', 'POST', payload)
                : await postJson('/api/admin/users/' + editingId, 'PUT', payload);

            if (res.code === 0) {
                $('#userModal').hidden = true;
                toast(isNew ? '用户已创建' : '已保存修改');
                await loadUsers($('#searchInput').value.trim());
            } else {
                openFormError(res.message || '保存失败');
            }
        } finally {
            btn.disabled = false;
        }
    }

    // ---------------- 启用 / 禁用 ----------------

    async function toggleEnabled(user) {
        const next = !user.enabled;
        if (!next) {
            const ok = await uiConfirm({
                icon: 'userX',
                eyebrow: 'Disable',
                title: '禁用该账号？',
                sub: (user.displayName || user.username) + ' · @' + user.username,
                note: '禁用后该账号<strong>无法登录</strong>，已建立的会话在下次请求时失效。随时可以重新启用。',
                okText: '禁用'
            });
            if (!ok) return;
        }
        const res = await postJson('/api/admin/users/' + user.id, 'PUT', { enabled: next });
        if (res.code === 0) {
            toast(next ? '已启用' : '已禁用');
            await loadUsers($('#searchInput').value.trim());
        } else {
            toast(res.message || '操作失败');
        }
    }

    // ---------------- 删除 ----------------

    async function removeUser(user) {
        const ok = await uiConfirm({
            icon: 'trash',
            danger: true,
            eyebrow: 'Danger',
            title: '删除该用户？',
            sub: (user.displayName || user.username) + ' · @' + user.username,
            note: '此操作<strong>不可撤销</strong>，账号将被永久删除，且无法再登录。该用户上传的文件不会被删除。',
            okText: '删除用户'
        });
        if (!ok) return;
        const res = await api('/api/admin/users/' + user.id, { method: 'DELETE' });
        if (res.code === 0) {
            toast('用户已删除');
            await loadUsers($('#searchInput').value.trim());
        } else {
            toast(res.message || '删除失败');
        }
    }

    // ---------------- 重置密码 ----------------

    function openReset(user) {
        resettingId = user.id;
        $('#pwdModalSub').textContent = '@' + user.username;
        $('#pwdNew').value = '';
        $('#pwdNew').type = 'password';
        $('#pwdError').hidden = true;
        $('#pwdModal').hidden = false;
        setTimeout(() => $('#pwdNew').focus(), 30);
    }

    async function doReset() {
        const pwd = $('#pwdNew').value;
        if (!pwd || pwd.length < 6) {
            $('#pwdError').textContent = '密码至少 6 位';
            $('#pwdError').hidden = false;
            return;
        }
        const btn = $('#pwdSaveBtn');
        btn.disabled = true;
        try {
            const res = await postJson('/api/admin/users/' + resettingId + '/password', 'PUT', { password: pwd });
            if (res.code === 0) {
                $('#pwdModal').hidden = true;
                toast('密码已重置');
            } else {
                $('#pwdError').textContent = res.message || '重置失败';
                $('#pwdError').hidden = false;
            }
        } finally {
            btn.disabled = false;
        }
    }

    // ---------------- 交互绑定 ----------------

    $('#addBtn').onclick = openCreate;
    $('#userSaveBtn').onclick = saveUser;
    $('#pwdSaveBtn').onclick = doReset;

    ['#fPwdEye', '#pwdNewEye'].forEach((sel) => {
        const eye = $(sel);
        const input = $(sel === '#fPwdEye' ? '#fPassword' : '#pwdNew');
        eye.addEventListener('click', () => {
            const showing = input.type === 'text';
            input.type = showing ? 'password' : 'text';
            eye.style.color = showing ? '' : 'var(--accent)';
            input.focus();
        });
    });

    document.querySelectorAll('[data-close]').forEach((el) => {
        el.addEventListener('click', () => { $('#' + el.dataset.close).hidden = true; });
    });

    $('#searchInput').addEventListener('input', (e) => {
        clearTimeout(searchTimer);
        const v = e.target.value.trim();
        searchTimer = setTimeout(() => loadUsers(v), 280);
    });

    $('#logoutBtn').onclick = async () => {
        await fetch('/api/auth/logout', { method: 'POST' });
        goLogin();
    };

    const railToggle = $('#railToggle');
    if (railToggle) railToggle.onclick = () => document.body.classList.toggle('rail-open');
    const railMask = $('#railMask');
    if (railMask) railMask.onclick = () => document.body.classList.remove('rail-open');

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
            ['userModal', 'pwdModal'].forEach((id) => {
                const m = $('#' + id);
                if (m && !m.hidden) m.hidden = true;
            });
            if (typing) $('#searchInput').blur();
        }
    });

    // ---------------- 启动 ----------------

    (async function boot() {
        const me = await loadMe();
        if (!me) return;
        await loadUsers('');
    })();
})();
