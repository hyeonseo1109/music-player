# Luminara — Android 로컬 음악 플레이어

Luminara는 Galaxy를 포함한 Android 10+ 기기에서 로컬 음원을 재생하는 Kotlin/Jetpack Compose 앱입니다. 기존 React/Vite 디자인 프로토타입은 `src/`에 보존했고, 실제 APK는 네이티브 Android `app/` 모듈에서 빌드합니다.

## 현재 구현

- 단계식 권한 안내, Android 13+ 오디오/알림 권한, 오버레이 설정 이동
- MediaStore 전체 검색과 SAF `ACTION_OPEN_DOCUMENT_TREE` 복수 폴더 영구 권한/하위 폴더 재귀 검색
- Room 음악 인덱스, URI 중복 방지, 재스캔 추가/삭제/변경 반영
- Media3 `ExoPlayer` + `MediaSessionService` 백그라운드 재생, 미디어 알림/잠금화면/Bluetooth 미디어 키
- Audio Focus 및 audio-becoming-noisy에 의한 유선·USB·Bluetooth 출력 분리 시 일시정지
- 전체 곡, 실시간/한글 초성 검색, 정렬 저장, MusicRow/곡 메뉴
- Now Playing, seek/이전/재생/다음/repeat/shuffle, MediaController와 동기화되는 Mini Player
- 실제 Media3 playlist 추가/이동/비우기/Play Next, Room 큐·현재 곡·위치·repeat·shuffle 저장/복원
- 삭제된 곡을 제외한 안전한 큐 복원, 재실행 시 자동 재생하지 않는 paused 복원
- 좋아요, 사용자 앨범/앨범 폴더 데이터 모델과 생성/곡 추가
- 로컬 일반 가사, 밀리초 싱크 가사, 재생 중 줄별 싱크 입력, LRC codec
- 실제 `TYPE_APPLICATION_OVERLAY` 플로팅 창, 드래그/위치 저장, compact/control 모드
- LRCLIB 가사, iTunes artwork, Supabase 공유 가사 provider와 RLS 스키마
- 다크/라이트/시스템 테마와 DataStore 설정, Android 자동 백업 대상에 Room/DataStore 포함

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

Media3 playlist가 실제 재생 큐입니다. `playback_queue`와 singleton `playback_session`에는 instance ID, track ID, 순서, current track/index, 위치, repeat, shuffle, 시각을 저장합니다. 5초 주기와 timeline/mode 변경 때 저장하며 100ms마다 쓰지 않습니다. 복원 시 유효한 URI만 MediaItem으로 만들고 `setMediaItems(..., index, position)` 후 pause합니다.

`QueueEngine`은 shuffle sequence 안에서 Play Next 항목을 현재 곡 바로 다음에 삽입하며 단위 테스트가 있습니다. Controller도 실제 playlist의 current index+1에 항목을 삽입합니다. Media3가 shuffle permutation을 내부 관리하므로 앱이 완전한 기존 permutation을 Player에 재주입하는 부분은 custom `ShuffleOrder`로 더 강화할 수 있습니다.

### Audio Focus / 이어폰·Bluetooth 분리

ExoPlayer에 media `AudioAttributes`와 audio focus 자동 처리를 활성화했습니다. `setHandleAudioBecomingNoisy(true)`는 유선/USB/Bluetooth route가 끊길 때 pause합니다. 재연결 시 자동 재생하지 않습니다. 제조사별 route 동작은 아래 Galaxy 체크리스트로 확인하세요.

### Lyrics Sync / Floating Lyrics

일반 가사와 `startTimeMs` 줄 데이터를 별도로 저장합니다. `LrcCodec`은 `.lrc` timestamp, active line, 전체 offset을 처리합니다. Overlay는 화면 전체가 아닌 `WRAP_CONTENT` 창이라 바깥 앱 터치를 가로채지 않습니다. 앱 foreground에서는 숨고 background 진입 시 설정이 ON이면 표시합니다. X는 표시만 닫고 설정은 유지합니다.

### Supabase

`supabase/schema.sql`에는 tracks, lyrics, lyric_lines, votes, reports와 RLS가 있습니다. 설정하지 않아도 오프라인 기능은 동작합니다. 연결 시 사용자 전역 Gradle properties에 다음을 넣습니다. 음원 파일은 업로드하지 않습니다.

```properties
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR_PUBLISHABLE_ANON_KEY
```

운영 환경에서는 Supabase Anonymous Auth를 활성화해야 쓰기 RLS가 동작합니다. 키/keystore는 커밋하지 않습니다.

## Android 정책상 제약

- 최신 Android는 임의의 잠금화면 UI를 허용하지 않습니다. 표준 MediaSession 잠금화면 카드에 제목/가수/커버/controls가 표시됩니다. 별도 가사 잠금화면은 보안을 우회하지 않고 overlay/알림을 사용합니다.
- MP3/FLAC embedded tag의 범용 in-place 수정 API가 없습니다. 현재 편집은 공식 MediaStore update를 호출합니다. 소유하지 않은 파일은 시스템 승인이 필요하거나 provider가 거절할 수 있고, 실패 시 Room만 몰래 바꾸지 않습니다.
- Android 11+ 삭제/쓰기 승인은 `createDeleteRequest`/`createWriteRequest` PendingIntent가 필요합니다. 삭제 UI와 이미지 crop/바이너리 태그 재작성은 Activity result 연결이 더 필요한 영역입니다.
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

## 에뮬레이터 검증 결과

API 34 Pixel 7 AVD에서 다음을 확인했습니다.

- APK 설치/Activity cold launch/온보딩
- MediaStore에서 실제 MP3 2곡 검색 및 embedded title/artist 표시
- 행 터치 재생, Mini Player, Now Playing
- MediaSession `PLAYING`, 2곡 queue, metadata
- public MediaStyle notification의 previous/pause/next
- 재설치/서비스 재생성 후 queue 2곡, 현재 곡, 약 12초 위치를 paused로 복원
- 실제 테스트 중 발견한 Player wrong-thread crash 수정 후 fatal exception 없음

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

## 자동 테스트

`app/src/test`는 한글 초성/대소문자·공백 검색, queue reorder, Play Next, shuffle+Play Next, repeat, lyric lookup/offset/LRC round trip, play count threshold, album folder move, rescan diff, missing track queue restore, position과 shuffle sequence 복원을 검증합니다.

## 남은 제품화 작업

현재 APK는 핵심 로컬 재생·스캔·백그라운드 세션·알림·큐 복원·앨범 데이터·가사 싱크·오버레이를 실제 Android API에 연결한 개발 빌드입니다. Play Store 수준에는 drag gesture queue/앨범 정렬, delete/write PendingIntent 결과 처리, 이미지 crop/선택 확인 UI, remote 가사/커버 결과 UI, sleep timer, 수동 JSON backup picker, Supabase anonymous 업로드/투표/신고 UI, instrumented UI tests가 더 필요합니다. 이 항목들은 동작하는 척하는 placeholder로 숨기지 않았습니다.

기존 웹 프로토타입은 `npm run dev`, Android 앱은 Gradle 명령으로 각각 실행합니다.
