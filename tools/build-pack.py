#!/usr/bin/env python3
"""
리소스팩 빌드.

TestSVR/resoucepack/ 안의 .ogg 를 모아 사운드 팩 zip 을 만든다.
구조는 GCBResourcePackManager 산출물과 동일하다:

    pack.mcmeta
    pack.png                          (있으면 포함)
    assets/minecraft/sounds.json
    assets/minecraft/sounds/ship/bgm/<이름>.ogg

사운드 ID 는 파일명을 그대로 쓴다 -> ship.bgm.1 · ship.bgm.4
.ogg 를 추가하고 다시 돌리면 자동으로 잡힌다.
"""

import hashlib
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.environ.get("PACK_SRC", r"C:\Users\hamst\Downloads\GCBServer\TestSVR\resoucepack")
OUT_DIR = os.path.join(ROOT, "pack")
OUT_ZIP = os.path.join(OUT_DIR, "GhastRaft.zip")

# 소리 경로 접두사 (assets/minecraft/sounds/<PREFIX>/<이름>.ogg)
PREFIX = "ship/bgm"
# 사운드 ID 접두사 (ship.bgm.<이름>)
SOUND_NS = "ship.bgm"

MCMETA = {
    "pack": {
        "pack_format": 46,
        "supported_formats": [9, 99],
        "description": "GhastRaft Resource Pack",
    }
}


def main() -> int:
    if not os.path.isdir(SRC):
        print(f"소스 폴더가 없습니다: {SRC}")
        return 1

    oggs = sorted(f for f in os.listdir(SRC) if f.lower().endswith(".ogg"))
    if not oggs:
        print(f"{SRC} 에 .ogg 파일이 없습니다.")
        return 1

    sounds = {}
    for name in oggs:
        stem = os.path.splitext(name)[0]
        sounds[f"{SOUND_NS}.{stem}"] = {
            "category": "music",
            "sounds": [{
                "name": f"{PREFIX}/{stem}",
                "stream": True,               # 긴 음원은 스트리밍으로 (메모리 절약)
                "attenuation_distance": 256,
            }],
        }

    os.makedirs(OUT_DIR, exist_ok=True)
    with zipfile.ZipFile(OUT_ZIP, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("pack.mcmeta", json.dumps(MCMETA, indent=2, ensure_ascii=False))
        zf.writestr("assets/minecraft/sounds.json",
                    json.dumps(sounds, indent=4, ensure_ascii=False))

        icon = os.path.join(SRC, "pack.png")
        if os.path.isfile(icon):
            zf.write(icon, "pack.png")

        for name in oggs:
            stem = os.path.splitext(name)[0]
            zf.write(os.path.join(SRC, name), f"assets/minecraft/sounds/{PREFIX}/{stem}.ogg")

    sha1 = hashlib.sha1()
    with open(OUT_ZIP, "rb") as fp:
        for chunk in iter(lambda: fp.read(1 << 20), b""):
            sha1.update(chunk)

    size_mb = os.path.getsize(OUT_ZIP) / (1024 * 1024)
    print(f"빌드 완료: {OUT_ZIP}  ({size_mb:.1f} MB)")
    print(f"SHA-1: {sha1.hexdigest()}")
    print("사운드 ID:")
    for key in sounds:
        print(f"  {key}")

    with open(os.path.join(OUT_DIR, "pack.sha1"), "w", encoding="utf-8") as fp:
        fp.write(sha1.hexdigest())
    return 0


if __name__ == "__main__":
    sys.exit(main())
