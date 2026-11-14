# Storage Tracking Android v1.0 — 사전 코드 및 환경 분석 (ANALYSIS)

> 확인 시점: 2026-11-14 (Asia/Seoul)

## 1. 대상 및 참조 저장소 현황

| 역할 | 저장소 URL | 브랜치 | 최신 원격 `main` 커밋 SHA | 상태 요약 |
| --- | --- | --- | --- | --- |
| **구현 대상 (읽기/쓰기)** | `https://github.com/cjftya/storage_tracking_android.git` | `main` (작업: `feat/android-v1.0`) | `56f1f31615ef771dd9f067577f95eb96b3425bcf` | `.gitignore`, `README.md`만 존재 (앱 코드 없음) |
| **기능 기준 (읽기 전용)** | `https://github.com/cjftya/storage_tracking.git` | `main` | `10e1138e5da9c27711cf4a58dcff66e35edfd2c2` | 지시서 스냅샷(`35cdbfc`) 이후 PR #1(`505de78`)이 병합되어 **v1.2 독립 폴더 스캐너가 이미 구현됨** |
| **빌드 기준 (읽기 전용)** | `https://github.com/cjftya/jelly_markdown.git` | `main` | `f445c130506213d305d3045b7c67d3a9b1b62727` | AGP 9.1.1, Gradle 9.3.1, Daemon JDK 21, Java 11, compileSdk 36.1 / targetSdk 36 / minSdk 26 |

---

## 2. PC Storage Tracking (`10e1138`) 분석 결과

### 2.1 핵심 모듈별 동작 확인

- **파일 스캔 엔진 (`storage_scanner/core/`)**
  - `models.py`: `ScanConfig`(기본 `min_size_bytes = 100 * 1024 * 1024`, `batch_size = 512`, `flush_interval = 0.25s`, `progress_interval = 0.2s`, `cancellation_check_interval = 128`), `FileMatch(path, size_bytes)`, `ScanProgress`, `ScanSummary`.
  - `scanner.py`: 단일 워커 반복(명시적 스택) 순회. 심볼릭 링크를 따라가지 않으며(`follow_symlinks=False`), 일반 파일(`S_ISREG`)의 논리적 크기(`st_size >= min_size_bytes`)만 검사해 `FileMatch` 배치로 전달. 파일 내용은 읽지 않음.
  - `results.py` (`MemoryResultStore`): 일치한 파일과 상위 폴더 체인만 생성(`O(일치 파일 + 조상 폴더)`). 상위 폴더의 `size_bytes`는 **조건에 일치한 하위 파일들의 합계**만 누적. 탐색 순서 유지.
- **폴더 스캔 엔진 (`storage_scanner/folder/`)**
  - `models.py`: `FolderConfig`(최소 파일 크기 설정 없음), `DirectoryDelta(path, direct_bytes)`, `FolderProgress`, `FolderSummary`.
  - `scanner.py`: 단일 워커 반복 순회. 디렉터리 내 모든 일반 파일의 논리적 크기를 `direct_bytes`로 합산해 `DirectoryDelta` 배치로 전달. 개별 파일 결과 객체를 생성·보존하지 않음.
  - `results.py` (`FolderResultStore`): 폴더 노드만 보관(`O(결과 폴더 수)`). 각 `DirectoryDelta.direct_bytes`를 해당 폴더의 `direct_bytes`에 더하고, 해당 폴더와 상위 조상들의 `size_bytes`에 **정확히 한 번씩만** 누적. 0B 하위 폴더는 생략하되 선택된 루트는 0B여도 유지. 상태는 스캔 중 `"scanning"`(표시: `aggregating`), 정상 완료 시 `"complete"`, 취소·오류 발생 시 `"partial"`. 완료(`finish`) 시 부모별 자식 리스트를 `(-size_bytes, key)` 내림차순 정렬.
- **작업 수명 및 UI (`storage_scanner/gui/`, `folder/controller.py`)**
  - `FolderController`와 `ScanController`는 각각 독립된 워커 스레드 + 크기 8 제한 큐 + 수집(collector) 스레드를 사용하며, 앱 수준의 단일 `scan_lock`만 공유해 동시 순회를 방지.
  - 기본 모드는 `folder`(`Folder scan`), 선택 모드는 `file`(`Large file scan`). 모드 전환 시 기존 작업을 취소·초기화하며 자동 시작하지 않고 `Scan / Restart` 조작 시 시작.
  - 약 1,000ms 타이머 주기로 진행 상태와 변경된 트리 노드를 반영하며, 폴더당 200개 단위 페이지네이션(`Load next 200…`)을 적용.

### 2.2 PC 기능 대조 및 Android 구현 분류

| 구분 | 항목 및 분석 내용 |
| --- | --- |
| **1. PC 코드(`10e1138`)에서 실제 구현된 것** | - 기본 **Folder scan**(`storage_scanner/folder/`)과 선택 **Large file scan**(`storage_scanner/core/`)의 완전 독립 구조(AST 테스트로 상호 import 금지 검증)<br>- 단일 폴더 루트 선택(`Select folder` / `Clear`), 줄 단위 제외 경로(`Excluded paths`, 경로 경계 일치 `PathFilter`), 볼륨 새로고침(`Refresh drives`)<br>- 폴더 모드: 모든 일반 파일 논리 크기 합산, 최소 크기 입력 비활성화, 완료 시 크기 내림차순 정렬, `aggregating` / `complete` / `partial` 표시<br>- 파일 모드: 기본 100 MiB 이상(`>=`) 파일 탐색, 일치 파일 및 상위 폴더 합계만 표시, 탐색 순서 유지<br>- 1,000ms 주기 상태/부분 결과 갱신, 폴더별 200개 페이지 로딩(`Load next 200…`), 취소 시 마지막 배치 반영 및 부분 결과 유지, 완료 요약 알림, 폴더 열기(`Open folder`) |
| **2. PC v1.2로 논의됐으나 미구현된 것** | - 지시서 작성 스냅샷(`35cdbfc`) 당시에는 미병합 상태였으나, **현재 최신 원격 `main`(`10e1138`)에 PR #1이 병합되어 논의된 v1.2 사양이 모두 실제 코드로 구현되어 있음.** |
| **3. Android에서 그대로 구현할 수 있는 것** | - `FolderScanner`/`FolderResultStore`/`FolderScanController`와 `FileScanner`/`FileResultStore`/`FileScanController`의 철저한 분리 및 공유 `scanLock` 기반 단일 순회 직렬화<br>- 단일 워커 비재귀(명시적 스택) 탐색, 심볼릭 링크 미추적, 일반 파일의 논리적 바이트(`length()`) 합산(파일 본문 미열람)<br>- 폴더 모드(전체 파일 합산, 최소 크기 무관, 0B 하위 폴더 생략·루트 유지, 완료 시 내림차순 정렬, 진행 중/부분/완료 상태 명시) 및 파일 모드(기본 100 MiB `>=`, 일치 파일 합계만 표시)<br>- 경로 경계 기반 제외 경로 필터, 약 1,000ms 주기 UI 스로틀 갱신, 200개 단위 지연/페이지 확장(`Load next 200…`), 실행 ID·세대(generation) 격리, 완료 요약 다이얼로그 |
| **4. Android 저장소 정책으로 동일 구현이 불가하거나 제한되는 것** | - Windows API / Linux `/proc/self/mountinfo` 대신 Android `StorageManager.getStorageVolumes()` 및 `Environment` API로 공유 저장소·이동식(SD/USB) 볼륨을 탐색해야 함<br>- Android 11(API 30)+에서는 `MANAGE_EXTERNAL_STORAGE` 특별 권한(`Environment.isExternalStorageManager()`) 및 시스템 설정 Intent 안내가 필요하며, Android 8~10(API 26~29)에서는 `READ_EXTERNAL_STORAGE` 런타임 권한 분기가 필요함<br>- 권한이 허용되어도 Android 보안 정책상 앱 비공개 영역(`/data/data` 등) 및 Android 11+의 `Android/data`, `Android/obb` 하위는 접근이 거부될 수 있음(우회 금지, `errors` 증가 및 `partial` 상태로 표시)<br>- Android 11+ SAF 폴더 선택기는 볼륨 루트나 `Download` 선택이 제한되므로, 앱 내에서 탐지된 볼륨 루트 선택 및 하위 폴더 탐색/선택 UI를 직접 제공해야 함<br>- 데스크톱 파일 탐색기 대신 Android Intent로 폴더 열기를 시도하되, 기기 파일 관리자가 직접 경로 열기를 지원하지 않으면 경로 안내 및 실패 안내를 표시함 |

---

## 3. Gradle / SDK / JDK 선택 근거 및 프로젝트 초기화 계획

`cjftya/jelly_markdown` (`f445c13`)의 검증된 빌드 구성을 그대로 유지한다.

- **AGP**: `9.1.1` / **Gradle**: `9.3.1` (`gradle-wrapper.jar` 포함)
- **Gradle Daemon JDK**: `21` (`gradle/gradle-daemon-jvm.properties`, 로컬 OpenJDK 21.0.10 확인 완료)
- **Java 호환성**: `JavaVersion.VERSION_11`
- **SDK 버전**: `compileSdk` `release(36) { minorApiLevel = 1 }`, `targetSdk = 36`, `minSdk = 26`
- **패키지 및 앱 식별자**: `com.core.storagetracking` (`rootProject.name = "StorageTracking"`)
- **의존성 원칙**:
  - `commonmark` 및 Markdown/WebView/TOC/Editor/Theme 관련 코드·리소스 전량 제외
  - `appcompat`, `material`, `constraintlayout` 유지 + 네이티브 트리 리스트와 수명 관리를 위한 `androidx.recyclerview:recyclerview`, `androidx.lifecycle:lifecycle-viewmodel`만 최소 추가

---

## 4. Android 저장소 접근 제약·호환성·권한 처리 방안

1. **권한 분기**:
   - **Android 11+ (API 30+)**: `AndroidManifest.xml`에 `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />` 선언. `Environment.isExternalStorageManager()`로 허용 여부를 확인하고, 미허용 시 권한 상태 배너와 설정 이동 버튼(`Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`, 실패 시 `Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` 폴백)을 제공한다. 거부 상태에서도 강제 종료하지 않는다.
   - **Android 8~10 (API 26~29)**: `READ_EXTERNAL_STORAGE`(`maxSdkVersion="29"`) 선언 및 런타임 권한 요청, Android 10(API 29) 호환을 위해 `android:requestLegacyExternalStorage="true"` 설정.
2. **볼륨 및 폴더 루트 선택**:
   - `StorageManager.getStorageVolumes()`를 사용해 마운트된 공유 저장소(내부 공유 저장소, SD 카드, USB OTG 등)의 실제 경로를 탐지한다(API 30+ `StorageVolume.getDirectory()`, API 26~29 리플렉션/환경 경로 폴백 및 `Environment.getExternalStorageDirectory()`).
   - SAF의 루트 선택 제한을 피하기 위해, 접근 가능한 스토리지 볼륨 목록에서 루트를 바로 선택하거나 앱 내 읽기 전용 디렉터리 브라우저 다이얼로그로 하위 폴더 하나를 선택할 수 있게 한다.
3. **링크·경계·오류 정책**:
   - 심볼릭 링크 여부를 `Files.isSymbolicLink(Path)`(API 26+)로 확인하여 디렉터리·파일 링크를 따라가지 않으며 순환 참조와 중복 합산을 방지한다.
   - 접근 거부(`SecurityException`, `AccessDeniedException`, `File.listFiles() == null`), 스캔 중 삭제된 항목, 볼륨 분리 상황을 모두 `errors` 카운트에 반영하고 폴더 모드에서는 결과를 `partial`(불완전한 합계)로 표시한다.

---

## 5. 폴더·파일 스캐너 분리 설계 및 위험 관리

```text
app/src/main/java/com/core/storagetracking/
  ui/                 # MainActivity, FolderPickerDialog, TreeNodeAdapter
  viewmodel/          # ScannerViewModel (UI 상태 조정, 1000ms 주기 폴링, 수명주기 유지)
  scan/
    folder/           # FolderConfig, DirectoryDelta, FolderProgress, FolderSummary,
                      # FolderScanner, FolderResultStore, FolderScanController
    file/             # FileScanConfig, FileMatch, FileScanProgress, FileScanSummary,
                      # FileScanner, FileResultStore, FileScanController
    common/           # PathFilter, SizeConverter (모드 비의존 순수 값/경로 유틸리티)
  storage/            # StorageAccessManager, StorageVolumeInfo, StorageVolumeProvider
  util/               # SizeFormatter
```

- **철저한 분리**: `scan.folder` 패키지와 `scan.file` 패키지는 서로를 절대 import하지 않는다(단위 테스트에서 소스/import 분리 검증). 결과 저장소, 진행 상태, 작업 ID, 컨트롤러가 완전히 독립된다.
- **메모리 위험 방어**:
  - `FolderScanner`는 파일별 객체를 만들지 않고 디렉터리별 직접 바이트 증분(`DirectoryDelta`)만 배치로 발행하며, `FolderResultStore`는 양수 바이트가 있는 폴더 체인과 루트만 보관한다.
  - `FileScanner`는 `size >= minSizeBytes` 조건을 만족하는 파일만 `FileMatch`로 발행하며, 불일치 파일 객체를 메모리에 누적하지 않는다.
  - UI 리스트(`RecyclerView`)는 현재 펼쳐진 폴더의 로드된 페이지(200개 단위 + `Load next 200…` 행)만 평탄화(flatten)하여 표시한다.

---

## 6. 구현 범위와 구현하지 않을 범위

- **구현할 범위 (Android v1.0)**:
  - 기본 폴더 스캔 모드(전체 일반 파일 논리 크기 합산, 완료 후 크기 내림차순 정렬, `aggregating`/`complete`/`partial` 표시)
  - 선택 파일 스캔 모드(지정 MiB 이상 `>=` 파일 탐색, 기본 100 MiB, 일치 파일 합계 표시)
  - 저장소 접근 권한 확인·설정 안내, 볼륨 새로고침, 단일 루트 폴더 선택/해제, 줄 단위 제외 경로 설정
  - 스캔 시작/재시작, 취소, 약 1초 주기 진행 상태·부분 결과 갱신, 200개 단위 트리 펼치기·더 보기, 폴더 열기(가능 범위), 완료 요약 알림
- **구현하지 않을 범위 (금지 사항)**:
  - 파일 내용 읽기, 해시 계산, 중복 파일 검사
  - 파일/폴더 삭제, 이동, 이름 변경, 자동 정리 기능
  - 실시간 파일시스템 감시, 백그라운드 상시 스캔, 클라우드/계정/서버 연동, 광고/분석/추적 SDK
  - 시스템/앱 비공개 영역 루팅·우회 접근
