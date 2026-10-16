#!/usr/bin/env python3
"""Resolves the model catalog on Hugging Face (pinned commit, size, SHA-256) and fetches the
bundled Silero VAD model. Runs in CI; writes app/src/main/assets/{catalog.json, VAD model}."""
import hashlib
import json
import os
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
HF = "https://huggingface.co"


def get(url, tries=4):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "ai-voice-to-text-ci"})
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            print(f"  retry {i + 1}/{tries} {url}: {e}", file=sys.stderr)
            time.sleep(3 * (i + 1))
    raise RuntimeError(f"failed: {url}")


def resolve(repo, file):
    info = json.loads(get(f"{HF}/api/models/{repo}/revision/main?blobs=true"))
    commit = info["sha"]
    for s in info.get("siblings", []):
        if s.get("rfilename") == file:
            lfs = s.get("lfs") or {}
            return commit, lfs.get("sha256"), lfs.get("size") or s.get("size")
    raise RuntimeError(f"{file} not found in {repo}")


def main():
    src = json.load(open(os.path.join(ROOT, "scripts", "catalog_source.json"), encoding="utf-8"))
    os.makedirs(ASSETS, exist_ok=True)
    out = []
    for m in src["models"]:
        entry = {k: v for k, v in m.items() if k not in ("repo",)}
        try:
            commit, sha, size = resolve(m["repo"], m["file"])
            entry["url"] = f"{HF}/{m['repo']}/resolve/{commit}/{m['file']}"
            entry["sha256"] = sha or ""
            entry["size"] = int(size or m["size"])
            print(f"ok   {m['id']}: {commit[:8]} {entry['size']} bytes")
        except Exception as e:  # noqa: BLE001
            print(f"WARN {m['id']}: {e}; using branch main without checksum", file=sys.stderr)
            entry["url"] = f"{HF}/{m['repo']}/resolve/main/{m['file']}"
            entry["sha256"] = ""
        out.append(entry)
    with open(os.path.join(ASSETS, "catalog.json"), "w", encoding="utf-8") as f:
        json.dump({"models": out}, f, ensure_ascii=False, indent=1)

    vad = src["vad"]
    target = os.path.join(ASSETS, vad["file"])
    if not os.path.exists(target):
        commit, sha, _ = resolve(vad["repo"], vad["file"])
        data = get(f"{HF}/{vad['repo']}/resolve/{commit}/{vad['file']}")
        if sha and hashlib.sha256(data).hexdigest() != sha:
            raise RuntimeError("VAD model checksum mismatch")
        open(target, "wb").write(data)
        print(f"ok   VAD {len(data)} bytes")


if __name__ == "__main__":
    main()
