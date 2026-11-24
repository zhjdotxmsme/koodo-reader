# 功能优化（排版 / 书架 / 统计 / PDF）— 需求边界与设计

- 任务：`t-mwu0cxm5-wpn4bg`（新增：功能优化）
- 日期：2026-11-24
- 代码库：koodo-reader（Electron + React CRA + Redux；阅读引擎 kookit/kookit-extra 为闭源 ESM）

## 1. 现状盘点（已存在，不重复实现）

| 任务清单要求 | 现状 |
|---|---|
| 字体/字号/行距/段距/页边距/主题深度调节 | ✅ `sliderConfigs`（fontSize/letterSpacing/paraSpacing/margin/scale/brightness）+ `dropdownList`（lineHeight/textAlign/theme） |
| 边距支持负数 | ✅ margin 滑块 `minValue: -40` |
| 英文两端对齐 | ✅ `textAlign: Justify` |
| 禁用翻页动画 | ✅ `animation: None` |
| 自定义字体导入 | ✅ `fontSetting` + `FontUtil.saveFont` |
| 书架布局 | ✅ card / list / cover（`constants/viewMode`） |
| 文件大小排序、置顶排序 | ✅ `bookSortCode.sort=8(size)` + `topBooks` 置顶 |
| 组合筛选（标题/作者） | 部分 ✅ `BookUtil.searchBooksByKeyword`（name/author/key） |
| 年度热力图、30 天柱状/折线、连续天数 | ✅ `src/pages/stats`（recharts + 52 周热力图） |
| OCR（TXT） | ✅ `ocrEngineList`（system-ocr/paddle/tesseract） |
| 音量键可绑定 | 部分 ✅ `shortcutUtil` KEY_LABELS 已含 VolumeUp/Down，默认绑定缺失 |

## 2. 本次实现范围（需求边界）

原则：只做**渲染进程内可落地、可用单测证明**的部分；依赖闭源引擎（kookit-extra）内部能力或外部服务（OCR 扫描版、网络封面搜索、云端同步新字段）的项全部列为「延后」，不假装完成。

### A. 阅读排版与沉浸体验
| # | 功能 | 设计 | 验收标准 |
|---|---|---|---|
| A1 | 夜间/深色模式图片反色 | 新增开关 `isInvertImage`；`readingCss.ts#buildExtraReaderCss` 生成 `body img{filter:invert(1) hue-rotate(180deg)}`，由 `styleUtil.addDefaultCss` 注入 `<style id="reader-extra-style">` | 开关=on 时 CSS 字符串包含 invert 规则；off 时不包含（单测） |
| A2 | 图片透明度配置 | 新增滑块 `imageOpacity`（30–100）；`sanitizeImageOpacity` 钳制到 [0.3,1]，非数→1.0；CSS `body img{opacity:X}` | 边界值单测（-5→30%、200→100%、"abc"→100%） |
| A3 | 单图片居中显示 | 新增开关 `isCenterImage` → `body img{display:block;margin-inline:auto;max-width:100%}` | CSS 生成单测 |
| A4 | 排版增强（表格/列表/注音） | 新增开关 `isTypoEnhance` → 表格边框合并、列表行距、ruby 注音基线 CSS（引擎布局预设之外） | CSS 生成单测；关闭时输出空 |
| A5 | 音量键翻页 | `shortcutUtil` DEFAULT：prevPage+174(VolumeDown)、nextPage+175(VolumeUp)；`getShortcutConfig` 合并旧配置时用 `ensureVolumeBindings` 幂等补齐（不重复、不冲突） | matchShortcut(175)→nextPage；旧配置补齐不产生重复绑定（单测） |
| A6 | 竖屏自动双页 | 新开关 `isPortraitAutoDouble`；`readerModeUtil#shouldAutoDoublePage({enabled,width,height,mode})` 纯函数；`modeControl` 加 resize 监听，竖屏且当前非 double/scroll 时自动切 double | 纯函数 4 象限单测（横屏/竖屏/已double/禁用） |
| A7 | 字体生效范围：全局/当前主题 | `fontScopeUtil`：`resolveEffectiveFonts(themeFonts, themeColor, base)`；localStorage `koodo:themeFonts`（按主题色键）；`styleUtil.applyReaderFonts` 按 `fontScope` 解析；fontSetting 增加范围选择器 | 主题命中/未命中/空主题三态单测；JSON 损坏时回退全局 |
| A8 | 批量导出字体 | `fontZip.ts#buildFontZip(files)`（fflate，纯函数，空列表抛错）；fontSetting「批量导出」按钮收集全部自定义字体 zip 下载 | fake buffer 打包字节数>0、空列表抛错（单测） |
| A9 | 页眉页脚文案（电池百分比+剩余阅读时间） | `statsUtil#buildFooterCaption` + `estimateRemainingSeconds(remainingWords, ratePerMin)` 纯函数；`batteryUtil#getBatteryLevel`（Web Battery API，超时→null）；文案模板存入 reader config 供后续引擎版本识别 | 文案拼接受单测；不支持环境返回 null |

### B. 阅读目标与统计（`src/pages/stats` + `statsUtil`）
| # | 功能 | 设计 | 验收标准 |
|---|---|---|---|
| B1 | 每日字数/时长目标 | `ReadingGoals{dailyWords,dailyMinutes,yearlyBooks}`；localStorage 读存 + `normalizeGoals` 校验（负数/NaN→0，上限钳制） | 非法输入全部归一（单测） |
| B2 | 年度读完书籍目标 | 目标卡片 + `countDistinctBooks(entries)` | 去重计数单测 |
| B3 | 手动录入时长与字数 | `addManualStat/removeManualStat`（日期 YYYY-MM-DD、seconds 1–86400、words 0–10^7 校验，越界抛中文报错）；统计页表单 | 非法日期/0 秒/超 24h 抛错（单测） |
| B4 | 单页最大记录阅读时长 | `capEntrySeconds(entries,maxSec)`（0/负=不限） | 上限生效/不限两态单测 |
| B5 | 时间分布柱状图 | `aggregateByHour(entries): number[24]`（无 hour 字段数据→全 0，UI 展示提示） | 含/不含 hour 字段单测 |
| B6 | 阅读时长书籍排行 | `aggregateByBook(entries, topN)` 降序、未知 bookKey 归 "(unknown)" | TopN 截断+排序单测 |
| B7 | 日历/时间线/年份视图 | 30 天（已有柱状/折线）+ 新增「年度」：年份选择 + 12 月柱状（数据源=全部日期 map，纯聚合 `yearlyMonthlySeconds`） | 跨年假数据单测 |
| B8 | 统计页按标签/分类筛选 | `bookShelf.ts#matchBookFilters`（title/author/intro 字段组合 + 状态）；排行视图支持按书架名过滤（经 `getAllMapConfig("shelfList")`） | 组合过滤命中/漏选单测 |
| B9 | 分享海报（目标/单本） | `buildGoalPosterLines` / `buildBookPosterLines`（纯数据）+ 组件内 canvas 绘制（jsdom 无 canvas 时优雅降级 toast）+ file-saver 下载 | 数据行构建单测 |

### C. 书架、分类与标签
| # | 功能 | 设计 | 验收标准 |
|---|---|---|---|
| C1 | 极简列表布局 | `viewMode` 增加 `compact`；新组件 `bookCompactItem`（小封面+书名+作者，紧凑行高）；bookList 三分支渲染 | 常量含 4 种模式（单测）；渲染分支存在 |
| C2 | 列表显示简介/上次阅读 | `buildBookMetaLines(book, opts)`（简介截断、上次阅读时间格式化）纯函数；compact/list 项展示 description 首行 | 截断/空字段单测 |
| C3 | 组合筛选（简介等） | `searchBooksByKeyword` 扩展 `OR description LIKE ?`（仅 description，不探未知列）；web 分支同字段 | SQL 串/客户端过滤单测（对纯函数部分） |
| C4 | 批量创建/自定义标签颜色 | 依赖 kookit `noteTags`/shelf 内部结构与云同步字段，引擎侧能力 → **延后**（见 §4） | — |

### D. PDF
| # | 功能 | 设计 | 验收标准 |
|---|---|---|---|
| D1 | 记住密码 | `pdfPasswordUtil`：remember/get/remove（localStorage，bookKey 清洗、长度上限、base64 混淆；明确不加密级仅防窥） | 存取删三态 + 非法 key 抛错（单测） |
| D2 | 全文搜索/笔记/字数/双页/转电子书/滚动手势/内存 | 引擎（kookit-extra）能力 → **延后** | — |
| D3 | 扫描版 OCR | 引擎+外部模型 → **延后**（任务原文亦建议待官方确认） | — |

## 3. 类型与错误处理约定
- 所有新模块在 `interface.tsx`/同文件 `export type` 定义类型，禁止 `any` 扩散（引擎边界处显式 `unknown` 断言）。
- localStorage 读写一律 try/catch：JSON 解析失败→默认值，不向调用方抛。
- 纯函数（聚合/构建/校验）独立于 React 与引擎 API，可被 jsdom 单测直接覆盖；引擎 `ConfigService` 相关逻辑在测试中 `jest.mock`（沿用 `dropdownList.test.tsx` 模式）。
- 用户可见文案全部走 `t("...")`，新 key 补入 `src/assets/locales/en.json`。

## 4. 延后清单（不在本次交付，需引擎/后续迭代）
1. PDF 全文搜索、笔记、字数统计、双页连续、转电子书模式、滚动手势与内存优化（kookit-extra 闭源引擎）。
2. 扫描版 PDF OCR（同引擎；且需外部 OCR 运行时）。
3. 自定义标签颜色、多级分类批量移动、按目录自动建分类、网络封面搜索、批量下载书籍（依赖引擎 shelf 数据结构与云同步协议）。
4. 页眉页脚「电池百分比/剩余时间」的引擎侧页内渲染（本次交付工具函数与配置 key，渲染待引擎版本）。
5. 自动阅读优化、封面书脊效果等视觉细节。

## 5. 测试策略
- 新增单测文件（8 个）：`readingCss.test.ts`、`shortcutUtil.test.ts`、`fontScopeUtil.test.ts`、`fontZip.test.ts`、`statsUtil.test.ts`、`batteryUtil.test.ts`、`bookShelf.test.ts`、`pdfPasswordUtil.test.ts`、`viewMode.test.tsx`。
- 回归：`react-scripts test`（CRA jest, jsdom）全量跑，既有 9 个测试文件不得回归。
- 不做 `yarn build` 全量构建（时间盒）；以 TS 单测编译（ts-jest/babel）覆盖类型错误。
