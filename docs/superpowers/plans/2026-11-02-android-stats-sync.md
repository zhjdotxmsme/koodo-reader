# Android Statistics and Sync Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 扩展本地阅读统计并将元数据、进度、标注、设置和统计纳入统一版本化同步快照，预留 WebDAV/本地文件夹/S3/MinIO 适配边界。

**Architecture:** 复用现有 `ReadingSessionRepository` 和 `koodo-stats.db`，新增聚合只读会话/标注。同步仅操作 BackupBundle 兼容的快照 DTO，不实现网络客户端或后台同步。

**Tech Stack:** Kotlin、Room、Compose/M3、StateFlow、JUnit 5、现有 core:dbio 和 feature:stats。

## Global Constraints

- 不新增重复 DailyStats 物理表；统计由现有会话和标注聚合。
- 进入前台计时，退出/后台停止，每 30 秒持久化；复用现有时钟、跨午夜和 idle 逻辑。
- 不迁移/删除旧 WebView 数据，保留原生 Room 与统计库。
- 不实现 WebDAV、S3/MinIO 客户端、账号、自动上传或冲突 UI。
- 快照必须版本化并可与桌面/后续 PC mapper 往返转换。

## File Map

- `android/feature/stats/src/main/kotlin/com/koodoreader/feature/stats/`：扩展聚合模型、每日指标和仓库。
- `android/feature/stats/src/main/kotlin/com/koodoreader/feature/stats/ui/`：范围选择、分布图和排行。
- `android/core/sync/src/main/kotlin/com/koodoreader/core/sync/`：版本化快照和传输接口。

---

### Task 1: 统计会话、每日指标与 30 秒持久化

**Files:**
- Create: `android/feature/stats/src/main/kotlin/com/koodoreader/feature/stats/StatsDomain.kt`
- Create: `android/feature/stats/src/main/kotlin/com/koodoreader/feature/stats/AnnotationStatsStore.kt`
- Modify: `android/feature/stats/src/main/kotlin/com/koodoreader/feature/stats/ReadingSessionRepository.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/ReadingSessionClock.kt`
- Test: `android/feature/stats/src/test/kotlin/com/koodoreader/feature/stats/StatsDomainTest.kt`
- Test: `android/feature/stats/src/test/kotlin/com/koodoreader/feature/stats/ReadingSessionRepositoryTest.kt`

**Interfaces:**
- Produces: `ReadingSessionView`、`DailyStatsView`、`StatsRange(SEVEN_DAYS, THIRTY_DAYS, ALL)`。
- Produces: `AnnotationStatsStore.events(): List<AnnotationEvent>`，由 app Room DAO 适配。
- 保留 `ReadingSessionRepository.DEFAULT_FLUSH_INTERVAL_MILLIS = 30_000L`。

- [x] **Step 1: 写失败统计测试**

```kotlin
@Test
fun `daily stats combines sessions and annotation events without double counting`() {
    val result = StatsDomain.daily(
        day = IsoDay(2026, 11, 2),
        sessions = listOf(ReadingSession("s", "b", IsoDay(2026, 11, 2), 120)),
        events = listOf(AnnotationEvent("b", AnnotationEventKind.NOTE, IsoDay(2026, 11, 2))),
    )
    assertEquals(120, result.totalDurationSeconds)
    assertEquals(1, result.notesAddedCount)
    assertEquals(0, result.highlightsAddedCount)
}
```

覆盖今日/本周/本月/累计、小时分布、格式分布、书籍最后阅读、Top 10、跨午夜、后台暂停和每 30 秒 flush；现有 flush 测试不得删弱。

- [ ] **Step 2: 确认失败**

Run: `./gradlew :feature:stats:test --tests '*StatsDomainTest' --tests '*ReadingSessionRepositoryTest'`
Expected: FAIL，新聚合模型不存在。

- [ ] **Step 3: 实现纯聚合和会话视图**

```kotlin
enum class AnnotationEventKind { NOTE, HIGHLIGHT }
data class AnnotationEvent(val bookKey: String, val kind: AnnotationEventKind, val day: IsoDay)

data class DailyStatsView(
    val date: IsoDay, val totalDurationSeconds: Long, val booksReadCount: Int,
    val notesAddedCount: Int, val highlightsAddedCount: Int,
)

object StatsDomain {
    fun daily(day: IsoDay, sessions: List<ReadingSession>, events: List<AnnotationEvent>): DailyStatsView {
        val daySessions = sessions.filter { it.day == day }
        val dayEvents = events.filter { it.day == day }
        return DailyStatsView(day,
            daySessions.sumOf { it.seconds.coerceAtLeast(0) },
            daySessions.map { it.bookKey }.toSet().size,
            dayEvents.count { it.kind == AnnotationEventKind.NOTE },
            dayEvents.count { it.kind == AnnotationEventKind.HIGHLIGHT })
    }
}
```

`ReadingSessionView` 从现有 Entity 映射 bookKey/start/end/seconds，并用 CFI 提供章节/页信息；不改表结构。`ReadingSessionClock` 只调用 repository 的 begin/tick/end，不另设计时器。

- [x] **Step 4: 运行统计全量回归**

Run: `./gradlew :feature:stats:test :app:testDebugUnitTest --tests '*ReadingSession*'`
Expected: PASS；30 秒、后台和跨午夜语义保持不变。

- [ ] **Step 5: 提交**

```bash
git add android/feature/stats android/app && git commit -m "feat(android): extend reading statistics aggregation"
```

### Task 2: 统计范围、趋势、分布与书籍排行 UI

**Files:**
- Modify: `android/feature/stats/src/main/kotlin/com/koodoreader/feature/stats/StatsModels.kt`
- Modify: `android/feature/stats/src/main/kotlin/com/koodoreader/feature/stats/StatsAggregator.kt`
- Modify: `android/feature/stats/src/main/kotlin/com/koodoreader/feature/stats/ui/StatsScreen.kt`
- Create: `android/feature/stats/src/main/kotlin/com/koodoreader/feature/stats/ui/StatsDistributionCharts.kt`
- Modify: `android/app/src/main/java/com/koodoreader/reader/shell/StatsRoute.kt`
- Test: `android/feature/stats/src/test/kotlin/com/koodoreader/feature/stats/StatsAggregatorTest.kt`

**Interfaces:**
- Consumes: Task 1 的会话/标注聚合和现有 `StatsUiState`。
- Produces: `StatsSnapshot.todaySeconds/weekSeconds/monthSeconds`、`formatDistribution`、`hourDistribution`、`bookRanking` 和范围选择。

- [x] **Step 1: 写失败聚合与排行测试**

```kotlin
@Test
fun `top books are sorted by duration then stable title`() {
    val ranked = StatsAggregator.rankBooks(sessions = bookSessions(), titles = mapOf("a" to "Alpha", "b" to "Beta"))
    assertEquals(listOf("b", "a"), ranked.map { it.bookKey })
}
```

覆盖 7/30/全部范围、无数据、格式百分比总和、24 小时桶、笔记/高亮时间和 Top 10 上限。

- [ ] **Step 2: 确认失败**

Run: `./gradlew :feature:stats:test --tests '*StatsAggregatorTest'`
Expected: FAIL，新聚合方法不存在。

- [ ] **Step 3: 实现范围过滤和稳定排行**

```kotlin
data class BookReadingRank(val bookKey: String, val title: String, val seconds: Long, val lastReadMillis: Long)

fun StatsAggregator.rankBooks(
    sessions: List<ReadingSession>, titles: Map<String, String>,
): List<BookReadingRank> = sessions.groupBy { it.bookKey }.map { (key, rows) ->
    BookReadingRank(key, titles[key].orEmpty(), rows.sumOf { it.seconds }, rows.maxOf { it.endMillis })
}.sortedWith(compareByDescending<BookReadingRank> { it.seconds }.thenBy { it.title.lowercase() }).take(10)
```

UI 使用现有设计 token 和 Canvas 绘制折线/柱状/环形图，新增范围 SegmentedButton 和排行卡片；所有数据来自 StateFlow，Loading 时不展示旧值为新结果。

- [x] **Step 4: 运行统计测试和 Compose 构建**

Run: `./gradlew :feature:stats:test :app:compileDebugKotlin`
Expected: PASS；所有统计屏状态有数据/空态。

- [ ] **Step 5: 提交**

```bash
git add android/feature/stats android/app && git commit -m "feat(android): add reading statistics dashboards"
```

### Task 3: 版本化同步快照与多端适配边界

**Files:**
- Create: `android/core/sync/src/main/kotlin/com/koodoreader/core/sync/SyncSnapshot.kt`
- Create: `android/core/sync/src/main/kotlin/com/koodoreader/core/sync/SyncSnapshotCodec.kt`
- Modify: `android/core/sync/src/main/kotlin/com/koodoreader/core/sync/SyncTarget.kt`
- Modify: `android/core/sync/src/main/kotlin/com/koodoreader/core/sync/SyncCoordinator.kt`
- Create: `android/core/sync/src/test/kotlin/com/koodoreader/core/sync/SyncSnapshotCodecTest.kt`
- Modify: `android/core/dbio/src/main/kotlin/com/koodoreader/core/dbio/BackupBundle.kt`

**Interfaces:**
- Produces: `SyncSnapshot(version = 1, books, progress, annotations, bookmarks, settings, statistics)`。
- Produces: `SyncSnapshotCodec.encode/decode`，支持 WebDAV/本地文件夹/S3/MinIO 的传输仅依赖 `SyncTarget`。
- 快照只含 DTO，不含 Room/Android/WebDAV/S3 类型。

- [x] **Step 1: 写失败 round-trip 与版本测试**

```kotlin
@Test
fun `snapshot round trip preserves stats and annotation anchors`() {
    val snapshot = SyncSnapshot.fixture()
    assertEquals(snapshot, SyncSnapshotCodec.decode(SyncSnapshotCodec.encode(snapshot)))
}

@Test(expected = SyncVersionException::class)
fun `newer unsupported snapshot is rejected without dropping data`() {
    SyncSnapshotCodec.decode("""{"version":2}""")
}
```

覆盖空库、元数据编辑、PDF 矩形、统计会话、阅读设置和桌面 mapper；未知版本只报不支持，不自动清空现有数据。

- [ ] **Step 2: 确认失败**

Run: `./gradlew :core:sync:test --tests '*SyncSnapshotCodecTest'`
Expected: FAIL，快照模型/codec 不存在。

- [ ] **Step 3: 实现版本化 DTO 和确定性 JSON**

```kotlin
data class SyncSnapshot(
    val version: Int = 1,
    val books: List<BookSnapshot>, val progress: List<ProgressSnapshot>,
    val annotations: List<AnnotationSnapshot>, val bookmarks: List<BookmarkSnapshot>,
    val settings: Map<String, String>, val statistics: List<ReadingSessionSnapshot>,
)

object SyncSnapshotCodec {
    fun encode(snapshot: SyncSnapshot): String = SyncJson.write(snapshot)
    fun decode(text: String): SyncSnapshot {
        val version = SyncJson.version(text)
        if (version != 1) throw SyncVersionException(version)
        return SyncJson.readSnapshot(text)
    }
}
```

`SyncJson` 复用项目现有无依赖 JSON 编码风格并处理所有 DTO 字段；`BackupBundle` 的桌面适配器负责 DTO 与现有五表/私有偏好映射。`SyncTarget` 只读写字节快照，仍不加入应用运行时依赖或 UI。

- [x] **Step 4: 运行同步、备份和统计回归**

Run: `./gradlew :core:sync:test :core:dbio:test :feature:stats:test`
Expected: PASS；快照往返后元数据、位置、设置和统计完全一致。

- [ ] **Step 5: 提交**

```bash
git add android/core/sync android/core/dbio && git commit -m "feat(android): version cross-device reader sync snapshots"
```
