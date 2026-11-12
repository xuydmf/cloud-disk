/* ============================================================
   登录页交互
   ============================================================ */
(function () {
    'use strict';

    const form = document.getElementById('loginForm');
    const usernameEl = document.getElementById('username');
    const passwordEl = document.getElementById('password');
    const errorBox = document.getElementById('authError');
    const errorText = document.getElementById('authErrorText');
    const submitBtn = document.getElementById('loginBtn');
    const submitText = document.getElementById('loginBtnText');
    const pwdEye = document.getElementById('pwdEye');
    const capsHint = document.getElementById('capsHint');

    function showError(msg) {
        errorText.textContent = msg;
        errorBox.hidden = false;
    }

    function clearError() {
        errorBox.hidden = true;
    }

    function setBusy(busy) {
        submitBtn.disabled = busy;
        submitText.textContent = busy ? '登录中…' : '登录';
    }

    // 已登录则直接进工作台
    fetch('/api/auth/current', { headers: { 'Accept': 'application/json' } })
        .then((r) => (r.ok ? r.json() : null))
        .then((res) => {
            if (res && res.code === 0 && res.data) {
                location.replace('index.html');
            }
        })
        .catch(() => { /* 离线时留给表单提交报错 */ });

    // 密码显隐：图标 + 文字状态都跟着变，避免只有一个很淡的小眼睛
    pwdEye.addEventListener('click', () => {
        const show = passwordEl.type === 'password';
        passwordEl.type = show ? 'text' : 'password';
        pwdEye.classList.toggle('is-on', show);
        pwdEye.setAttribute('aria-pressed', show ? 'true' : 'false');
        pwdEye.setAttribute('aria-label', show ? '隐藏密码' : '显示密码');
        pwdEye.title = show ? '隐藏密码' : '显示密码';
        passwordEl.focus();
    });

    // Caps Lock 提示
    function checkCaps(e) {
        if (typeof e.getModifierState !== 'function') return;
        capsHint.hidden = !e.getModifierState('CapsLock');
    }
    [usernameEl, passwordEl].forEach((el) => {
        el.addEventListener('keydown', checkCaps);
        el.addEventListener('keyup', checkCaps);
        el.addEventListener('blur', () => { capsHint.hidden = true; });
        el.addEventListener('input', clearError);
    });

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearError();

        const username = usernameEl.value.trim();
        const password = passwordEl.value;
        if (!username) { showError('请输入用户名'); usernameEl.focus(); return; }
        if (!password) { showError('请输入密码'); passwordEl.focus(); return; }

        setBusy(true);
        try {
            const resp = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });
            const res = await resp.json();

            if (res.code === 0) {
                submitText.textContent = '登录成功';
                location.replace('index.html');
                return;
            }
            showError(res.message || '登录失败，请重试');
            passwordEl.value = '';
            passwordEl.focus();
        } catch (err) {
            showError('无法连接服务器，请检查网络后重试');
        } finally {
            setBusy(false);
        }
    });
})();
