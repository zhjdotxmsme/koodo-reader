# Android 2026-11-02 计划缺口表（As-built）

> 对照 `docs/superpowers/plans/2026-11-02-*.md` 与仓库现状；checkbox 以本表为准逐步回写计划文档。

## 总进度

| 计划 | 状态 | 说明 |
|------|------|------|
| import-metadata | **~99%** | JVM：`:core:importer:test` + `ExternalBookOpenerTest` PASS；native VIEW → trampoline |
| native-text-reader | **~98%** | Canvas + 标注/搜索/TTS/滚动高亮；**EPUB 内联图**（`ChapterLoaderImages` + `TextLayoutDraw`）；固定版式/JS EPUB 仍拒 |
| native-pdf | **~JVM 就绪** | 计划 Task 1–2 已改为 pdf.js；Task 3–5 标为废弃草稿；设备 smoke 未填 |
| reader-domain-ui | **~45%** | `ReaderModels` / `ReaderMappers` / **`ReaderContracts`** + 单测 |
| stats-sync | **~90%** | Task 1–2 JVM/UI 回归绿；Task 3 `SyncSnapshotCodec` + `SyncSnapshotMapper` |
| native-closeout | **~40%** | closeout Task 2 Step 1/4 绿；`scripts/check-native-apk-assets.js`；Task 3 与 pdf.js POC 冲突已注记 |

## 真机安装（native debug）

```bat
set JAVA_HOME=D:\jdk-21.0.1
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
cd /d E:\open-source\koodo-reader\android
gradlew.bat :app:assembleNativeHostDebug -Ptarget=native -PversionCode=20409 -PversionName=2.4.4
"%ANDROID_HOME%\platform-tools\adb.exe" install -r app\build\outputs\apk\nativeHost\debug\app-nativeHost-debug.apk
```

> **修复（2026-11-03）**：`-Ptarget=native` 不再用 `src/native` **替换**主 Manifest（会丢 LAUNCHER）；改为 `productFlavor nativeHost` + `src/nativeHost/AndroidManifest.xml` overlay。

## 验证（Windows，JDK 21）

```bat
set JAVA_HOME=D:\jdk-21.0.1
cd /d E:\open-source\koodo-reader\android
gradlew.bat :core:reader:test :feature:stats:test :engine:layout:test ^
  :app:testDebugUnitTest --tests com.koodoreader.reader.reader.* ^
  --tests com.koodoreader.reader.epubhost.ReaderEnginePolicyTest
```

Agent 环境 Gradle worker 若仍为 Java 25 → Kotlin ICE；须本机 JDK 21 跑上述命令。

## 本次已落地（2026-11-03 续）

- **`core:reader`**：`ReaderModels`、`ReaderMappers` + 单测
- **Canvas 主路径（native APK）**：`-Ptarget=native` 默认 `READER_ENGINE=canvas`
- **`RoomNoteAnnotationStore`**、`NativeNoteHighlights`、长按选词 → 高亮写 Room
- **`IncrementalBookSearch`** 接入 `NativeEpubScreen` 搜索对话框 + 跳转
- **`ChapterLoaderProgress`**：`cfiForLocation` / `excerptAt`
- **`AnnotationEvents`**（stats）+ 单测
- **`CssCascade`**：`text-align: center` 编译修复（映射 `TextAlign.LEFT`）
- **`MainActivity`**：native 外部打开后 `finish`，避免落回 WebView

## 仍阻塞 closeout 的前置项

1. **文本**：大 EPUB 滚动模式全量拉图内存（可后续按可见区懒加载）
2. **PDF**：计划 Task 正文仍含 Pdfium 步骤，需整篇改为 pdf.js 门禁（Goal 已修订）
3. **legacy 删除**：Foliate / pdfengine / webview target（native VIEW 已迁 trampoline）
4. **设备矩阵**：`docs/android-native-validation-2026-11-02.md` + Macrobenchmark

## 建议下一迭代

1. release 跑 `check-native-apk-assets.js` + `check-elf-16kb.js`；legacy 岛/Foliate 删除（PDF 路径除外）
2. 填 `docs/android-native-validation-2026-11-02.md` + Macrobenchmark JSON
3. PDF 计划 Task 1–2 改写为 pdf.js 能力/性能门禁（删 Pdfium 步骤）
