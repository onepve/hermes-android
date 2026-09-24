#!/usr/bin/env python3
"""
CI Invariant Gate: Guard against regressions of OnePve customized client rules.
Rules guarded:
1. Pure app name: app_name must strictly be "Hermes" across all resources, never "Hermes Dev" or "开发版".
2. Relay disabled: probeHealth and isRelayConfiguredFor must remain disabled to prevent background relay loops.
3. Standard Chinese locales: values-zh and values-zh-rCN must exist as directories with valid strings.xml.
4. Auto-fallback for Direct API: ChatRuntimeStatus must connect immediately when ApiSse is ready.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

REPO_ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []


def check_app_name():
    print("[1/4] Checking app name purity across source sets...")
    for strings_file in REPO_ROOT.glob("app/src/*/res/values*/strings.xml"):
        try:
            tree = ET.parse(strings_file)
            root = tree.getroot()
            for string_node in root.findall("string"):
                if string_node.get("name") == "app_name":
                    text = "".join(string_node.itertext()).strip()
                    if "开发版" in text or "Dev" in text or text != "Hermes":
                        ERRORS.append(
                            f"{strings_file.relative_to(REPO_ROOT)}: app_name is '{text}', expected strictly 'Hermes'"
                        )
        except Exception as e:
            ERRORS.append(f"{strings_file.relative_to(REPO_ROOT)}: failed to parse XML: {e}")


def check_relay_disabled():
    print("[2/4] Checking Relay hard-disable and health probe severance...")
    relay_client_kt = REPO_ROOT / "app/src/main/kotlin/com/hermesandroid/relay/network/relay/RelayHttpClient.kt"
    if not relay_client_kt.exists():
        ERRORS.append("RelayHttpClient.kt missing")
    else:
        content = relay_client_kt.read_text(encoding="utf-8")
        if "Relay disabled in pure AI mode" not in content:
            ERRORS.append(
                "RelayHttpClient.kt: probeHealth must be short-circuited with 'Relay disabled in pure AI mode'"
            )

    conn_vm_kt = REPO_ROOT / "app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ConnectionViewModel.kt"
    if not conn_vm_kt.exists():
        ERRORS.append("ConnectionViewModel.kt missing")
    else:
        content = conn_vm_kt.read_text(encoding="utf-8")
        if "private fun isRelayConfiguredFor(connection: Connection?, auth: AuthState): Boolean {\n        return false\n    }" not in content:
            ERRORS.append(
                "ConnectionViewModel.kt: isRelayConfiguredFor must strictly return false"
            )


def check_chinese_locales():
    print("[3/4] Checking standard Chinese locale resource directories...")
    required_dirs = [
        REPO_ROOT / "app/src/main/res/values-zh",
        REPO_ROOT / "app/src/main/res/values-zh-rCN",
        REPO_ROOT / "app/src/sideload/res/values-zh",
        REPO_ROOT / "app/src/sideload/res/values-zh-rCN",
    ]
    for d in required_dirs:
        if not d.is_dir() or d.is_symlink():
            ERRORS.append(f"{d.relative_to(REPO_ROOT)}: must exist as a real directory (not symlink or missing)")
        strings_xml = d / "strings.xml"
        if not strings_xml.is_file():
            ERRORS.append(f"{strings_xml.relative_to(REPO_ROOT)}: missing strings.xml")


def check_direct_api_fallback():
    print("[4/4] Checking Direct API automatic fallback in ChatRuntimeStatus...")
    chat_status_kt = REPO_ROOT / "app/src/main/kotlin/com/hermesandroid/relay/viewmodel/ChatRuntimeStatus.kt"
    if not chat_status_kt.exists():
        ERRORS.append("ChatRuntimeStatus.kt missing")
    else:
        content = chat_status_kt.read_text(encoding="utf-8")
        if "fallbackTransport" not in content or "ChatTransportPath.ApiSse" not in content:
            ERRORS.append(
                "ChatRuntimeStatus.kt: missing fallback logic to ApiSse when preferred transport is not Ready"
            )


def main():
    check_app_name()
    check_relay_disabled()
    check_chinese_locales()
    check_direct_api_fallback()

    if ERRORS:
        print("\n❌ CI Invariant Gate FAILED with the following violations:")
        for err in ERRORS:
            print(f"  - {err}")
        sys.exit(1)
    else:
        print("\n✅ All OnePve client invariant checks PASSED successfully.")


if __name__ == "__main__":
    main()
