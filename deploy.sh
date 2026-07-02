#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# 声灵 (ShengLing) — GitHub 部署脚本
#
# 安全说明：
#   本脚本不会在代码中硬编码任何 Token。
#   运行时会交互式提示输入 GitHub 用户名和 Personal Access Token。
#   Token 仅在当前会话中使用，不会被保存到任何文件。
# ============================================================

REPO_NAME="ShengLing"
BRANCH="main"
VERSION="v1.0.0"
RELEASE_TITLE="声灵 v1.0.0"
RELEASE_NOTES="首次发布：本地AI声音克隆工具，内置模型，开箱即用"

echo "============================================"
echo "  声灵 (ShengLing) — GitHub 部署脚本"
echo "============================================"
echo ""

# 检查工作目录
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
echo "工作目录: $SCRIPT_DIR"

# 检查 git
if ! command -v git &> /dev/null; then
    echo "[错误] 未找到 git，请先安装。"
    exit 1
fi

# 检查 gh CLI（用于创建仓库和 Release）
HAS_GH=false
if command -v gh &> /dev/null; then
    HAS_GH=true
    echo "[OK] 检测到 GitHub CLI (gh)"
else
    echo "[警告] 未检测到 GitHub CLI (gh)，将使用 git remote 推送。"
    echo "       如需自动创建仓库和发布 Release，请安装: https://cli.github.com/"
fi
echo ""

# 交互式获取凭据
read -p "GitHub 用户名: " GH_USER
read -s -p "GitHub Personal Access Token (输入不可见): " GH_TOKEN
echo ""
echo ""

if [ -z "$GH_USER" ] || [ -z "$GH_TOKEN" ]; then
    echo "[错误] 用户名和 Token 不能为空。"
    exit 1
fi

# 验证 Token 格式
if [[ ! "$GH_TOKEN" =~ ^(ghp_|github_pat_) ]]; then
    echo "[警告] Token 格式可能不正确（应以 ghp_ 或 github_pat_ 开头）。"
    read -p "是否继续？(y/N): " confirm
    if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
        echo "已取消。"
        exit 0
    fi
fi

echo "============================================"
echo "  Step 1/5: Git 初始化"
echo "============================================"

if [ -d ".git" ]; then
    echo "Git 仓库已存在，跳过初始化。"
    git remote remove origin 2>/dev/null || true
else
    git init
    git branch -M "$BRANCH"
fi

# 配置 git（如果尚未配置）
if [ -z "$(git config user.name)" ]; then
    git config user.name "$GH_USER"
fi
if [ -z "$(git config user.email)" ]; then
    git config user.email "$GH_USER@users.noreply.github.com"
fi

git add -A
git commit -m "Initial commit: 声灵 - 本地AI声音克隆工具" || echo "无变更需要提交。"
echo ""

echo "============================================"
echo "  Step 2/5: 创建远程仓库"
echo "============================================"

if [ "$HAS_GH" = true ]; then
    echo "$GH_TOKEN" | gh auth login --with-token 2>/dev/null || true
    if gh repo view "$GH_USER/$REPO_NAME" &> /dev/null; then
        echo "仓库 $GH_USER/$REPO_NAME 已存在。"
    else
        gh repo create "$REPO_NAME" --public --description "声灵 — 完全离线的本地AI声音克隆工具" 2>/dev/null || {
            echo "[警告] 通过 gh 创建仓库失败，将尝试直接推送。"
        }
    fi
else
    echo "请手动在 GitHub 上创建仓库: https://github.com/new"
    echo "仓库名: $REPO_NAME"
    read -p "创建完成后按 Enter 继续..."
fi
echo ""

echo "============================================"
echo "  Step 3/5: 推送代码"
echo "============================================"

REMOTE_URL="https://${GH_USER}:${GH_TOKEN}@github.com/${GH_USER}/${REPO_NAME}.git"
git remote add origin "$REMOTE_URL"
git push -u origin "$BRANCH"
# 推送完成后移除带 token 的 remote，避免泄露
git remote set-url origin "https://github.com/${GH_USER}/${REPO_NAME}.git"
echo "代码已推送到 main 分支。"
echo ""

echo "============================================"
echo "  Step 4/5: 编译 Release APK"
echo "============================================"

if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    ./gradlew assembleRelease
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
elif command -v gradle &> /dev/null; then
    gradle assembleRelease
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
else
    echo "[警告] 未找到 gradlew 或 gradle，跳过 APK 编译。"
    echo "       请在 Android Studio 中编译 Release APK 后手动发布。"
    APK_PATH=""
fi

if [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ]; then
    echo "APK 编译成功: $APK_PATH ($(du -h "$APK_PATH" | cut -f1))"
else
    echo "[警告] APK 文件未找到，跳过 Release 发布。"
    echo "       可稍后手动执行: gh release create $VERSION $APK_PATH"
    APK_PATH=""
fi
echo ""

echo "============================================"
echo "  Step 5/5: 创建 GitHub Release"
echo "============================================"

if [ -n "$APK_PATH" ] && [ "$HAS_GH" = true ]; then
    gh release create "$VERSION" \
        --repo "$GH_USER/$REPO_NAME" \
        --title "$RELEASE_TITLE" \
        --notes "$RELEASE_NOTES" \
        "$APK_PATH"
    echo "Release $VERSION 已发布！"
else
    echo "请手动创建 Release:"
    echo "  gh release create $VERSION --title '$RELEASE_TITLE' --notes '$RELEASE_NOTES' $APK_PATH"
fi
echo ""

echo "============================================"
echo "  部署完成！"
echo "============================================"
echo ""
echo "仓库地址: https://github.com/$GH_USER/$REPO_NAME"
echo ""
echo "[安全提示] 请确保 Token 未被写入任何文件。"
echo "  - 检查 .git/config 中不含 token"
echo "  - 如 Token 曾泄露，请立即到 GitHub Settings → Tokens 撤销并重新生成"
