#!/usr/bin/env python3
"""Upload a generated PNG/JPEG/WebP sprite to this GitHub repository.

Usage:
  export GITHUB_TOKEN=...
  python tools/upload_sprite.py ./guan_yu_idle.png --name guan_yu_idle.png

The token needs Contents: Read and write permission. Never hard-code it.
"""
import argparse
import base64
import json
import mimetypes
import os
import urllib.error
import urllib.request
from pathlib import Path

OWNER = "qiang1284779398-hue"
REPO = "tubu"
BRANCH = "main"
ASSET_DIR = "game-assets/sprites"

def request(url, token, payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="GET" if payload is None else "PUT")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        raise SystemExit(f"GitHub API error {exc.code}: {detail}") from exc

def main():
    parser = argparse.ArgumentParser(description="Commit a sprite image to the game repository.")
    parser.add_argument("image", help="Path to a PNG, JPG, or WebP image")
    parser.add_argument("--name", help="Filename in the repository; defaults to source filename")
    parser.add_argument("--message", default="Add generated 2D game sprite")
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        raise SystemExit("Set GITHUB_TOKEN first; do not put the token in this script.")

    image = Path(args.image)
    if not image.is_file():
        raise SystemExit(f"Image not found: {image}")
    suffix = image.suffix.lower()
    if suffix not in {".png", ".jpg", ".jpeg", ".webp"}:
        raise SystemExit("Only PNG, JPG/JPEG, and WebP images are supported.")

    filename = args.name or image.name
    if Path(filename).name != filename:
        raise SystemExit("--name must be a filename, not a path.")
    repo_path = f"{ASSET_DIR}/{filename}"
    mime = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    encoded = base64.b64encode(image.read_bytes()).decode("ascii")

    url = f"https://api.github.com/repos/{OWNER}/{REPO}/contents/{repo_path}"
    # Read current SHA when replacing an existing file.
    existing_sha = None
    try:
        existing = request(url + f"?ref={BRANCH}", token)
        existing_sha = existing.get("sha")
    except SystemExit as exc:
        if "GitHub API error 404" not in str(exc):
            raise

    body = {
        "message": args.message,
        "content": encoded,
        "branch": BRANCH,
    }
    if existing_sha:
        body["sha"] = existing_sha

    result = request(url, token, body)
    print("Uploaded:", result.get("content", {}).get("path", repo_path))
    print("Commit:", result.get("commit", {}).get("html_url", "(commit created)"))
    print("MIME:", mime)

if __name__ == "__main__":
    main()
