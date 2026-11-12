# iOS App：Capacitor 套壳 + 原生相册 / 原生上传

> 状态：工程、配置与前端接入已完成，浏览器侧回归全过。
> **真机编译必须在 macOS + Xcode 上做**（当前开发机是 Windows，只能生成工程和同步资源）。

## 1. 它解决什么问题

网页版在 iPhone 上点「照片/视频」选完照片后，系统相册要先把选中的照片**导出成文件**
（HEIC→JPEG 转码、拷贝、iCloud 原图下载），全部完成才关窗、才触发 `change`。
10 张照片大约 2~3 秒，这段时间页面一行 JS 都没有执行，网页没有 API 能提前关窗。

原生壳把「选图」换成 PHPicker：

| | 网页版 `<input type=file>` | App（本工程） |
|---|---|---|
| 点「添加」后关窗 | 等系统导出完（10 张 ≈ 2~3 s） | **立即关**，取文件在 App 里异步做 |
| 等待期间用户看到 | 卡住的系统相册 | App 自己的「正在从相册导入…」 |
| 大视频 | 整段读进 WebView 内存 | 原生 `FileTransfer` 从磁盘直接发，不进 JS |

「上传/预览/列表/回收站」仍然用服务器上的接口，但**页面资源已经打包进 App**：
装好后启动不读服务器网页，只有数据和接口走局域网。代价是：前端改完要重新打包、重新安装，
不再「改完刷新就生效」。

## 2. 结构

```
package.json                     Capacitor 依赖与脚本
capacitor.config.json            没有 server.url：加载安装包内的 public/
ios/                             Xcode 工程（Swift Package Manager，无 Pods）
  App/App/Info.plist             相册/相机/本地网络权限 + ATS
  App/CapApp-SPM/Package.swift   由 cap sync 生成，引用 node_modules 里的插件
src/main/resources/static/js/api-bridge.js   跨域 API 桥：绝对地址 + Bearer token
src/main/resources/static/js/native.js       原生相册 / 原生上传
src/main/java/.../config/TokenAuthService.java   token（= Spring Session id）鉴权
```

依赖：

- `@capacitor/core`、`@capacitor/ios`、`@capacitor/cli` 8.6.0
- `@capacitor/camera` 8.2.6 —— 照片用 `pickImages()`（系统 PHPicker），视频用 `chooseFromGallery()`
- `@capacitor/file-transfer` 2.0.6 —— 从原生文件路径直传，带进度事件

## 3. 运行方式（独立 App + token）

- 页面从 `capacitor://localhost` 加载（`webDir` 里的资源被 `cap sync` 拷进安装包）。
- 接口地址是绝对的：`api-bridge.js` 里的 `DEFAULT_BASE = http://192.168.3.31:8080`。
- 从 `capacitor://localhost` 访问 `http://192.168.3.31:8080` 属于跨域，浏览器**不会带会话 cookie**，
  所以登录响应多给一个 `X-Auth-Token`（就是 Spring Session 的 id）：
  - `fetch` / `XHR`：自动改成绝对地址 + `Authorization: Bearer <token>`
  - `<img>` / `<video>`：`mediaUrl()` 拼上 `?token=<token>`
  - 服务端 `WebMvcConfig` 放行 `/api/**` 的 CORS 并暴露 `X-Auth-Token`
  - token 存在 `localStorage.cd_auth_token`，30 天滑动过期；退出登录会删服务端会话
- 换服务器地址：改 `api-bridge.js` 的 `DEFAULT_BASE` → 重新跑 Actions 打包（App 内没有设置页）。
- 断网时界面能打开，但列表/缩略图/上传会报网络异常，不会白屏。

## 4. 构建（在 macOS 或 GitHub Actions 上）

前置：macOS、Xcode 16+、Node 20+、pnpm。

```bash
pnpm install          # ios/App/CapApp-SPM/Package.swift 引用 node_modules 里的插件，必须先装依赖
pnpm exec cap sync ios   # 把 src/main/resources/static 拷进 ios/App/App/public
pnpm exec cap open ios
```

Xcode 里：

1. 选 `App` target → **Signing & Capabilities** → 选自己的 Team（Bundle ID 默认 `com.example.clouddisk`，可改）。
2. 选真机运行。首次启动会请求「本地网络」权限（连接局域网服务器需要）。
3. 打开后登录（走 token，不再依赖 cookie），点「照片/视频」验证：点「添加」应立即回 App。

> 也可以用脚本一键出**未签名** IPA：`bash scripts/build-ios-ipa.sh`，
> 产物在 `build/cloud-disk-unsigned.ipa`，Windows 用 Sideloadly 装（见第 9 节）。
> GitHub Actions 的 workflow 已包含同样的步骤，推送后手动 Run workflow 即可。

> Windows 上 `cap add/sync` 都能跑（生成工程、写 Package.swift、复制资源），只有编译和签名不行。

## 5. 实现了什么

### 5.1 自定义相册（`native/photo-album` + `native.js`）

`@capacitor/camera` 的两条路都不合适：

- `chooseFromGallery` 底层是 IONCAMR 自绘相册，关窗前要把整批资产导出完；
- `pickImages` 是系统 PHPicker，快，但界面不是我们要的（不是「按日期分组的本地相册」）。

所以写了一个本地 Capacitor 插件 `native/photo-album`（Swift + SPM）：

- `PHAsset` 拉取照片/视频，按天分组，`UICollectionView` 三列缩略图；
- 角标：LIVE、视频时长；多选，最多 20 项；
- 点「添加」**先 dismiss 相册**，再由插件在后台逐张 `PHAssetResourceManager.writeData`
  写临时文件；写完才 resolve，期间 JS 显示「正在从相册导入…」；
- 返回 `{ files: [{ path, webPath, name, size, type, creationDate, isVideo }] }`；
- 照片 `webPath` → JS 按需读取后压缩；视频 `file://` → 原生 `FileTransfer` 直传。

原生壳里点「照片/视频」**直接开这个相册**；旧安装包没有插件时退回 `#mediaSheet`（照片/视频面板）。
权限：`PHPhotoLibrary.requestAuthorization(for: .readWrite)`；拒绝/受限时前端提示去设置。

### 5.2 照片按需读取

插件导出的是**原文件**（可能是 HEIC）。上传流水线轮到某一张照片时才
`fetch(webPath)` 读成 Blob（一次一张，内存峰值约等于并发数），再走原来的 canvas 压缩
（长边 2560 / q86）。不再像 `pickImages` 那样在原生侧预压缩，所以「上传原图」开关仍对照片生效。

### 5.3 视频原生直传

视频不读进 JS：`prepare()` 发现 `nativePath` 就原样带到上传阶段，
由 `NativeShell.upload()` 调 `FileTransfer.uploadFile()` 从磁盘直接发。

- 服务端接口不变：multipart 字段 `file`，`groupId` / `capturedTime` 放查询参数（`@RequestParam` 两者都认）。
- 进度用 `FileTransfer` 的 `progress` 事件回填到现有进度面板。
- 重试、`code=2`（重复文件）、401 跳登录等语义与网页直传一致。
- 原生上传走「大文件串行链」，避免两路进度事件混在一起。

## 6. 验证

已在 Windows + Chrome（CDP）里完成：

- **自定义相册 JS 胶水**（注入假的 `PhotoAlbum` 插件）：`canPickAlbum`、`pickAlbum` 的
  `webPath`/`nativePath`/拍摄时间映射、`limit=20`、点相册按钮直接开自定义相册（不再弹旧面板）—— 4/4 通过。
- **旧回退路径**（注入假的 Camera）：`pickPhotos` 调 `pickImages`、`pickVideos`、
  「照片/视频」面板、`precompressed` 上传 —— 9/9 通过（旧安装包没有相册插件时用）。
- **独立 App 模式**：静态资源在 8099（模拟 `capacitor://localhost`），接口在 8080；
  跨域登录拿 token、`/api/auth/current`、列表、缩略图带 `?token=` 能显示、XHR 上传、退出登录清 token —— 6/6 通过。
- **网页模式**：同源 cookie 登录、无 token、缩略图相对地址、真实上传 —— 4/4 通过。

> Swift 相册插件本身只能在 macOS / GitHub Actions 上编译，Windows 无法本地验证；
> 第一次跑 workflow 时重点看 `PhotoAlbumPlugin` 的编译日志。

真机上还需要确认（Windows 无法执行）：

1. 装好后登录（token 模式）能否保持，杀 App 重开是否免登录。
2. 打开相册：日期分组、三列缩略图、LIVE/视频时长角标是否正常。
3. 选 10~20 张 → 点「添加」：相册应**立即关闭**，回到 App 显示「正在从相册导入…」；
   导入完成后确认面板的张数/日期/大小正确。
4. 视频：时长角标、上传进度、完成后库里文件名、可播放。
5. 权限：首次弹「照片」权限；选「有限访问」时只能看到受限的资产（v1 不做管理入口）。
6. 20 张上限、导出大视频时的内存/等待时间。

## 7. 已知限制 / 下一步

- **自定义相册 v1 的边界**：只有「全部照片」按日期分组，没有相册切换、没有「管理有限访问」、
  没有导出进度条；点「添加」后相册立即关，但大视频的导出仍要等（只是不再挡着界面）。
- **独立 App 的代价**：页面在安装包里，**改前端（含 JS/CSS/HTML）必须重新打包、重新安装**，
  不像原来的网页壳那样改完刷新就生效。原生插件、`api-bridge.js` 的服务器地址同理。
- **服务器地址是打包时写死的**：`api-bridge.js` 的 `DEFAULT_BASE`。电脑 IP 变了就得改它重新打包；
  后续可以在 App 里加一个「服务器地址」设置页（存 localStorage）。
- **真正的后台上传没有做**：`@capacitor/file-transfer` 是前台 URLSession，App 切后台会被 iOS 挂起。
  要「锁屏也继续传」需要自定义 Swift 插件（background `URLSessionConfiguration` + AppDelegate 回调），
  这一步建议在 Mac 上单独做一轮，且要真机验证。
- 视频的文件名由插件按拍摄时间生成（`VID_...mov`）；和相册里的原始文件名不同。
- `window.open()`（下载）在 WebView 里和浏览器行为不同；在线编辑在原生模式改成同页打开。
- App Store 审核风险：这是「本地页面 + 原生相册/上传 + 远程 API」的 App，
  比纯网页壳好一些，但功能仍偏薄，公开上架需要补原生功能。
- iOS 不允许明文 HTTP：`Info.plist` 里用 `NSAllowsArbitraryLoads` 放行了 ATS（见下）。
  以后上 HTTPS 应收紧成域名白名单。

## 8. Info.plist 改了什么

```xml
NSCameraUsageDescription            拍照上传
NSPhotoLibraryUsageDescription      从相册选择照片/视频
NSPhotoLibraryAddUsageDescription   保存编辑后的图片
NSLocalNetworkUsageDescription      连接局域网云盘服务器
NSAppTransportSecurity              NSAllowsArbitraryLoads = true（放行 http://192.168.x.x）
```

PHPicker 正常不触发相册权限弹窗；如果弹了，是 Camera 插件为了取 `metadata`（拍摄时间/大小/格式）。

## 9. 没有 Mac 怎么办

先说一句：**独立 App 的页面在安装包里，改前端也要重新打包**（不再像网页壳那样刷新生效）。
所以没有 Mac 时，推荐直接用 GitHub Actions：推代码 → 跑 workflow → 下载 IPA → Sideloadly 覆盖安装，
登录态和数据都在。Mac 仍然只在「编译 IPA」这一步需要，四条路：

| 路线 | 花钱 | 你还需要 | 安装有效期 |
|---|---|---|---|
| 云端 Mac 按小时 + 免费签名 | 几十元/次 | Windows + Sideloadly | 7 天，到期重签 |
| GitHub Actions + 免费签名 | 0（公开仓库） | GitHub 账号 + Windows | 7 天，到期重签 |
| Codemagic / Appflow + TestFlight | $99/年（中国区约 ¥688） | 开发者账号 | 90 天 / 构建 |
| 二手 Mac mini | 2000~3000 元 | 一台 Mac | 永久 |

### 路线 A：租一台云端 Mac（最直接）

MacinCloud、Scaleway、AWS EC2 Mac、国内 Mac 云主机都可以按小时租，
**选预装 Xcode 16+ 的镜像**（Xcode 十几个 GB，自己下很浪费时间）。

```bash
git clone <你的仓库>
cd cloud-disk
npm i -g pnpm
bash scripts/build-ios-ipa.sh
```

产物 `build/cloud-disk-unsigned.ipa` 用 scp / 网盘拷回 Windows，按下面「Windows 侧安装」操作。

### 路线 B：GitHub Actions（最省钱）

Gitee 仓库镜像一份到 GitHub（私有/公开都行）：

```bash
git remote add github git@github.com:<你的账号>/cloud-disk.git
git push github master
```

然后在 GitHub 的 Actions 页面手动运行 **「iOS 未签名 IPA」**（或推一个 `ios-*` 标签），
构建完在 Artifacts 里下载 `cloud-disk-unsigned-ipa`，解压就是 ipa。

> 公开仓库的 macOS runner 免费；私有仓库按 10 倍分钟计费，一次构建约 5~10 分钟。
> workflow 在 `.github/workflows/ios-unsigned.yml`，底层就是 `scripts/build-ios-ipa.sh`。

### 路线 C：Codemagic / Ionic Appflow + TestFlight（完全不碰 Mac）

有 $99 开发者账号时最省心：Codemagic 连 Git（支持 GitHub/GitLab/Bitbucket/自定义 Git），
配好 App Store Connect API Key 后能**自动签名并上传 TestFlight**，iPhone 装 TestFlight 安装，
每个构建 90 天有效。全程不需要 Mac。

### Windows 侧安装（Sideloadly / AltStore）

1. 电脑装 iTunes（或 Apple Devices）+ Sideloadly。
2. 数据线连 iPhone，手机上点「信任此电脑」。
3. Sideloadly 选 `cloud-disk-unsigned.ipa`，输入 Apple ID（双重认证要填验证码）。
   > 未签名 IPA 是正常输入，Sideloadly 会用你的账号重新签名。
4. iPhone：设置 → 通用 → VPN 与设备管理 → 信任你的开发者证书。
5. 打开 App；免费账号 **7 天后过期**，重连 Sideloadly 重签即可。
   想自动续签可以用 AltStore（同 Wi-Fi 的电脑挂着 AltServer）。

免费 Apple ID 的限制：同时最多 3 个 App、每周 10 个 App ID、证书 7 天。
$99 账号：ad hoc 安装 1 年，或走 TestFlight。

### 连不上服务器？

接口地址是打包时写进 App 的（`api-bridge.js` 的 `DEFAULT_BASE`），必须是手机能访问到的地址：

- 同一 Wi-Fi：直接用 `http://<电脑局域网 IP>:8080`（当前配置 `192.168.3.31`）。
- 不在同一网络：用 Tailscale / ZeroTier / frp 把服务器的 8080 暴露成一个地址，
  改 `api-bridge.js` 的 `DEFAULT_BASE`，重新跑一次构建脚本。

### 不想打包的兜底

Safari → 分享 → 「添加到主屏幕」也能得到图标和全屏，**但选图等待依旧存在**（那是 WebKit 的限制，
不是浏览器 UI 的问题），所以它只能算临时方案。

