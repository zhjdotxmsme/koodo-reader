# M3 真机烟雾清单（PDF 裁边/重排 + EPUB spread）

> 设备：**CYKRYX89XO6TAIGM**（OPPO PKT110）· 包名 `com.koodoreader.reader` · 原生壳 `-Ptarget=native`  
> 关联计划：`docs/superpowers/plans/2026-10-21-android-reader-m3-pdf-spread.md`

## 0 · 前置

- [ ] 手机 **解锁**（adb 无法代输 PIN/指纹；锁屏时 screencap 只有黑屏/锁屏界面）
- [ ] USB 调试已开；`adb devices` 见 `device`
- [ ] 测试书：书架空置时可从 **设置 → 导入** 选 `Download/koodo-smoke/`（脚本会 push），或使用已有 `files/books/*.pdf` / `*.epub`

**注意：** `adb` 用 `file://` 打开 PDF 在 Android 10+ 常因 **无读权限** 导入失败；请用应用内导入或「分享到 Koodo Reader」。

---

## 1 · 构建与安装（PowerShell）

```powershell
$root = "E:\open-source\koodo-reader"
$tmp = "$root\.tools\tmp"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$env:JAVA_HOME = "$root\.tools\jdk-17.0.20.1+1"
$env:Path = "$env:JAVA_HOME\bin;$env:LOCALAPPDATA\Android\Sdk\platform-tools;" + $env:Path
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:GRADLE_USER_HOME = "$root\.tools\gradle-home"
$env:LOCALAPPDATA = "$root\.tools\localappdata"
$env:TEMP = $tmp; $env:TMP = $tmp
Set-Location "$root\android"
.\gradlew.bat --no-daemon --init-script "$root\.tools\reloc-build.gradle" --init-script "$root\.tools\debug-signing.gradle" --project-cache-dir "$root\.tools\gradle-project-cache" "-Djava.io.tmpdir=$tmp" "-Dkotlin.compiler.execution.strategy=in-process" :app:assembleDebug -Ptarget=native -PabiFilters=arm64-v8a

$apk = "$root\.tools\out\_app\outputs\apk\debug\app-debug.apk"
& $adb install -r $apk
& $adb shell mkdir -p /sdcard/Download/koodo-smoke
& $adb push "$root\.tools\testbooks\sample.pdf" /sdcard/Download/koodo-smoke/sample.pdf
& $adb push "$root\.tools\testbooks\KoodoTestBook.epub" /sdcard/Download/koodo-smoke/KoodoTestBook.epub
& $adb shell am start -n com.koodoreader.reader/.LauncherAlias
```

快捷脚本（仅安装 + push + 启动）：`scripts/android-m3-smoke.ps1`

---

## 2 · PDF（NativePdfScreen）

打开书架中 **文字 PDF**（或导入 `sample.pdf` 后打开）。

| # | 步骤 | 预期 |
|---|------|------|
| P1 | 默认栅格：页码、缩放、左右 tap 翻页 | 位图正常，无 `render failed` Toast |
| P2 | 顶部 **⋯** → **PDF reflow view** | 变为可滚动 HTML 正文；字号随 **设置 → Font size** |
| P3 | 再点 **⋯** → **PDF raster view** | 回到位图 |
| P4 | **设置 → 阅读 → PDF margin crop** 调到 **16px** → 回到 PDF 栅格 | 四边白边 visibly 变窄（同页对比截图） |
| P5 | **⋯** → **Double-page view**（栅格） | 左右两页并排；翻页步进 2 |
| P6 | `adb logcat -d \| findstr /i pdfengine` | 无 `FATAL`；可见 `pdfengine ready` 类 info（Tag 因 ROM 而异） |

**扫描 PDF：** 重排应显示「无文本」占位或极短内容 → 切回栅格 + 可选 OCR。

---

## 3 · EPUB spread（NativeEpubScreen / Foliate）

打开 **EPUB**（如 `KoodoTestBook.epub` 或书架已有 epub）。

| # | 步骤 | 预期 |
|---|------|------|
| E1 | **设置 → 阅读 → Double-page view** 打开 | Foliate **分页**（非连续滚动），一次可见 **两栏 spread** |
| E2 | 关闭 Double-page view | 恢复原先滚动/单栏（与 **Two-column layout**、Lite 档一致） |
| E3 | Lite 档 + Double-page | 仍为 **2 列 spread**（不被 Lite 压成 1 列） |
| E4 | 规则/CSS（M1 回归） | 换章后规则仍生效；夜间主题无白框 |

---

## 4 · 截图归档

保存到 `.tools/`，便于 PR / 记录：

| 文件 | 内容 |
|------|------|
| `device-m3-pdf-raster.png` | PDF 栅格 + crop |
| `device-m3-pdf-reflow.png` | PDF 重排 |
| `device-m3-pdf-spread.png` | PDF 双页 |
| `device-m3-epub-spread.png` | EPUB 双页 spread |

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell screencap -p /sdcard/koodo-cap.png
& $adb pull /sdcard/koodo-cap.png E:\open-source\koodo-reader\.tools\device-m3-xxx.png
```

---

## 5 · 2026-10-21 自动化会话记录

| 项 | 结果 |
|----|------|
| `:app:assembleDebug -Ptarget=native` | **SUCCESS**（曾修 `ReadestViewSettings` 缺 `ReaderPerformancePolicy` import） |
| `adb install -r app-debug.apk` | **Success** |
| `run-as … ls files/books` | 已有 `1792472487719101.epub`、`1792472487858388.pdf` |
| `LauncherAlias` 启动 | OK（`NativeShellActivity` 为 focused app） |
| `file://` VIEW 打开 sample.pdf | 已发 Intent；**未验证**导入（锁屏 + file 权限限制） |
| screencap | 锁屏期间无效；**需解锁后**重拍 |

---

## 6 · 勾选汇总

- [ ] P1–P6 PDF
- [ ] E1–E4 EPUB
- [ ] 截图 4 张
- [ ] 无崩溃 / ANR

*完成勾选即 M3 真机 smoke 通过。*
