#!/usr/bin/env python3
"""
Genera el formato v2 del repositorio: repo.json, index.pb e index.json.

Mihon y Tachimanga leen repo.json, siguen el campo index_v2 y consumen un
protobuf comprimido con gzip. El index.min.json legacy lo sigue generando
generate_index.py, para clientes viejos.

Esquema del index.pb, deducido del repositorio de keiyoushi:

  RepoIndex
    1    name           string
    2    badgeLabel     string
    3    signingKey     string   fingerprint SHA-256 del certificado de firma
    4    contact        message  { 1 website, 2 discord }
    101  extensionList  message  { 1 extensions (repetido) }

  Extension
    1  name            string
    2  packageName     string
    3  resources       message  { 1 apkUrl, 2 iconUrl, 501 jarUrl }
    4  extensionLib    string
    5  versionCode     varint
    6  versionName     string
    7  contentWarning  enum     1=SAFE 2=MIXED 3=NSFW
    8  sources         message (repetido)

  Source
    1  id        varint
    2  name      string
    3  language  string
    4  homeUrl   string
"""

import gzip
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate_index import calc_source_id, parse_base_url, parse_build_gradle

REPO_DIR = "repo"
APK_DIR = os.path.join(REPO_DIR, "apk")
SRC_DIR = "src"

KEYSTORE = os.path.join("signing", "repo.keystore")
KEYSTORE_PASS = os.environ.get("KEYSTORE_PASSWORD", "")

REPO_NAME = os.environ.get("REPO_NAME", "Tachimanga Extensions")
BADGE_LABEL = os.environ.get("BADGE_LABEL", "TME")
WEBSITE = os.environ.get(
    "REPO_WEBSITE", "https://github.com/thaboy9090-bit/tachimanga-hitomi"
)
BASE_URL = os.environ.get(
    "REPO_BASE_URL",
    "https://raw.githubusercontent.com/thaboy9090-bit/tachimanga-hitomi/repo",
).rstrip("/")

CW_SAFE, CW_NSFW = 1, 3


# ---------- codificador protobuf ----------

def _varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def _tag(field, wire):
    return _varint((field << 3) | wire)


def pb_varint(field, value):
    return _tag(field, 0) + _varint(value)


def pb_bytes(field, value):
    return _tag(field, 2) + _varint(len(value)) + value


def pb_str(field, value):
    return pb_bytes(field, value.encode("utf-8"))


# ---------- fingerprint ----------

def signing_fingerprint():
    env = os.environ.get("SIGNING_KEY_FINGERPRINT")
    if env:
        return env.replace(":", "").lower()
    if not (os.path.exists(KEYSTORE) and KEYSTORE_PASS):
        print("AVISO: sin keystore ni KEYSTORE_PASSWORD, signingKey queda vacio")
        return ""
    out = subprocess.run(
        ["keytool", "-list", "-v", "-keystore", KEYSTORE, "-storepass", KEYSTORE_PASS],
        capture_output=True,
        text=True,
    ).stdout
    match = re.search(r"SHA256:\s*([0-9A-Fa-f:]+)", out)
    if not match:
        print("AVISO: keytool no devolvio el SHA256")
        return ""
    return match.group(1).replace(":", "").lower()


# ---------- construccion ----------

def collect_extensions():
    extensions = []
    if not os.path.isdir(APK_DIR):
        return extensions

    for apk_file in sorted(os.listdir(APK_DIR)):
        match = re.match(r"tachiyomi-(\w+)\.(\w+)-v([\d.]+)\.apk", apk_file)
        if not match:
            continue
        dir_lang, dir_src, version_name = match.groups()

        gradle_path = os.path.join(SRC_DIR, dir_lang, dir_src, "build.gradle")
        if not os.path.exists(gradle_path):
            continue

        props = parse_build_gradle(gradle_path)
        ext_name = props.get("extName", dir_src.capitalize())
        pkg_suffix = props.get("pkgNameSuffix", "%s.%s" % (dir_lang, dir_src))
        version_code = int(props.get("extVersionCode", "1"))
        nsfw = int(props.get("nsfw", "0"))
        lang = pkg_suffix.split(".")[0]

        icon_name = "eu.kanade.tachiyomi.extension.%s.png" % pkg_suffix
        has_icon = os.path.exists(os.path.join(REPO_DIR, "icon", icon_name))

        extensions.append({
            "name": ext_name,
            "packageName": "eu.kanade.tachiyomi.extension.%s" % pkg_suffix,
            "apkUrl": "%s/apk/%s" % (BASE_URL, apk_file),
            "iconUrl": "%s/icon/%s" % (BASE_URL, icon_name) if has_icon else "",
            "extensionLib": "1.6",
            "versionCode": version_code,
            "versionName": version_name,
            "contentWarning": CW_NSFW if nsfw else CW_SAFE,
            "sources": [{
                "id": calc_source_id(ext_name, lang),
                "name": ext_name,
                "language": lang,
                "homeUrl": parse_base_url(os.path.join(SRC_DIR, dir_lang, dir_src)),
            }],
        })
    return extensions


def encode_index(fingerprint, extensions):
    body = pb_str(1, REPO_NAME) + pb_str(2, BADGE_LABEL)
    if fingerprint:
        body += pb_str(3, fingerprint)
    body += pb_bytes(4, pb_str(1, WEBSITE))

    ext_list = b""
    for e in extensions:
        resources = pb_str(1, e["apkUrl"])
        if e["iconUrl"]:
            resources += pb_str(2, e["iconUrl"])

        entry = (
            pb_str(1, e["name"])
            + pb_str(2, e["packageName"])
            + pb_bytes(3, resources)
            + pb_str(4, e["extensionLib"])
            + pb_varint(5, e["versionCode"])
            + pb_str(6, e["versionName"])
            + pb_varint(7, e["contentWarning"])
        )
        for s in e["sources"]:
            entry += pb_bytes(8, (
                pb_varint(1, s["id"])
                + pb_str(2, s["name"])
                + pb_str(3, s["language"])
                + pb_str(4, s["homeUrl"])
            ))
        ext_list += pb_bytes(1, entry)

    return body + pb_bytes(101, ext_list)


def as_json(fingerprint, extensions):
    # En la forma JSON del protobuf los int64 van como string.
    names = {CW_SAFE: "CONTENT_WARNING_SAFE", CW_NSFW: "CONTENT_WARNING_NSFW"}
    return {
        "name": REPO_NAME,
        "badgeLabel": BADGE_LABEL,
        "signingKey": fingerprint,
        "contact": {"website": WEBSITE},
        "extensionList": {
            "extensions": [{
                "name": e["name"],
                "packageName": e["packageName"],
                "resources": {
                    k: v for k, v in
                    (("apkUrl", e["apkUrl"]), ("iconUrl", e["iconUrl"])) if v
                },
                "extensionLib": e["extensionLib"],
                "versionCode": str(e["versionCode"]),
                "versionName": e["versionName"],
                "contentWarning": names[e["contentWarning"]],
                "sources": [{
                    "id": str(s["id"]),
                    "name": s["name"],
                    "language": s["language"],
                    "homeUrl": s["homeUrl"],
                } for s in e["sources"]],
            } for e in extensions]
        },
    }


def main():
    os.makedirs(REPO_DIR, exist_ok=True)
    fingerprint = signing_fingerprint()
    extensions = collect_extensions()

    raw = encode_index(fingerprint, extensions)
    # mtime fijo para que el gzip no cambie cuando el contenido tampoco.
    with open(os.path.join(REPO_DIR, "index.pb"), "wb") as f:
        f.write(gzip.compress(raw, mtime=0))

    with open(os.path.join(REPO_DIR, "index.json"), "w", encoding="utf-8") as f:
        json.dump(as_json(fingerprint, extensions), f, indent=2, ensure_ascii=False)

    with open(os.path.join(REPO_DIR, "repo.json"), "w", encoding="utf-8") as f:
        json.dump({
            "index_v2": "%s/index.pb" % BASE_URL,
            "meta": {
                "name": REPO_NAME,
                "website": WEBSITE,
                "signingKeyFingerprint": fingerprint,
            },
        }, f, indent=2, ensure_ascii=False)

    print("repo.json, index.pb (%d bytes sin comprimir) e index.json" % len(raw))
    print("extensiones: %d | signingKey: %s..." % (
        len(extensions), fingerprint[:16] or "(vacio)"))
    for e in extensions:
        print("  %s v%s code=%s" % (e["name"], e["versionName"], e["versionCode"]))


if __name__ == "__main__":
    main()
