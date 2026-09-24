# ADR-005 安卓原生字体策略（导入范围 + 内置字体）

- 日期：2026-09-24
- 状态：已采纳

## 背景

桌面端（`src/utils/file/fontUtil.ts`）字体导入接受 ttf/otf/woff，woff2 有转 ttf 的转换路径。安卓原生轨（任务卡 t-muew0ia6）需要决定导入范围与内置字体清单；同时 WebView 兜底岛轨经勘察已确认字体链路自洽（WebView IndexedDB + 文件选择器，见迁移文档附录 A「主题/字体」行），无需原生桥。

## 决策

1. **原生导入范围：仅 ttf/otf**（`NativeFontKeys.IMPORTABLE_EXTS`）。woff/woff2 不在原生轨导入：
   - Android `Typeface` 不支持 woff/woff2，需要引入解码/转换库（依赖与体积代价）；
   - woff 系字体的使用场景在 WebView 兜底岛内，该轨已自洽。
2. **内置字体随 APK 打包**（`res/font` + `FontCatalog`，`bundled:*` 键）：
   - LXGW WenKai Lite Regular（霞鹜文楷，13.2MB，SIL OFL）
   - Inter 可变字体（0.8MB，SIL OFL）
   - 体积影响：APK 18.35 → 26.16 MB（TTF 在 APK 内 deflate 压缩率约 44%）。
   - 解析用 `ResourcesCompat.getFont`（minSdk 24 可用；可变字体低于 API 26 渲染默认实例）。
3. **系统字体枚举**：API 29+ `SystemFonts.getAvailableFonts()`（按文件名去重）；低版本回退 `/system/fonts` 文件清单 + 已知 generic family。
4. **自定义字体落盘**：`filesDir/fonts/<key>.<ext>`，key 与桌面 `normalizeFontName` 逐字对齐；导入时即刻用 `Typeface.createFromFile` 验证可加载，失败即删（目录永不留下坏字体）。
5. **元数据与桌面同键**：`fontList` + `customFonts`（SharedPreferences 承载，`FontPrefs`），桌面配置迁移后 key 语义不变。
6. **回退链**：`FontFallbackResolver` 接口 + 系统回退默认实现占位；真正的 per-glyph 混排链随 P2 `engine/layout` 落地（Android 渲染器对缺字已有系统级回退，自绘分页必须经该接口测量以保证一致性）。

## 边界（不做）

- 字体在线下载/商店；
- 字重合成（faux bold 由 paint 处理）；
- woff/woff2 原生解码。

## 验证方式

- core/common JVM 单测：normalizeFontName 桌面逐字对齐、扩展名白名单、配置键恒等；
- core/dbio JVM 单测：bundle fonts/ 目录解析与流式读取；
- 设备验证（挂账 P2）：字体切换、CJK 混排、导入、桌面迁移互通。

## 回滚方案

若需要 woff 系支持：引入转换步骤（导入时 woff→ttf）或换用支持 woff 的渲染栈，`FontManager.importFont` 单点扩展，接口不变。
