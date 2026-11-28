#!/usr/bin/env python3
"""Build pinned Top-20 Fire Wisp translation model mirror & manifest from Mozilla Remote Settings."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import gzip
import hashlib
import json
from pathlib import Path
import re
import urllib.request

RS_BASE = "https://firefox.settings.services.mozilla.com/v1"
MPL2_LICENSE_URL = "https://raw.githubusercontent.com/mozilla/translations/main/LICENSE"
FIREWISP_MODELS_BASE_URL = "https://firewisp.de/models"
OUT_MANIFEST_PATH = (
    Path(__file__).resolve().parent.parent
    / "app"
    / "src"
    / "main"
    / "assets"
    / "translation_models_manifest.json"
)
DEFAULT_MIRROR_DIR = Path(__file__).resolve().parent.parent.parent / "firewisp-models"

TOP_20_LANGS = {
    "es", "de", "fr", "pt", "it", "nl", "pl", "ru", "uk", "hr",
    "cs", "sv", "tr", "ja", "zh", "ko", "ar", "hi", "id", "vi",
}

FILE_TYPE_TO_ROLE = {
    "lex": "shortlist",
    "model": "model",
    "vocab": "vocab",
    "srcvocab": "srcvocab",
    "trgvocab": "trgvocab",
}


def http_get_bytes(url: str, timeout: int = 120) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "firewisp-manifest-gen/3.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def http_get_json(url: str) -> dict:
    return json.loads(http_get_bytes(url, timeout=30).decode("utf-8"))


def is_android_release(record: dict) -> bool:
    fe = (record.get("filter_expression") or "").strip()
    if fe not in ("", "env.appinfo.OS == 'Android'"):
        return False
    version = str(record.get("version") or "").strip()
    return bool(re.match(r"^\d+(\.\d+)*$", version))


def version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def normalize_lang(code: str) -> str | None:
    if code == "zh-Hans":
        return "zh"
    if len(code) == 2 and code.isalpha():
        return code.lower()
    return None


def deterministic_gzip(raw: bytes, out_path: Path) -> tuple[str, int]:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = out_path.with_suffix(out_path.suffix + ".tmp")
    with open(tmp_path, "wb") as raw_fh:
        with gzip.GzipFile(filename="", mode="wb", compresslevel=6, fileobj=raw_fh, mtime=0) as gz:
            gz.write(raw)
    tmp_path.replace(out_path)
    gz_bytes = out_path.read_bytes()
    return hashlib.sha256(gz_bytes).hexdigest(), len(gz_bytes)


def process_file(
    att_base_url: str,
    mirror_dir: Path,
    pair_id: str,
    file_spec: dict,
) -> dict:
    gz_name = f"{file_spec['name']}.gz"
    rel_location = f"{pair_id}/{gz_name}"
    out_path = mirror_dir / pair_id / gz_name

    # Reuse already-verified gzipped file if its uncompressed stream still matches upstream SHA-256 & size.
    if out_path.is_file() and out_path.stat().st_size > 0:
        try:
            existing_gz = out_path.read_bytes()
            existing_raw = gzip.decompress(existing_gz)
            if (
                len(existing_raw) == file_spec["upstreamBytes"]
                and hashlib.sha256(existing_raw).hexdigest() == file_spec["upstreamSha256"]
            ):
                return {
                    "name": gz_name,
                    "role": file_spec["role"],
                    "compressedSha256": hashlib.sha256(existing_gz).hexdigest(),
                    "compressedBytes": len(existing_gz),
                    "location": rel_location,
                }
        except Exception:
            pass

    url = f"{att_base_url}/{file_spec['upstreamLocation'].lstrip('/')}"
    raw = http_get_bytes(url, timeout=180)
    actual_sha = hashlib.sha256(raw).hexdigest()
    if len(raw) != file_spec["upstreamBytes"]:
        raise ValueError(
            f"Size mismatch for {pair_id}/{file_spec['name']}: expected {file_spec['upstreamBytes']}, got {len(raw)}"
        )
    if actual_sha != file_spec["upstreamSha256"]:
        raise ValueError(
            f"SHA-256 mismatch for {pair_id}/{file_spec['name']}: expected {file_spec['upstreamSha256']}, got {actual_sha}"
        )

    gz_sha, gz_size = deterministic_gzip(raw, out_path)
    return {
        "name": gz_name,
        "role": file_spec["role"],
        "compressedSha256": gz_sha,
        "compressedBytes": gz_size,
        "location": rel_location,
    }


def write_compliance_and_caddy(mirror_dir: Path, manifest: dict) -> None:
    license_bytes = http_get_bytes(MPL2_LICENSE_URL, timeout=30)
    (mirror_dir / "LICENSE").write_bytes(license_bytes)

    notice = (
        "Mozilla Firefox Translations Models (MPL-2.0)\n"
        "=============================================\n\n"
        "These translation model files (.bin.gz / .spm.gz) are unmodified neural machine\n"
        "translation weights, SentencePiece vocabularies, and lexical shortlists produced\n"
        "by Mozilla's Firefox Translations project:\n"
        "  https://github.com/mozilla/translations\n\n"
        "License:\n"
        "  Mozilla Public License Version 2.0 (see ./LICENSE in this directory).\n\n"
        "Mirror policy:\n"
        "  Hosted on firewisp.de/models for Fire Wisp offline on-device translation.\n"
        "  Served with gzip transport encoding disabled in app downloads and zero access logging.\n"
    )
    (mirror_dir / "NOTICE.txt").write_text(notice, encoding="utf-8")
    (mirror_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )

    caddy_snippet = """# Fire Wisp offline translation models (add inside `firewisp.de { ... }` in /etc/caddy/Caddyfile)
# Requires zero access logging (`log_skip`) + strict static GET/HEAD delivery.
handle_path /models/* {
    log_skip
    root * /srv/firewisp-models

    @disallowed not method GET HEAD
    respond @disallowed 405

    header {
        X-Content-Type-Options "nosniff"
        Referrer-Policy "no-referrer"
        Cache-Control "public, max-age=31536000, immutable"
        -Server
    }

    @gz path *.gz
    header @gz Content-Type "application/gzip"

    file_server {
        disable_canonical_uris
    }
}
"""
    (mirror_dir / "Caddyfile.models.snippet").write_text(caddy_snippet, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build Fire Wisp Top-20 model mirror and manifest")
    parser.add_argument(
        "--mirror-dir",
        type=Path,
        default=DEFAULT_MIRROR_DIR,
        help="Output directory for verified .gz model mirror files",
    )
    parser.add_argument(
        "--base-url",
        default=FIREWISP_MODELS_BASE_URL,
        help="Manifest baseUrl (default: https://firewisp.de/models)",
    )
    args = parser.parse_args()

    mirror_dir: Path = args.mirror_dir.resolve()
    mirror_dir.mkdir(parents=True, exist_ok=True)

    root = http_get_json(f"{RS_BASE}/")
    att_base_url = root["capabilities"]["attachments"]["base_url"].rstrip("/")

    records = http_get_json(
        f"{RS_BASE}/buckets/main/collections/translations-models/records"
    )["data"]

    grouped: dict[tuple[str, str, str], dict[str, list[dict]]] = {}
    max_last_modified = 0

    for record in records:
        if not is_android_release(record):
            continue
        role = FILE_TYPE_TO_ROLE.get(record.get("fileType", ""))
        if not role:
            continue
        src = normalize_lang(str(record.get("fromLang") or ""))
        tgt = normalize_lang(str(record.get("toLang") or ""))
        if not src or not tgt or (src != "en" and tgt != "en"):
            continue
        other = tgt if src == "en" else src
        if other not in TOP_20_LANGS:
            continue
        att = record.get("attachment") or {}
        location = str(att.get("location") or "").strip()
        sha256 = str(att.get("hash") or "").strip().lower()
        size_bytes = int(att.get("size") or 0)
        name = str(record.get("name") or "").strip()
        if not location or not re.match(r"^[0-9a-f]{64}$", sha256) or size_bytes <= 0 or not name:
            continue

        last_mod = int(record.get("last_modified") or 0)
        if last_mod > max_last_modified:
            max_last_modified = last_mod

        pair_id = f"{src}{tgt}"
        ver = str(record["version"]).strip()
        grouped.setdefault((pair_id, src, tgt), {}).setdefault(ver, []).append({
            "name": name,
            "role": role,
            "upstreamSha256": sha256,
            "upstreamBytes": size_bytes,
            "upstreamLocation": location,
        })

    selected_specs: list[tuple[str, str, str, str, list[dict]]] = []
    for (pair_id, src, tgt), ver_map in sorted(grouped.items(), key=lambda kv: kv[0][0]):
        for ver in sorted(ver_map.keys(), key=version_key, reverse=True):
            files = ver_map[ver]
            roles = {f["role"] for f in files}
            has_vocab = "vocab" in roles or ("srcvocab" in roles and "trgvocab" in roles)
            if "model" in roles and "shortlist" in roles and has_vocab:
                dedup = {f["name"]: f for f in files}
                selected_specs.append((pair_id, src, tgt, ver, sorted(dedup.values(), key=lambda f: f["name"])))
                break

    if len(selected_specs) != len(TOP_20_LANGS) * 2:
        found = {p[0] for p in selected_specs}
        raise RuntimeError(
            f"Expected {len(TOP_20_LANGS) * 2} pairs, found {len(selected_specs)} ({sorted(found)})"
        )

    futures = {}
    with ThreadPoolExecutor(max_workers=6) as pool:
        for pair_id, src, tgt, ver, files in selected_specs:
            for spec in files:
                fut = pool.submit(process_file, att_base_url, mirror_dir, pair_id, spec)
                futures[fut] = (pair_id, src, tgt, ver)

    by_pair_files: dict[str, list[dict]] = {}
    for fut in as_completed(futures):
        pair_id, _, _, _ = futures[fut]
        by_pair_files.setdefault(pair_id, []).append(fut.result())

    pairs_out = []
    for pair_id, src, tgt, ver, _ in selected_specs:
        files_sorted = sorted(by_pair_files[pair_id], key=lambda f: f["name"])
        total_bytes = sum(f["compressedBytes"] for f in files_sorted)
        pairs_out.append({
            "pair": pair_id,
            "source": src,
            "target": tgt,
            "tier": f"v{ver}",
            "totalCompressedBytes": total_bytes,
            "files": files_sorted,
        })

    manifest = {
        "schemaVersion": 2,
        "commitSha": f"firewisp-top20-{max_last_modified}",
        "baseUrl": args.base_url.rstrip("/"),
        "pairs": pairs_out,
    }
    OUT_MANIFEST_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_MANIFEST_PATH.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    write_compliance_and_caddy(mirror_dir, manifest)

    total_gz = sum(p["totalCompressedBytes"] for p in pairs_out)
    print(
        f"Built Top-20 mirror ({len(pairs_out)} pairs, {total_gz / (1024**3):.2f} GiB gzipped) -> {mirror_dir}"
    )
    print(f"Manifest written -> {OUT_MANIFEST_PATH}")


if __name__ == "__main__":
    main()
