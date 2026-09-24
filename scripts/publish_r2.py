#!/usr/bin/env python3
"""
Hermes-Relay Cloudflare R2 Publisher
将构建出的纯净版 Release APK 上传到 Cloudflare R2 (dl.onepve.com)，并生成/刷新 version.json
"""

import os
import sys
import json
import hashlib
import datetime
from pathlib import Path

try:
    import boto3
except ImportError:
    print("Error: boto3 is required. Run 'pip install boto3'")
    sys.exit(1)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def md5_file(path: Path) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    if len(sys.argv) < 3:
        print("Usage: publish_r2.py <apk_path> <version> [version_code]")
        sys.exit(1)

    apk_path = Path(sys.argv[1])
    version = sys.argv[2].lstrip("v")
    version_code = int(sys.argv[3]) if len(sys.argv) > 3 else 1

    if not apk_path.exists():
        print(f"Error: APK not found at {apk_path}")
        sys.exit(1)

    # 优先环境变量，其次本地 .env 兜底
    endpoint = os.environ.get("R2_ENDPOINT")
    access_key = os.environ.get("R2_ACCESS_KEY_ID")
    secret_key = os.environ.get("R2_SECRET_ACCESS_KEY")
    bucket = os.environ.get("R2_BUCKET", "downloads")

    if not (endpoint and access_key and secret_key):
        env_file = Path.home() / ".hermes/onepve/.env"
        if env_file.exists():
            with open(env_file) as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#") and "=" in line:
                        k, v = line.split("=", 1)
                        k, v = k.strip(), v.strip()
                        if k == "R2_DOWNLOADS_ENDPOINT" and not endpoint:
                            endpoint = v
                        elif k == "R2_DOWNLOADS_ACCESS_KEY_ID" and not access_key:
                            access_key = v
                        elif k == "R2_DOWNLOADS_SECRET_ACCESS_KEY" and not secret_key:
                            secret_key = v
                        elif k == "R2_DOWNLOADS_BUCKET" and not bucket:
                            bucket = v

    if not (endpoint and access_key and secret_key):
        print("Error: Missing R2 credentials in environment or .env")
        sys.exit(1)

    file_size = apk_path.stat().st_size
    sha256 = sha256_file(apk_path)
    md5 = md5_file(apk_path)

    print(f"📦 Packaging Hermes-Relay v{version} (code: {version_code})")
    print(f"   Size: {file_size / (1024 * 1024):.2f} MB ({file_size} bytes)")
    print(f"   SHA256: {sha256}")
    print(f"   MD5: {md5}")

    s3 = boto3.client(
        "s3",
        endpoint_url=endpoint,
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        region_name="auto",
    )

    prefix = "hermes-relay"
    versioned_key = f"{prefix}/hermes-relay-v{version}.apk"
    latest_key = f"{prefix}/hermes-relay-latest.apk"
    manifest_key = f"{prefix}/version.json"

    download_url_latest = f"https://dl.onepve.com/{latest_key}"
    download_url_versioned = f"https://dl.onepve.com/{versioned_key}"

    print(f"🚀 Uploading {versioned_key} ...")
    with open(apk_path, "rb") as f:
        s3.put_object(
            Bucket=bucket,
            Key=versioned_key,
            Body=f,
            ContentType="application/vnd.android.package-archive",
        )

    print(f"🚀 Uploading {latest_key} ...")
    with open(apk_path, "rb") as f:
        s3.put_object(
            Bucket=bucket,
            Key=latest_key,
            Body=f,
            ContentType="application/vnd.android.package-archive",
        )

    now_iso = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    manifest = {
        "version": version,
        "version_code": version_code,
        "download_url": download_url_latest,
        "versioned_download_url": download_url_versioned,
        "sha256": sha256,
        "md5": md5,
        "size": file_size,
        "changelog": "极限精简纯净版，纯原生能量VAD，专为现代64位Android手机设计",
        "published_at": now_iso,
    }

    manifest_bytes = json.dumps(manifest, indent=2, ensure_ascii=False).encode("utf-8")
    print(f"🚀 Uploading {manifest_key} ...")
    s3.put_object(
        Bucket=bucket,
        Key=manifest_key,
        Body=manifest_bytes,
        ContentType="application/json",
        CacheControl="no-cache, no-store, must-revalidate",
    )

    print("\n✅ Successfully published to Cloudflare R2!")
    print(f"   Manifest: https://dl.onepve.com/{manifest_key}")
    print(f"   Download: {download_url_latest}")


if __name__ == "__main__":
    main()
