#!/usr/bin/env python3
"""
빌드된 리소스팩을 Dropbox 에 올리고, 마인크래프트가 바로 받을 수 있는 직링크를 만든다.

인증 (둘 중 하나)
  1) 단기 토큰 - 앱 콘솔에서 "Generate access token" (4시간 만료)
         set DROPBOX_TOKEN=sl.xxxxx
  2) 갱신 토큰 - 만료 없음. 한 번 발급받아 두면 계속 쓴다
         set DROPBOX_APP_KEY=...
         set DROPBOX_APP_SECRET=...
         set DROPBOX_REFRESH_TOKEN=...

사용
    python tools/upload-dropbox.py
    python tools/upload-dropbox.py --write-config      # 서버 플러그인 config.yml 까지 갱신
"""

import base64
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ZIP_PATH = os.path.join(ROOT, "pack", "GhastRaft.zip")
REMOTE_PATH = "/GhastRaft.zip"
CONFIG_PATH = os.environ.get(
    "PLUGIN_CONFIG",
    r"C:\Users\hamst\Downloads\GCBServer\TestSVR\plugins\GhastRaft\config.yml")


def post(url, headers, data, is_json=True):
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req) as resp:
        body = resp.read()
    return json.loads(body) if is_json and body else body


def access_token():
    refresh = os.environ.get("DROPBOX_REFRESH_TOKEN")
    key = os.environ.get("DROPBOX_APP_KEY")
    secret = os.environ.get("DROPBOX_APP_SECRET")
    if refresh and key and secret:
        basic = base64.b64encode(f"{key}:{secret}".encode()).decode()
        data = urllib.parse.urlencode({
            "grant_type": "refresh_token",
            "refresh_token": refresh,
        }).encode()
        result = post("https://api.dropbox.com/oauth2/token",
                      {"Authorization": f"Basic {basic}",
                       "Content-Type": "application/x-www-form-urlencoded"}, data)
        return result["access_token"]

    token = os.environ.get("DROPBOX_TOKEN")
    if not token:
        print("DROPBOX_TOKEN 또는 DROPBOX_REFRESH_TOKEN/APP_KEY/APP_SECRET 를 설정하세요.")
        sys.exit(1)
    return token


def upload(token, path):
    with open(path, "rb") as fp:
        payload = fp.read()
    args = json.dumps({"path": REMOTE_PATH, "mode": "overwrite",
                       "autorename": False, "mute": True})
    post("https://content.dropboxapi.com/2/files/upload",
         {"Authorization": f"Bearer {token}",
          "Dropbox-API-Arg": args,
          "Content-Type": "application/octet-stream"}, payload)
    print(f"업로드 완료: {REMOTE_PATH} ({len(payload) / 1048576:.1f} MB)")


def shared_link(token):
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    body = json.dumps({"path": REMOTE_PATH,
                       "settings": {"requested_visibility": "public"}}).encode()
    try:
        return post("https://api.dropboxapi.com/2/sharing/create_shared_link_with_settings",
                    headers, body)["url"]
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        if "shared_link_already_exists" not in detail:
            raise
        # 이미 있으면 기존 링크를 가져온다
        body = json.dumps({"path": REMOTE_PATH, "direct_only": True}).encode()
        links = post("https://api.dropboxapi.com/2/sharing/list_shared_links", headers, body)
        return links["links"][0]["url"]


def direct(url):
    """공유 링크를 원본 파일이 그대로 내려오는 주소로 바꾼다."""
    parsed = urllib.parse.urlparse(url)
    query = urllib.parse.parse_qs(parsed.query)
    query.pop("dl", None)
    return urllib.parse.urlunparse((
        parsed.scheme, "dl.dropboxusercontent.com", parsed.path, parsed.params,
        urllib.parse.urlencode(query, doseq=True), parsed.fragment))


def main():
    if not os.path.isfile(ZIP_PATH):
        print(f"먼저 build-pack.py 를 실행하세요. 없음: {ZIP_PATH}")
        return 1

    sha1 = hashlib.sha1()
    with open(ZIP_PATH, "rb") as fp:
        for chunk in iter(lambda: fp.read(1 << 20), b""):
            sha1.update(chunk)
    digest = sha1.hexdigest()

    token = access_token()
    upload(token, ZIP_PATH)
    url = direct(shared_link(token))

    print()
    print(f"URL   : {url}")
    print(f"SHA-1 : {digest}")

    if "--write-config" in sys.argv:
        os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
        with open(CONFIG_PATH, "w", encoding="utf-8") as fp:
            fp.write("resourcepack:\n")
            fp.write(f"  url: \"{url}\"\n")
            fp.write(f"  sha1: \"{digest}\"\n")
            fp.write("  force: true\n")
        print(f"config 갱신: {CONFIG_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
