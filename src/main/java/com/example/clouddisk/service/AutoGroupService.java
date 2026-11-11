package com.example.clouddisk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.clouddisk.common.FileType;
import com.example.clouddisk.entity.FileGroup;
import com.example.clouddisk.entity.FileInfo;
import com.example.clouddisk.mapper.FileGroupMapper;
import com.example.clouddisk.mapper.FileInfoMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片智能识别自动分组。
 *
 * <p>工作方式：上传的图片如果是「未分组」或用户希望让 AI 再判一次，就把图片和每个分组的
 * <b>样貌参考图</b>一起发给视觉大模型，让模型判断图中最显著的人物是否属于某个分组。
 * 命中且置信度足够高才改写 group_id，否则保持原样。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>绝不影响上传</b>：识别在独立线程池里跑，任何异常只写日志和 ai_note，不向上抛。</li>
 *   <li><b>没有配置 key 就自动关闭</b>：不报错、不提示，静默跳过，避免污染上传流程。</li>
 *   <li><b>只认 high 置信</b>：模型说「不确定」一律不改分组，宁可漏分也不误分。</li>
 * </ol>
 */
@Slf4j
@Service
public class AutoGroupService {

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*\\}");

    /** 送进模型的图片长边上限。太大既慢又费 token，太小又认不清 */
    private static final int MAX_EDGE = 1024;

    private final FileGroupMapper groupMapper;
    private final FileInfoMapper fileInfoMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${file.storage.root}")
    private String storageRoot;

    @Value("${file.auto-group.enabled:true}")
    private boolean enabled;

    @Value("${file.auto-group.api-key:}")
    private String apiKey;

    @Value("${file.auto-group.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${file.auto-group.model:qwen-vl-max}")
    private String model;

    @Value("${file.auto-group.timeout-seconds:40}")
    private int timeoutSeconds;

    @Value("${file.auto-group.max-candidates:6}")
    private int maxCandidates;

    /** 只接受这个级别及以上的置信度才改分组 */
    @Value("${file.auto-group.min-confidence:high}")
    private String minConfidence;

    /** 覆盖用户手动选择的开关：false 时手上已选分组的图片不再被 AI 改写 */
    @Value("${file.auto-group.override-manual:true}")
    private boolean overrideManual;

    private RestTemplate restTemplate;
    private ExecutorService executor;

    public AutoGroupService(FileGroupMapper groupMapper, FileInfoMapper fileInfoMapper) {
        this.groupMapper = groupMapper;
        this.fileInfoMapper = fileInfoMapper;
    }

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(Math.max(5, timeoutSeconds) * 1000);
        this.restTemplate = new RestTemplate(factory);

        final AtomicInteger seq = new AtomicInteger(1);
        this.executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "auto-group-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });

        if (!enabled) {
            log.info("图片智能识别自动分组：已关闭（file.auto-group.enabled=false）");
        } else if (isBlank(apiKey)) {
            log.warn("图片智能识别自动分组：未配置 file.auto-group.api-key，功能将跳过不执行");
        } else {
            log.info("图片智能识别自动分组：已启用，model={}，最多比对 {} 个分组，仅采纳 {} 及以上置信度",
                    model, maxCandidates, minConfidence);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** 功能是否真正可用（开关打开 + 有 key） */
    public boolean isAvailable() {
        return enabled && !isBlank(apiKey);
    }

    // ==================== 对外入口 ====================

    /**
     * 提交一次异步识别。调用方不需要关心结果，也拿不到异常。
     *
     * @param info            已落库的文件记录
     * @param physical        磁盘上的真实文件
     * @param currentGroupId  上传时用户选择的分组（null 表示未分组）
     */
    public void submitAsync(final FileInfo info, final File physical, final Long currentGroupId) {
        if (!isAvailable() || info == null || physical == null || !physical.isFile()) {
            return;
        }
        if (!FileType.IMAGE.name().equals(info.getFileType())) {
            return;
        }
        final Long fileId = info.getId();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    recognize(fileId, physical, currentGroupId);
                } catch (Exception e) {
                    // 识别是锦上添花，任何失败都不能冒泡
                    log.warn("AI 自动分组失败 id={}：{}", fileId, e.getMessage());
                    markChecked(fileId, null, "识别失败：" + brief(e));
                }
            }
        });
    }

    // ==================== 识别主流程 ====================

    private void recognize(Long fileId, File physical, Long currentGroupId) {
        // 用户明确选过分组，且配置为不覆盖 → 直接跳过
        if (currentGroupId != null && currentGroupId > 0 && !overrideManual) {
            markChecked(fileId, null, "已手动指定分组，跳过识别");
            return;
        }

        List<FileGroup> candidates = loadCandidates();
        if (candidates.isEmpty()) {
            markChecked(fileId, null, "没有设置参考图的分组，无法比对");
            return;
        }

        String prompt = buildPrompt(candidates);
        String raw = callModel(physical, candidates, prompt);
        if (isBlank(raw)) {
            markChecked(fileId, null, "模型无响应");
            return;
        }

        Verdict verdict = parseVerdict(raw, candidates);
        if (verdict == null) {
            markChecked(fileId, null, "模型返回无法解析");
            return;
        }
        if (verdict.group == null) {
            markChecked(fileId, null, "未匹配到已有分组" + (isBlank(verdict.reason) ? "" : "：" + verdict.reason));
            return;
        }
        if (!confidenceEnough(verdict.confidence)) {
            // 命中了但不够确定 → 只记录，不改分组
            markChecked(fileId, verdict.group.getId(),
                    "疑似「" + verdict.group.getName() + "」，置信度 " + verdict.confidence + "，未自动分组");
            return;
        }

        applyGroup(fileId, verdict.group, verdict.confidence, verdict.reason);
    }

    /** 取有参考图、且参考图文件确实存在的分组，按候选上限截断 */
    private List<FileGroup> loadCandidates() {
        LambdaQueryWrapper<FileGroup> qw = new LambdaQueryWrapper<>();
        qw.eq(FileGroup::getDeleted, 0)
                .isNotNull(FileGroup::getRefImagePath)
                .orderByAsc(FileGroup::getSort)
                .orderByAsc(FileGroup::getId);
        List<FileGroup> all = groupMapper.selectList(qw);

        List<FileGroup> valid = new ArrayList<>();
        for (FileGroup g : all) {
            File ref = resolveRefImage(g);
            if (ref != null && ref.isFile()) {
                valid.add(g);
                if (valid.size() >= Math.max(1, maxCandidates)) {
                    break;
                }
            } else {
                log.debug("分组 {} 的参考图缺失，跳过参与比对", g.getId());
            }
        }
        return valid;
    }

    /**
     * 一次请求把「待识别图 + 全部参考图」都发给模型，让它自己挑最像的那个。
     * 比逐个分组调用 N 次更省、也更准（模型能横向比较）。
     */
    private String callModel(File target, List<FileGroup> candidates, String prompt) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            // 关掉随机性：同一张图两次结果要一致
            body.put("temperature", 0.0);
            body.put("max_tokens", 300);

            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            ArrayNode content = userMsg.putArray("content");

            ObjectNode textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", prompt);

            // 第 1 张：待识别图片
            addImagePart(content, target, "待归类照片");

            // 之后：按候选顺序依次追加参考图，与 prompt 里的编号一一对应
            for (int i = 0; i < candidates.size(); i++) {
                addImagePart(content, resolveRefImage(candidates.get(i)),
                        "候选" + (i + 1) + "：" + candidates.get(i).getName());
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

            long start = System.currentTimeMillis();
            String resp = restTemplate.postForObject(
                    baseUrl + "/chat/completions", entity, String.class);
            long cost = System.currentTimeMillis() - start;

            JsonNode root = objectMapper.readTree(resp);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                log.warn("AI 自动分组：响应缺少 choices，原始响应={}", abbreviate(resp, 300));
                return null;
            }
            String text = choices.get(0).path("message").path("content").asText("");
            log.debug("AI 自动分组耗时 {}ms，模型输出：{}", cost, abbreviate(text, 200));
            return text;
        } catch (Exception e) {
            log.warn("AI 自动分组调用失败：{}", e.getMessage());
            return null;
        }
    }

    private void addImagePart(ArrayNode content, File image, String label) throws IOException {
        String dataUrl = toDataUrl(image);
        if (dataUrl == null) {
            throw new IOException("图片编码失败：" + label);
        }
        ObjectNode part = content.addObject();
        part.put("type", "image_url");
        ObjectNode urlNode = part.putObject("image_url");
        urlNode.put("url", dataUrl);
    }

    /** 压到长边 1024 再转 base64 data URL，避免请求体过大被拒 */
    private String toDataUrl(File image) {
        if (image == null || !image.isFile()) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(image)
                    .size(MAX_EDGE, MAX_EDGE)
                    .keepAspectRatio(true)
                    .outputFormat("jpg")
                    .outputQuality(0.85)
                    .toOutputStream(out);
            String b64 = Base64.getEncoder().encodeToString(out.toByteArray());
            return "data:image/jpeg;base64," + b64;
        } catch (Exception e) {
            log.warn("图片压缩失败 {}：{}", image.getName(), e.getMessage());
            return null;
        }
    }

    // ==================== 提示词 ====================

    /**
     * 提示词要点：
     * - 明确「只看最显著的那个人」，这正是需求里对多人场景的要求
     * - 用编号代替名字回传，避免模型把名字写错或加引号
     * - 强制 JSON、禁止解释，便于解析
     */
    private String buildPrompt(List<FileGroup> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("你在做相册自动归类。第 1 张图是待归类的照片，之后依次是各候选分组的人物样貌参考图。\n");
        sb.append("候选清单（编号与参考图的先后顺序一致）：\n");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append(i + 1).append(") ").append(candidates.get(i).getName()).append("\n");
        }
        sb.append("\n判断规则：\n");
        sb.append("1. 只看第 1 张照片中「最显著的那个人」：画面占比最大、最清晰、位置最靠中心的那一个。\n");
        sb.append("2. 如果照片里有多个人，忽略其余次要人物，只针对最显著的这个人做判断。\n");
        sb.append("3. 只能依据参考图里的人脸外观做比对，不要根据名字做任何推测。\n");
        sb.append("4. 照片里没有人、人脸太小太模糊、或无法确认是同一人时，match 一律填 null。\n");
        sb.append("5. 宁可判断为无匹配，也不要把不同的人认成同一个。\n");
        sb.append("\n只输出一行 JSON，不要 Markdown 代码块，不要任何额外解释：\n");
        sb.append("{\"match\": <匹配到的候选编号，无匹配填 null>, ")
                .append("\"confidence\": \"high|medium|low\", ")
                .append("\"reason\": \"不超过20字的中文说明\"}");
        return sb.toString();
    }

    private Verdict parseVerdict(String raw, List<FileGroup> candidates) {
        Matcher m = JSON_BLOCK.matcher(raw);
        if (!m.find()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(m.group());
            Verdict v = new Verdict();
            v.confidence = node.path("confidence").asText("low").trim().toLowerCase();
            v.reason = node.path("reason").asText("").trim();

            JsonNode matchNode = node.path("match");
            if (matchNode.isNull() || matchNode.isMissingNode()) {
                return v;
            }
            int idx = -1;
            if (matchNode.isNumber()) {
                idx = matchNode.asInt();
            } else {
                String s = matchNode.asText("").trim();
                // 兼容模型偶尔返回名字或 "候选1" 的情况
                if (!s.isEmpty()) {
                    try {
                        idx = Integer.parseInt(s.replaceAll("[^0-9]", ""));
                    } catch (NumberFormatException ignored) {
                        for (int i = 0; i < candidates.size(); i++) {
                            if (candidates.get(i).getName().equals(s)) {
                                idx = i + 1;
                                break;
                            }
                        }
                    }
                }
            }
            if (idx >= 1 && idx <= candidates.size()) {
                v.group = candidates.get(idx - 1);
            }
            return v;
        } catch (Exception e) {
            log.warn("AI 自动分组：解析模型输出失败 {}", abbreviate(raw, 200));
            return null;
        }
    }

    private boolean confidenceEnough(String confidence) {
        int got = rank(confidence);
        int need = rank(minConfidence);
        return got >= need && got > 0;
    }

    private int rank(String level) {
        if (level == null) {
            return 0;
        }
        String v = level.trim().toLowerCase();
        if ("high".equals(v)) {
            return 3;
        }
        if ("medium".equals(v)) {
            return 2;
        }
        if ("low".equals(v)) {
            return 1;
        }
        return 0;
    }

    // ==================== 落库 ====================

    /** 命中且够可信：改写分组，并留下可追溯的记录 */
    private void applyGroup(Long fileId, FileGroup group, String confidence, String reason) {
        FileInfo update = new FileInfo();
        update.setId(fileId);
        update.setGroupId(group.getId());
        update.setAiChecked(1);
        update.setAiGroupId(group.getId());
        String note = "已按 AI 识别归入「" + group.getName() + "」(" + confidence + ")";
        if (!isBlank(reason)) {
            note = note + "：" + reason;
        }
        update.setAiNote(truncate(note, 250));
        fileInfoMapper.updateById(update);
        log.info("AI 自动分组：文件 {} → 分组「{}」({})", fileId, group.getName(), confidence);
    }

    /** 未命中/失败：只记录结论，不动分组 */
    private void markChecked(Long fileId, Long hitGroupId, String note) {
        try {
            FileInfo update = new FileInfo();
            update.setId(fileId);
            update.setAiChecked(1);
            update.setAiNote(truncate(note, 250));
            if (hitGroupId != null) {
                update.setAiGroupId(hitGroupId);
            }
            fileInfoMapper.updateById(update);
        } catch (Exception e) {
            log.debug("写入识别结论失败 id={}：{}", fileId, e.getMessage());
        }
    }

    // ==================== 分组参考图 ====================

    /** 参考图存放目录：{root}/.group-ref */
    private File refDir() {
        File dir = new File(storageRoot, ".group-ref");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** 参考图的绝对路径；未设置返回 null */
    public File resolveRefImage(FileGroup group) {
        if (group == null || isBlank(group.getRefImagePath())) {
            return null;
        }
        String rel = group.getRefImagePath().replace('\\', '/');
        if (rel.contains("..") || rel.startsWith("/")) {
            return null;
        }
        return new File(storageRoot, rel);
    }

    /**
     * 保存/替换分组参考图。统一转成 jpg 并压到长边 1024，控制后续请求体积。
     */
    public File saveRefImage(Long groupId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("参考图不能为空");
        }
        String name = file.getOriginalFilename();
        String lower = name == null ? "" : name.toLowerCase();
        boolean looksImage = file.getContentType() != null && file.getContentType().startsWith("image/");
        if (!looksImage && !(lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp")
                || lower.endsWith(".bmp") || lower.endsWith(".heic"))) {
            throw new IllegalArgumentException("只支持图片格式（jpg/png/webp/bmp/heic）");
        }

        File dir = refDir();
        // 每次换新文件名，避免浏览器/CDN 缓存住旧图
        String storedName = "g" + groupId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".jpg";
        File target = new File(dir, storedName);

        File tmp = File.createTempFile("ref-", ".img");
        try {
            file.transferTo(tmp);
            Thumbnails.of(tmp)
                    .size(MAX_EDGE, MAX_EDGE)
                    .keepAspectRatio(true)
                    .outputFormat("jpg")
                    .outputQuality(0.88)
                    .toFile(target);
        } catch (Exception e) {
            // 有些格式（如 heic）Thumbnailator 读不了，直接原始字节落盘，交给模型自己解
            log.warn("参考图压缩失败，改为原样保存：{}", e.getMessage());
            Files.copy(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }

        String relative = ".group-ref/" + storedName;
        FileGroup update = new FileGroup();
        update.setId(groupId);
        update.setRefImagePath(relative);
        groupMapper.updateById(update);

        return target;
    }

    /** 清空参考图（只清数据库引用，文件在下次换图时自然被替换） */
    public void clearRefImage(Long groupId) {
        FileGroup group = groupMapper.selectById(groupId);
        if (group != null && !isBlank(group.getRefImagePath())) {
            File old = resolveRefImage(group);
            try {
                if (old != null) {
                    old.delete();
                }
            } catch (Exception ignored) {
                // 删不掉就留着，不影响功能
            }
        }
        groupMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FileGroup>()
                        .eq(FileGroup::getId, groupId)
                        .set(FileGroup::getRefImagePath, null));
    }

    /**
     * 删除分组时顺手清掉它的参考图文件。
     * 只删磁盘文件、不动数据库（分组本身马上就被逻辑删除了），
     * 避免 .group-ref 目录越积越多无主图片。
     */
    public void purgeRefImageQuietly(Long groupId) {
        try {
            FileGroup group = groupMapper.selectById(groupId);
            File old = resolveRefImage(group);
            if (old != null && old.isFile()) {
                old.delete();
            }
        } catch (Exception e) {
            log.debug("清理分组 {} 的参考图失败：{}", groupId, e.getMessage());
        }
    }

    /** 手动触发一次重新识别（供接口调用） */
    public void recognizeAsyncById(final FileInfo info, final File physical) {
        if (!isAvailable() || info == null || physical == null) {
            return;
        }
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    recognize(info.getId(), physical, info.getGroupId());
                } catch (Exception e) {
                    log.warn("手动重新识别失败 id={}：{}", info.getId(), e.getMessage());
                    markChecked(info.getId(), null, "识别失败：" + brief(e));
                }
            }
        });
    }

    // ==================== 小工具 ====================

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "null";
        }
        String t = s.replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        if (m == null) {
            return e.getClass().getSimpleName();
        }
        return m.length() > 100 ? m.substring(0, 100) : m;
    }

    /** 模型结论 */
    private static class Verdict {
        FileGroup group;
        String confidence = "low";
        String reason = "";
    }
}
