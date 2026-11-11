#!/usr/bin/env bash
# 在 macOS（本机或云端 Mac / GitHub Actions 的 macOS runner）上构建**未签名** IPA。
#
# 产物：build/cloud-disk-unsigned.ipa
# 用法（Windows 侧）：Sideloadly / AltStore 选这个 ipa + 自己的 Apple ID 重签安装。
#
# 为什么是未签名：没有 Mac 的人通常也没有付费开发者账号。免签安装由 Sideloadly
# 在 Windows 上完成（免费 Apple ID，7 天有效）；有 $99 账号就走 TestFlight。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v xcodebuild >/dev/null 2>&1; then
    echo "缺少 Xcode：这个脚本只能在 macOS 上运行。" >&2
    exit 1
fi
if ! command -v pnpm >/dev/null 2>&1; then
    echo "缺少 pnpm：先装 Node 20+，再执行 npm i -g pnpm。" >&2
    exit 1
fi

echo "==> 1/4 安装依赖（SPM 包直接引用 node_modules 里的插件）"
pnpm install --frozen-lockfile

echo "==> 2/4 同步 Capacitor 工程（重写 Package.swift，复制配置文件）"
pnpm exec cap sync ios

echo "==> 3/4 xcodebuild（不签名）"
cd "$ROOT/ios/App"
xcodebuild \
    -project App.xcodeproj \
    -scheme App \
    -configuration Release \
    -sdk iphoneos \
    -derivedDataPath build \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGN_IDENTITY="" \
    build

APP="$ROOT/ios/App/build/Build/Products/Release-iphoneos/App.app"
if [ ! -d "$APP" ]; then
    echo "构建产物不存在：$APP" >&2
    exit 1
fi

echo "==> 4/4 打包成 IPA"
rm -rf "$ROOT/build/ipa"
mkdir -p "$ROOT/build/ipa/Payload"
cp -R "$APP" "$ROOT/build/ipa/Payload/"
( cd "$ROOT/build/ipa" && zip -qry "$ROOT/build/cloud-disk-unsigned.ipa" Payload )

echo
echo "完成：$ROOT/build/cloud-disk-unsigned.ipa"
echo "Windows 侧：Sideloadly 选它 + Apple ID 安装（免费账号 7 天有效）。"
