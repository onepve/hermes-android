#!/usr/bin/env python3
"""
Hermes-Relay Upstream Sync Tool
用于一键同步 Codename-11/hermes-relay 上游代码，并自动保持 onepve 定制与瘦身补丁。
"""

import subprocess
import sys
import os

UPSTREAM_URL = "https://github.com/Codename-11/hermes-relay.git"

def run(cmd, check=True):
    print(f"==> {cmd}")
    res = subprocess.run(cmd, shell=True, text=True, capture_output=True)
    if res.returncode != 0 and check:
        print(f"Error executing command: {cmd}\nStderr: {res.stderr}\nStdout: {res.stdout}")
        sys.exit(res.returncode)
    return res.stdout.strip()

def ensure_upstream_remote():
    remotes = run("git remote", check=False)
    if "upstream" not in remotes.split():
        print("[*] Adding upstream remote...")
        run(f"git remote add upstream {UPSTREAM_URL}")
    else:
        print("[*] Upstream remote already configured.")

def check_status():
    ensure_upstream_remote()
    print("[*] Fetching upstream releases and tags...")
    run("git fetch upstream --tags")
    
    # 查找上游最新 tag
    latest_upstream = run("git tag -l 'android-v*' --sort=-v:refname | head -n 1", check=False)
    print(f"[*] Upstream latest Android release tag: {latest_upstream}")

def main():
    check_status()
    print("\n✅ 上游状态检查完毕。若需合并，请执行: git merge upstream/main 并重新核验定制补丁。")

if __name__ == "__main__":
    main()
