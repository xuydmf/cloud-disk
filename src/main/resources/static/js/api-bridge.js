/**
 * 原生 App 的 API 桥。
 *
 * 独立 App 的页面从 `capacitor://localhost` 加载（打包在安装包里），
 * 访问 `http://192.168.3.31:8080` 属于跨域：浏览器不会带会话 cookie，
 * 所以登录响应给一个 `X-Auth-Token`，之后：
 *   · fetch / XHR 自动改成绝对地址并带 `Authorization: Bearer <token>`
 *   · <img> / <video> 用 `mediaUrl()` 拼上 `?token=<token>`
 *
 * 浏览器里（同源 + cookie）这个文件什么都不做，PC / 手机浏览器行为不变。
 *
 * 换服务器地址：改下面的 DEFAULT_BASE，然后重新跑一次 GitHub Actions 打包。
 */
(function () {
    'use strict';

    const cap = window.Capacitor;
    const native = !!(cap && typeof cap.isNativePlatform === 'function' && cap.isNativePlatform());

    const DEFAULT_BASE = 'http://192.168.3.31:8080';
    const BASE = native ? (window.CAP_API_BASE || DEFAULT_BASE) : '';
    const TOKEN_KEY = 'cd_auth_token';

    let token = '';
    try { token = localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { /* 隐私模式 */ }

    function setToken(next) {
        token = next || '';
        try {
            if (token) localStorage.setItem(TOKEN_KEY, token);
            else localStorage.removeItem(TOKEN_KEY);
        } catch (e) { /* 忽略 */ }
    }

    function apiUrl(path) {
        if (!BASE || !path) return path;
        if (/^[a-z][a-z0-9+.-]*:/i.test(path)) return path;   // http/blob/data/capacitor...
        return BASE + (path.charAt(0) === '/' ? path : '/' + path);
    }

    function mediaUrl(path) {
        if (!path) return path;
        const url = apiUrl(path);
        if (!BASE || !token) return url;
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + 'token=' + encodeURIComponent(token);
    }

    window.APP_IS_NATIVE = native;
    window.apiUrl = apiUrl;
    window.mediaUrl = mediaUrl;
    window.getAuthToken = function () { return token; };

    // 网页：同源 cookie，原样不动
    if (!native) return;

    // ---- fetch ----
    const realFetch = window.fetch.bind(window);
    window.fetch = function (input, init) {
        const url = typeof input === 'string' ? input : '';
        if (!url || url.indexOf('/api/') !== 0) {
            return realFetch(input, init);
        }
        const opts = Object.assign({}, init || {});
        const headers = new Headers((init && init.headers) || {});
        if (token) headers.set('Authorization', 'Bearer ' + token);
        opts.headers = headers;

        return realFetch(apiUrl(url), opts).then(function (res) {
            const fresh = res.headers.get('X-Auth-Token');
            if (fresh) setToken(fresh);
            if (url.indexOf('/api/auth/logout') === 0) setToken('');
            return res;
        });
    };

    // ---- XMLHttpRequest（上传进度要走它） ----
    const xhrOpen = XMLHttpRequest.prototype.open;
    const xhrSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
        const rest = Array.prototype.slice.call(arguments, 2);
        this.__cdApi = typeof url === 'string' && url.indexOf('/api/') === 0;
        return xhrOpen.apply(this, [method, this.__cdApi ? apiUrl(url) : url].concat(rest));
    };
    XMLHttpRequest.prototype.send = function (body) {
        if (this.__cdApi && token) {
            try { this.setRequestHeader('Authorization', 'Bearer ' + token); } catch (e) { /* 忽略 */ }
        }
        return xhrSend.call(this, body);
    };
})();
