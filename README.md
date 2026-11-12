# 类阿里云云盘 · 文件上传与管理平台

参考阿里云云盘体验，基于 **Spring Boot 3 + MySQL** 实现的文件管理系统：支持**批量上传**、**图片在线管理与浏览**、**视频在线播放**（含苹果手机 MOV/HEVC 等多格式兼容）。

---

## 一、功能特性

| 能力 | 说明 |
|------|------|
| 批量上传 | 拖拽 / 多选，单次请求可传多个文件，带上传进度 |
| 大文件分块上传 | 超过 **8 MB** 自动切片（5 MB/片）并发上传，**中断可续传**：重新选择同一文件会跳过已传分片继续 |
| 图片管理 | 列表、预览（灯箱）、重命名、删除、搜索；**按拍摄日期分组**（顶部日期条 + 网格日期分组头），一眼看出哪天传了多少 |
| 图片浏览 | 自动生成缩略图，原图在线预览 |
| 视频播放 | 浏览器内在线播放，支持**拖动进度**（HTTP Range 分段流式） |
| 多格式视频 | MP4/WebM 原生播放；**苹果手机 MOV/HEVC、AVI、MKV 等自动用 ffmpeg 转码为 H.264 MP4** |
| 文件存储 | 按 `年/月/日` 落盘，MySQL 记录元数据，逻辑删除 |
| 回收站 | 删除先进回收站，**7 天内可恢复**；到期由定时任务彻底删除（含磁盘文件）；支持批量恢复 / 彻底删除 / 清空 |
| 去重支持 | 记录 MD5 指纹；上传前按「MD5 + 文件大小」判重（视频按「文件名 + 大小」，因为 iOS 每次导出的字节都不同），同一份内容只允许存在一条，命中直接拒绝（不影响其他文件），并把刚落盘的那份连同缩略图删掉；**上传前还有秒传预检**，库里已有的文件一个字节都不用再传 |
| 账号与权限 | 登录 / 退出（HttpSession + BCrypt）；管理员由配置文件初始化；**不开放注册**，普通账号只能在后台「用户管理」页添加；ADMIN / USER 两级角色 |
| 批量操作 | 多选后批量移动分组、批量删除、批量打包下载（流式 zip） |
| 在线编辑（表格） | xlsx / xls **点「编辑」在新标签页整页打开**（像 Excel 一样全屏铺满，无弹窗遮挡）：保留原有格式/合并单元格/列宽/冻结，支持公式（输入 `=` 开头），保存只提交改动过的单元格，保存前自动备份 |
| 在线编辑（文本） | txt / md / csv / json / log / 代码等纯文本文件，同样在新标签页全屏编辑，Tab 缩进、Ctrl+S 保存，2 MB 上限 |

---

## 二、技术栈

- **后端**：Spring Boot 2.7.18、Spring MVC、MyBatis-Plus 3.5.7（已适配 **JDK 8**）
- **数据库**：MySQL 8.0（utf8mb4）
- **会话**：Spring Session JDBC（登录态落 MySQL，重启不掉线）+ BCrypt 密码
- **图片处理**：Thumbnailator（缩略图 / 视频封面）
- **视频转码**：FFmpeg（可选，用于 Apple 等不兼容格式）
- **表格读写**：Apache POI 5.2.5（xlsx / xls 在线编辑）
- **前端**：原生 HTML + CSS + JS（零构建，开箱即用）
- **JDK**：8+（本项目已适配 JDK 8，可直接运行）

---

## 三、环境要求

| 依赖 | 版本 | 必须 |
|------|------|------|
| JDK | 8+ | ✅ |
| Maven | 3.5+ | ✅（或用 IDE 构建） |
| MySQL | 8.0+ | ✅ |
| FFmpeg | 任意最新版 | ⚠️ 仅视频转码需要（苹果视频兼容） |

> 本项目已降级适配 JDK 8（Spring Boot 2.7 + javax 命名空间），可直接用本机 JDK 8 运行。

---

## 四、快速开始

### 1. 准备数据库

方式 A（推荐，Docker）：
```bash
cd cloud-disk
docker compose up -d        # 启动 MySQL（root/root，库 cloud_disk 自动创建）
```

方式 B（已有 MySQL）：手动执行建库建表脚本
```bash
mysql -uroot -p < sql/init.sql
```

### 2. 调整配置 `src/main/resources/application.yml`

```yaml
spring.datasource:
  url: jdbc:mysql://localhost:3306/cloud_disk?...   # 改地址/账号/密码
  username: root
  password: root

file:
  storage:
    root: D:/cloud-disk-data/files      # 文件物理存放根目录
  ffmpeg:
    path: ffmpeg                        # ffmpeg 可执行文件（在 PATH 里直接写 ffmpeg）
    force-transcode: false              # true=所有视频都转码；false=仅转码非 mp4 封装

app:
  admin:                                # 管理员账号：启动时自动写入 sys_user 表
    username: admin
    password: Admin@123456              # BCrypt 加密后落库，明文不入库
    display-name: 系统管理员
    role: ADMIN
    sync-password: true                 # true=每次启动以本文件为准覆盖密码；false=仅首次初始化
```

> 上传大文件请调大 `spring.servlet.multipart.max-file-size` / `max-request-size`。

### 3. 运行

```bash
mvn clean package
java -jar target/cloud-disk.jar
# 或开发模式
mvn spring-boot:run
```

浏览器打开：`http://localhost:8080` → 自动跳转登录页，用上面配置的管理员账号登录。

### 4. 登录与账号

系统**不提供注册入口**，账号只能由管理员创建：

| 角色 | 能力 |
|------|------|
| `ADMIN` | 云盘全部功能 + `admin.html` 用户管理（新增 / 编辑 / 重置密码 / 启用禁用 / 删除用户） |
| `USER` | 云盘全部文件功能，无法进入用户管理 |

- 首次启动时，`app.admin.*` 会被写入 `sys_user` 表（表由 `SchemaInitializer` 幂等创建，无需手工建库）。
- 配置文件里的管理员是**托管账号**：后台不可改它的密码 / 角色 / 用户名，也不能删除它；改密码请改 `app.admin.password` 后重启。
- 密码用 **BCrypt** 存储；登录态用 **HttpSession**，未登录访问接口返回 `401`、访问页面重定向到 `login.html`。
- **登录态持久化**：会话由 Spring Session 存进 MySQL 的 `SPRING_SESSION` 表，所以**重启应用不会掉登录**，
  只要还在有效期内就是同一套凭证。有效期在 `application.yml` 里由 `spring.session.timeout` 控制（默认 `30d`），
  属于**滑动过期**：期间任意请求都会自动续期，连续 30 天不访问才失效。
- **浏览器 / 手机重启也不掉登录**：`server.servlet.session.cookie.max-age` 也设成了 `30d`。
  默认值 `-1` 是「会话 cookie」，只活在浏览器进程内存里，关浏览器就没了——服务端 session 还在，但浏览器
  不再带上那张凭证，表现就是「明明登录过，重启后又要登录」。
- 账号被禁用或删除后，**已登录的会话在下一次请求时立即失效**；点「退出登录」会立即销毁服务端会话。
- 系统始终保证至少有一个启用状态的管理员，且不能删除 / 禁用自己。

> 有效期由两处共同决定，**必须一起改**：`spring.session.timeout`（服务端）和
> `server.servlet.session.cookie.max-age`（浏览器）。只改前者会出现「cookie 已删、服务端还有」的白等，
> 只改后者会出现「cookie 还在、服务端已过期」的假登录。
>
> ⚠️ cookie 是**按访问地址**存的：用 `http://192.168.3.31:8080` 登录后，换成 `http://localhost:8080`
> 或 IP 变了，浏览器不会带上原来的 cookie，看起来就像掉登录。固定 IP（或在路由器上做 DHCP 静态绑定）
> 可以避免这种情况。


---

## 五、接口一览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录（`{username, password}`），成功写入 HttpSession |
| POST | `/api/auth/logout` | 退出登录 |
| GET  | `/api/auth/current` | 当前登录用户（未登录返回 `data: null`，不抛 401） |
| POST | `/api/auth/password` | 修改自己的密码（`{oldPassword, newPassword}`） |
| GET  | `/api/admin/users?keyword=` | 用户列表（仅 ADMIN） |
| POST | `/api/admin/users` | 新增用户（仅 ADMIN，系统内唯一的建号入口） |
| PUT  | `/api/admin/users/{id}` | 编辑显示名 / 角色 / 启用状态（仅 ADMIN） |
| PUT  | `/api/admin/users/{id}/password` | 重置某用户密码（仅 ADMIN） |
| DELETE | `/api/admin/users/{id}` | 删除用户（仅 ADMIN） |
| POST | `/api/files/upload` | 单文件上传（`file`）；内容重复返回 **HTTP 409 + `code=2`** |
| POST | `/api/files/precheck` | 秒传预检：`{fileName,fileSize,md5?}` → `{exists,id?,name?}`，命中就别传字节了 |
| POST | `/api/files/batch-upload` | 批量上传（`files` 数组） |
| POST | `/api/files/chunk/init` | 分块上传初始化：`{fileName,fileSize,chunkSize,totalChunks,fileKey,contentType,groupId}`，返回 `uploadId` 与**已收分片**（断点续传靠它） |
| POST | `/api/files/chunk/{uploadId}?index=n` | 上传第 n 片（`chunk`，除末片外大小须等于 `chunkSize`，可并发/乱序） |
| GET  | `/api/files/chunk/{uploadId}` | 查询进度：`{received:[...], completed}` |
| POST | `/api/files/chunk/{uploadId}/complete` | 合并分片并落库，返回 `FileInfo` |
| DELETE | `/api/files/chunk/{uploadId}` | 取消上传，清掉已传分片 |
| POST | `/api/files/batch/group?groupId=` | 批量移动到分组（body 为 id 数组） |
| POST | `/api/files/batch/delete` | 批量删除（body 为 id 数组） |
| GET  | `/api/files/batch/download?ids=1,2,3` | 批量打包下载（流式 zip，UTF-8 文件名） |
| GET  | `/api/files?keyword=&type=&groupId=&date=` | 列表（type: IMAGE/VIDEO/OTHER；date 形如 `2026-11-09`，按拍摄日期过滤） |
| GET  | `/api/files/dates?keyword=&type=&groupId=` | 按日期聚合 `[{day,count,size}]`，相册日期条用 |
| GET  | `/api/files/ids?keyword=&type=&groupId=&date=&limit=` | 只取 id 列表（过滤条件同列表），「全选这天」用 |
| GET  | `/api/files/{id}` | 文件详情 |
| DELETE | `/api/files/{id}` | 逻辑删除 |
| PUT  | `/api/files/{id}/rename?name=` | 重命名 |
| GET  | `/api/files/{id}/raw` | 原文件预览（支持 Range） |
| GET  | `/api/files/{id}/thumb` | 缩略图 / 视频封面 |
| GET  | `/api/files/{id}/preview` | 相册预览图（长边 1920，按需生成并缓存） |
| GET  | `/api/files/{id}/play` | 视频播放（自动转码） |
| GET  | `/api/files/{id}/download` | 下载（附件） |
| GET  | `/api/files/{id}/sheet` | 读取表格为可编辑视图（xlsx/xls，含合并/列宽/冻结/公式） |
| PUT  | `/api/files/{id}/sheet` | 增量保存表格（`{sheetName, edits:[{r,c,v,t}]}`），返回重算后的视图 |
| GET  | `/api/files/{id}/text` | 读取纯文本内容（UTF-8，2 MB 上限） |
| PUT  | `/api/files/{id}/text` | 保存纯文本（`{content}`） |
| GET  | `/api/recycle?keyword=&page=&size=` | 回收站列表（按删除时间倒序，含剩余天数 `remainDays`） |
| GET  | `/api/recycle/stats` | 回收站概况：`{count, totalSize, keepDays}` |
| POST | `/api/recycle/{id}/restore` | 恢复单个文件 |
| POST | `/api/recycle/restore` | 批量恢复（body 为 id 数组） |
| DELETE | `/api/recycle/{id}` | 彻底删除单个（含磁盘文件，不可恢复） |
| POST | `/api/recycle/purge` | 批量彻底删除（body 为 id 数组） |
| DELETE | `/api/recycle` | 清空回收站 |
| GET  | `/api/recycle/{id}/thumb` | 回收站文件的缩略图 / 封面 |
| GET  | `/api/recycle/{id}/preview` | 回收站文件预览（支持 Range） |

---

## 六、在线编辑的打开方式

- 工作台卡片操作栏有「编辑」按钮（图片/视频没有），缩略图也可直接点开。
- 点开是**新标签页 `editor.html?id=xxx`**，整页铺满浏览器窗口，不再是弹窗遮罩：
  顶部一条工具栏（返回 / 文件名 / 重新载入 / 保存 / 关闭），中间是工作表或文本框，底部状态条。
- 页面级鉴权：未登录直接访问 `editor.html` 会被跳回登录页。
- 未保存时标签页标题带 `•` 前缀，关闭/刷新浏览器会弹出原生确认；单元格内 `Ctrl+S` 也能保存。
- 表格会按窗口大小补足空白行列（右侧/下方不留白），窗口缩放时自动重算。

---

## 七、关于「苹果手机视频」兼容（重点）

苹果手机录制的视频常见为 **`.MOV` 封装 + HEVC(H.265) 编码**，大多数浏览器（Chrome / Firefox / Edge）**无法直接播放** HEVC。本项目的处理策略：

1. **MP4(H.264) / WebM** → 浏览器原生播放，无需转码。
2. **MOV / M4V / AVI / WMV / FLV / MKV / 3GP / `video/quicktime`** → 访问 `/play` 时后台调用 **FFmpeg 转码为 H.264 MP4**（带 `+faststart`，边下边播），前端 `<video>` 直接播放，且支持拖动进度。
3. 转码结果按 `id` 缓存在 `storage.root/.transcode/{id}.mp4`，重复播放不重复转码。

**使用前提**：服务器需安装 FFmpeg 并在 `file.ffmpeg.path` 配置（加入 PATH 后直接写 `ffmpeg` 即可）。

如希望所有视频统一转码（例如确保一致体验），将 `file.ffmpeg.force-transcode` 设为 `true`。

---

## 八、大文件分块上传

工作台的上传**不需要手动选择模式**：单个文件小于 8 MB 走普通上传，大于等于 8 MB 自动切成 5 MB 的分片上传。

- **并发**：最多 3 个分片同时上传，失败自动重试 3 次；进度条按字节汇总，不会因为分片完成顺序乱跳。
- **断点续传**：`uploadId` 由前端算出的 `fileKey`（文件名 + 大小 + 最后修改时间）派生，服务端据此生成固定目录。
  网络断了、刷新页面、甚至换了一台机器重传同一个文件，`init` 都会返回**已收到的分片下标**，只补缺失的那几片。
- **落盘方式**：每片单独存成 `{uploadId}/{index}.part`，所以并发不用加锁；写入时先落 `.tmp` 再原子改名，
  不会出现「半片被当成完整分片」导致合并错位的文件。
- **合并校验**：按序边读边写（内存占用与文件大小无关），合并完先比对总字节数，一致才落库；
  之后复用普通上传的缩略图 / 视频封面 / MD5 计算流程。
- **安全**：`uploadId` 只接受 32 位十六进制，挡住路径穿越；单片 ≤ 64 MB、分片数 ≤ 20000、单文件 ≤ 20 GB。
- **清理**：未完成的分块目录保留 48 小时（`file.chunk.expire-hours`），应用启动时清掉过期的；
  合并成功或主动取消会立即删除。

单元测试覆盖了乱序合并、重复分片幂等、续传命中、文件变更后重来、各类非法参数与路径穿越：

```bash
mvn -Dtest=ChunkUploadServiceImplTest test
```

---

## 九、重复文件校验与上传速度

### 9.1 同一份内容只留一条

上传落库前会先比对「内容指纹」，规则**按类型分两套**：

| 类型 | 判定依据 | 为什么 |
|------|----------|--------|
| 图片 / 其它 | **MD5 + 文件大小** | 客户端压缩后字节是稳定的，同一张照片两次压缩结果一模一样 |
| 视频 | **文件名 + 文件大小** | iOS 每次把相册视频导出给网页都会重写容器元数据（创建时间、UUID 原子），内容相同、字节不同，实测同一段 62 MB 的 `IMG_7450.mov` 连传 6 次得到 **6 个不同的 md5** |

| 场景 | 结果 |
|------|------|
| 库里没有相同内容 | 正常落库 |
| 库里已有相同内容（含换个文件名再传） | 拒绝：`HTTP 409`，`{"code":2,"message":"文件已存在：xxx.jpg"}` |
| 同名同大小的视频、内容字节其实不同 | 同样判重（这正是 iOS 的常态） |
| 相同内容在**回收站**里 | 允许上传（用户删了再传一遍是合理诉求） |
| 一次多选里混了重复项 | 其他文件照常上传，重复的那个单独跳过 |

命中重复时，刚落盘的那份文件和缩略图会被立刻删掉，不留垃圾；分块上传则连分片目录一起清掉，
不用等 48 小时过期。

查重与落库在同一把**分段锁**里完成（按内容指纹散列到 64 把锁），避免同一张图并发上传时
两个请求同时通过「库里没有」的判断。

前端据此把重复算作「跳过」而不是「失败」：上传结束后提示形如
`已上传 12 个文件，跳过重复 3 个`，不会刷一屏红色错误。

> 副作用：同一张照片想放进两个分组是做不到的——先传的那条就是唯一一条，改分组请用「移动」。

### 9.1.1 秒传：传之前先问一句

上面那套判重是「收完字节再拒绝」，重复上传的流量还是白花了。所以多了一步预检：

```
选择文件 → 压缩（图片）→ POST /api/files/precheck ─┬─ 命中 → 跳过，0 字节
                                                  └─ 未命中 → 正常上传 → 服务端再判一次
```

- **图片**：客户端在本地算出压缩后字节的 MD5（`crypto.subtle` 不提供 MD5，所以内嵌了一份 RFC 1321 实现，
  1 MB 约 8 ms），随预检一起发过去。
- **视频**：只发「文件名 + 大小」，不读内容——iOS 的 md5 每次都变，算它纯属浪费 CPU。
- **PDF / zip 之类**：不预检（没有秒传，别白跑一次请求）。

预检只是**省流量**，真正拦重复的还是服务端的 `register()`：两边规则必须一致，
因为预检和上传之间总有一个时间窗（别的设备可能刚好传了同一份）。

实测（真实后端 + Chrome，3 张同内容的图连传两轮）：

```
第 1 轮: change→首包 185ms · 首包→完成 1832ms · 3 个 · 成功 1 · 跳过 2 · 发出 1.3 MB
第 2 轮:                               · 3 个 · 成功 0 · 跳过 3 · 发出 0 B
```

第二轮三个文件总共只发了 3 次预检请求，图片字节一个都没发。

### 9.2 手机端批量选 50 张为什么快了

| 位置 | 原来 | 现在 |
|------|------|------|
| 前端读文件元信息 | 等 50 张的 `size/type` 全读完才开始建进度条，中间界面像卡死 | 先亮进度面板，**读一张丢一张进队列**，上传线程立刻开跑，每 2 张让一次主线程 |
| 直传并发 | 3 条 | 4 条（大文件仍串行，避免分片把手机上行打满） |
| 图片解码缓存 | ImageIO 默认把解码后的原始像素写临时文件（一张 12MP 照片 ≈ 36 MB 磁盘写） | `ImageIO.setUseCache(false)`，解码全在内存里 |
| 单张服务端耗时（1.6 MB / 12MP） | ≈ 1300 ms | ≈ 550 ms（其中缩略图 ≈ 410 ms、md5 ≈ 15 ms） |
| 服务端缩略图解码 | 整张 12MP 解出来再缩（解码占九成耗时） | **抽样解码**：解码器每 4 个像素取一个，只解 1/16 的像素；12MP 照片 **225 ms → 152 ms** |
| 上传的字节数 | 手机原图，20 张 12MP ≈ 50~80 MB | **大图先缩到长边 2560**，同样 20 张 ≈ 8~15 MB（见 9.4） |
| `change` 回调 | `slice(FileList)` + 固定 350 ms 空窗才开始 | **只取一个 FileList 引用就返回**，下一个宏任务立刻开传（见 9.3） |

日志里可以用 `[upload]` / `[register]` / `[thumb]` 关键字查每张图的分段耗时（DEBUG 级别）：

```
[register] total=538ms md5+thumb=431ms check+insert=107ms file=tb.jpg
[upload]   total=545ms write=7ms file=tb.jpg size=1607849
[thumb]    96ms 9f2c...jpg (4032x3024)
```

### 9.3 选完照片后「相册关不掉」——先把三段耗时分开量

「选 20 张要等 10 秒」这句话里其实混着三段完全不同的时间，不分开量就只能靠猜：

| 阶段 | 谁在干活 | 能不能改 |
|------|----------|----------|
| ① 点「照片/视频」→ `change` 触发 | iOS 相册把选中的照片导出成文件交给网页（HEIC 可能还要转 JPEG，开了「优化储存空间」还要先从 iCloud 拉原图） | **改不了**：网页一行 JS 都还没跑 |
| ② `change` → 第一条上传请求发出 | 本项目的 JS | 上一轮还剩 350 ms 空窗 + 一次 `slice`，这一轮清掉了 |
| ③ 上传本身 | 字节数 ÷ 上行带宽 + 服务端处理 | 这一轮主要改这里（9.4） |

**① 的实测依据**（同一台 iPhone、同一网络，零依赖测试页 `/picker-test.html`）：

| 测试 | 文件数 | 体积 | 点击 → 事件 | 主线程最长停顿 |
|------|--------|------|-------------|----------------|
| 选 1 张 | 1 | 2.3 MB | 4089 ms | 105 ms |
| 选 5 张 | 5 | 11.8 MB | 5008 ms | 106 ms |
| 打开后直接取消 | 0 | — | 3510 ms | 105 ms |

- 主线程最长停顿始终是 105 ms（100 ms 心跳的固有抖动），**页面全程没卡过**——渲染和 JS 不是瓶颈。
- **取消（0 个文件）也要 3.5 秒**：大头是相册自身的呈现/关闭；5 张比 1 张多 919 ms ≈ **每张 230 ms**。
- 这 230 ms 发生在 `change` 之前；而且 iOS 弹相册时**不触发 `visibilitychange`**，
  页面「以为」自己在前台，连个 loading 都显示不出来。

**这一轮把 ② 压到了接近 0**

| 做法 | 原因 |
|------|------|
| `change` 回调只做三件事：复位标志、取 `input.files` 引用、丢给 `setTimeout(0)` | 回调里不遍历、不读 `size/type`、不碰 DOM、不发请求。`Array.prototype.slice` 也要逐个取元素，一并去掉 |
| 删掉固定 350 ms 空窗 | 那是拿用户的时间去猜宿主什么时候收完；真正要让出的只有当前这一帧 |
| 仍不在回调里清 `input.value` | 部分 Android WebView 会同步释放每个 File 的底层句柄，50 个文件就是一串同步 IPC |

**关于 HEIC 转码**（这段决定「还能不能再快」）

WebKit 到 2024 年 3 月才改掉「iOS 上 HEIC 无条件转 JPEG」的行为
（[Bug 267277](https://www2.webkit.org/show_bug.cgi?format=multiple&id=267277)：
*only happens if the set of image types is restricted, and does not include HEIC*）。
也就是说：

- **没跟上这个修复的老系统**：选什么都要转 JPEG，那 230 ms/张 里有很大一块是转码，网页侧无解。
- **跟上了的新系统**：`accept="image/*"` 属于「未受限」，相册**直接给 HEIC 原图**，省掉转码；
  但服务端 ImageIO 解不了 HEIC，缩略图会缺席。所以 9.4 的客户端压缩顺带把 HEIC 转成了 JPEG，
  列表在 Chrome 里也不会裂。

`/picker-test.html` 现在是个 A/B 页，三个变体只差 `accept`：`image/*,video/*`（当前写法）、
不写 `accept`、显式列出（含 HEIC）。每个按钮点两次（先 1 张、再 20 张），页面上会直接显示
「点击→change / 每张约多少 ms / **交付类型**」：

> 交付类型比时间更有说服力。相册里明明是 HEIC，交付的却是 `image/jpeg` + `.JPG`，
> 说明「完成」那一刻 iOS 真的在转码；反过来，交付 `image/heic` 就说明转码没发生，慢的只是拷贝。

结果会自动 POST 到 `/api/debug/picker`（只写日志，不需要登录），在服务器日志里 `grep "[picker]"` 即可。

**上传页自带的埋点**：地址栏加 `?perf=1`（或 `localStorage.cd_perf=1`），传完会弹一条
`耗时：相册→change … · change→首包 … · 首包→完成 …`，并写进同一份日志。
不开启时这些调用全是空操作。手机上排查就靠它，不用手抄数字。

> 结论没变：**相册导出那一段由 iOS 决定，页面改不动**（想更快就分批选，让排队上传吸收掉准备时间）。
> 但 ② 现在基本归零，③ 靠下面的压缩砍掉大半。

### 9.4 大图压缩：手机端上传慢的最大一笔

一张 iPhone 12MP 照片导出后 2.5~4 MB，20 张就是 50~80 MB。上行按家用 Wi-Fi 常见的
2.5 MB/s 算，**光发出去就要 20 秒以上**——这才是「上传慢」的主体。

现在图片在发出去之前先在浏览器里缩一遍：长边 2560、JPEG q0.86。
列表缩略图 320、相册预览 1920，2560 已经覆盖所有展示场景。

| | 原图 | 压缩后 |
|---|---|---|
| 单张体积（12MP 照片） | 2.5~4 MB | 0.5~0.9 MB |
| 20 张合计 | 50~80 MB | 8~15 MB |
| 服务端缩略图解码 | 12MP | 4MP，快 3 倍 |
| 服务端存 HEIC 的风险 | ImageIO 解不了，Chrome 里图裂 | 统一 JPEG，所有浏览器都能显示 |

**实测（桌面 Chrome，上行节流到 20 Mbps，20 张合成图）**：

```
自动压缩 : 发出 41.0 MB   上传 18.7 s
原图直传 : 发出 85.3 MB   上传 36.9 s      → 2.0x
```

这组测试图是人工合成的高频噪声，**几乎压不动**（体积只降到 48%）。真实照片的压缩率高得多，
翻 3~5 倍很常见；再加上 HEIC 原图本来就不含 JPEG 那种冗余，所以真机上省下的比例通常比这张表更大。

**四条保险**（都做了，缺一条都可能把用户的原图弄坏或弄丢）：

| 情况 | 行为 |
|------|------|
| 解不出来（安卓上的 HEIC、损坏文件） | 原样上传，不报错、不丢文件 |
| 压完没省到 30% | 放弃压缩，用原图 |
| 动图 / SVG | 不碰（压了就成静帧） |
| 想要原图 | 拖拽区里的「原图 / 压缩」开关，选择记在 `localStorage`，一次点击随时还原 |

实现上没有用 `createImageBitmap` 整图解码那条路：同一张 4032×3024 的图缩到 2560 再编码，
`<img>` + canvas 约 336 ms，`createImageBitmap` 约 490 ms（多出来的是 48 MB 位图的分配）。
现在优先 `<img>`，`createImageBitmap` 只做兜底。

> 代价：云端存的是长边 2560 的 JPEG，不是原始分辨率。打印 / 二次裁剪请先开「原图」开关。
> 这也是为什么开关必须显式可见、而不是藏在设置里。

### 9.5 预览弹窗：图片与视频

两个线上问题，原因都不在"加载慢"，而在尺寸和内存。

**视频窗口很小**：`<video>` 是替换元素，没有显式宽高时默认按 **300×150** 渲染。
之前只写了 `max-width / max-height`，等于给了上限没给下限——元数据一解析失败
（MOV/HEVC 在部分浏览器就是这种情况），元素就永远停在 300×150，怎么点都不会变大。

现在 `.modal-video` 有一个由视口决定的"舞台"尺寸（`min(92vw, 1180px) × min(88vh, 720px)`），
画面用 `object-fit: contain` 等比适配，黑底留边看不出来；拿到真实分辨率后
`fitVideoBox()` 再把弹窗收到贴合比例，避免竖屏视频留两条大黑边。解码失败时
覆盖一层"浏览器播不了，点这里下载"。

**图片有时候打不开**：预览直接用 `/raw` 原图。一张 1200 万像素照片解码后约 36 MB，
手机连翻几张就能让 Safari 悄悄放弃渲染——**黑屏，没有报错，也没有提示**。

现在多了 `/api/files/{id}/preview`：长边 1920 的 JPEG，首次访问生成后缓存到磁盘
（`.preview/{id}.jpg`），之后直接命中。实测：

| | 原图 | 预览图 |
|---|---|---|
| 体积 | 3.3 MB | 594 KB |
| 解码内存 | ≈ 48 MB | ≈ 11 MB |
| 首次 | — | 1.37 s（一次性） |
| 再次 | 0.13 s | **0.06 s** |

前端三级回退：`/preview` → `/raw` 原图 → "这张图加载失败，点一下重试"。
载入中有转圈（`pointer-events: none`，不挡关闭和左右滑动），成功后预取相邻两张。

> `.preview/` 目录可以随时删，删了下次访问会自动重建。

### 9.5.1 PC 上预览的两个"灵异"问题

**① 快速点「下一张」，显示的图和背景里的图不是一张**

两层交叉切换本身没问题，坏在 CSS：`.gallery-stage .gal-layer` 写了 `display: block`，
作者样式压过浏览器默认的 `[hidden] { display: none }`——**所谓"隐藏层"一直在绘制**。
于是换到比例不同的图时，旧图会从新图左右两边露出来；快速连点时更乱：
下一次换层后，旧的那层在 DOM 里更靠后（画在上面），看起来就是"点了下一张，画面却没换"。

修法是补一条 `.gallery-stage .gal-layer[hidden] { display: none; }`；关闭弹窗时把两层的
`src`、`hidden` 都清掉，并让 `imgToken` 作废（否则还在解码的那次回合并发会在关闭后把图层又显示出来，
下次打开先闪一下上一张）。验证方式不再是"看着没问题"，而是用 CDP 读 `getComputedStyle().display`：
任何时刻只允许一层不是 `none`，且它的 `src` 必须等于当前 `galleryIndex` 指向的那张。

**② 右上角 X 点了没反应**

舞台上的滑动手势在 `pointerdown` 就调了 `setPointerCapture`。指针被舞台捕获后，
`pointerup` 和随后的 `click` 都会改派给舞台，舞台里的关闭键永远收不到 `click`。
实测事件序列：`pointerdown=svg` → `pointerup=galleryStage`（`hasPointerCapture=true`）→ `click=galleryStage`。
遮罩能关、X 不能关，差别就在这里（遮罩不在舞台里，没被捕获）。

现在改为「确认是横向拖动（>10px）之后再捕获」：点按不捕获、按钮上的按下也不启动手势。
滑动、关闭、Esc 三条路径都保留，CDP 里用真实鼠标事件逐条回归。

### 9.6 相册按日期看：哪天传了哪些

**先划清边界**：手机点「照片/视频」弹出来的是 **iOS / Android 自己的系统相册**
（iOS 上是 PHPicker），它属于宿主进程，网页没有任何 API 能改它的分组、样式或排序。
所以「按日期分组」做在了云盘这一侧——那里才能回答「哪天传了多少」。

界面上两处：

| 位置 | 内容 | 点一下 |
|------|------|--------|
| 网格上方的**日期条**（手机上横向滚动） | `全部 97 · 今天 14 · 昨天 76 · 11-04 7` | 只看那一天（再点一次取消） |
| 网格里的**日期分组头** | `2026年11月5日 · 周四` + 右侧 `76 项 · 624.2 MB` | —— |

**某天全选**：选了某一天之后，批量操作栏的「全选本页」会变成「全选这天」，
一次把那天**跨页的全部**（最多 500 条）勾上，再用现成的 移动 / 下载 / 删除。
实现上多了一个 `GET /api/files/ids`（过滤条件与列表完全一致，只返回 id），
服务端和前端共用同一套过滤口径，避免「日期条说 76 项、勾选只勾到 20 项」。

日期口径（关键）：

```
有拍摄时间 → 用拍摄时间分组
没有       → 回退到上传时间（老数据、取不到的文件都不会掉进「无日期」）
```

**拍摄时间从哪来**：不解析 EXIF，直接取浏览器给的 `File.lastModified`。
iOS 相册交给网页的 `lastModified` 就是这张照片/视频本身的时间，压缩后的 File 也会把它带过去。
服务端只做一次粗筛（早于 2000 年、晚于「现在 + 2 天」的一律丢弃，防止设备时钟抽风让相册里冒出 1902 年）。

分两条路上传都要带上，否则视频会全部按上传日归组：

| 路径 | 传递方式 |
|------|----------|
| 直传 `/api/files/upload` | 表单字段 `capturedTime`（毫秒时间戳） |
| 分块 `/api/files/chunk/init` | `ChunkInitForm.capturedTime` → 落 `meta.json` → 合并时写库 |

实测（真实后端 + 浏览器）：

```
今天上传、拍摄于 2026-09-20 的照片  → capturedTime=2026-09-20T18:30:00, createTime=2026-11-09T22:46:51
                                     → 日期条出现 2026-09-20（1 项），date 过滤只返回它
分块上传（3MB，3 片）带 capturedTime → capturedTime=2026-06-03T09:15:00 ✔
```

> 老数据（这次改动之前的 97 个文件）没有 `captured_time`，会按上传日期归组；
> 之后再传的文件按拍摄日期归组。想统一的话只能重传或手工补库。

### 9.7 选完文件先看一眼（App 式确认面板）

系统相册返回后，不再直接开传，而是先摆一个按日期分组的清单：

- 每行：文件名、大小、所属日期组；可逐项取消。
- **已在库里的项当场标灰、划线、取消勾选**，并在标题里提示「其中 N 项已在库里」。
- 底下就一个动作：`开始上传 N 项`。

这里有个刻意的取舍：**不做「全部检查完再传」**。
要判断一张图在不在库里，必须先用**压缩后的字节**算 md5（服务端存的就是那个指纹），
20 张就是 3~5 秒干等——等于把前面省下来的等待又加回去。所以只有零等待的判定放在这一层：

| 类型 | 什么时候判定 | 为什么快 |
|------|--------------|----------|
| 视频（≥ 8 MB） | 面板一打开 | 判定靠「文件名 + 大小」，不用读内容，几十毫秒 |
| 图片 | 上传过程中回填 | 要等压缩完才有 md5；而秒传命中本身只花几十毫秒 |

单个小文件不弹这个面板（直接传，少一次点击）；单个大文件或一次多选才弹。

实测（真实后端 + Chrome）：

```
面板: 2 项 → 分两组「2026年10月3日 · 周六 / 2026年10月2日 · 周五」
      IMG_7450.mov（同名同大小的既有视频）→ 标灰、取消勾选、显示「已在库里」
      底部：开始上传 1 项
上传: 只发出 1 个请求（[upload] +1），落库记录的 capturedTime=2026-10-02T15:00:00 ✔
取消: 面板关掉，库里 0 新增 ✔
全选这天: 按钮文案变成「全选这天」，点击后选中该日全部 76 项 ✔
```

### 9.8 相册翻图为什么会「卡一下」

症状：局域网里翻下一张，会顿一下。拆开量，是三笔完全不同的开销：

| 现象 | 实测 | 原因 |
|------|------|------|
| 每张图第一次看要等半秒到一秒 | `/preview` **500~1100 ms** | 预览图是**按需生成**的，12MP 全图解码 + 缩放 + 编码 |
| 每个 API 请求都比静态资源慢一截 | 静态 96 KB = **9 ms**，`/api/**` = **33~41 ms** | 鉴权每个请求回查一次账号 + MyBatis 把 SQL 同步打到标准输出 |
| 换图时画面顿一下 | 换图本身 **11~18 ms** | 直接改同一个 `<img>` 的 `src`：解码被压进"绘制"那一帧；弹窗尺寸跟着新图比例变，整块重排；全屏 `backdrop-filter` 每换一张重算一次 |

三处都改了：

**① 上传完就把预览图生成好**（后台单线程，LIFO）
`register()` 里登记完成后提交一个后台任务生成 `.preview/{id}.jpg`。
单线程是因为一张 12MP 解码要 40MB 内存，20 张并发能直接把堆顶爆；
LIFO 是因为相册**最新在前**，用户传完立刻点开的就是最后上传的那张——FIFO 的话它排在队尾。
实测：上传返回 998 ms（不受影响），后台 `[preview] 1063ms`，之后再取 71 ms。

**② 换图改成两层交叉 + 预解码**
隐藏层先 `src`，`await img.decode()` 完成后再交换可见性；当前可见层保持不动。
预取左右各一张时也一并 `decode()`（只 `new Image()` 只缓存了网络响应，解码后的位图没留住）。
舞台尺寸固定为 `min(92vw,1400px) × min(88vh,900px)`、图片 `object-fit: contain`，
换图不再触发弹窗重排；全屏毛玻璃在 `#imageModal` 下去掉（黑底看图本来也看不到）。

**③ 去掉每个请求的固定开销**
- MyBatis 的 SQL 日志从 `StdOutImpl`（同步写控制台）改成 SLF4J，默认 `info` 关掉；
  要看 SQL 把 `logging.level.com.example.clouddisk.mapper` 调成 `debug` 即可。
- 鉴权回查账号从"每个请求一次"改成 **30 秒节流**。

> 这个节流第一版把时间戳写进了 session，结果翻相册时并发取图（一次 5 张）几条线程同时
> `setAttribute`，撞上 Spring Session JDBC 的「先删后插」，直接
> `Duplicate entry ... for key 'PRIMARY'` → 那几个请求 500。
> 现在时间戳放在进程内 `ConcurrentHashMap`（按 sessionId），不碰 session，既没有竞态也少一次会话写回。
> 压测：同一会话并发取图 30 次，**全部 200**，日志无主键冲突。

改完的实测（手机宽度、真实后端）：

```
翻图 8 次「点击 → 新图可见」: 12, 13, 12, 16, 16, 18, 17, 2 ms
中位数 16ms   长任务(>50ms): 无   空帧: 0   console 报错: 无
```

### 9.9 分页：每页条数可调

列表底部多了「每页 [10/20/50/100/200]」下拉（手机上点开就是系统滚轮），选完立即生效，
选择记在 `localStorage.cd_page_size`，下次打开还是这个值。

分页栏只保留**两个对齐锚点**：左边状态、右边控件（翻页 + 每页条数作为一个整体靠右）。
「每页条数」是设置项，之前夹在状态文字和翻页按钮中间，用 `space-between` 会飘在正中，
看着像第三个对等的东西——现在它和翻页按钮同属右侧控件组，落在视觉终点上。

几个细节：

- 改条数后**页码回到第 1 页**——原来停在第 5 页、改成每页 100 条时可能根本没有第 5 页。
- 服务端 `/api/files` 本来就把 `size` 夹在 200 以内，所以下拉给到 200 为止；
  文件总数不足时按实际数量一页装完（107 条选 200 → 第 1/1 页）。
- 切换时会顺手清空勾选：跨页选中的含义在条数变化后就变了，留着容易误删。
- 日期条、日期分组头、批量栏的「全选这天」都跟着新页大小工作，不用额外适配。
- 手机上分两行：第一行「状态 + 每页」，第二行翻页居中；按钮高度 44px（触摸目标），
  页码只列 3 个——列 5 个就放不下 44px 的按钮了。桌面端仍是单行 28px。

> 排版规则来自 `web-ui-review` 技能（用户级技能，装在 `~/.dsh/skills/web-ui-review/SKILL.md`）。
> 顺手记一个坑：`@media` 当初写在了 `.page-btn` 基础规则**之前**，同优先级下
> `height: 28px` 把移动端的 44px 覆盖掉了——肉眼看不出来，是量 `getBoundingClientRect()`
> 才发现的。CSS 里覆盖关系靠先后顺序，媒体查询要放在基础规则之后。

实测（CDP 量 DOM，两个视口）：

```
手机 390×844:  无横向溢出；信息与「每页」同一行(中线 716)、翻页独占一行(中线 770)
               按钮 54×44 / 40×44 / 54×44，下拉 52×44
桌面 1440×900: 全部同一行(中线 826)；工具组右边距 = 容器右边距；info 贴左
               focus-visible: outline solid 2px 可见
```

## 十、回收站

删除不再是一去不回：点「删除」的文件先落进回收站，**7 天内随时可以恢复**，超过保留期自动彻底删除。

### 行为约定

| 操作 | 实际行为 | 磁盘文件 |
|------|----------|----------|
| 删除 / 批量删除 | `deleted=1` + 记下 `deleted_time`，一秒完成 | **不动** |
| 回收站 → 恢复 | 清掉两个标记，文件回到原分组 | 不动 |
| 回收站 → 彻底删除 | 删记录 | **删除**原文件 + 缩略图 + 转码缓存 |
| 超过 7 天未处理 | 定时任务 / 启动补跑自动清理 | **删除** |

「删除」之所以不搬文件，是为了让 2 GB 的视频也能瞬间进回收站——真删磁盘的成本留到「彻底删除」那一步。

### 保留期与清理

`application.yml`：

```yaml
file:
  recycle:
    keep-days: 7                  # 保留天数，到期后物理删除，不再可恢复
    clean-cron: "0 10 3 * * ?"    # 每天 03:10 低峰清理
```

清理有**两个触发点**：定时任务（默认每天 03:10）+ 应用启动时补跑一次。如果服务器在凌晨三点是关机的，
启动那次会把攒下的过期文件一并清掉，不会一直堆着。清理失败只记日志，不影响应用启动和正常请求。

### 几个容易踩的坑（都已在实现里处理）

- **MyBatis-Plus 的逻辑删除会自动给 SQL 追加 `AND deleted=0`**，所以回收站的查询、恢复、物理删除全部用注解 SQL 手写，
  绕开这个自动过滤，否则回收站里一条数据都查不出来。
- **物理删除必须「先删磁盘、再删记录」**。反过来的话，记录没了、文件还在，就成了永远没人认领的垃圾。
- **分批清理必须「删完这批再取下批」**，否则同一批记录会被反复取出来，变成死循环。
  另外每批若实际删除 0 条也会主动收手，防止并发清理下的空转。
- **恢复时原分组可能已被删除**，这类文件的 `groupId` 会被清空归入「未分组」，避免前端显示出一个不存在的幽灵分组。
- **剩余天数向上取整**：删了 6 天 5 小时显示「1 天」而不是「0 天」，不会让人误以为已经过期。
- 物理文件删失败（被占用等）只记 `warn`，记录照样删掉——否则用户会一直看到删不掉的僵尸条目。

### 数据库迁移

新库直接跑 `sql/init.sql` 即可。老库启动时会由 `SchemaInitializer` 幂等补列，也可以手动执行：

```bash
mysql -u root -p cloud_disk < sql/migration_recycle_bin.sql
```

迁移做的事：加 `deleted_time` 列、加 `(deleted, deleted_time)` 索引、
并把历史上已删除但没有时间的记录用 `update_time` 回填（这些文件会被当作已满期，尽快清掉）。

### 单元测试

```bash
mvn -Dtest=RecycleServiceImplTest test
```

覆盖：先删磁盘后删库的顺序、分批清理不死循环、删 0 条时主动收手、恢复时清空失效分组、
剩余天数取整、路径穿越防护、批量上限、预览定位的各种异常分支。

---

## 十一、目录结构

```
cloud-disk/
├── pom.xml
├── package.json                # Capacitor 依赖（iOS 壳，见 docs/native-app.md）
├── capacitor.config.json
├── ios/                        # Capacitor 生成的 Xcode 工程（SPM）
├── docker-compose.yml          # 一键起 MySQL
├── sql/init.sql                # 建库建表
└── src/main/
    ├── java/com/example/clouddisk/
    │   ├── CloudDiskApplication.java
    │   ├── common/             # 统一返回、全局异常、文件类型枚举、表格 VO（Cell/Sheet/Workbook）
    │   ├── config/             # MyBatis-Plus 自动填充、鉴权拦截器、建表与管理员初始化
    │   ├── entity/             # FileInfo / FileGroup / SysUser
    │   ├── mapper/             # MyBatis-Plus Mapper
    │   ├── dto/                # 登录、用户、表格增量保存表单
    │   ├── service/            # FileService / FileGroupService / RecycleService / SysUserService / SheetService + 实现
    │   ├── task/               # RecycleCleanTask（回收站过期清理：定时 + 启动补跑）
    │   └── controller/         # FileController（含 Range 流式输出）、RecycleController、EditorController、Auth / UserAdmin
    └── resources/
        ├── application.yml
        └── static/
            ├── index.html      # 工作台（列表 / 预览 / 批量操作）
            ├── recycle.html    # 回收站（恢复 / 彻底删除 / 清空）
            ├── editor.html     # 在线编辑整页（表格 / 文本）
            ├── login.html / admin.html
            ├── css/style.css
            └── js/  app.js · native.js · recycle.js · editor-page.js · edit-types.js · login.js · admin.js
```

---

## 十二、iOS App（Capacitor 套壳）

网页版在 iPhone 上有一个绕不过去的问题：点「照片/视频」选完照片，**相册要等系统导出完才关**。
导出（HEIC→JPEG 转码、拷贝、iCloud 原图下载）发生在 `change` 之前，页面一行 JS 都没跑，
没有 API 能提前关窗。10 张照片大约 2~3 秒。

所以加了一个 Capacitor 壳，把「选图」换成原生 PHPicker，其余（列表 / 预览 / 回收站 / 在线编辑）
仍然是服务器上的那套网页——**前端改完不用发版**。

真机踩坑：`@capacitor/camera` 的 `chooseFromGallery` 底层是 IONCAMR 自己画的相册，**要等导出完才关窗**。
现在照片改走 `Camera.pickImages()`（系统 PHPicker），点「添加」立即关；视频仍走原相册（关窗等待还在）。

| | 网页 `<input type=file>` | App |
|---|---|---|
| 照片关窗 | 等系统导出完（10 张 ≈ 2~3 s） | **立即关**（`pickImages` + PHPicker） |
| 视频关窗 | 等系统导出完 | 仍要等（IONCAMR 相册，暂时接受） |
| 等待期间 | 卡住的系统相册 | App 的「正在从相册导入…」 |
| 照片上传 | Blob → 压缩 → XHR | 原生缩到 2560/q86 → 按需读取 → 上传 |
| 视频上传 | 整段进 WebView 内存 | 原生 `FileTransfer` 从磁盘直传，不进 JS |

原生壳里点「照片/视频」会先弹一个「照片 / 视频」选择面板；照片一次最多 20 张，
首次使用要允许「访问所有照片」（「有限访问」会被插件拒绝）。

接入点都在前端，浏览器里自动禁用（`NativeShell.canPickPhotos() === false`），PC / 手机浏览器行为不变：

| 位置 | 作用 |
|------|------|
| `static/js/native.js` | 桥接层：`pickPhotos`（PHPicker）/ `pickVideos` / `readFile` / `upload`（FileTransfer） |
| `app.js#openPicker` | 相册按钮在原生壳里打开 `#mediaSheet` 选择面板 |
| `app.js#prepare` | `precompressed` 照片跳过 canvas 压缩；视频保留 `nativePath` |
| `app.js#uploadNative` | 原生直传，重试 / 重复 / 401 语义与网页直传一致 |

构建（**编译需要 macOS + Xcode**；日常改前端不用重新打包，App 加载的是服务器网页）：

```bash
pnpm install
pnpm exec cap sync ios
pnpm exec cap open ios
```

没有 Mac 也能出包：`bash scripts/build-ios-ipa.sh` 在云端 Mac / GitHub Actions 上产出
**未签名 IPA**，Windows 用 Sideloadly / AltStore 重签安装（免费账号 7 天，$99 账号走 TestFlight）。
四条路线的成本与步骤见 [docs/native-app.md#9-没有-mac-怎么办](docs/native-app.md#9-没有-mac-怎么办)。
CI workflow 在 `.github/workflows/ios-unsigned.yml`（需要把仓库镜像到 GitHub）。

`capacitor.config.json` 里 `server.url` 指向 `http://192.168.3.31:8080`（局域网自用形态，断网白屏）。
权限、ATS、验证清单、已知限制（后台续传未做、App Store 4.2）见
[docs/native-app.md](docs/native-app.md)。

---

## 十三、可扩展方向

- **分片上传限速 / 动态片大小**：按网络状况自适应调整分片大小与并发数。
- **权限 / 多用户**：增加用户表与文件归属。
- **对象存储**：把 `storage.root` 的实现替换为阿里云 OSS / MinIO。
- **回收站保留期按角色区分**：管理员可永久保留，普通用户 7 天。
- **文件版本历史**：同一次上传的多版本可回溯，避免误覆盖。

