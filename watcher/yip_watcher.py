#!/usr/bin/env python3
"""
Yip playback config watcher.

Loads twitch.tv in headless Chromium, records the web Client-ID and the persisted-query
hashes the site uses, checks they work, and publishes a signed JSON file the Yip app trusts.

  keygen   create the Ed25519 signing key (run once)
  run      discover, validate, sign and publish (run from cron every ~30 minutes)
  verify   check a published file's signature
"""
from __future__ import annotations

import argparse
import base64
import contextlib
import fcntl
import json
import logging
import os
import sys
import tempfile
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

log = logging.getLogger("yip-watcher")

GQL_URL = "https://gql.twitch.tv/gql"
CONFIG_VERSION = 1

# Operations the app depends on. Missing ones keep their previous (or built-in) value.
# Playback needs either the raw template query (current player) or the persisted hash (older player).
PLAYBACK_TEMPLATE = "PlaybackAccessToken_Template"
WANTED_QUERIES = {PLAYBACK_TEMPLATE}
WANTED_OPS = {
    "PlaybackAccessToken",
    "VideoAccessToken_Clip",
    "VideoCommentsByOffsetOrCursor",
    "ChannelPointsContext",
    "ClaimCommunityPoints",
    "VideoPlayer_ChapterSelectButtonVideo",
    "StreamMetadata",
    "FollowButton_FollowUser",
    "FollowButton_UnfollowUser",
}


# region signing

def load_private_key(path: Path) -> Ed25519PrivateKey:
    key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise SystemExit(f"{path} is not an Ed25519 key")
    return key


def public_key_b64(key: Ed25519PrivateKey | Ed25519PublicKey) -> str:
    pub = key.public_key() if isinstance(key, Ed25519PrivateKey) else key
    raw = pub.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return base64.b64encode(raw).decode()


def sign_envelope(config: dict, key: Ed25519PrivateKey) -> dict:
    payload = json.dumps(config, separators=(",", ":"), sort_keys=True).encode()
    return {
        "payload": base64.b64encode(payload).decode(),
        "sig": base64.b64encode(key.sign(payload)).decode(),
        "kid": "1",
    }


def open_envelope(envelope: dict, public_b64: str) -> dict:
    payload = base64.b64decode(envelope["payload"])
    pub = Ed25519PublicKey.from_public_bytes(base64.b64decode(public_b64))
    pub.verify(base64.b64decode(envelope["sig"]), payload)  # raises on tamper
    return json.loads(payload)


def write_atomic(path: Path, data: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".tmp-", suffix=".json")
    try:
        with os.fdopen(fd, "w") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(tmp)

# endregion


# region discovery

@dataclass
class Discovery:
    client_id: str | None = None
    user_agent: str | None = None
    hashes: dict[str, str] = field(default_factory=dict)
    queries: dict[str, str] = field(default_factory=dict)


def gql_raw(client_id: str, query: str, variables: dict | None = None, user_agent: str | None = None) -> dict:
    body = json.dumps({"query": query, "variables": variables or {}}).encode()
    req = urllib.request.Request(GQL_URL, data=body, method="POST", headers={
        "Client-Id": client_id,
        "Content-Type": "application/json",
        "User-Agent": user_agent or "Mozilla/5.0",
    })
    with urllib.request.urlopen(req, timeout=20) as res:  # noqa: S310 (fixed https URL)
        return json.loads(res.read())


def find_targets(client_id: str, user_agent: str | None) -> tuple[str | None, str | None, str | None]:
    """A live channel login, one of its VOD ids and a clip slug, to visit."""
    q = """query { streams(first: 5) { edges { node { broadcaster { login
             videos(first: 1, types: [ARCHIVE]) { edges { node { id } } }
             clips(first: 1, criteria: { period: LAST_WEEK }) { edges { node { slug } } } } } } } }"""
    try:
        data = gql_raw(client_id, q, user_agent=user_agent)["data"]["streams"]["edges"]
    except Exception as e:  # noqa: BLE001
        log.warning("Couldn't look up targets: %s", e)
        return None, None, None
    login = vod = clip = None
    for edge in data:
        b = edge["node"]["broadcaster"]
        login = login or b["login"]
        vids = b.get("videos", {}).get("edges", [])
        clips = b.get("clips", {}).get("edges", [])
        vod = vod or (vids[0]["node"]["id"] if vids else None)
        clip = clip or (clips[0]["node"]["slug"] if clips else None)
        if login and vod and clip:
            break
    return login, vod, clip


def discover(timeout_s: int = 25, headful: bool = False) -> Discovery:
    from playwright.sync_api import sync_playwright  # imported lazily so keygen/verify need no browser

    found = Discovery()

    def on_request(request):
        if not request.url.startswith(GQL_URL) or request.method != "POST":
            return
        headers = request.headers
        cid = headers.get("client-id")
        if cid and not found.client_id:
            found.client_id = cid
        found.user_agent = found.user_agent or headers.get("user-agent")
        try:
            body = request.post_data_json
        except Exception:  # noqa: BLE001
            return
        for op in body if isinstance(body, list) else [body]:
            if not isinstance(op, dict):
                continue
            name = op.get("operationName")
            pq = (op.get("extensions") or {}).get("persistedQuery") or {}
            sha = pq.get("sha256Hash")
            if name and sha and len(sha) == 64:
                found.hashes[name] = sha
            elif name in WANTED_QUERIES and isinstance(op.get("query"), str):
                found.queries[name] = op["query"]

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not headful, args=["--mute-audio", "--autoplay-policy=no-user-gesture-required"])
        context = browser.new_context(locale="en-US", viewport={"width": 1280, "height": 800})
        page = context.new_page()
        page.on("request", on_request)

        def visit(url: str, settle: float = 6.0) -> None:
            log.info("Visiting %s", url)
            try:
                page.goto(url, wait_until="domcontentloaded", timeout=timeout_s * 1000)
                page.wait_for_timeout(int(settle * 1000))
            except Exception as e:  # noqa: BLE001
                log.warning("Load failed for %s: %s", url, e)

        visit("https://www.twitch.tv/directory")
        if found.client_id:
            login, vod, clip = find_targets(found.client_id, found.user_agent)
            if login:
                visit(f"https://www.twitch.tv/{login}", settle=10)
            if vod:
                visit(f"https://www.twitch.tv/videos/{vod}", settle=10)
            if clip:
                visit(f"https://clips.twitch.tv/{clip}", settle=6)
        browser.close()

    log.info("Client-ID %s, %d persisted operations, raw queries: %s", found.client_id, len(found.hashes), sorted(found.queries))
    return found


def validate(client_id: str, hashes: dict[str, str], queries: dict[str, str], user_agent: str | None) -> bool:
    """Ask for a playback token with the discovered values; proves they work before publishing."""
    try:
        login, _, _ = find_targets(client_id, user_agent)
        variables = {"isLive": True, "login": login or "twitch", "isVod": False, "vodID": "", "playerType": "site", "platform": "web"}
        if PLAYBACK_TEMPLATE in queries:
            op = {"operationName": PLAYBACK_TEMPLATE, "query": queries[PLAYBACK_TEMPLATE], "variables": variables}
        else:
            op = {"operationName": "PlaybackAccessToken", "variables": variables,
                  "extensions": {"persistedQuery": {"version": 1, "sha256Hash": hashes["PlaybackAccessToken"]}}}
        req = urllib.request.Request(GQL_URL, data=json.dumps(op).encode(), method="POST", headers={
            "Client-Id": client_id, "Content-Type": "application/json", "User-Agent": user_agent or "Mozilla/5.0",
        })
        with urllib.request.urlopen(req, timeout=20) as res:  # noqa: S310
            data = json.loads(res.read())
        token = (data.get("data") or {}).get("streamPlaybackAccessToken") or {}
        ok = bool(token.get("value") and token.get("signature"))
        if not ok:
            log.error("Validation failed: %s", json.dumps(data)[:300])
        return ok
    except Exception as e:  # noqa: BLE001
        log.error("Validation error: %s", e)
        return False

# endregion


# region commands

def cmd_keygen(args) -> int:
    path = Path(args.key)
    if path.exists() and not args.force:
        print(f"{path} already exists. Use --force to replace it (the app will need the new public key).")
        return 1
    key = Ed25519PrivateKey.generate()
    path.parent.mkdir(parents=True, exist_ok=True)
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    print(f"Private key written to {path} (keep it secret, mode 600).")
    print("Add this line to the app's local.properties (or the YIP_CONFIG_PUBLIC_KEY build secret):")
    print(f"yip.configPublicKey={public_key_b64(key)}")
    return 0


def cmd_run(args) -> int:
    lock_path = Path(args.state).with_suffix(".lock")
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with open(lock_path, "w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            log.warning("Another run is in progress; skipping")
            return 0
        return _run_locked(args)


def _run_locked(args) -> int:
    key = load_private_key(Path(args.key))
    out = Path(args.out)
    state_path = Path(args.state)
    previous: dict = {}
    if state_path.exists():
        with contextlib.suppress(Exception):
            previous = json.loads(state_path.read_text())

    found = discover(timeout_s=args.timeout, headful=args.headful)
    if not found.client_id:
        log.error("No Client-ID seen; Twitch may be blocking the browser. Keeping the last config.")
        return 2

    hashes = dict(previous.get("hashes", {}))
    hashes.update({k: v for k, v in found.hashes.items() if k in WANTED_OPS or args.all_ops})
    queries = dict(previous.get("queries", {}))
    queries.update(found.queries)
    if PLAYBACK_TEMPLATE not in queries and "PlaybackAccessToken" not in hashes:
        log.error("Neither the playback template query nor the PlaybackAccessToken hash was seen")
        return 3

    if not args.skip_validate and not validate(found.client_id, hashes, queries, found.user_agent):
        log.error("Discovered values don't work; not publishing")
        return 4

    config = {
        "version": CONFIG_VERSION,
        "generatedAt": int(time.time()),
        "clientId": found.client_id,
        "hashes": dict(sorted(hashes.items())),
        "queries": dict(sorted(queries.items())),
        "userAgent": None,
        "message": args.message if args.message is not None else previous.get("message"),
    }

    changed = (
        config["clientId"] != previous.get("clientId")
        or config["hashes"] != previous.get("hashes")
        or config["queries"] != previous.get("queries")
        or config["message"] != previous.get("message")
    )
    stale = time.time() - previous.get("generatedAt", 0) > args.refresh_hours * 3600
    if not changed and not stale and out.exists() and not args.force:
        log.info("No changes; published file is fresh")
        return 0

    if changed:
        diff = {k: v for k, v in config["hashes"].items() if previous.get("hashes", {}).get(k) != v}
        log.info("Changes: client ID %s, hashes %s", "changed" if config["clientId"] != previous.get("clientId") else "same", list(diff))

    write_atomic(out, json.dumps(sign_envelope(config, key), indent=2) + "\n")
    write_atomic(state_path, json.dumps(config, indent=2) + "\n")
    log.info("Published %s", out)
    return 0


def cmd_verify(args) -> int:
    envelope = json.loads(Path(args.file).read_text())
    pub = args.public_key or public_key_b64(load_private_key(Path(args.key)))
    try:
        config = open_envelope(envelope, pub)
    except Exception as e:  # noqa: BLE001
        print(f"INVALID: {e}")
        return 1
    print(json.dumps(config, indent=2))
    return 0

# endregion


def main(argv: list[str] | None = None) -> int:
    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-v", "--verbose", action="store_true")
    sub = ap.add_subparsers(dest="cmd", required=True)

    k = sub.add_parser("keygen", help="create the signing key")
    k.add_argument("--key", default=str(here / "keys" / "config_ed25519.pem"))
    k.add_argument("--force", action="store_true")
    k.set_defaults(fn=cmd_keygen)

    r = sub.add_parser("run", help="discover, validate, sign, publish")
    r.add_argument("--key", default=str(here / "keys" / "config_ed25519.pem"))
    r.add_argument("--out", default=os.environ.get("YIP_CONFIG_OUT", "/var/www/config.yipchat.dev/yip/playback.json"))
    r.add_argument("--state", default=str(here / "state" / "last.json"))
    r.add_argument("--timeout", type=int, default=25, help="page load timeout, seconds")
    r.add_argument("--refresh-hours", type=float, default=24, help="re-sign even without changes after this long")
    r.add_argument("--message", default=None, help="optional notice shown in the app's About screen")
    r.add_argument("--all-ops", action="store_true", help="publish every operation seen, not just the ones Yip uses")
    r.add_argument("--skip-validate", action="store_true")
    r.add_argument("--headful", action="store_true", help="show the browser (debugging)")
    r.add_argument("--force", action="store_true", help="publish even if nothing changed")
    r.set_defaults(fn=cmd_run)

    v = sub.add_parser("verify", help="check a published file")
    v.add_argument("file")
    v.add_argument("--key", default=str(here / "keys" / "config_ed25519.pem"))
    v.add_argument("--public-key", help="base64 public key instead of reading the private key")
    v.set_defaults(fn=cmd_verify)

    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
