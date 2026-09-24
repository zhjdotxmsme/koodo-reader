package com.koodoreader.engine.text

/**
 * Language-specific character priors for the legacy-charset heuristics.
 *
 * WHY THIS EXISTS: structure alone cannot tell GBK from Big5/EUC-KR, because
 * GBK's trail-byte range (`0x40..0x7E`, `0x80..0xFE`) is a superset of
 * everybody else's — any Big5/Shift_JIS/EUC-KR stream is *structurally* perfect
 * GBK. What differs is the text that comes out: decode Japanese bytes as GBK and
 * you get thousands of rare Han characters that never appear in ordinary prose;
 * decode them as Shift_JIS and you get kana and the 常用漢字.
 *
 * So each candidate is scored on how much of the decoded text falls into its
 * script's high-frequency set. The sets below are deliberately *small* (a few
 * hundred characters each) and cover ~35–50 % running text, which is plenty of
 * signal while keeping the module data-free of any model file.
 *
 * Sources: standard frequency tables (现代汉语常用字表 / 通用规范汉字表 first tier,
 * 常 用 漢 字 for Japanese incl. 教育漢字, and the Korean basic-Hangul set). The
 * module ships no table *files* — the sets are inline string constants, so the
 * "zero dependency" property of `engine/text` is preserved.
 */
internal object FrequencyPriors {

    /**
     * Chinese (both scripts decode to the same Han block, so GBK and Big5 share
     * this set; the *trail-byte* statistics and the mapping failures separate
     * those two).
     */
    val HAN: Set<Char> = (
        "的一是不了人我在有他這为这中大來来上以個个到说國国和地也子時时道出而要於于就下得可你年生自會会那後后能對著对事其里所去行過过家十用發发天如然作方成者多日都三小軍军二無无同么經经法當当起與与好看學学進进种将还分此心前面又定見见只主沒没公從从"
            + "知理眼志观记意见爱几九开外门问间闻关美习写让认识语词诗话读谁请谢课谈议论文字书笔纸画声听说明晚晨春夏秋冬风雪云雨雷电山川河海湖江泉石土田林森鸟鱼虫马牛羊犬猫"
            + "父母兄弟姐妹妹儿女夫妻朋亲友邻家室房窗户床桌椅餐具茶飯饭菜酒水"
            + "手足口目耳鼻头面身体力气血骨肉皮发"
            + "一二三四五六七八九十百千万亿零两"
        ).toSet()

    /**
     * Japanese: kana are the decisive signal (they are impossible to produce in
     * bulk from non-Shift_JIS bytes without hitting rare/unmappable pairs), plus
     * the most common 漢字.
     */
    val JP_KANA: Set<Char> = (
        "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"
            + "がぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽ"
            + "ぁぃぅぇぉっゃゅょゎゝゞー"
            + "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン"
            + "ガギグゲゴザジズゼゾダヂヅデドバビブベボパピプペポ"
            + "ァィゥェォッャュョヮヴヵヶ・ー"
        ).toSet()

    val JP_KANJI: Set<Char> = (
        "日一国会人年大十二本中長出三同時政事自行社見月分議後前民生連五発間対上部東者党地合市業内相方四定今回新場金員九入選立開手米力学問高代明実円関決子動京全目表戦経通外最言氏現理調体化田当八六約主題下首意法不来作性的要用制治度務強気小七成期公持野協取都和統以機平総加山思家話世受区領多県続進正安設保改数記院女初北午指権心界支第産結百派点教報済書府活原先共得解名交資予川向際査勝面委告軍文反元重近千考判認画海参売利組知案道信策集在件団別物側任引使求所次水半品昨論計死官増係感特情投示変打男基私各始島直両朝革価式確村提運終挙果西勢減台広容必応演電歳住争談能無再位置企真流格有疑口過局少放税検町常校料沢裁状工建語球営空職証土与急止送援供可役構木割聞身費付施切由説転食比難防補車優夫研収断井何南石足違消境神番規術護展態導鮮備宅害配副算視条幹独警宮究育席輸訪楽起万着乗店述残想線率病農州武声質念待試族象銀域助労例衛然早張映限親額監環験追審商葉義伝働形景落欧担好退準賞訴辺造英被株頭技低毎医復仕去姿味負閣韓渡失移差衆個門写評課末守若脳極種美岡影命含福蔵量望松非撃佐核観察整段横融型白深字答夜製票況音申様財港識注呼渉達良響阪帰針専推谷古候史天階程満敗管値歌買突然愛苦魚今高"
        ).toSet()

    /** Korean: Hangul syllables are the decisive signal (EUC-KR only). */
    val KR_HANGUL: Set<Char> = (
        "이다는을를에가은한하다있없것수나우리그저사람때말보다있다없다되다같다오다가다보이다주다받다살다만들다알다모르다좋다크다작다많다적다높다낮다길다짧다"
            + "나너그것이것무엇어디언제누구어떻게왜왜냐하면하지만그리고그러나또한따라서그래서왜냐하면"
            + "이그저것곳분명히정말아주매우너무잘못다시또이미아직항상자주가끔전혀결코"
            + "나라사람친구가족부모형제자매아이어른남자여자선생학생회사학교집밥물시간하루아침저녁밤"
            + "안밖위아래앞뒤옆사이속가운데"
        ).toSet()

    /**
     * Korean grammatical morphemes and verb endings. These are what make Korean
     * *Korean*: a GBK byte stream decoded as EUC-KR yields Hangul with the right
     * shape statistic but essentially never forms these syllables in bulk.
     */
    private val KR_MORPHEMES: Set<String> = setOf(
        "입니다", "습니다", "합니다", "입니다", "이고", "에서", "에게", "으로", "로서",
        "하는", "했다", "한다", "하였다", "되는", "있다", "없다", "같다", "보다",
        "그리고", "그러나", "하지만", "그래서", "때문에", "대해", "통해", "위해",
        "습니까", "입니까", "이다", "았다", "었다", "였다", "지고", "라고",
        "한국", "사람", "이야기", "옛날", "어느", "모두", "매우", "아주",
    )

    /**
     * `0.0 … 1.0` estimate of "this text really is Korean", based on how densely
     * the grammatical morphemes above occur. Deliberately relative to the amount
     * of Hangul, so a mostly-ASCII file scores 0.
     */
    fun koreanFlavour(text: String): Double {
        if (text.isEmpty()) return 0.0
        val hangul = text.count { isHangul(it) }
        if (hangul == 0) return 0.0
        var hits = 0
        for (m in KR_MORPHEMES) {
            var idx = text.indexOf(m)
            while (idx >= 0) {
                hits++
                idx = text.indexOf(m, idx + m.length)
            }
        }
        // ~20 % of a Korean sentence's syllables belong to one of these forms.
        return (hits.toDouble() / hangul * 4.0).coerceIn(0.0, 1.0)
    }

    /** Punctuation shared by every CJK text — neutral evidence, so it is skipped. */
    val NEUTRAL_PUNCT: Set<Char> =
        "，。！？、；：“”‘’（）《》〈〉【】…—～·　「」『』〜,.:;!?()\"' \t\n-–—/\\|".toSet()

    fun isKana(c: Char): Boolean = c in JP_KANA

    fun isHangul(c: Char): Boolean =
        c in '\uAC00'..'\uD7A3' || c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F'

    /** True for characters that carry no discriminating information. */
    fun isNeutral(c: Char): Boolean =
        c == Charsets.REPLACEMENT_CHAR || c.isWhitespace() || c.isDigit() ||
            c in NEUTRAL_PUNCT || c.isAsciiLetter()

    private fun Char.isAsciiLetter(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z'

    /**
     * How strongly one decoded character supports [legacy].
     *
     * `2.0` — the character is in the encoding's own frequent set (its native
     * script). `1.0` — plausible Han that we simply do not have in the set.
     * `0.0` — a character this encoding's language does not use in running text;
     * a wrong-encoding decode is dominated by these.
     */
    fun support(legacy: Charsets.Legacy, c: Char): Double = when (legacy) {
        Charsets.Legacy.GBK ->
            when {
                c in HAN -> 2.0
                FrequencyPriors.isHan(c) -> 1.0
                else -> 0.0
            }
        Charsets.Legacy.BIG5 ->
            when {
                c in HAN -> 2.0
                FrequencyPriors.isHan(c) -> 1.0
                else -> 0.0
            }
        Charsets.Legacy.SHIFT_JIS ->
            when {
                isKana(c) -> 2.0
                c in JP_KANJI -> 2.0
                c in HAN -> 1.0
                FrequencyPriors.isHan(c) -> 0.6
                else -> 0.0
            }
        Charsets.Legacy.EUC_KR ->
            when {
                isHangul(c) -> 2.0
                c in HAN -> 1.0
                FrequencyPriors.isHan(c) -> 0.5
                else -> 0.0
            }
    }

    fun isHan(c: Char): Boolean =
        c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' || c in '\uF900'..'\uFAFF'
}
