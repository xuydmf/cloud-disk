/* ============================================================
   统一确认 / 输入弹框（替代原生 confirm() / prompt()）
   ------------------------------------------------------------
   原生弹框的四个问题在这里解决：
     1. 多行文本靠 \n 硬折行，文件名一长就挤在一起  → 拆成 title + sub + note
     2. 危险操作与普通操作长得一样                  → danger 变体走红色主按钮
     3. 没有上下文（数量 / 能否恢复）                → 用 note 说明后果
     4. prompt 无法校验                             → validate 回调 + 实时错误提示

   依赖 style.css 里的 .modal / .modal-confirm / .m-head / .m-body / .form-actions

   用法：
     await uiConfirm({ title, sub, note, noteDanger, okText, danger })
     await uiConfirm({ ..., altText: '仅删除分组' })   // 三选一：主按钮 / 次要按钮
     await uiPrompt({ title, sub, label, value, placeholder, hint, maxlength,
                      required, validate, okText })
   返回 Promise：
     uiConfirm          确认 → true，取消/Esc → false
     uiConfirm + altText 确认 → true，次要按钮 → 'alt'，取消/Esc → false
     uiPrompt           确认 → 输入值，取消/Esc → null
   ============================================================ */

(function () {
    'use strict';

    const ICONS = {
        trash:
            '<path d="M4 7h16"/><path d="M9.5 7V5.4A1.4 1.4 0 0 1 10.9 4h2.2a1.4 1.4 0 0 1 1.4 1.4V7"/>' +
            '<path d="M6.2 7l.8 12.1a1.5 1.5 0 0 0 1.5 1.4h7a1.5 1.5 0 0 0 1.5-1.4L17.8 7"/>' +
            '<path d="M10.4 11v5.4M13.6 11v5.4"/>',
        folderPlus:
            '<path d="M3 8.2A2.2 2.2 0 0 1 5.2 6h3.4l1.8 2h8.4A2.2 2.2 0 0 1 21 10.2v7.6A2.2 2.2 0 0 1 18.8 20H5.2A2.2 2.2 0 0 1 3 17.8V8.2Z"/>' +
            '<path d="M12 11.6v5M9.5 14.1h5"/>',
        pencil:
            '<path d="M4 20h4.2L19 9.2a2 2 0 0 0 0-2.8l-1.4-1.4a2 2 0 0 0-2.8 0L4 15.8V20Z"/>' +
            '<path d="M14.4 6.9 17.1 9.6"/>',
        userX:
            '<circle cx="9.5" cy="8.2" r="3.6"/><path d="M2.8 20.4a6.7 6.7 0 0 1 13.4 0"/>' +
            '<path d="m16.4 9.2 4.2 4.2M20.6 9.2l-4.2 4.2"/>',
        userCheck:
            '<circle cx="9.2" cy="8" r="3.7"/><path d="M2.6 20.4a6.6 6.6 0 0 1 13.2 0"/>' +
            '<path d="m15.4 13.1 2.1 2.2 4.1-4.4"/>',
        sparkle:
            '<path d="M12 3.4l1.7 4.6 4.6 1.7-4.6 1.7L12 16l-1.7-4.6L5.7 9.7l4.6-1.7L12 3.4Z"/>' +
            '<path d="M18.6 15.4l.8 2.1 2.1.8-2.1.8-.8 2.1-.8-2.1-2.1-.8 2.1-.8.8-2.1Z"/>',
        save:
            '<path d="M5.6 3.8h9.2l4.4 4.4v11a1.4 1.4 0 0 1-1.4 1.4H5.6a1.4 1.4 0 0 1-1.4-1.4V5.2a1.4 1.4 0 0 1 1.4-1.4Z"/>' +
            '<path d="M8 3.8v4.6h6.4V3.8"/><path d="M8 20.4v-6.2h8v6.2"/>',
        warn:
            '<path d="M12 8.5v4.2"/><circle cx="12" cy="16.4" r=".1" fill="currentColor" stroke-width="2.4"/>' +
            '<path d="M12 3.6 2.8 19.4a1 1 0 0 0 .9 1.5h16.6a1 1 0 0 0 .9-1.5L12 3.6Z"/>',
        info:
            '<circle cx="12" cy="12" r="8.4"/><path d="M12 11.4v5"/>' +
            '<circle cx="12" cy="7.9" r=".1" fill="currentColor" stroke-width="2.4"/>'
    };

    const closeX =
        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round">' +
        '<path d="m6 6 12 12M18 6 6 18"/></svg>';

    function icon(name) {
        return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" ' +
            'stroke-linecap="round" stroke-linejoin="round">' + (ICONS[name] || ICONS.info) + '</svg>';
    }

    function esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    let seq = 0;

    /**
     * 建一个弹框 DOM 并挂到 body。
     * 做成一个「一次性实例」：关闭后整体移除，不污染 DOM。
     */
    function build(opts) {
        const id = 'uiDialog' + (++seq);
        const danger = !!opts.danger;

        const el = document.createElement('div');
        el.className = 'modal';
        el.id = id;
        el.hidden = true;

        const noteHtml = opts.note
            ? '<div class="m-note' + (danger ? ' danger' : '') + '">' +
                  icon(danger ? 'warn' : 'info') +
                  '<span>' + opts.note + '</span>' +
              '</div>'
            : '';

        // prompt 变体才渲染输入框
        const inputHtml = opts.input
            ? '<label class="field">' +
                  '<span class="field-label">' + esc(opts.label || '') + '</span>' +
                  '<input type="text" id="' + id + '-input" autocomplete="off" spellcheck="false"' +
                      (opts.maxlength ? ' maxlength="' + opts.maxlength + '"' : '') +
                      ' value="' + esc(opts.value || '') + '"' +
                      ' placeholder="' + esc(opts.placeholder || '') + '">' +
                  '<span class="field-hint" id="' + id + '-hint">' + esc(opts.hint || '') + '</span>' +
              '</label>'
            : '';

        el.innerHTML =
            '<div class="modal-mask" data-act="cancel"></div>' +
            '<div class="modal-body modal-confirm" role="dialog" aria-modal="true" ' +
                 'aria-labelledby="' + id + '-t">' +
                '<div class="m-head">' +
                    '<span class="m-icon' + (danger ? ' danger' : '') + '">' + icon(opts.icon || 'info') + '</span>' +
                    (opts.eyebrow ? '<p class="m-eyebrow">' + esc(opts.eyebrow) + '</p>' : '') +
                    '<h3 class="modal-title" id="' + id + '-t">' + esc(opts.title || '') + '</h3>' +
                    (opts.sub ? '<p class="modal-sub">' + esc(opts.sub) + '</p>' : '') +
                    '<button class="modal-close" type="button" data-act="cancel" aria-label="关闭">' + closeX + '</button>' +
                '</div>' +
                '<div class="m-body">' +
                    (noteHtml || inputHtml
                        ? (inputHtml ? inputHtml : '') + (noteHtml || '')
                        : '') +
                '</div>' +
                '<div class="form-actions">' +
                    '<button class="btn btn-ghost" type="button" data-act="cancel">' + esc(opts.cancelText || '取消') + '</button>' +
                    (opts.altText
                        ? '<button class="btn btn-outline" type="button" data-act="alt">' + esc(opts.altText) + '</button>'
                        : '') +
                    '<button class="btn ' + (danger ? 'btn-danger' : 'btn-primary') + '" type="button" data-act="ok">' +
                        esc(opts.okText || '确定') +
                    '</button>' +
                '</div>' +
            '</div>';

        document.body.appendChild(el);
        return { el: el, id: id };
    }

    /**
     * 通用执行流程：挂载 → 绑定 → 显示 → 返回 Promise
     */
    function run(opts, resolveValue, isInput) {
        const { el, id } = build(Object.assign({ input: isInput }, opts));
        const input = isInput ? el.querySelector('#' + id + '-input') : null;
        const hint = isInput ? el.querySelector('#' + id + '-hint') : null;

        let done = false;

        // 恢复焦点：弹框关闭后把焦点还给触发它的元素
        const prevFocus = document.activeElement;

        function finish(result) {
            if (done) return;
            done = true;
            el.hidden = true;
            document.removeEventListener('keydown', onKey, true);
            // 等动画结束再移除，避免闪现
            setTimeout(function () { el.remove(); }, 180);
            if (prevFocus && typeof prevFocus.focus === 'function') {
                try { prevFocus.focus(); } catch (e) {}
            }
            resolveValue(result);
        }

        function readInput() {
            return input ? input.value.trim() : '';
        }

        /** 校验：返回错误文案则拦截，返回空串通过 */
        function check() {
            if (!input) return '';
            const v = readInput();
            if (opts.required !== false && !v) {
                return opts.requiredMessage || '不能为空';
            }
            if (opts.validate) {
                const msg = opts.validate(v, input);
                if (msg) return msg;
            }
            return '';
        }

        function showError(msg) {
            if (!input) return;
            input.classList.toggle('invalid', !!msg);
            if (hint) {
                hint.textContent = msg || opts.hint || '';
                hint.classList.toggle('error', !!msg);
            }
        }

        function submit() {
            if (isInput) {
                const msg = check();
                if (msg) {
                    showError(msg);
                    input.focus();
                    return;
                }
                finish(readInput());
            } else {
                finish(true);
            }
        }

        // 点击：遮罩 / 关闭 / 取消 / 次要选择 / 确定
        el.addEventListener('click', function (e) {
            const t = e.target.closest('[data-act]');
            if (!t) return;
            const act = t.dataset.act;
            if (act === 'cancel') finish(isInput ? null : false);
            else if (act === 'alt') finish('alt');
            else if (act === 'ok') submit();
        });

        // 键盘：Esc 取消，Enter 提交（输入框内）
        // prompt 场景下 Enter 提交、Shift+Enter 也提交，与原生行为一致
        function onKey(e) {
            if (e.key === 'Escape') {
                e.preventDefault();
                e.stopPropagation();
                finish(isInput ? null : false);
            } else if (e.key === 'Enter') {
                // 焦点在次要按钮上时，Enter 应触发次要按钮而不是主按钮
                const active = document.activeElement;
                if (!isInput && active && active.dataset && active.dataset.act === 'alt') {
                    e.preventDefault();
                    e.stopPropagation();
                    finish('alt');
                    return;
                }
                // 非输入型也允许 Enter 确认，减少鼠标移动
                e.preventDefault();
                submit();
            }
        }
        document.addEventListener('keydown', onKey, true);

        if (input) {
            // 输入即清错，避免用户还没改完就一直红着
            input.addEventListener('input', function () {
                if (input.classList.contains('invalid')) showError('');
            });
        }

        el.hidden = false;

        // 聚焦策略：
        //   prompt → 聚焦输入框并全选（直接输入即替换旧值）
        //   confirm → 聚焦弹框容器本身，而不是主按钮。
        //   若聚焦主按钮，浏览器会判定为 focus-visible 并画出轮廓环，
        //   套在红色按钮外很脏；而聚焦容器既能让 Esc/Enter 生效，又不产生可见环。
        //   键盘用户按一次 Tab 即可落到「取消」，两下到主按钮，顺序依然自然。
        const dialogEl = el.querySelector('.modal-body');
        setTimeout(function () {
            if (input) {
                input.focus();
                input.select();
            } else if (dialogEl) {
                dialogEl.setAttribute('tabindex', '-1');
                dialogEl.focus();
            }
        }, 30);
    }

    /**
     * 确认弹框
     * @returns {Promise<boolean>} 确认 true，取消 false
     */
    function uiConfirm(opts) {
        return new Promise(function (resolve) {
            run(opts || {}, resolve, false);
        });
    }

    /**
     * 输入弹框
     * @returns {Promise<string|null>} 确认返回去空格后的值，取消返回 null
     */
    function uiPrompt(opts) {
        return new Promise(function (resolve) {
            run(opts || {}, resolve, true);
        });
    }

    // 暴露到全局（各页面的 JS 直接调用）
    window.uiConfirm = uiConfirm;
    window.uiPrompt = uiPrompt;
})();
