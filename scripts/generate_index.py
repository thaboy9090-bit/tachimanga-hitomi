#!/usr/bin/env python3
"""
Genera repo/index.min.json a partir de los APKs compilados y build.gradle de cada extensión.
"""

import json
import os
import re
import hashlib
import struct

REPO_DIR = "repo"
APK_DIR = os.path.join(REPO_DIR, "apk")
SRC_DIR = "src"


def calc_source_id(name: str, lang: str, version_id: int = 1) -> int:
    """Calcula el ID de fuente igual que Tachiyomi HttpSource (big-endian)."""
    key = f"{name.lower()}/{lang}/{version_id}"
    digest = hashlib.md5(key.encode()).digest()
    source_id = 0
    for i in range(8):
        source_id = (source_id << 8) | (digest[i] & 0xFF)
    return source_id & 0x7FFFFFFFFFFFFFFF


def parse_build_gradle(path: str) -> dict:
    """Lee las propiedades ext de build.gradle."""
    props = {}
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    for key in ["extName", "pkgNameSuffix", "extClass", "extVersionCode", "nsfw"]:
        match = re.search(rf"{key}\s*=\s*['\"]?([^'\"\n]+)['\"]?", content)
        if match:
            props[key] = match.group(1).strip()
    return props


def build_index() -> list:
    entries = []

    for apk_file in sorted(os.listdir(APK_DIR)):
        if not apk_file.endswith(".apk"):
            continue

        # Nombre de archivo: tachiyomi-en.hitomi-v1.4.1.apk
        match = re.match(r"tachiyomi-(\w+)\.(\w+)-v([\d.]+)\.apk", apk_file)
        if not match:
            print(f"Skipping (unexpected name): {apk_file}")
            continue

        lang = match.group(1)
        src = match.group(2)
        version = match.group(3)

        gradle_path = os.path.join(SRC_DIR, lang, src, "build.gradle")
        if not os.path.exists(gradle_path):
            print(f"No build.gradle for {lang}/{src}, skipping.")
            continue

        props = parse_build_gradle(gradle_path)
        ext_name = props.get("extName", src.capitalize())
        pkg_suffix = props.get("pkgNameSuffix", f"{lang}.{src}")
        version_code = int(props.get("extVersionCode", "1"))
        nsfw = int(props.get("nsfw", "0"))

        pkg = f"eu.kanade.tachiyomi.extension.{pkg_suffix}"
        source_id = calc_source_id(ext_name, lang)

        icon_filename = f"eu.kanade.tachiyomi.extension.{pkg_suffix}.png"
        icon_file = os.path.join(REPO_DIR, "icon", icon_filename)
        icon_url = f"icon/{icon_filename}" if os.path.exists(icon_file) else ""

        entry = {
            "name": ext_name,
            "pkg": pkg,
            "apk": apk_file,
            "lang": lang,
            "code": version_code,
            "version": version,
            "nsfw": nsfw,
            "icon": icon_url,
            "sources": [
                {
                    "name": ext_name,
                    "lang": lang,
                    "id": source_id,
                    "baseUrl": f"https://{src}.la",
                }
            ],
        }
        entries.append(entry)
        print(f"Added: {ext_name} ({lang}.{src}) v{version} id={source_id}")

    return entries


def main():
    os.makedirs(REPO_DIR, exist_ok=True)
    os.makedirs(APK_DIR, exist_ok=True)

    entries = build_index()

    out_path = os.path.join(REPO_DIR, "index.min.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(entries, f, separators=(",", ":"), ensure_ascii=False)

    print(f"\n✓ index.min.json generado con {len(entries)} extensión(es) → {out_path}")


if __name__ == "__main__":
    main()
