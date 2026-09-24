package com.koodoreader.core.locale

/**
 * Curated OpenCC-format seed dictionaries (upstream file format, so the full
 * upstream packs load through [OpenCcDictionary.parse] without changes).
 *
 * Provenance: the entries follow the published OpenCC dictionaries
 * (https://github.com/BYVoid/OpenCC, Apache-2.0) — `STCharacters`,
 * `STPhrases`, `TWPhrasesIT` — reduced to the high-frequency core that the
 * desktop reader exercises in practice. Two deliberate design points, both
 * mirroring upstream:
 *
 *  1. **Ambiguous characters are NOT in the character table.** `干` (乾/幹/干),
 *     `后` (後/后), `里` (裏/里), `面` (麵/面), `只` (隻/只), `台` (臺/檯/颱),
 *     `历` (歷/曆), `于` (於/于), `复` (復/複/覆), `脏` (髒/臟), `周` (週/周)
 *     are resolved by the *phrase* stage instead, which runs first. That is why
 *     `皇后`/`乾隆`/`干涉` stay untouched while `后来`/`饼干`/`干扰` convert.
 *  2. **Traditional→Simplified is derived** by reversing the S→T tables plus a
 *     tiny explicit table for traditional-only characters that have no S→T
 *     counterpart in the seed (`臺/檯/颱/裏/裡/隻/髮`). One known gap: `著` is
 *     intentionally absent, so `看著` stays `看著` instead of becoming `看着` —
 *     adding `著` would corrupt `著名`/`著作`. See design doc §3.3.
 *
 * Every line is `key<TAB>value1 [value2 …]`; `#` starts a comment.
 */
object OpenCcSeed {

    /** Simplified → Traditional, unambiguous single characters. */
    val ST_CHARACTERS: String = """
        # --- pronouns, particles, everyday verbs -------------------------------
        们	們
        这	這
        来	來
        说	說
        时	時
        会	會
        对	對
        开	開
        关	關
        门	門
        问	問
        间	間
        闻	聞
        电	電
        见	見
        现	現
        观	觀
        规	規
        视	視
        觉	覺
        学	學
        举	舉
        与	與
        写	寫
        师	師
        帅	帥
        归	歸
        当	當
        录	錄
        寻	尋
        导	導
        尔	爾
        层	層
        尽	盡
        尝	嘗
        岁	歲
        岂	豈
        岛	島
        岭	嶺
        币	幣
        帮	幫
        带	帶
        广	廣
        庆	慶
        应	應
        库	庫
        废	廢
        张	張
        弥	彌
        强	強
        彻	徹
        径	徑
        忆	憶
        忧	憂
        怀	懷
        态	態
        总	總
        恶	惡
        惯	慣
        戏	戲
        战	戰
        户	戶
        扑	撲
        执	執
        扩	擴
        扫	掃
        扬	揚
        护	護
        报	報
        担	擔
        拟	擬
        拥	擁
        择	擇
        挂	掛
        换	換
        据	據
        损	損
        摄	攝
        摆	擺
        数	數
        敌	敵
        断	斷
        无	無
        旧	舊
        显	顯
        术	術
        机	機
        杀	殺
        杂	雜
        权	權
        条	條
        极	極
        构	構
        树	樹
        样	樣
        检	檢
        楼	樓
        欢	歡
        欧	歐
        残	殘
        毁	毀
        气	氣
        汉	漢
        汤	湯
        决	決
        没	沒
        沟	溝
        泪	淚
        测	測
        济	濟
        浅	淺
        浊	濁
        润	潤
        渐	漸
        温	溫
        湿	濕
        满	滿
        潜	潛
        灭	滅
        灯	燈
        灵	靈
        灾	災
        炉	爐
        点	點
        烦	煩
        热	熱
        爱	愛
        爷	爺
        牵	牽
        状	狀
        犹	猶
        获	獲
        献	獻
        环	環
        产	產
        毕	畢
        画	畫
        疗	療
        发	發
        变	變
        叠	疊
        叶	葉
        号	號
        叹	嘆
        听	聽
        吗	嗎
        吨	噸
        唤	喚
        团	團
        园	園
        围	圍
        图	圖
        圆	圓
        场	場
        坏	壞
        块	塊
        坚	堅
        坛	壇
        坝	壩
        坟	墳
        垄	壟
        垒	壘
        垦	墾
        垫	墊
        墙	牆
        壮	壯
        声	聲
        壳	殼
        处	處
        备	備
        够	夠
        头	頭
        夹	夾
        夺	奪
        奋	奮
        奖	獎
        妇	婦
        妈	媽
        娱	娛
        婴	嬰
        宁	寧
        实	實
        宝	寶
        审	審
        宽	寬
        宾	賓
        将	將
        尘	塵
        届	屆
        属	屬
        岗	崗
        帐	帳
        账	賬
        幂	冪
        并	並
        庄	莊
        庐	廬
        异	異
        弃	棄
        弹	彈
        复	復
        宫	宮
        寿	壽
        尴	尷
        尸	屍
        屿	嶼
        帘	簾
        帜	幟
        帧	幀
        铁	鐵
        银	銀
        铜	銅
        钢	鋼
        锁	鎖
        镜	鏡
        钱	錢
        针	針
        钟	鐘
        钩	鉤
        钥	鑰
        闭	閉
        闲	閒
        闯	闖
        闷	悶
        闹	鬧
        阳	陽
        阴	陰
        队	隊
        际	際
        陆	陸
        陈	陳
        险	險
        随	隨
        隐	隱
        难	難
        虽	雖
        双	雙
        优	優
        馈	饋
        么	麼
        万	萬
        丑	醜
        专	專
        业	業
        丝	絲
        两	兩
        严	嚴
        丧	喪
        个	個
        丰	豐
        临	臨
        为	為
        丽	麗
        义	義
        乌	烏
        乐	樂
        乔	喬
        习	習
        乡	鄉
        书	書
        买	買
        乱	亂
        争	爭
        亏	虧
        亚	亞
        亩	畝
        亲	親
        亿	億
        仅	僅
        从	從
        仑	侖
        仓	倉
        仪	儀
        价	價
        众	眾
        伟	偉
        传	傳
        伤	傷
        伦	倫
        体	體
        余	餘
        侠	俠
        侣	侶
        侥	僥
        侦	偵
        侧	側
        俭	儉
        债	債
        倾	傾
        偿	償
        储	儲
        儿	兒
        兑	兌
        兰	蘭
        兴	興
        养	養
        兽	獸
        内	內
        冈	岡
        册	冊
        军	軍
        农	農
        冯	馮
        冻	凍
        况	況
        净	淨
        凉	涼
        减	減
        凑	湊
        凤	鳳
        凭	憑
        凯	凱
        击	擊
        凿	鑿
        刍	芻
        刘	劉
        则	則
        刚	剛
        创	創
        删	刪
        别	別
        刹	剎
        剂	劑
        剐	剮
        剑	劍
        剥	剝
        剧	劇
        劝	勸
        办	辦
        务	務
        动	動
        励	勵
        劲	勁
        劳	勞
        势	勢
        勋	勳
        匀	勻
        匮	匱
        区	區
        医	醫
        华	華
        协	協
        单	單
        卖	賣
        卢	盧
        卫	衛
        却	卻
        厂	廠
        厅	廳
        历	歷
        厉	厲
        压	壓
        厌	厭
        厕	廁
        厨	廚
        县	縣
        参	參
        # --- 讠 (speech) radical ------------------------------------------------
        计	計
        订	訂
        认	認
        讨	討
        让	讓
        训	訓
        议	議
        讯	訊
        记	記
        讲	講
        许	許
        论	論
        设	設
        访	訪
        诀	訣
        证	證
        评	評
        识	識
        诉	訴
        诊	診
        词	詞
        译	譯
        试	試
        诗	詩
        诚	誠
        话	話
        询	詢
        该	該
        详	詳
        语	語
        误	誤
        请	請
        诸	諸
        诺	諾
        读	讀
        课	課
        谁	誰
        调	調
        谈	談
        谋	謀
        谎	謊
        谢	謝
        谨	謹
        谱	譜
        誉	譽
        谦	謙
        # --- 贝 / 页 (money, pages) --------------------------------------------
        贝	貝
        财	財
        责	責
        货	貨
        质	質
        贵	貴
        购	購
        费	費
        资	資
        赏	賞
        赔	賠
        赚	賺
        赞	讚
        赠	贈
        赢	贏
        贫	貧
        贺	賀
        贴	貼
        页	頁
        顶	頂
        项	項
        顺	順
        须	須
        顾	顧
        预	預
        领	領
        频	頻
        颗	顆
        题	題
        颜	顏
        愿	願
        颠	顛
        # --- 纟 (silk) radical --------------------------------------------------
        组	組
        细	細
        织	織
        终	終
        经	經
        绍	紹
        给	給
        络	絡
        绝	絕
        统	統
        继	繼
        绩	績
        绪	緒
        续	續
        维	維
        绿	綠
        缘	緣
        编	編
        练	練
        线	線
        红	紅
        级	級
        纪	紀
        约	約
        纯	純
        纸	紙
        纳	納
        缓	緩
        缩	縮
        绳	繩
        # --- 车 / 马 (vehicles) -------------------------------------------------
        车	車
        轮	輪
        转	轉
        轻	輕
        载	載
        输	輸
        辆	輛
        辅	輔
        轨	軌
        辈	輩
        辉	輝
        辑	輯
        马	馬
        驾	駕
        骂	罵
        骑	騎
        验	驗
        骗	騙
        骄	驕
        # --- 饣 / 门 / 鱼 / 鸟 / 龙 (daily items) -------------------------------
        饭	飯
        饮	飲
        饱	飽
        馆	館
        饲	飼
        饰	飾
        闪	閃
        阅	閱
        阔	闊
        鱼	魚
        鲜	鮮
        鸟	鳥
        鸡	雞
        鹅	鵝
        鹰	鷹
        龙	龍
        龟	龜
        齿	齒
        龄	齡
        # --- IT / reader vocabulary --------------------------------------------
        网	網
        软	軟
        键	鍵
        盘	盤
        标	標
        签	簽
        档	檔
        简	簡
        类	類
        联	聯
        响	響
        员	員
        风	風
        飞	飛
    """.trimIndent()

    /**
     * Simplified → Traditional phrase disambiguation (runs BEFORE the character
     * stage; longest key wins). These entries exist because the corresponding
     * character is ambiguous on its own — see the class comment.
     */
    val ST_PHRASES: String = """
        # --- 发 (發 / 髮) -------------------------------------------------------
        头发	頭髮
        白发	白髮
        理发	理髮
        发型	髮型
        发廊	髮廊
        # --- 干 (乾 / 幹 / 干) -------------------------------------------------
        干净	乾淨
        干脆	乾脆
        干旱	乾旱
        干杯	乾杯
        饼干	餅乾
        干活	幹活
        干部	幹部
        能干	能幹
        树干	樹幹
        干事	幹事
        干什么	幹什麼
        干吗	幹嗎
        骨干	骨幹
        苦干	苦幹
        才干	才幹
        干扰	干擾
        干预	干預
        # --- 后 (後 / 后) ------------------------------------------------------
        后来	後來
        后面	後面
        后果	後果
        然后	然後
        最后	最後
        背后	背後
        之后	之後
        以后	以後
        # --- 里 (裏 / 里) ------------------------------------------------------
        里面	裏面
        心里	心裏
        家里	家裏
        那里	那裏
        这里	這裏
        哪里	哪裏
        # --- 面 (麵 / 面) ------------------------------------------------------
        面条	麵條
        面粉	麵粉
        面包	麵包
        方便面	方便麵
        # --- 划 (劃 / 划) ------------------------------------------------------
        计划	計劃
        规划	規劃
        策划	策劃
        # --- 复 (復 / 複 / 覆) -------------------------------------------------
        复杂	複雜
        复制	複製
        重复	重複
        复习	復習
        恢复	恢復
        反复	反覆
        答复	答覆
        # --- 准 (準 / 准) ------------------------------------------------------
        准备	準備
        标准	標準
        准确	準確
        # --- 脏 (髒 / 臟) ------------------------------------------------------
        脏话	髒話
        心脏	心臟
        # --- 于 (於 / 于) ------------------------------------------------------
        由于	由於
        对于	對於
        关于	關於
        终于	終於
        于是	於是
        # --- 只 (隻 / 只) ------------------------------------------------------
        一只	一隻
        两只	兩隻
        三只	三隻
        几只	幾隻
        只身	隻身
        # --- 历 (歷 / 曆) ------------------------------------------------------
        日历	日曆
        农历	農曆
        阳历	陽曆
        阴历	陰曆
    """.trimIndent()

    /**
     * Traditional → Traditional(Taiwan) terms — the equivalent of OpenCC's
     * `TWPhrasesIT` (IT/settings vocabulary) plus a few everyday items. Applied
     * after the S→T chain, so keys are written in traditional characters.
     */
    val TW_PHRASES: String = """
        軟件	軟體
        硬件	硬體
        網絡	網路
        互聯網	網際網路
        信息	資訊
        計算機	電腦
        鼠標	滑鼠
        硬盤	硬碟
        內存	記憶體
        打印機	印表機
        打印	列印
        視頻	影片
        音頻	音訊
        錄像	錄影
        短信	簡訊
        出租車	計程車
        自行車	腳踏車
        公交車	公車
        地鐵	捷運
        方便麵	泡麵
        土豆	馬鈴薯
        西紅柿	番茄
        菠蘿	鳳梨
        酸奶	優酪乳
        質量	品質
        激光	雷射
        導彈	飛彈
        芯片	晶片
        程序	程式
        計劃	計畫
        數組	陣列
        對象	物件
        接口	介面
        界面	介面
        應用程序	應用程式
        服務器	伺服器
        缺省	預設
        默認	預設
        文件	檔案
        文件夾	資料夾
        剪貼板	剪貼簿
        屏幕	螢幕
        顯示器	螢幕
        光標	游標
        快捷鍵	快速鍵
        卸載	移除
        博客	部落格
        在線	線上
        鏈接	連結
        帶寬	頻寬
        U盤	隨身碟
        賬號	帳號
        登錄	登入
        註銷	登出
        優化	最佳化
        反饋	回饋
        兼容	相容
        集成	整合
        支持	支援
        用戶	使用者
        導航	導覽
        加載	載入
        保存	儲存
        設置	設定
        菜單	選單
        搜索	搜尋
        查找	尋找
    """.trimIndent()

    /**
     * Traditional-only characters that have no S→T counterpart in
     * [ST_CHARACTERS], so reversing cannot produce them. Added verbatim to the
     * T→S character table.
     */
    val TS_EXTRA_CHARACTERS: String = """
        臺	台
        檯	台
        颱	台
        裏	里
        裡	里
        隻	只
        髮	发
    """.trimIndent()
}
