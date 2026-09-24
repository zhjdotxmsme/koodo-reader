# P6 — Android TTS 语义对照表（desktop `ttsUtil.ts` → `:feature:tts`）

任务卡：`t-muexn66v-0z6jje`「P6：TTS（Android TTS + MediaSession/前台服务）」
模块：`android/feature/tts`（Android library，包名 `com.koodoreader.feature.tts`）
桌面参考：`src/utils/reader/ttsUtil.ts`、`src/components/textToSpeech/*`、`src/utils/common.ts`
（`detectLocalLanguage` / `splitSentences` / `getAllVoices`）、`src/utils/plugins/catalog.ts`（15 个 `type: "voice"` 插件）

> 说明：任务描述里的桌面参考 `plugins/main/voice/*` 在本 workspace 中不存在（`plugins/` 目录整体缺失）。
> 15 个 voice 插件的 **key / displayName / 语音列表结构** 已从 `src/utils/plugins/catalog.ts` 的
> 15 条 `type: "voice"` 记录中提取（见 §3），Android 侧按任务要求原生实现，未查询外部仓库。

---

## 1. 文件清单与职责

| 文件 | 类型 | 职责 | DoD |
|---|---|---|---|
| `build.gradle` | Gradle | Android library 声明 + 依赖（compose / media / datastore / engine:text / engine:feature） | — |
| `TtsModels.kt` | 纯 JVM | 配置、状态、快照、`TtsReadingPosition` 值类型 | [1] |
| `TtsConfigRepository.kt` | 纯 JVM | `TtsConfigStore` port + `TtsConfigCodec`（桌面 key 名）+ 缓存 façade | [1] |
| `DataStoreTtsConfigStore.kt` | Android | DataStore Preferences 适配器（配置 + 断点） | [1][4] |
| `TtsVoiceCatalog.kt` | 纯 JVM | 15 个桌面 voice 标签 + 引擎种类解析 + `pickVoice` | [1] |
| `TtsSentenceSplitter.kt` | 纯 JVM | `detectLocalLanguage` + `splitSentences` 移植 | [1] |
| `EngineProvider.kt` | 纯 JVM | `TtsEngineProvider` port + 引擎排序/回退链 | [1] |
| `EngineEnumerator.kt` | Android | `queryIntentActivities(TTS_SERVICE)` 实现 + `TextToSpeech` 引擎列表 | [1] |
| `TtsSessionController.kt` | 纯 JVM | 播放状态机 + 句队列 + `TtsSpeaker` port | [2] |
| `MediaSessionController.kt` | Android | `MediaSessionCompat` + `PlaybackStateCompat` 映射 | [2][3] |
| `ForegroundTtsService.kt` | Android | 前台服务 + 通知 + `TextToSpeech` 适配 + 音频焦点 | [2] |
| `TtsMediaCommand.kt` | 纯 JVM | `Intent/keyCode → TtsMediaCommand` 映射表、锁屏文案、控制面板状态 | [3] |
| `LockscreenControlsReceiver.kt` | Android | `ACTION_MEDIA_BUTTON` / 通知按钮广播入口 | [3] |
| `TtsControlSheet.kt` | Android (Compose) | 锁屏/应用内控制弹窗骨架（Material3） | [3] |
| `BookmarkResumeController.kt` | 纯 JVM | 断点锚点、模糊匹配、`TtsResumeStore` port、`:engine:text` 适配 | [4] |
| `src/test/kotlin/**`（7 个 JUnit4 测试 + `TtsSelfCheck.kt` + `Fakes.kt`） | 纯 JVM | 上述纯逻辑的全部单测（JVM only） | [1][2][3][4] |

**分层原则（P6 硬约束）**：所有 *决策* 逻辑放在无 `android.*` import 的纯 Kotlin 文件中，
Android 文件只做 framework 适配。因此 `queryIntentActivities` 被抽象为 `TtsEngineProvider` port，
`TextToSpeech` 被抽象为 `TtsSpeaker` port，单测注入 fake 即可，**不需要 Android SDK / 模拟器 / Robolectric**。

---

## 2. 配置语义对照（DoD [1]）

| 桌面 | 位置 | Android | 说明 |
|---|---|---|---|
| `ConfigService.getReaderConfig("voiceName")` | component.tsx:534 | `TtsConfigKeys.VOICE_NAME` → DataStore `voiceName` | 同名同义 |
| `getReaderConfig("voiceEngine")` | component.tsx:535 | `TtsConfigKeys.VOICE_ENGINE` | `system` 或 15 个插件 key（见 §3） |
| `getReaderConfig("voiceSpeed")` | component.tsx:560、1097 | `TtsConfigKeys.VOICE_SPEED` | 桌面存字符串 `"1"`；`parseFloat(x) \|\| 1` → `TtsRate.fromDesktop()`（空/NaN/≤0 → 1） |
| `getReaderConfig("voiceLocale")` | component.tsx:990 | `TtsConfigKeys.VOICE_LOCALE` | 桌面默认 `navigator.language`；Android 默认空（由引擎/系统语言决定） |
| `speed * 100 - 100`（插件百分比） | ttsUtil.ts:580、601 | `TtsRate.desktopPercent(speed)` | 仅保留为兼容/日志语义，Android 直接 `setSpeechRate(speed)` |
| —（桌面无） | — | `ttsEnginePackage` | Android TTS 引擎包名，如 `com.google.android.tts` |
| —（桌面无） | — | `ttsPitch` / `ttsVolume` / `ttsChunkLength` | `setPitch` / `KEY_PARAM_VOLUME` / 分句长度覆盖 |

**持久化**：DataStore Preferences（`koodo_tts_config`），键名与桌面完全一致，
因此桌面配置可以逐字段映射进 Android；`TtsConfigCodec` 是唯一编解码点，测试覆盖
round-trip、桌面字符串形态、垃圾值降级、越界 clamp。

**引擎选择回退链**（`TtsEngineResolver`，桌面无对应逻辑）：
配置包名仍安装 → 平台默认引擎（`TextToSpeech.getDefaultEngine()`）→ 排序最高者
（`com.google.android.tts` 优先）→ `null`（交给系统挑）。用户卸载引擎或跨设备恢复配置后不会静音。

---

## 3. 15 个桌面 voice 插件的 Android 语义（DoD [1]）

桌面 `voiceList` / `plugin.key` 三元组 `{text, voiceName, voiceEngine}`（ttsUtil.ts:50-54、91-99）
在 Android 侧**不做插件运行时**（任务约束），而是降级为"标签 + 配置兼容"：

| # | plugin key（= `voiceEngine`） | displayName | Android 处置 |
|---|---|---|---|
| 1 | `azure-tts-voice-plugin` | Azure TTS | 标签，`available=false` |
| 2 | `amazon-polly-voice-plugin` | Amazon Polly | 标签 |
| 3 | `minimax-tts-voice-plugin` | MiniMax TTS | 标签 |
| 4 | `openai-tts-voice-plugin` | OpenAI TTS | 标签 |
| 5 | `qwen-tts-voice-plugin` | 千问 TTS | 标签 |
| 6 | `zhipu-tts-voice-plugin` | 智谱 TTS | 标签 |
| 7 | `elevenlabs-tts-voice-plugin` | ElevenLabs TTS | 标签 |
| 8 | `grok-tts-voice-plugin` | grok TTS | 标签 |
| 9 | `mimo-tts-voice-plugin` | MiMo TTS | 标签 |
| 10 | `volcengine-tts-voice-plugin` | 豆包 TTS | 标签 |
| 11 | `multitts-voice-plugin` | MultiTTS | 自建服务（桌面走局域网 8774） |
| 12 | `ttsserver-voice-plugin` | TTS Server | 自建服务 |
| 13 | `chatttsui-voice-plugin` | ChatTTS UI | 自建服务 |
| 14 | `chattts-voice-plugin` | ChatTTS | 自建服务 |
| 15 | `coquitts-voice-plugin` | Coqui TTS | 自建服务 |

- `voiceEngine = "system"` → Android `TextToSpeech`（+ 已选引擎包）。
- `voiceEngine = <插件 key>` → `TtsSelection.DesktopOnly`：UI 显示但置灰；朗读回退系统引擎。
  **桌面同款回退**：`component.tsx:151-157` 在找不到 voice 时 `setReaderConfig("voiceEngine", "system")`。
- `official-ai-voice-plugin`（已被桌面移除、但仍在代码与历史配置中出现，见 `store/actions/manager.tsx:359`）
  作为 legacy key 保留，标签标注 `(removed)`；不属于 15 个插件，但同样回退系统引擎。
- 原生语音选择（`pickVoice`，`component.tsx:783-801` 的 `nativeVoices`）：先精确 name，
  再 `voiceLocale` 全标签、再语言前缀，最后引擎列表第一项（桌面 `nativeVoices[0]`）。

---

## 4. 文本管线语义对照（DoD [1]）

| 桌面 | Android | 一致性 |
|---|---|---|
| `detectLocalLanguage(text)`（common.ts:1032） | `TtsLanguageDetector.detect` | CJK 比例 ≤ 0.3 → `en`；否则按 zh/ja/ko 计数最大者。阈值常量与判定顺序一致 |
| `splitSentences(text, maxLength?)`（common.ts:1172） | `TtsSentenceSplitter.split` | 默认长度 `en=150`，其他 `50`；trim、去空、长句按 `(?<=[,，;；:：、…])` 切分后贪心合并、末段 `[\p{L}\p{N}]` 过滤 —— 全部一致 |
| `Intl.Segmenter(lang, {granularity:"sentence"})` | 终止符扫描（`.!?。！？…\n`）+ 缩写保护 | **有意偏差**：JVM 无等价 API（ICU `BreakIterator` 行为随 Android 版本漂移）。小数 `3.14`、省略号 `...`（`Wait... really?` 不切分）均按 ICU 习惯保护；引号 `"”」』）` 归入前句 |
| `nl.flatMap(splitLongSentence).filter(/[\p{L}\p{N}]/)` | 同序实现（含 offset 版本 `splitWithOffsets`） | 一致；offset 供高亮与断点使用 |
| `TTSUtil.applyTextRules`（ttsUtil.ts:279，replace/delete） | `ForegroundTtsService` 调 `:engine:feature` 的 `TextRuleEngine`（P6 卡已实现同语义引擎） | 规则引擎复用，未在本模块重复实现 |
| 多角色朗读 | 单语音 | 桌面已在本地全功能模式下移除多角色（component.tsx:530 注释），Android 保持一致 |

---

## 5. 播放、锁屏与断点（DoD [2][3][4]）

### 5.1 状态机（桌面 `isAudioOn` / `isPaused` / `pausedMidSentence` → `TtsPlaybackState`）

| 桌面 | Android | 备注 |
|---|---|---|
| `state.isAudioOn` | `PREPARING/PLAYING/PAUSED`（`isSessionActive`） | 通知与 MediaSession 以此判断存活 |
| `TTSUtil.isPaused` | `PAUSED` / `STOPPED`（`desktopIsPaused`） | |
| `pausedMidSentence` + `resumeAudio()`（ttsUtil.ts:210，mid-sentence 续播） | `play()` 重新朗读**当前整句** | **有意偏差**：`TextToSpeech` 无 pause/resume，`stop()` 即结束该 utterance；整句重读同时保证高亮一致 |
| `handleStop()` 保留 `nodeList` | `stop()` 保留队列与 index，`next()` 后回 `IDLE` | 与桌面"停止后再播放从当前节点继续"一致 |
| `nodeList[currentIndex]` 走到底 → `rendition.nextChapter()` | `onUtteranceDone()` 末尾 → `chapterFinished=true` + `pendingChapterJump=+1`（宿主翻页） | 翻页由 reader 负责，服务不越权 |
| `audioPaths` 缓存 + `targetCacheCount=10` + `clearAudioPaths` | **不需要**：Android 按需合成，无音频文件 | 桌面缓存是为插件 HTTP 合成产物；Android 引擎即时出声 |
| `toast.error(t("Audio loading failed, stopped playback"))` | `TtsPlaybackState.ERROR` + `lastError` + 通知/弹窗错误文案（同 key） | i18n key 与桌面一致 |
| `handlePreviewVoice` 试听 | 未实现（缺口，见 §8） | |

### 5.2 MediaSession / 锁屏（桌面无对应，Android 新增）

- `MediaSessionCompat`（tag `KoodoReaderTts`）承载媒体键与锁屏卡片；`PlaybackStateCompat` 覆盖
  play/pause/play_pause/stop/skip_next/skip_previous/fast_forward/rewind/seek，
  元数据：书名（title）、章节（artist）、`n/total`（display description / track number）。
- 媒体键 → `TtsMediaCommandMapper`：keyCode 85/79→`PLAY_PAUSE`、126→`PLAY`、127→`PAUSE`、86→`STOP`、
  87/88→`NEXT`/`PREVIOUS`、90/89→`FAST_FORWARD`/`REWIND`；未知 key 返回 `null`（不猜测、不抢别人的会话）。
  `PLAY_PAUSE` 由服务按当前状态解析（`resolveToggle`）——单键耳机线控语义。
- `LockscreenControlsReceiver` 处理 `android.intent.action.MEDIA_BUTTON` 广播（部分 ROM/车机/耳机栈走此路径）
  与通知按钮 `PendingIntent`（同一张映射表）；服务未运行时才用 `startForegroundService` 并容错。
- 前台服务：`ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`，`IMPORTANCE_LOW` 渠道，
  `VISIBILITY_PUBLIC` + `MediaStyle`（锁屏可见），`setOngoing(true)`；`AudioManager` 音频焦点丢失（LOSS/LOSS_TRANSIENT）自动暂停。

### 5.3 断点续播（桌面 `speechStartText` → `TtsResumeAnchor`）

| 桌面 | Android |
|---|---|
| Redux `speechStartText`（`store/reducers/reader.tsx:34`） | `TtsResumeAnchor.anchorText`，DataStore 持久化（进程死亡后仍在） |
| `getSpeechStartIndex`：`item.includes(anchor) \|\| anchor.includes(item)` | `TtsResumeMatcher.findSentenceIndex` —— **逐字一致**（含双向 includes、取首个匹配、`-1` 表示未命中） |
| 未命中 → 从当前可见文本开头朗读 | 未命中 → 回退 `sentenceIndex`（队列内）→ 否则 0；**Android 新增**，桌面只有文本锚点 |
| 位置来自 `rendition.getPosition()`（PDF 分支） | `TtsReadingPosition`（bookKey/spineIndex/cfi/chapterPercent/totalPercent）随锚点持久化 |
| 与阅读引擎 | `TextSourceSentenceSource` 消费 `:engine:text` 的 `TextSource`（`readAll` 失败时读 2 MiB 前缀），解码器由宿主传入（`TextDecoder::decode`） |

**`:engine:toc` 说明**：`TtsReadingPosition` 与 `com.koodoreader.engine.toc.ReadingPosition`
字段一一对应（同名校验规则）。之所以不直接 import：该 P2 模块在本次改动时尚未进入已提交的宿主构建，
直接依赖会让 `:feature:tts` 单独不可编译。宿主接线为一行：

```kotlin
// reader 侧，:engine:toc 在 classpath 上时：
service.setReadingPositionProvider {
    val p = rendition.currentReadingPosition()      // engine:toc ReadingPosition
    TtsReadingPosition(p.bookKey, p.spineIndex, p.cfi, p.chapterPercent, p.totalPercent)
}
```

---

## 6. DoD 对照

| DoD | 对应实现 | 对应测试 |
|---|---|---|
| [1] 引擎枚举 + 语速/语调/音量持久化 | `TtsConfigRepository.kt`（codec+façade）、`DataStoreTtsConfigStore.kt`、`EngineProvider.kt`、`EngineEnumerator.kt`（`queryIntentActivities`）、`TtsVoiceCatalog.kt`（15 标签）、`TtsSentenceSplitter.kt` | `TtsConfigRepositoryTest`(8)、`EngineProviderTest`(7)、`TtsVoiceCatalogTest`(6)、`TtsSentenceSplitterTest`(9) |
| [2] MediaSession + 前台服务后台朗读 | `MediaSessionController.kt`、`ForegroundTtsService.kt`、`TtsSessionController.kt` | `TtsSessionControllerTest`(10) |
| [3] 锁屏/媒体键控制 | `TtsMediaCommand.kt`（映射表）、`LockscreenControlsReceiver.kt`、`TtsControlSheet.kt`（Compose 骨架） | `TtsMediaCommandTest`(6) |
| [4] 断点续播 | `BookmarkResumeController.kt`、`DataStoreTtsConfigStore.kt`(`DataStoreTtsResumeStore`) | `BookmarkResumeControllerTest`(7) |
| [5] 语义对照文档 | 本文件 | — |

合计 **55 个 JUnit 用例** + 24 项 `TtsSelfCheck` 断言（同一批纯逻辑、无框架路径）。

---

## 7. 验证方式（无 Android SDK 也可跑）

`:feature:tts` 是 Android library，模块内单测位于 `src/test/kotlin`（JUnit4，纯 JVM）。
正式路径（打上 §patch 后）：

```bash
# 需要 Android SDK（local.properties 的 sdk.dir），只跑纯 JVM 单测：
gradle :feature:tts:testDebugUnitTest
```

无 SDK 环境下的**等价验证**（本次已执行，脚本为临时产物、不入库）：

```powershell
# 1) 汇集纯 JVM 源：feature/tts 的 8 个纯文件 + engine/text 的 TextSource.kt + 全部 src/test/kotlin
# 2) 用 Gradle 缓存里的 kotlin-compiler-embeddable 1.9.24 直接编译（jvm-target 11）
java -cp "$compiler;$stdlib;$daemon;$script;$trove;$annotations" `
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 11 `
  -classpath "$stdlib;$coroutines;$junit" -d out <sources...>
# 3) 跑单测
java -cp "out;$stdlib;$coroutines;$junit;$hamcrest" org.junit.runner.JUnitCore `
  com.koodoreader.feature.tts.TtsConfigRepositoryTest ... BookmarkResumeControllerTest
# 4) 无框架自检（等价于 :engine:* 的 selfCheck 约定）
java -cp "out;$stdlib;$coroutines" com.koodoreader.feature.tts.TtsSelfCheckKt
```

本次结果：编译 **0 error / 0 warning**；`OK (55 tests)`；`TtsSelfCheck: all checks passed`。

---

## 8. 已知缺口 / 后续

1. **插件运行时未移植**（任务明确要求）：15 个 key 仅作标签与配置兼容，云端/自建语音在 Android 上不可用。
2. **试听（`handlePreviewVoice`）未实现**：需要一条"合成后立即 stop"的短句通道，留待 UI 卡。
3. **高亮联动**：`TtsUtterance.charStart/charEnd` 已提供，`TtsSessionController.snapshot()` 可驱动 reader 高亮；
   实际高亮渲染属阅读器卡（P2/P6 `engine:annotate`）。
4. **多角色朗读**：桌面已在本地模式下移除，Android 同样不做。
5. **i18n**：新增 key（`Pitch` / `Volume` / `Done` / `Text to speech`）需按 ADR-004 补进
   `src/assets/locales/en.json`（见 patch 的可选 hunk）；其余用桌面既有 key
   （`Play`/`Resume`/`Pause`/`Stop`/`Previous`/`Next`/`Speed`/`Voice`/`Please select`/
   `Audio loading failed, stopped playback`），缺失时与桌面一样回退为 key 文本。
6. **引擎包可见性**：Android 11+ 必须在宿主 manifest 声明
   `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>`，
   否则 `queryIntentActivities` 静默退化（patch 已包含）。
