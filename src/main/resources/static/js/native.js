/**
 * Capacitor 原生外壳适配层：原生相册（PHPicker）+ 原生大文件上传。
 *
 * 为什么需要这一层
 * ---------------
 * 网页里的 `<input type="file">` 在 iOS 上由 WebKit 负责把选中的照片「导出」成文件：
 * 点「添加」之后，系统要先转码（HEIC→JPEG）、拷贝、可能还要从 iCloud 下原图，
 * 全部做完才关窗、才触发 `change`。10 张照片就是 2~3 秒，页面一行 JS 都还没跑，
 * 既关不掉也提示不了。
 *
 * 原生壳换成 PHPicker：点「添加」立刻关窗，`chooseFromGallery()` 在后台把文件取回来。
 * 用户在等待期间看到的是 App 自己的界面 + 「正在导入」提示，而不是一个卡住的系统相册。
 *
 * 浏览器里这个文件不起作用（`canPick() === false`），所有调用自动退回原来的
 * `<input type="file">` 逻辑，PC / 手机浏览器行为完全不变。
 */
(function () {
    'use strict';

    const cap = window.Capacitor;
    const native = !!(cap && typeof cap.isNativePlatform === 'function' && cap.isNativePlatform());
    const plugins = (cap && cap.Plugins) || {};
    const Camera = plugins.Camera || null;
    const FileTransfer = plugins.FileTransfer || null;

    const IMAGE_MIME = {
        jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif',
        heic: 'image/heic', heif: 'image/heif', webp: 'image/webp',
        tif: 'image/tiff', tiff: 'image/tiff', bmp: 'image/bmp'
    };
    const VIDEO_MIME = {
        mov: 'video/quicktime', mp4: 'video/mp4', m4v: 'video/x-m4v',
        '3gp': 'video/3gpp', webm: 'video/webm', avi: 'video/x-msvideo'
    };

    // 与原生枚举对应：MediaTypeSelection.Photo=0 / Video=1 / All=2
    const MEDIA_ALL = 2;
    const MEDIA_VIDEO = 1;

    function pad(n) { return (n < 10 ? '0' : '') + n; }

    /** 拍摄时间：优先相册给的 creationDate；设备时钟抽风的（1970 / 未来）按现在算 */
    function dateOf(iso) {
        const t = Date.parse(iso || '');
        if (!t || t < 946684800000 || t > Date.now() + 2 * 86400000) return new Date();
        return new Date(t);
    }

    function extOf(format, isVideo) {
        let f = String(format || '').toLowerCase().replace(/^\./, '');
        if (f === 'jpeg') f = 'jpg';
        if (f === 'quicktime') f = 'mov';
        if (f) return f;
        return isVideo ? 'mp4' : 'jpg';
    }

    function makeName(prefix, date, ext, seq) {
        const base = prefix + '_' + date.getFullYear() + pad(date.getMonth() + 1) + pad(date.getDate())
            + '_' + pad(date.getHours()) + pad(date.getMinutes()) + pad(date.getSeconds());
        return base + (seq > 1 ? '_' + seq : '') + '.' + ext;
    }

    /** 连拍会出现「同一秒好几张」：按 前缀+时间 累加序号，避免重名 */
    function nameMaker() {
        const seen = new Map();
        return function next(prefix, date, ext) {
            const key = prefix + '|' + date.getTime();
            const n = (seen.get(key) || 0) + 1;
            seen.set(key, n);
            return makeName(prefix, date, ext, n);
        };
    }

    function basename(p) {
        try {
            const clean = decodeURIComponent(String(p || '')).split('?')[0];
            return (clean.split('/').pop() || '').replace(/[\\/:*?"<>|]/g, '_');
        } catch (e) {
            return '';
        }
    }

    function canPick() {
        return native && !!Camera && typeof Camera.chooseFromGallery === 'function';
    }

    function canUpload() {
        return native && !!FileTransfer && typeof FileTransfer.uploadFile === 'function';
    }

    /**
     * 打开系统相册（PHPicker）。返回的是「描述对象」而不是 File：
     *   · 照片 { nativePhoto: true, webPath, name, size, type, lastModified }
     *     不上来就读进内存——20 张 12MP 照片就是 60MB Blob，WKWebView 会吃紧。
     *     等上传流水线真正轮到它时，再 readFile() 一张一张读。
     *   · 视频 { nativePath, name, size, type, lastModified }
     *     视频可能有几百 MB，永远不进 JS 内存，直接交给原生 FileTransfer 从磁盘发。
     */
    async function pickMedia() {
        if (!canPick()) return [];

        let res;
        try {
            res = await Camera.chooseFromGallery({
                mediaType: MEDIA_ALL,
                allowMultipleSelection: true,
                limit: 0,              // 0 = 不限张数
                includeMetadata: true
            });
        } catch (e) {
            // 用户点「取消」不是错误
            if (/cancel/i.test((e && e.message) || '')) return [];
            throw e;
        }

        const items = (res && res.results) || [];
        const nextName = nameMaker();

        return items.map(function (item) {
            const isVideo = item.type === MEDIA_VIDEO || item.type === 'video';
            const meta = item.metadata || {};
            const ext = extOf(meta.format, isVideo);
            const mime = (isVideo ? VIDEO_MIME : IMAGE_MIME)[ext] || (isVideo ? 'video/mp4' : 'image/jpeg');
            const date = dateOf(meta.creationDate);

            if (isVideo) {
                // 文件名：FileTransfer 用的是原生路径的文件名，这里跟着它走，避免
                // 「确认面板显示 A、库里变成 B」。取不到就用生成的名字。
                const base = basename(item.uri || item.webPath);
                const name = (base && /\.[a-z0-9]{2,5}$/i.test(base)) ? base : nextName('VID', date, ext);
                return {
                    nativePath: item.uri,
                    name: name,
                    size: meta.size || 0,
                    type: mime,
                    lastModified: date.getTime()
                };
            }

            return {
                nativePhoto: true,
                webPath: item.webPath,
                name: nextName('IMG', date, ext),
                size: meta.size || 0,
                type: mime,
                lastModified: date.getTime()
            };
        }).filter(function (f) {
            return (f.nativePhoto && f.webPath) || (f.nativePath && String(f.nativePath).length > 0);
        });
    }

    /** 把一张相册照片读成 Blob（上传流水线轮到它时才调用） */
    async function readFile(webPath) {
        let lastErr = null;
        for (let attempt = 0; attempt < 2; attempt++) {
            try {
                const r = await fetch(webPath, { cache: 'no-store' });
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return await r.blob();
            } catch (e) {
                lastErr = e;
                await new Promise(function (r) { setTimeout(r, 150 * (attempt + 1)); });
            }
        }
        throw lastErr || new Error('读取相册文件失败');
    }

    /**
     * 原生上传一个文件（multipart，字段名 file）。
     * job: { url, path, params, mimeType }，params 会变成 URL 查询参数
     * （服务端 @RequestParam 同时认 query 和 form，所以 groupId / capturedTime 这样传就行）。
     */
    async function upload(job, onProgress) {
        if (!canUpload()) throw new Error('当前安装包没有原生上传插件');
        let handle = null;
        if (onProgress) {
            handle = await FileTransfer.addListener('progress', function (p) {
                onProgress(p.bytes, p.contentLength);
            });
        }
        try {
            // 插件要的是绝对地址（原生 URLSession 不认相对路径）
            const absolute = new URL(job.url, window.location.href).toString();
            const res = await FileTransfer.uploadFile({
                url: absolute,
                path: job.path,
                params: job.params || {},
                fileKey: 'file',
                mimeType: job.mimeType || '',
                chunkedMode: false,
                progress: true
            });
            return { status: String(res.responseCode || ''), body: res.response || '' };
        } finally {
            if (handle && handle.remove) {
                try { await handle.remove(); } catch (e) { /* 忽略 */ }
            }
        }
    }

    window.NativeShell = {
        isNative: native,
        platform: (cap && typeof cap.getPlatform === 'function') ? cap.getPlatform() : 'web',
        canPick: canPick,
        canUpload: canUpload,
        pickMedia: pickMedia,
        readFile: readFile,
        upload: upload
    };
})();
