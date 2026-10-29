# M1 真机烟雾清单（Foliate + 规则 + CSS + Readest）

> 无设备时可跳过；有 adb 时按序勾选。APK：`:app:assembleDebug -Ptarget=native`。

## 构建安装

```powershell
$root = "E:\open-source\koodo-reader"
$tmp = "$root\.tools\tmp"
$env:JAVA_HOME = "$root\.tools\jdk-17.0.20.1+1"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:GRADLE_USER_HOME = "$root\.tools\gradle-home"
$env:LOCALAPPDATA = "$root\.tools\localappdata"
$env:TEMP = $tmp; $env:TMP = $tmp
Set-Location "$root\android"
.\gradlew.bat --no-daemon --init-script "$root\.tools\reloc-build.gradle" --init-script "$root\.tools\debug-signing.gradle" --project-cache-dir "$root\.tools\gradle-project-cache" "-Djava.io.tmpdir=$tmp" "-Dkotlin.compiler.execution.strategy=in-process" :app:assembleDebug -Ptarget=native -PabiFilters=arm64-v8a
```

安装后启动 **LauncherAlias**（勿直接调未导出的 Activity）。

## 重排书（EPUB 优先）

- [ ] 默认 **滚动**：连续滑动正文
- [ ] 阅读页 `⋯` → 外观 → **Paginated**：点击左右区翻页
- [ ] 外观 → **夜间 / 护眼**：整页配色一致（无内嵌白框）
- [ ] 设置 → 阅读 → **净化规则**：添加 `广告` → 空，回到书内换章或等自动刷新后广告文案消失
- [ ] 设置 → 阅读 → **Custom book CSS**：开启并填 `a { color: red !important; }`，链接变红
- [ ] 目录跳转、高亮添加/删除、重启后仍在
- [ ] 划词 → **翻译**（净化后文本）
- [ ] 听书：章节朗读不含被规则删掉的片段（若规则作用于全文）

## 脚注（有 noteref 的书）

- [ ] 点击脚注引用 → **底部/对话框**展示摘录，而非空白章

## 截图

保存至 `.tools/device-m1-*.png` 便于 PR 说明。
