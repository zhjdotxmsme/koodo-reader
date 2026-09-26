# ADR-006 漫画 / 图片引擎（`engine/image`，P5）

- 日期：2026-09-25
- 状态：已采纳（P5 实现落地；真机回归与 CB7/CBR 见 §10）
- 关联：迁移方案 §5「CBZ / CBR / CBT / CB7 → 原生 `engine/image`，懒加载」、ADR-001（双轨）、
  ADR-003（兜底岛生命周期）、P1 `core/importer`（导入规则单一事实源）、`docs/patches/p5-image.patch`（注册步骤）

## 1. 背景与范围

P5 的四条格式线里，漫画线要求「原生图片阅读器 + 懒加载（当前页 + 后 3 页加载、卸载 -4 页）」。
本卡交付 `android/engine/image`：**纯 Kotlin JVM 模块**（无 Android 依赖），把「解包 + 页序 +
加载窗口 + 单双页排版 + 缩放平移」全部做成可在无 Android SDK 机器上单测的逻辑；Compose 宿主
落在 `:app` 侧（§6）。

### 参考资产勘察结论（先查证，再动手）

| 资产 | 状态 | 对本案的影响 |
|---|---|---|
| kookit `ComicRender` / `comic-book.js` | **不可获得**：桌面引擎是闭源压缩产物 `src/assets/lib/kookit-extra.min.mjs`（CLAUDE.md 明确禁止阅读），本地源码仓库 `D:\Project\kookit` 按本卡约束不访问 | 按任务描述的契约（懒加载窗口、封面=natural 序首图）**原生实现**，不猜测内部实现细节 |
| `plugins/main/voice/*`、`plugins/renderer/translation/*`、`dictionary/*` | 不存在（已核对） | 与 P5 无关 |
| `public/lib/7z-wasm/**`（`7zz.wasm` 1.8 MB） | **存在** | CB7 的**兜底岛**解码器（不是缺失资产）；原生侧另走纯 Java（§4.1） |
| `public/lib/libunrar/**`（`libunrar.wasm` 174 KB） | **存在** | CBR 的兜底岛解码器，P5 保留（§4.2） |
| `public/lib/tesseractjs` | 存在 | P5 不用（OCR 属其他卡） |
| `feature/webisland` 模块 | **尚未创建**（ADR-001 定义的兜底岛只是资产集合：`LocalAssetServer.kt`、`nativeBridge.js`、`folderBridge.js`、`target=webview` 变体） | 「下线开关」以**数据驱动的路由表**形式给出（§7），不依赖一个还不存在的模块 |

## 2. 决策

1. **`engine/image` 为纯 JVM 模块**，与 `:engine:cfi / :engine:text / :engine:pdf` 一致：
   `gradle :engine:image:test` 不需要 Android SDK、不需要网络（运行时依赖只有 `:core:importer`）。
2. **IO 与策略分离**：`ArchiveExtractor`（解包）↔ `PageLoader`（窗口）↔ `ComicViewerModel`
   （跨页 + 进度），Compose 宿主只做「快照 → UI + 手势回灌」。
3. **懒加载窗口是硬契约**（§3），不放在 UI 层，避免每个入口各写一套窗口逻辑。
4. **页序/图片扩展名以 P1 为唯一事实源**：`api(project(":core:importer"))`，`ImageEntries.EXTS`
   直接引用 `ComicCover.IMAGE_EXTS`，并有一条**行为断言**锁死「封面 == 第 0 页」（§8）。
5. **不引入任何 `.so`**（§5）；CB7 靠纯 Java 实现，CBR 明确不做原生、维持兜底岛。
6. Compose 宿主骨架**不进本模块编译**（`sourceSets.main.kotlin.exclude`，§6），只作为 `:app` 的移植模板。

## 3. 懒加载契约（本卡核心）

`LoadWindow`（常量 + 纯函数）与 `DefaultPageLoader`（驻留表 + 预算）实现下表：

| 项 | 规则 | 常量 |
|---|---|---|
| 加载 | `current .. current+3`（卷尾截断），只补缺失页 | `LoadWindow.AHEAD = 3` |
| 保留 | `[current-3, current+3]`，顺序阅读时最多 7 页驻留 | `KEEP_BEHIND = 3` |
| 卸载 | `index <= current-4` 即释放 | `EVICT_BEHIND = 4`（= KEEP_BEHIND+1） |
| 跳页 | 落到远处（目录跳转/进度条）时，窗口外的旧页一并释放 —— 否则驻留集合随跳页次数无界增长 | 同 `retention()` |
| 预算 | `maxResidentBytes`（默认 64 MB）超限时从**当前页之前**最老的页开始淘汰，**永不淘汰当前页** | `DefaultPageLoader.DEFAULT_MAX_RESIDENT_BYTES` |
| 失败 | 单页读取异常 → 不进驻留表 + 记入 `lastError`，下次翻页重试；阅读器不崩 | — |
| 线程 | `seek` 同步（可测）；`seekAsync` 把 IO 丢给 `Executor`，期间又翻页则丢弃过期任务 | — |

顺序阅读的窗口演化（`pageCount=20`）：

```
seek(0) → 加载 0,1,2,3                 驻留 {0,1,2,3}
seek(1..3) → 各补 1 页                 驻留 {0..6}
seek(4) → 加载 7；卸载 0               驻留 {1..7}   ← 7 页上限，往回翻一页(1)仍命中
```

页对象 `ComicPage` 只持「编码字节 + 固有尺寸（`ImageHeader` 解析容器头，不解码像素）」；
像素解码交给宿主（`BitmapFactory` / `PageDecoder` 钩子），因此 JVM 侧无需图形栈。

## 4. 容器支持矩阵

| 容器 | 状态 | 实现 | 说明 |
|---|---|---|---|
| **CBZ** / `.zip` | ✅ 实现 | `ZipExtractor` | 精确名匹配 + 大小写不敏感兜底（与 `ComicCover.readEntryBytes` 同策略） |
| **CBT** / `.tar` | ✅ 实现 | `TarExtractor`（`RandomAccessFile` 按偏移随机读） | ustar、GNU long name（`L`）、`prefix` 字段、八进制/base-256 尺寸 |
| `.tar.gz` / `.tgz` | ✅ 实现（有取舍） | `TarExtractor`（打开时整体解压到内存） | gzip 无法随机访问；超 `MAX_GZIP_MATERIALISE_BYTES`（96 MB）直接拒绝并提示转 CBZ |
| 散图目录（SAF tree / 文件夹漫画） | ✅ 实现 | `TreeExtractor` | 相对路径 natural 序；跳过隐藏项、`._` 资源分支、符号链接 |
| **CB7** / `.7z` | ✅ 实现（P5-CB7 补强） | `SevenZExtractor` | 纯 Java（commons-compress + xz），零 `.so`；见 §4.1 |
| **CBR** / `.rar` | ⛔ 不做原生 | `RarExtractor`（骨架 + 理由） | 见 §4.2 |

路由入口是 `ArchiveExtractors.kindOf(file)`：**魔数优先**（`.cbz` 里其实可能是 rar）、扩展名兜底；
宿主看 `ArchiveKind.support`（`READY / PLANNED / DEFERRED / UNSUPPORTED`）决定是否回落兜底岛。

### 4.1 CB7：可以原生，而且不需要 `.so`（已接线）

`org.apache.commons:commons-compress:1.27.1` 的 `SevenZFile` 是纯 Java、Apache-2.0、只读、无 `.so`
⇒ **没有 16 KB 页对齐问题**。P5-CB7 补强（卡 `t-mubaoux…` 系列，见 `docs/patches/p5-image-cb7.patch`）
已完成接线：`ArchiveKind.SEVEN_ZIP.support = READY`，页表/读页与 `ZipExtractor` 同形
（`ImageEntries` 过滤 + `NaturalOrder` 排序 ⇒ 「封面 = 第 0 页、页数与导入一致」在 CB7 上同样成立）。

依赖两条，都是纯 Java：

| 依赖 | 作用 |
|---|---|
| `org.apache.commons:commons-compress:1.27.1` | 7z 容器 + Copy/LZMA/DEFLATE/BZIP2/AES 解码 |
| `org.tukaani:xz:1.10` | LZMA / LZMA2 解码器（`SevenZFile` 的可选依赖，7z 默认就用 LZMA2） |

这是本模块**唯一**的运行时依赖（此前运行时零依赖；接线后 `gradle :engine:image:test` 首次需要下载）。

**边界（诚实标注，已验证）**：

- **BCJ2**（`-m0=BCJ2`，x86 可执行文件的多输入/输出流过滤器）：commons-compress 明确未实现
  （`IOException: Multi input/output stream coders are not yet supported`），图像归档几乎不会用到
  ⇒ 遇到即抛 `UnsupportedArchiveException`，提示路由到兜底岛（与 CBR 同口径，不假装支持）。
- **AES 头部加密**（`-mhe=on`）：无密码连条目列表都读不出来 ⇒ 打开即失败并给出可执行提示。
- 单页解压上限 `MAX_PAGE_BYTES = 64 MB`（与 `DefaultPageLoader` 的常驻上限同量级），超限报错不 OOM。
- 页表夹具覆盖 5 种变体（Copy / LZMA / LZMA2 / BCJ2 / 加密头），见
  `src/test/resources/sevenz/README.md`。

### 4.2 CBR：暂不原生（明确不做，不是遗漏）

1. **许可证**：unrar / libunrar / junrar / 7-Zip-JBinding 的 RAR 部分都带 UnRAR restriction
   （非 OSI 许可，且限制「不得用于实现 RAR 压缩器」）——塞进 AGPL-3.0 主仓需要单独法务判断；
2. **没有可用的纯 Java RAR5**：junrar 只完整支持 RAR4 ⇒ 「像 7z 那样绕开 `.so`」的路不存在；
3. **`.so` 风险**：预编译 `libunrar` 未必按 16 KB 页对齐，Android 15+ 设备会拒绝加载（§5）。

因此 CBR 维持兜底岛渲染（WebView + `public/lib/libunrar` wasm），并给用户「解压为 CBZ / 导入散图目录」
的可行替代。`RarExtractor` 里写清了立项时需要的东西（许可证结论、NDK r27+ 自编译、ABI 拆分方案）。

## 5. `.so` 与 16 KB 页对齐政策

- **本模块 0 个 `.so`**（约束⑤的落点）：解包与排版全在 JVM 侧，宿主只用系统 `BitmapFactory`。
  因此 16 KB page size 设备（Google Play 2025-11 起对 targetSdk 35+ 的要求）不受影响。
- 明确**不**引入：`libunrar`、`7-Zip-JBinding`（都带 `.so`，且许可证/对齐双重风险）。
- 若未来确需 `.so`（例如 CBR 立项）：
  1. 必须用 **NDK r27+** 且链接参数 `-Wl,-z,max-page-size=16384` 重新编译，产物用
     `llvm-readelf -l lib*.so | grep LOAD` 核对 `Align 0x4000`；
  2. 体积用 **ABI 拆分**（`splits { abi { enable true; reset(); include 'arm64-v8a', 'armeabi-v7a' } }`）
     或把整个漫画/压缩能力放进 **dynamic feature module**，避免主 APK 基线被拉高（ADR-003 §2 体积控制）；
  3. 建议来源优先级：**系统库 / 纯 Java 实现 > 自编译 `.so` > 第三方预编译 `.so`**。

## 6. Compose 宿主边界

`host/ComicViewerHost.kt` 是**移植模板**，被 `build.gradle` 的
`sourceSets.main.kotlin.exclude 'com/koodoreader/engine/image/host/**'` 排除出编译 ——
`androidx.compose.*` 以 AAR 发布，纯 JVM 模块无法解析；而正是「纯 JVM」让 §3 的窗口契约与
§4 的解包逻辑可以脱离 Android SDK 单测。这与 `:engine:pdf → engine/pdfhost`（宿主在 `:app`）
的既有分工一致。模板里已写清：`ArchiveKind.support != READY` 时必须回退兜底岛、
`onDispose` 释放归档句柄、双击/单击区的手势映射。移植步骤见 patch §3。

## 7. 兜底岛下线清单（P5 漫画线）

「开关」不依赖尚未创建的 `feature/webisland` 模块，而是**数据驱动的单表**：
`ArchiveKind.support` + `ArchiveExtractors.isNativelyReadable(file)`。任一格式回退一行即恢复兜底岛渲染
（ADR-003「回滚方案」）。

| # | 资产 / 行为 | 当前角色 | 下线前置条件 | 证据命令 / 验证 | 回退方式 |
|---|---|---|---|---|---|
| 1 | 路由表行「CBZ/CBT → 兜底岛」 | WebView 渲染漫画 | CBZ/CBT 回归集通过 + 原生理赔通过 | `gradle :engine:image:test`；真机打开 `.cbz`/`.cbt` 各 3 本 | 把该格式的 `support` 改回非 `READY`，路由回兜底岛 |
| 2 | `public/lib/7z-wasm/**`（1.8 MB wasm） | CB7 解码（兜底岛） | **`SevenZExtractor` 已接线 ✅（P5-CB7 补强）；剩余前置：CB7 路由切换 + 真机回归** | `ArchiveExtractors.kindOf(x.cb7) == SEVEN_ZIP` 且 `pageCount>0`（单测已锁）；真机回归未做 | 恢复该目录 + 路由回退（wasm 在 git 历史里） |
| 3 | `public/lib/libunrar/**`（174 KB wasm） | CBR 解码 | **CBR 单独立项**（§4.2：许可证 + `.so`）；当前**不下线** | — | 本就是保留项 |
| 4 | 桌面 `ComicRender` / `comic-book.js`（打包进 `kookit-extra.min.mjs`） | 桌面/兜底岛漫画渲染 | P8 整体退役（ADR-003 ④），且 CB7/CBR 都原生或明确驻留 | P8 验收：主变体启动无 WebView 加载 | git 历史 |
| 5 | 契约守卫 `nativeBridge.js` / `folderBridge.js` / `androidBuild.test.js` | 兜底岛↔原生壳兼容层 | **P8 前不得删除**（ADR-003 §1） | `node scripts/check-import-rules.js`、`yarn test` | — |
| 6 | `target=webview` 构建变体 | 纯 WebView 包 | **永久保留**（ADR-001） | `-Ptarget=webview` 出包 | — |

> **P5-CBZ-5 / P8-F1 状态更新（2026-09-25）**：第 1、2 行的「路由切换」已完成——
> `MainActivity.handleIntent → IntentRoutePolicy.decide(mime, name)` 数据驱动分流
> （CBZ/CBT/CB7 → `ComicViewerActivity` 原生屏；CBR/RAR 显式拦截走兜底岛；PDF →
> 导入管线 + 原生壳 `NativePdfScreen`；其余格式兜底岛 fallback）。路由表 = 单一
> `IntentRoutePolicy`（16 项 JVM 单测钉死）。真机回归仍未做（无设备），第 2 行的
> wasm 资产删除留待真机回归通过后执行。

**本卡不做**：删除任何兜底岛资产（第 2 项的前置条件尚未满足）。本卡只交付「开关」与清单。

## 8. 与 P1 `core/importer` 的衔接

- `build.gradle`：`api(project(":core:importer"))`（用 `java-library` 插件的 `api` 配置，
  让消费方 `:app` 也拿到漫画导入规则）。
- 常量衔接：`ImageEntries.EXTS ≡ ComicCover.IMAGE_EXTS`（测试断言同一实例/同一集合）。
- **行为衔接（关键）**：`ZipExtractorTest.page zero is exactly the cover the P1 importer extracts`
  —— 同一个 CBZ 上 `ComicCover.extract(file).cover.bytes == ZipExtractor.readPage(0)`、
  `pageCount` 相等。这样「书架上那张封面」与「阅读器第 1 页」不可能不是同一张图。
- 已知重复：`ComicCover.naturalComparator()` 是 `internal`，本模块无法调用，故 `NaturalOrder`
  保留一份**逐行对齐**的实现。待办：P1 把它提升为 `public` 后删除 `NaturalOrder` 改为直接引用
  （行为断言已先行，改完不会悄悄错位）。

## 9. 验证方式

```powershell
# 纯 JVM 单测（无需 Android SDK / 网络）
cd .worktrees/.p5-testbed
$env:JAVA_HOME           = 'D:\jdk-17\jdk-17.0.13+11'
$env:GRADLE_USER_HOME    = 'E:\open-source\koodo-reader\.worktrees\.gradle-home'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\54389\.gradle\caches'
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.5-bin\*\gradle-8.5\bin\gradle.bat" `
    :engine:image:test --tests "*PageLoaderTest*" --offline --console=plain
# 全量：:engine:image:test
```

结果：**67 tests / 67 passed**（`PageLoaderTest` 14 项、`ArchiveExtractorsTest` 9、`ComicViewerModelTest` 8、
`ZoomPanTest` 8、`SpreadPolicyTest` 7、`TarExtractorTest` 7、`ImageHeaderTest` 5、`ZipExtractorTest` 5、
`TreeExtractorTest` 4）。

环境注意：本机沙箱禁止写 `C:\Users\<user>\.gradle`（Gradle 用户目录必须指向工作区内），
且 Kotlin 编译守护进程连不上（命名管道受限）⇒ 测试台用
`kotlin.compiler.execution.strategy=in-process`。这两点只影响本地跑测，不影响仓库内容。

## 10. 风险与未验证项（诚实标注）

| 项 | 状态 | 说明 |
|---|---|---|
| 真机回归（打开真实 CBZ/CBT 的手感、内存） | ❌ 未做 | 本机无 Android SDK/设备；窗口策略只有 JVM 单测覆盖 |
| Compose 宿主编译 | ❌ 未做 | 模板文件被排除出本模块编译，移植到 `:app` 时需真编译一次 |
| CB7 真机路径 | ⚠️ 部分 | **实现已完成**（纯 Java、零 `.so`），5 种变体单测覆盖；剩余：路由切换 + 真机打开一本 `.cb7`。BCJ2 过滤器明确不支持（走兜底岛，见 §4.1） |
| CBR 真机路径 | ❌ 未做 | 明确不做原生（§4.2）；`RarExtractor` 给出路由建议，测试锁定「必须提示兜底岛」 |
| 桌面参考实现逐行对齐 | ⚠️ 部分 | `ComicRender` 不可读（§1），页序/封面契约以 P1 为准，排版手感以 ADR 描述为准 |
| `tar.gz` 内存解压 | ⚠️ 取舍 | 有 96 MB 上限 + 明确拒绝路径（已测） |
| pax 扩展头 `path` 覆盖、tar 校验和验证 | ⚠️ 未实现 | 对漫画卷宗罕见；列入后续 |
| SVG / AVIF 固有尺寸 | ⚠️ 部分 | SVG 走 `width/height/viewBox`；AVIF/HEIF（ISO-BMFF `ispe`）返回 null，排版退化为等比占位 |
