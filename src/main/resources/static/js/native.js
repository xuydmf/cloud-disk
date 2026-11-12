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

    const VIDEO_MIME = {
        mov: 'video/quicktime', mp4: 'video/mp4', m4v: 'video/x-m4v',
        '3gp': 'video/3gpp', webm: 'video/webm', avi: 'video/x-msvideo'
    };

    // 与原生枚举对应：MediaTypeSelection.Video = 1
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

    function canPickPhotos() {
        // 注意：用的是旧接口 pickImages，不是 chooseFromGallery。
        // chooseFromGallery 底层是 IONCAMR 自己画的相册，它会等所有照片导出完才 dismiss，
        // 真机上仍然"关得很慢"；pickImages 走系统 PHPicker，点「完成」立即关窗，回调异步。
        return native && !!Camera && typeof Camera.pickImages === 'function';
    }

    function canPickVideos() {
        return native && !!Camera && typeof Camera.chooseFromGallery === 'function';
    }

    function canPick() {
        return canPickPhotos() || canPickVideos();
    }

    function canUpload() {
        return native && !!FileTransfer && typeof FileTransfer.uploadFile === 'function';
    }

    /** EXIF 的 DateTimeOriginal（"2026:09:20 18:30:00"）→ Date */
    function exifDate(exif) {
        if (!exif) return null;
        const raw = exif.DateTimeOriginal || exif.DateTimeDigitized;
        const m = /^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(String(raw || ''));
        if (!m) return null;
        const d = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
        return isNaN(d.getTime()) ? null : d;
    }

    /**
     * 照片：系统 PHPicker（pickImages）。点「完成」立即关窗，随后在 App 里处理。
     *
     * 原生侧同时做了两件事：
     *   · 按 width/height = 2560 等比缩到长边 2560（和网页 shrinkImage 的规格一致）
     *   · 按 quality 86 编码成 JPEG
     * 所以描述对象带 precompressed: true，上传流水线不再重复压缩。
     *
     * 描述对象不读字节：等流水线轮到它时再 readFile() 一张一张读，
     * 避免 20 张照片一次性变成 60MB Blob。
     */
    async function pickPhotos() {
        if (!canPickPhotos()) return [];
        let res;
        try {
            res = await Camera.pickImages({
                limit: 20,             // 一次最多 20 张：原生侧会把整批解码后的图留在内存里
                width: 2560,
                height: 2560,
                quality: 86,
                correctOrientation: true
            });
        } catch (e) {
            // 用户点「取消」不是错误
            if (/cancel/i.test((e && e.message) || '')) return [];
            throw e;
        }

        const photos = (res && res.photos) || [];
        const nextName = nameMaker();
        return photos.map(function (p) {
            const date = exifDate(p.exif) || new Date();
            return {
                nativePhoto: true,
                precompressed: true,
                webPath: p.webPath,
                name: nextName('IMG', date, 'jpg'),
                size: 0,                    // 原生没回大小；流水线读到 Blob 后补上
                type: 'image/jpeg',
                lastModified: date.getTime()
            };
        }).filter(function (f) { return !!f.webPath; });
    }

    /**
     * 视频：只能走 chooseFromGallery（IONCAMR 的相册）。它在关窗前要把视频导出，
     * 所以视频的关窗速度仍由系统决定；照片已经不受影响了。
     */
    async function pickVideos() {
        if (!canPickVideos()) return [];
        let res;
        try {
            res = await Camera.chooseFromGallery({
                mediaType: MEDIA_VIDEO,
                allowMultipleSelection: true,
                limit: 0,
                includeMetadata: true
            });
        } catch (e) {
            if (/cancel/i.test((e && e.message) || '')) return [];
            throw e;
        }

        const items = (res && res.results) || [];
        const nextName = nameMaker();
        return items.map(function (item) {
            const meta = item.metadata || {};
            const ext = extOf(meta.format, true);
            const mime = VIDEO_MIME[ext] || 'video/quicktime';
            const date = dateOf(meta.creationDate);
            // FileTransfer 用原生路径的文件名，这里跟着它走，避免「面板显示 A、库里变成 B」
            const base = basename(item.uri || item.webPath);
            const name = (base && /\.[a-z0-9]{2,5}$/i.test(base)) ? base : nextName('VID', date, ext);
            return {
                nativePath: item.uri,
                name: name,
                size: meta.size || 0,
                type: mime,
                lastModified: date.getTime()
            };
        }).filter(function (f) { return f.nativePath && String(f.nativePath).length > 0; });
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
        canPickPhotos: canPickPhotos,
        canPickVideos: canPickVideos,
        canUpload: canUpload,
        pickPhotos: pickPhotos,
        pickVideos: pickVideos,
        readFile: readFile,
        upload: upload
    };
})();
