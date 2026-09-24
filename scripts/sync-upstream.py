#!/usr/bin/env python3
"""
Hermes-Relay Upstream Sync Tool
用于检查并安全同步 Codename-11/hermes-relay 上游代码，严密保护本地瘦身架构与定制补丁。
"""

import os
import sys
import subprocess
from pathlib import Path

UPSTREAM_URL = "https://github.com/Codename-11/hermes-relay.git"
REPO_DIR = Path(__file__).resolve().parent.parent

# 核心受保护文件（坚决不允许上游覆盖篡改的瘦身与定制资产）
PROTECTED_FILES = [
    "app/build.gradle.kts",
    "app/src/main/kotlin/com/hermesandroid/relay/audio/VadEngine.kt",
    "app/src/main/kotlin/com/hermesandroid/relay/update/UpdateChecker.kt",
    "app/src/main/kotlin/com/hermesandroid/relay/update/UpdateModels.kt",
    "app/src/main/kotlin/com/hermesandroid/relay/wake/WakeWordDetector.kt",
    "app/src/sideload/AndroidManifest.xml",
    ".github/workflows/release-android.yml",
    ".github/workflows/ci-android.yml",
    ".github/workflows/upstream-check.yml",
    "scripts/publish_r2.py",
]


def run(cmd, check=True):
    res = subprocess.run(cmd, shell=True, text=True, capture_output=True)
    if res.returncode != 0 and check:
        print(f"❌ 命令执行失败: {cmd}\n错误: {res.stderr}\n输出: {res.stdout}")
        sys.exit(res.returncode)
    return res.stdout.strip()


def ensure_upstream_remote():
    remotes = run("git remote", check=False).split()
    if "upstream" not in remotes:
        print("[*] 添加上游 remote: upstream -> " + UPSTREAM_URL)
        run(f"git remote add upstream {UPSTREAM_URL}")
    else:
        # 确保 url 正确
        run(f"git remote set-url upstream {UPSTREAM_URL}")


def get_local_version():
    toml_path = Path("gradle/libs.versions.toml")
    if not toml_path.exists():
        return "unknown"
    with open(toml_path) as f:
        for line in f:
            if "appVersionName" in line and "=" in line:
                return line.split("=")[1].strip().strip('"')
    return "unknown"


def main():
    os.chdir(REPO_DIR)
    print("=" * 60)
    print("🔍 检查 Codename-11/hermes-relay 上游状态与同步可行性")
    print("=" * 60)

    ensure_upstream_remote()

    print("[*] 正在拉取上游最新分支与标签...")
    run("git fetch upstream --tags -q")

    local_ver = get_local_version()
    upstream_tag = run("git tag -l 'android-v*' --sort=-v:refname | head -n 1", check=False)
    if not upstream_tag:
        upstream_tag = run("git tag -l 'v*' --sort=-v:refname | head -n 1", check=False)

    print(f"[*] 本地当前版本: v{local_ver}")
    print(f"[*] 上游最新标签: {upstream_tag}")

    # 检查上游 main 分支是否有新 commit
    upstream_head = run("git rev-parse upstream/main", check=False)
    merge_base = run("git merge-base HEAD upstream/main", check=False)

    if upstream_head == merge_base:
        print("\n✅ 上游 main 分支没有新提交，本地代码已包含所有上游修改。无需同步。")
        return

    # 获取上游新提交的数量
    ahead_count = run(f"git rev-list --count {merge_base}..upstream/main", check=False)
    print(f"\n⚡ 检测到上游有 {ahead_count} 个新提交尚未合入！")

    # 检查哪些受保护文件被上游修改了
    changed_files = run(f"git diff --name-only {merge_base}..upstream/main", check=False).splitlines()
    conflicts_risk = [f for f in changed_files if any(p in f for p in PROTECTED_FILES)]

    if conflicts_risk:
        print("\n⚠️ 【重点警示】上游修改涉及以下本地核心定制/瘦身保护文件：")
        for f in conflicts_risk:
            print(f"   - {f}")
        print("   若直接合并，可能会重新带入 ONNX/全架构依赖或覆盖 R2 更新逻辑！")
    else:
        print("\n✨ 上游变动未触碰核心保护文件，主要是纯业务功能更新。")

    print("\n📋 推荐安全合流 SOP：")
    print("   1. 创建临时隔离分支: git checkout -b sync-upstream-preview")
    print("   2. 合并上游分支:     git merge upstream/main --no-commit")
    print("   3. 检查受保护文件:   git checkout HEAD -- " + " ".join([f for f in conflicts_risk if Path(f).exists()]))
    print("   4. 本地编译与单测:   python3 scripts/check-android-capabilities.py")
    print("   5. 确认无误后提交合入 main。")


if __name__ == "__main__":
    main()
