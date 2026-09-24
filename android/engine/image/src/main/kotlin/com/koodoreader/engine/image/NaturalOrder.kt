package com.koodoreader.engine.image

/**
 * 归档条目名的 natural（数字感知）排序，用于漫画页序。
 *
 * **为什么有一份副本**：P1 已把同一算法落在 `core:importer` 的
 * `ComicCover.naturalComparator()`，但它是 `internal`（仅模块内可见），
 * 本模块无法直接调用，而页序必须与封面抽取/`books.page` 完全一致
 * （否则「封面 = 第 0 页」不成立）。
 *
 * 因此这里的实现**逐行对齐** P1 的算法，并由行为断言锁死两端一致：
 * `ZipExtractorTest.comicCoverParity_*` 用同一个 CBZ 调
 * `ComicCover.extract(file)`，断言其封面字节 == 本模块 `pages[0]` 的字节。
 *
 * 待办（ADR §8）：P1 把 `ComicCover.naturalComparator()` 提升为 public 后，
 * 删除本文件，`ZipExtractor` 改为直接引用，消除重复。
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            val da = ca.isDigit()
            val db = cb.isDigit()
            if (da && db) {
                val va = readInt(a, i)
                val vb = readInt(b, j)
                val cmp = va.value.compareTo(vb.value)
                if (cmp != 0) return cmp
                if (va.consumed != vb.consumed) {
                    // "07" vs "7"：数值相等，前导零更长者排前（与 P1 一致）
                    return vb.consumed.compareTo(va.consumed)
                }
                i += va.consumed
                j += vb.consumed
            } else if (da != db) {
                // 一侧是数字一侧是字母：数字在前（稳定选择，与 P1 一致）
                return if (da) -1 else 1
            } else {
                val cmp = Character.toLowerCase(ca).compareTo(Character.toLowerCase(cb))
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    /** 便捷入口：返回按 natural 顺序排列的新列表。 */
    fun sorted(names: Collection<String>): List<String> = names.sortedWith(this)

    private class IntRun(val value: Long, val consumed: Int)

    private fun readInt(s: String, from: Int): IntRun {
        var value = 0L
        var i = from
        while (i < s.length && s[i].isDigit()) {
            value = (value * 10 + (s[i] - '0')).coerceAtMost(Long.MAX_VALUE / 2)
            i++
        }
        return IntRun(value, i - from)
    }
}
