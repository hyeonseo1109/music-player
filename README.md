# Luminara — Android 로컬 음악 플레이어

Luminara는 Galaxy를 포함한 Android 10+ 기기에서 로컬 음원을 재생하는 Kotlin/Jetpack Compose 앱입니다. 기존 React/Vite 디자인 프로토타입은 `src/`에 보존했고, 실제 APK는 네이티브 Android `app/` 모듈에서 빌드합니다.

## 현재 구현

- 단계식 권한 안내, Android 13+ 오디오/알림 권한, 오버레이 설정 이동
- MediaStore 전체 검색과 SAF `ACTION_OPEN_DOCUMENT_TREE` 복수 폴더 영구 권한/하위 폴더 재귀 검색
- Room 음악 인덱스, URI 중복 방지, 재스캔 추가/삭제/변경 반영. 재스캔은 좋아요·재생 수·최근 재생·커스텀 커버·가사를 보존하고, 사라진 파일은 큐에서도 제거
- Media3 `ExoPlayer` + `MediaSessionService` 백그라운드 재생, 미디어 알림/잠금화면/Bluetooth 미디어 키
- Audio Focus 및 audio-becoming-noisy에 의한 유선·USB·Bluetooth 출력 분리 시 일시정지
- 전체 곡, 실시간/한글 초성 검색, 정렬 저장, MusicRow/곡 메뉴
- Now Playing, seek/이전/재생/다음/repeat/shuffle, MediaController와 동기화되는 Mini Player
- drag handle로 순서를 바로 바꾸는 실제 Media3 playlist, Play Next, Room 큐·현재 곡·위치·repeat·shuffle 저장/복원
- 삭제된 곡을 제외한 안전한 큐 복원, 재실행 시 자동 재생하지 않는 paused 복원
- 좋아요/많이 들은 곡 고정 앨범, 사용자 앨범 drag 정렬, 1-depth 폴더 생성·추가·root 이동·이름 변경·해체
- 로컬 일반/싱크 가사, LRCLIB+Luminara 검색, 전체 미리보기 → local copy 편집 → 저장, 직접 입력, LRC
- 싱크 줄 재지정, 이전/다음 줄, ±3초 seek, 전체 offset ±100/500/1000ms
- 실제 `TYPE_APPLICATION_OVERLAY` 플로팅 창, 재생 위치에 맞춘 일반/싱크 가사, 드래그/위치 저장, compact/control 모드
- Photo Picker → 1:1 이동/확대 crop → 미리보기/적용, iTunes artwork grid 검색의 loading/empty/error/retry
- Supabase Anonymous Auth 가사 업로드/검색/싱크 줄 가져오기/추천/신고 UI와 RLS·중복 추천 constraint
- Android 11+ MediaStore 파일 삭제/정보 쓰기 시스템 승인 Activity Result, 취소 시 무변경
- 다크/라이트/시스템 테마, 화면 켜짐 유지와 DataStore 설정, Android 자동 백업 대상에 Room/DataStore 포함

## 구조

```text
app/src/main/java/com/luminara/player/
├── data/       Room entities/DAO/database, DataStore
├── domain/     scan diff, queue restore, album move, repeat policy
├── library/    MediaStore/SAF scan, metadata, Korean search
├── playback/   MediaSessionService, controller connection, queue engine
├── lyrics/     local/synced lyrics and LRC
├── floating/   Android overlay service
├── network/    lyrics/artwork/Supabase providers
└── ui/         Compose theme and feature screens
```

UI → ViewModel → Repository → Room/Android API 방향을 유지합니다. `PlaybackService`의 ExoPlayer가 재생 상태의 단일 원본이며 Activity/ViewModel은 Player를 소유하지 않습니다. 모든 UI는 `MediaController`로 동일한 세션을 관찰하고 조작합니다.

## 핵심 설계

### MediaStore / SAF

전체 검색은 `MediaStore.Audio` content URI를 사용합니다. 선택 폴더 모드는 persistable URI permission을 저장하고 `DocumentFile`로 재귀 탐색합니다. 파일 경로에 의존하지 않아 Scoped Storage 정책을 지킵니다. Room은 빠른 조회와 앱 상태를 위한 인덱스이며 파일의 원본 저장소가 아닙니다.

### 큐 / Play Next / Persistence / Restoration

Media3 playlist가 실제 재생 큐입니다. `playback_queue`와 singleton `playback_session`에는 instance ID, track ID, 순서, current track/index, 위치, repeat, shuffle, 시각을 저장합니다. 5초 주기와 timeline/mode 변경 때 저장하며 100ms마다 쓰지 않습니다. 복원 시 유효한 URI만 MediaItem으로 만들고 `setMediaItems(..., index, position)` 후 pause합니다. 같은 곡을 큐에 여러 번 넣어도 저장한 index를 우선해 정확한 인스턴스를 복원합니다.

Queue drag는 UI 배열만 바꾸지 않고 각 경계를 넘을 때 `MediaController.moveMediaItem`을 호출합니다. 서비스 timeline listener가 즉시 Room에 저장하며, Media3가 현재 MediaItem을 유지하므로 재생을 다시 시작하지 않습니다. Play Next는 MediaSession custom command로 처리합니다. shuffle 중에도 기존 순회는 보존하고 새 곡을 현재 곡 바로 뒤에 배치합니다.

### Audio Focus / 이어폰·Bluetooth 분리

ExoPlayer에 media `AudioAttributes`와 audio focus 자동 처리를 활성화했습니다. `setHandleAudioBecomingNoisy(true)`는 유선/USB/Bluetooth route가 끊길 때 pause합니다. 재연결 시 자동 재생하지 않습니다. 제조사별 route 동작은 아래 Galaxy 체크리스트로 확인하세요.

### Lyrics Sync / Floating Lyrics

일반 가사와 `startTimeMs` 줄 데이터를 별도로 저장합니다. `LrcCodec`은 `.lrc` timestamp, active line, 전체 offset을 처리합니다. Now Playing과 Overlay는 재생 위치로 현재 싱크 줄을 갱신하며, 일반 가사는 줄 단위로 표시합니다. Overlay는 화면 전체가 아닌 `WRAP_CONTENT` 창이라 바깥 앱 터치를 가로채지 않고, 화면 회전/경계 밖 이동 때 위치를 보정합니다. X는 표시만 닫고 설정은 유지합니다.

### Supabase

`supabase/schema.sql`에는 tracks, lyrics, lyric_lines, votes, reports와 RLS가 있습니다. 설정하지 않아도 오프라인 기능은 동작합니다. 연결 시 사용자 전역 Gradle properties에 다음을 넣습니다. 음원 파일은 업로드하지 않습니다.

```properties
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR_PUBLISHABLE_ANON_KEY
```

운영 환경에서는 Supabase Anonymous Auth를 활성화해야 쓰기 RLS가 동작합니다. 2026년 Supabase Data API 권한 변경에 맞춰 schema는 필요한 `SELECT`/`INSERT`만 grant하며 tracks의 client update 권한은 주지 않습니다. 키/keystore는 커밋하지 않습니다.

공유는 음원을 전송하지 않고 normalized title/artist, album, duration, 가사/싱크 줄만 새 contribution으로 삽입합니다. 다른 사용자의 row를 수정하지 않습니다. `lyrics_votes(lyrics_id,user_id)` 기본키와 RLS가 중복 추천을 막고, 신고는 삭제 대신 reports row로 남습니다. 기존 Supabase 프로젝트는 업데이트된 `supabase/schema.sql`을 반영해야 합니다.

## Android 정책상 제약

- 최신 Android는 임의의 잠금화면 UI를 허용하지 않습니다. 표준 MediaSession 잠금화면 카드에 제목/가수/커버/controls가 표시됩니다. 별도 가사 잠금화면은 보안을 우회하지 않고 overlay/알림을 사용합니다.
- MP3/FLAC embedded tag의 범용 in-place 수정 API가 없습니다. 현재 편집은 공식 MediaStore update를 호출합니다. 소유하지 않은 파일은 시스템 승인이 필요하거나 provider가 거절할 수 있고, 실패 시 Room만 몰래 바꾸지 않습니다. Android의 `createWriteRequest`는 쓰기 접근만 부여하며 태그 인코더를 제공하지 않습니다.
- Android 11+ 삭제/쓰기는 `createDeleteRequest`/`createWriteRequest` Activity Result로 승인 후에만 반영합니다. 현재 정보 편집은 MediaStore 표준 컬럼을 갱신하며 MP3/FLAC 바이너리 embedded tag를 직접 재작성하지는 않습니다. SAF/provider 미지원 시 Room만 바꾸지 않고 오류를 표시합니다.
- 공개 API가 없는 사이트 HTML은 scraping하지 않고 LRCLIB/iTunes/설정 가능한 Supabase provider를 사용합니다.

## 빌드 / APK

JDK 17, Android SDK 36, Build Tools 36, ADB가 필요합니다.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

Release는 본인 keystore를 저장소 밖에서 만든 뒤 Android Studio **Generate Signed App Bundle or APK** 또는 로컬 signing config로 `./gradlew assembleRelease`를 실행합니다. `.jks`, `.keystore`, `local.properties`, 비밀키는 `.gitignore` 대상입니다.

## 검증 결과 (2026-08-28)

API 34 Pixel 7 AVD에서는 격리된 `/sdcard/Music/LuminaraValidation` 테스트 폴더의 65초 MP3 3개, FLAC 1개, M4A 1개로 확인했습니다. Galaxy S22 Ultra (`SM-S908N`, Android 16 / SDK 36 / One UI 8.0)에서는 사용자의 기존 2,616곡을 읽기·재생만으로 검증했으며 원본을 복사·삭제·수정하지 않았습니다.

| 항목 | Pixel 7 AVD | Galaxy 실기기 |
| --- | --- | --- |
| MediaStore scan / 중복 없음 / 형식·한글 metadata | PASS (5곡) | PASS (2,616곡) |
| 행 터치 재생·Mini Player·Now Playing·MediaSession | PASS | PASS |
| 30초 이상 재생 뒤 play count/history | PASS (30,652ms, count 1) | 미검증 |
| force-stop 뒤 큐·현재 곡·약 58초 위치 paused 복원 | PASS | PASS (약 39초, paused) |
| Queue 실제 drag 및 Room 순서 저장 | PASS | PASS (Galaxy drag handle, UI·Media3·Room 순서 일치) |
| Play Next (shuffle off/on) | PASS | 미검증 |
| 오버레이 권한·서비스·`TYPE_APPLICATION_OVERLAY` 생성 | 부분 PASS (창/경계 확인, 렌더·드래그 수동 검증 불가) | 미검증 |
| 알림/잠금화면 controls | 이전 AVD 검증 PASS, 이번 회차 재확인 불가 | 알림 PASS (제목·아티스트·이전·재생/일시정지·다음), 잠금화면 미검증 |
| 유선·Bluetooth route / Samsung 절전 | 해당 하드웨어 없음 | 미검증 |
| Photo Picker, MediaStore 승인 삭제·쓰기, artwork/LRCLIB | 미검증 | 미검증 |
| Supabase Anonymous Auth E2E | URL/key/CLI 없음 | 미검증 |

Galaxy 실기기 재생 테스트 뒤 force-stop하여 같은 곡과 약 39초 위치가 자동 재생 없이 복원되는 것을 확인했습니다. Android는 force-stop된 앱의 백그라운드 컴포넌트를 임의로 다시 시작하지 않으므로, 테스트 시 앱을 다시 열어 복원 상태를 확인합니다.

추가 Galaxy E2E에서 5번째 곡 `Blink`를 2번째와 3번째 사이로 실제 long-press drag해 `A, B, Blink, C, D` 순서가 UI와 Media3 playlist, Room `playback_queue`에 모두 반영되는 것을 확인했습니다. 이후 Next는 `B` 다음 `Blink`를 재생했습니다. 이 과정에서 seek 직후 force-stop하면 위치가 저장되지 않는 문제를 발견해 position discontinuity에서 즉시 저장하도록 수정했고, `Blink`의 약 91.5초 위치가 paused 상태로 정확히 복원되는 것을 재검증했습니다.

테스트 음원 투입 예:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push test-a.mp3 test-b.mp3 /sdcard/Music/
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/test-a.mp3
```

## Galaxy 실기기 체크리스트

1. 유선/USB 이어폰 연결 → 재생 → 분리 → 즉시 pause, 스피커로 재생되지 않는지 확인
2. Bluetooth 연결 → 재생 → 연결 해제 → 즉시 pause → 재연결 후 자동 재생되지 않는지 확인
3. 화면 잠금에서 Samsung Media controls/커버/제목/이전·재생·다음 확인
4. background 알림 controls, 플로팅 가사 위치/드래그/닫기/재진입 후 재표시 확인
5. Samsung 배터리 절전 설정에 따른 장시간 background 재생 확인
6. 최근 앱 제거, process kill, 재부팅 뒤 queue/곡/위치가 paused로 복원되는지 확인
7. 1,000곡 이상, 긴 텍스트, 커버 없음, SD 제거, 손상 음원, 오프라인 확인

## Embedded tag 판단

현 시점에는 임의 라이브러리를 추가하지 않았습니다. [TagLib](https://taglib.org/)은 MP3/MP4/AAC/FLAC 등을 폭넓게 다루지만 C++/NDK 통합, content URI의 seekable copy/원자적 교체, 형식별 손상 회귀 테스트가 선행돼야 합니다. `jaudiotagger-android`는 오래된 Java 포크이고, `mp3agic`은 MP3 전용이라 요구 범위를 만족하지 못합니다. 따라서 현재는 시스템 승인 기반 metadata 갱신을 안전한 범위로 유지합니다. Android 공식 [MediaStore 문서](https://developer.android.com/reference/android/provider/MediaStore.html)도 write request가 접근 승인을 위한 API임을 명시합니다.

## 자동 테스트

`app/src/test`는 queue drag 재정렬/복원, 중복 곡 인스턴스 복원, Play Next+재정렬, shuffle 순회 보존, 재스캔 state 병합, 앨범 정렬/폴더 생성·이동·해체, remote 가사 local copy, synced 줄 구조 변경, 추천 중복 guard 등을 검증합니다. 최종 실행: `./gradlew testDebugUnitTest lintDebug assembleDebug` PASS.

## 남은 제품화 작업

- MP3/FLAC/M4A 파일의 범용 embedded tag 바이너리 재작성(현재는 공식 MediaStore metadata update)
- sleep timer, 수동 JSON backup 파일 picker, 장시간/대규모 라이브러리 실기기 성능 튜닝
- 폴토 픽커·MediaStore 승인·드래그를 실제 제스처로 돌리는 Compose instrumented UI test 확장
- 실제 Supabase project/Anonymous Auth가 제공된 환경에서의 schema 적용·end-to-end 운영 검증과 moderation 관리 화면

기존 웹 프로토타입은 `npm run dev`, Android 앱은 Gradle 명령으로 각각 실행합니다.
