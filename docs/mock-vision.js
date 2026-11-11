// 本地 mock：模拟 DashScope 的 OpenAI 兼容 /chat/completions
// 用途：在没有真实 API Key 的情况下验证「请求构造 → 响应解析 → 自动分组 → 落库」整条链路。
const http = require('http');
const fs = require('fs');

const logPath = process.argv[2] || 'mock.log';
// 通过这个文件切换 mock 的返回结果，用来验证不同的判定分支
const modePath = process.argv[3] || 'mock-mode.txt';

function readMode() {
    try {
        return fs.readFileSync(modePath, 'utf8').trim() || 'high';
    } catch (e) {
        return 'high';
    }
}

/** 按 mode 返回不同的「模型判定」 */
function verdictFor(mode) {
    if (mode === 'medium') {
        return { match: 1, confidence: 'medium', reason: 'mock 中置信' };
    }
    if (mode === 'low') {
        return { match: 1, confidence: 'low', reason: 'mock 低置信' };
    }
    if (mode === 'none') {
        return { match: null, confidence: 'high', reason: '画面里没有可识别的人物' };
    }
    return { match: 1, confidence: 'high', reason: 'mock 高置信命中' };
}

const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
        const buf = Buffer.concat(chunks);
        if (!req.url.includes('/chat/completions')) {
            res.writeHead(404, { 'Content-Type': 'text/plain' });
            res.end('not found');
            return;
        }

        const text = buf.toString('utf8');
        const imageCount = (text.match(/"image_url"/g) || []).length;
        const mode = readMode();
        // 把关键信息写文件，方便外部断言「图片确实编码进去了、参考图数量对不对」
        try {
            fs.writeFileSync(logPath, JSON.stringify({
                mode: mode,
                bytes: buf.length,
                // 每张图在 JSON 里出现两次 image_url（type + 字段名），除以 2 才是张数
                imageParts: imageCount / 2,
                hasModel: /"model"\s*:\s*"qwen-vl-max"/.test(text),
                hasCandidates: /候选清单/.test(text),
                auth: req.headers['authorization'] || ''
            }) + '\n');
        } catch (e) { /* 忽略日志失败 */ }

        const content = JSON.stringify(verdictFor(mode));
        const body = JSON.stringify({
            id: 'mock-1',
            choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }]
        });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(body);
    });
});

server.listen(8899, '127.0.0.1', () => {
    console.log('mock listening on 127.0.0.1:8899');
});
