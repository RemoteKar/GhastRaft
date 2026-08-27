#!/usr/bin/env bash
# 오프라인 빌드: 서버 jar(모장 매핑) + libraries/ 를 그대로 클래스패스로 써서 javac -> jar.
# paperweight 없이도 CustomEntity.jar 와 동일한 형태(모장 네임스페이스 마킹)의 플러그인이 나온다.
set -euo pipefail

SVR="${SVR:-/c/Users/hamst/Downloads/GCBServer/TestSVR}"
MCVER="${MCVER:-1.21.8}"
JBIN="$SVR/java/graalvm/bin"
OUT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$OUT/build"

SERVER_JAR="$SVR/versions/$MCVER/purpur-$MCVER.jar"
[ -f "$SERVER_JAR" ] || { echo "서버 jar 없음: $SERVER_JAR (서버를 한 번 실행해 versions/ 를 생성하세요)"; exit 1; }

# /c/... -> C:/...  (javac @argfile 은 백슬래시를 이스케이프로 해석하므로 슬래시 경로를 쓴다)
win() { printf '%s' "$1" | sed -E 's|^/([a-zA-Z])/|\1:/|'; }

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/res"

CP="$(win "$SERVER_JAR")"
# 같은 아티팩트의 여러 버전이 있으면 최신만 남긴다(adventure-api 가 4.13~4.24 까지 6개 있다).
# 낮은 버전이 먼저 잡히면 showDialog 같은 신규 API 를 못 찾는다.
while IFS= read -r j; do CP="$CP;$(win "$j")"; done < <(
  find "$SVR/libraries" -name '*.jar' | while IFS= read -r j; do
    printf '%s	%s	%s
' "$(dirname "$(dirname "$j")")" "$(basename "$(dirname "$j")")" "$j"
  done | sort -t"$(printf '	')" -k1,1 -k2,2V | awk -F'	' '{ last[$1]=$3 } END { for (a in last) print last[a] }'
)

{
  echo "-encoding UTF-8"
  echo "-nowarn"
  echo "-cp \"$CP\""
  find "$OUT/src/main/java" -name '*.java' | while IFS= read -r f; do echo "\"$(win "$f")\""; done
} > "$BUILD/args.txt"

"$JBIN/javac.exe" -d "$(win "$BUILD/classes")" "@$(win "$BUILD/args.txt")"

cp -r "$OUT/src/main/resources/." "$BUILD/res/"
printf 'paperweight-mappings-namespace: mojang\n' > "$BUILD/manifest.txt"

"$JBIN/jar.exe" --create --file "$OUT/GhastRaft.jar" \
  --manifest "$BUILD/manifest.txt" \
  -C "$BUILD/classes" . -C "$BUILD/res" .

echo "빌드 완료: $OUT/GhastRaft.jar"

if [ "${INSTALL:-1}" = "1" ]; then
  mkdir -p "$SVR/plugins"
  cp "$OUT/GhastRaft.jar" "$SVR/plugins/GhastRaft.jar"
  echo "설치 완료: $SVR/plugins/GhastRaft.jar"
fi
