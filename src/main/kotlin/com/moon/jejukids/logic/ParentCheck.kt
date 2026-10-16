package com.moon.jejukids.logic

import com.moon.jejukids.data.Detail
import com.moon.jejukids.data.KidSignal

/**
 * 상세 화면 "부모 체크 정보": 부모가 가기 전에 확인하는 항목을 늘 같은 순서로.
 * 값은 관광공사 상세 원문 그대로, 없으면 "정보 없음". 블로그 후기에서만 나온 것은 "확인 필요"를 붙인다.
 * 운영시간이 있어도 "지금 영업 중"이라고 하지 않는다(안내된 시간일 뿐 임시 휴무·변경을 모른다).
 */
object ParentCheck {

    data class Item(val label: String, val value: String, val note: String? = null, val missing: Boolean = false)

    const val NONE = "정보 없음"

    fun items(d: Detail?, sig: KidSignal?): List<Item> {
        fun fact(vararg names: String) = d?.facts?.firstOrNull { it.first in names }?.second?.trim()?.takeIf { it.isNotEmpty() }
        fun kw(name: String) = sig?.keywords.orEmpty().firstOrNull { it.first == name }?.second ?: 0
        val out = mutableListOf<Item>()

        out += fact("이용 시간", "공연·운영 시간")?.let { Item("운영시간", it, "안내된 시간 · 바뀔 수 있어요") } ?: Item("운영시간", NONE, missing = true)
        out += fact("쉬는 날")?.let { Item("휴무일", it, "공휴일·임시 휴무는 따로 확인") } ?: Item("휴무일", NONE, missing = true)

        val age = fact("체험 가능 연령", "관람 연령")
        val reviewAges = sig?.let { Insight.agesFromCounts(it.ageCounts) }.orEmpty()
        out += when {
            age != null -> Item("이용 연령", age)
            reviewAges.isNotEmpty() -> Item("이용 연령", "후기상 ${reviewAges.label()} 아이가 많이 다녀감", "확인 필요")
            else -> Item("이용 연령", NONE, missing = true)
        }

        out += fact("요금")?.let { Item("요금", it) } ?: Item("요금", NONE, missing = true)

        val bookingText = listOfNotNull(fact("체험 안내", "이용 시간", "요금"), d?.overview).joinToString(" ")
        out += if (Regex("예약").containsMatchIn(bookingText)) Item("예약", "안내문에 '예약' 언급 있음", "확인 필요")
        else Item("예약", NONE, missing = true)

        out += fact("주차")?.let { p -> Item("주차", listOfNotNull(p, fact("주차 요금")?.let { "요금 $it" }).joinToString(" · ")) } ?: Item("주차", NONE, missing = true)

        val stroller = fact("유모차")
        val strollerPosts = kw("유모차 OK")
        out += when {
            stroller != null -> Item("유모차 대여", stroller, if (strollerPosts >= 2) "후기에 유모차 이동 언급 ${strollerPosts}건" else null)
            strollerPosts >= 2 -> Item("유모차", "후기에 유모차 이동 언급 ${strollerPosts}건", "확인 필요")
            else -> Item("유모차", NONE, missing = true)
        }

        val nursing = kw("수유실")
        val diaper = kw("기저귀 갈이대")
        out += if (nursing + diaper > 0) Item(
            "수유실·기저귀",
            listOfNotNull(nursing.takeIf { it > 0 }?.let { "수유실 후기 ${it}건" }, diaper.takeIf { it > 0 }?.let { "기저귀 후기 ${it}건" }).joinToString(" · "),
            "후기 언급 · 확인 필요",
        ) else Item("수유실·기저귀", NONE, missing = true)
        return out
    }
}
