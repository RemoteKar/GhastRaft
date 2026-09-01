# GhastRaft

Minecraft 1.21.8 (Paper / Purpur) 서버 플러그인. 완전한 공허 우주 맵에서 팀별 **비행 함선**을 몰고 싸우는 팀 대전 모드.

플레이어는 셜커로 만든 갑판 위를 **자유롭게 걸어다니고**, 함선은 그와 무관하게 비행한다. 마인크래프트에 무빙 플랫폼 물리가 없다는 점을 우회하는 것이 이 프로젝트의 핵심 기술 과제였다.

---

## 게임 규칙

- 팀마다 함선 한 척. 승리 조건은 셋 — **적 함선 격침 · 적 조종석 8초 점령 · 적 팀 전원 사망**
- 소행성에서 철·금을 캐 자기 함선에 우클릭으로 보급하고, 그 자원으로 함선을 강화한다
- 공허로 떨어지면 자기 배로 구조되지만 체력 절반을 잃는다 (긴급 인양 업그레이드로 무효화)

### 직업

| 직업 | 체력 | 장비 | 성격 |
|---|---|---|---|
| 해병 | 20 | 철갑옷 · 쇠뇌(신속장전 II) | 표준 · 원거리 |
| 사신 | 16 | 겉날개 · 검은 가죽 · 폭죽 | 유일한 비행 · 침투 |
| 불곰 | 24 | 네더라이트(방어 15) · 나무검 · 돌풍구 | 크기 1.5배 · 밀어내기 |
| SCV | 30 | 용접기 · 네더라이트 곡괭이 · 갑옷 없음 | 함선 수리 · 시설 건설 |
| 함장 | 18 | 금갑옷 · 철검 · 망원경 | 조종 시 함선 강화 |

함장이 **직접 조종석에 앉아 있는 동안** 그 함선은 선체 피해 25% 감소, 이동 속도 1.25배가 된다. 키를 잡은 한 명에게만 적용되므로 갑판에 함장이 여럿 있어도 의미가 없다. 대신 개인 전투력은 가장 낮다.

### 함선 업그레이드

크기 확장 I~III · 속도 증가 I~III · 내구도 증가 I~III · 안정화 · 재생로 · 긴급 인양

### 시설물 (SCV 전용)

| 시설 | 사거리 | 피해 | 동작 |
|---|---|---|---|
| 자동 포탑 | 22 | 2 | 주변 적을 눈덩이로 자동 사격 |
| 미사일 포탑 | 140 | 8 | 조종자 시야 ±50° 안의 적함에 유도 미사일 |

---

## 기술 노트

이 플러그인이 부딪힌 문제와 해법. 대부분 바닐라 코드를 디컴파일해서 찾아낸 것들이다.

### 왜 셜커인가

처음에는 해피가스트로 만들었다. 1.21.6 해피가스트는 `canBeCollidedWith` 안에 **클라이언트 전용 분기**가 있어 "윗면만 밟을 수 있는" 발판이 된다.

```java
// HappyGhast#canBeCollidedWith
if (level().isClientSide() && entity instanceof Player && entity.position().y >= getBoundingBox().maxY)
    return true;
```

문제는 이 판정에 **여유가 0**이라는 것이다. 발이 0.001만 아래로 내려가면 그 즉시 비고체가 되고, 조건이 영원히 거짓이라 복구도 안 된다 — 함선이 조금만 위로 움직여도 탑승자가 통째로 빠졌다.

셜커는 `return this.isAlive();` — **조건 없이 항상 solid**다. 클라이언트와 서버가 같은 판단을 하므로 리바운드도 없고, 닫힌 셜커는 모델이 히트박스를 꽉 채워 타일 사이 유격도 없다.

### 왜 아머스탠드에 태우는가

```java
// Shulker
public InterpolationHandler getInterpolation() { return null; }
// LivingEntity
public InterpolationHandler getInterpolation() { return this.interpolation; }
```

셜커는 **보간을 명시적으로 거부**한다. 좌표를 매 틱 정확히 쏴도 클라이언트가 20번 뚝뚝 끊어서 그린다. 그래서 좌표는 보이지 않는 마커 아머스탠드에만 보내고 셜커는 승객으로 붙인다. 마커 아머스탠드는 크기가 0이라 승객 부착점도 (0,0,0) — 셜커가 받침대 좌표에 1:1로 붙는다. 승객 좌표는 클라이언트가 직접 계산하므로 패킷도 늘지 않는다.

### 탑승자 캐리

마인크래프트에는 무빙 플랫폼 물리가 없다. 함선이 움직이면 갑판 위 사람은 제자리에 남는다.

- **수평** — 폭발 패킷(`ClientboundExplodePacket`)의 knockback으로 속도를 준다. 마찰 상쇄 피드포워드 `v × (1 − f)` 를 반드시 깔아야 한다. 피드백만 쓰면 정상상태가 뗏목 속도의 32%가 되어 계속 밀려난다
- **수직** — 위치 패킷으로 정확히 맞춘다. knockback은 속도에 누적되는 성질이라 대각선 상승에서 반드시 발산한다
- 위치 패킷은 `player.teleport()` 를 쓰면 안 된다. `awaitingPositionFromClient` 때문에 확인 패킷이 올 때까지 **플레이어 입력이 통째로 무시**된다. `ClientboundPlayerPositionPacket` 을 직접 보내되, 패킷 ID는 서버의 `awaitingTeleport`(1부터 증가)와 겹치지 않게 음수를 쓴다 — 겹치면 `invalid_player_movement` 로 킥된다

### 그 밖에

- **닫힌 셜커는 화살을 무시한다** (`isClosed() && AbstractArrow` → `return false`). `hurtServer` 를 직접 가로채 선체 체력으로 환산한다
- **Paper(Moonrise)는 하드콜라이딩 엔티티를 별도 색인으로 관리**한다. 커스텀 충돌 엔티티는 `moonrise$isHardCollidingUncached()` 를 오버라이드해야 충돌 후보에 오른다
- **속성 수정자는 키로 식별**된다. 방어구 네 부위에 같은 키를 쓰면 하나만 적용된다
- 커스텀 사운드는 `CraftPlayer#playSound(Location, String, ...)` 로 울리지 않는 경우가 있다. 콘솔 `playsound` 명령 경로가 확실하다
- 채굴 제한과 어드벤처 모드는 `can_break` 데이터 컴포넌트 하나로 동시에 해결된다

---

## 빌드

Gradle 없이 서버 jar과 `libraries/` 를 직접 클래스패스로 써서 컴파일한다. 인터넷이 필요 없고, 산출물은 paperweight와 동일하게 모장 매핑 + 네임스페이스 마킹이 찍힌다.

```bash
./build.sh                    # 빌드 후 TestSVR/plugins 에 자동 설치
INSTALL=0 ./build.sh          # 빌드만
SVR=/path/to/server ./build.sh
```

`build.gradle.kts` 도 있으나 선택 사항이다(네트워크 필요).

## 리소스팩

```bash
python tools/build-pack.py                      # resoucepack/*.ogg -> 팩 zip + SHA-1
python tools/upload-dropbox.py --write-config   # Dropbox 업로드 + 직링크 + config 갱신
```

Dropbox 토큰은 환경변수로 받는다 (`DROPBOX_TOKEN` 또는 `DROPBOX_APP_KEY`/`SECRET`/`REFRESH_TOKEN`).

## 명령어

```
/ship map                우주 맵 생성 + 정거장으로 이동
/ship team <수>          팀 편성 (2~8)
/ship start [격자]       선박 소환 + 소행성·정거장 산포 + 경기 시작
/ship stop | reset       종료 / 전체 초기화
/ship spawn [격자]       테스트용 단독 선박
/ship pack | book | kit | music | debug | mode | lead
```

## 구조

```
Raft.java        함선 - 갑판 타일, 조종, 캐리, 선체 체력, 격침 연출
Match.java       경기 - 팀, 점령, 자원, 승패, 스코어보드
Kits.java        직업과 지급 장비
Upgrades.java    함선 업그레이드
Turrets.java     SCV 시설물 (포탑 · 미사일)
SpaceMap.java    공허 월드 · 소행성 · 정거장 · 통로
Guide.java       규칙 안내서
Music.java       배경음악
```
