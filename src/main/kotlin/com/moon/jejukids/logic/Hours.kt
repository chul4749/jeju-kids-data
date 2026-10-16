package com.moon.jejukids.logic

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 관광공사 상세의 "쉬는 날"·"체험 가능 연령" 문장을 규칙으로 읽는다(AI 아님).
 * 확실한 문장만 해석하고, 애매하면 UNKNOWN — 추천에서 빼는 건 "오늘 정기 휴무"가 문장으로 분명할 때뿐이다.
 * 2026-10-16 실데이터 272곳: 연중무휴 216, "매주 월요일" 류, "매주 화요일~목요일", "매월 첫째 주 월요일",
 * "매주 수요일 / 일요일", 설·추석·공휴일(달력이 없어 판단 안 함), 시설마다 다름(판단 안 함).
 */
object Hours {

    enum class Today { CLOSED, OPEN_BY_NOTICE, UNKNOWN }

    private val days = listOf("월" to DayOfWeek.MONDAY, "화" to DayOfWeek.TUESDAY, "수" to DayOfWeek.WEDNESDAY, "목" to DayOfWeek.THURSDAY,
        "금" to DayOfWeek.FRIDAY, "토" to DayOfWeek.SATURDAY, "일" to DayOfWeek.SUNDAY)
    private fun day(ch: String) = days.first { it.first == ch }.second

    private val range = Regex("([월화수목금토일])요일\\s*[~\\-–]\\s*([월화수목금토일])요일")
    private val single = Regex("([월화수목금토일])요일")
    private val nth = mapOf("첫째" to 1, "둘째" to 2, "셋째" to 3, "넷째" to 4, "다섯째" to 5)

    /** 오늘 정기 휴무인지. [restText]는 "쉬는 날" 원문. */
    fun today(restText: String?, date: LocalDate): Today {
        val raw = restText?.trim().orEmpty()
        if (raw.isEmpty()) return Today.UNKNOWN
        // 시설마다 다르거나(대괄호로 여러 시설), "상이" 같은 말은 판단하지 않는다.
        if (raw.contains('[') || raw.contains("상이")) return Today.UNKNOWN
        // 설명 꼬리(※…)와 괄호 속 예외 설명은 뺀다("월요일이 공휴일이면 다음날").
        val text = raw.substringBefore('※').replace(Regex("\\([^)]*\\)"), " ")
        if (text.contains("연중무휴")) return Today.OPEN_BY_NOTICE
        val weekly = mutableSetOf<DayOfWeek>()
        var monthly = false
        var monthlyHit = false
        for (part in text.split('/')) {
            val p = part.trim()
            if (p.contains("매월")) {
                monthly = true
                val ns = nth.filterKeys { p.contains(it) }.values
                val ds = single.findAll(p).map { day(it.groupValues[1]) }.toSet()
                val weekIndex = (date.dayOfMonth - 1) / 7 + 1
                if (date.dayOfWeek in ds && weekIndex in ns) monthlyHit = true
                continue
            }
            // "매주" 문장, 또는 매주 문장 뒤에 "/"로 이어 쓴 요일("매주 수요일 / 일요일").
            if (p.contains("매주") || (weekly.isNotEmpty() && single.containsMatchIn(p) && !p.contains("월 1일"))) {
                range.findAll(p).forEach { m ->
                    var d = day(m.groupValues[1])
                    val end = day(m.groupValues[2])
                    while (true) { weekly += d; if (d == end) break; d = d.plus(1) }
                }
                single.findAll(p.replace(range, " ")).forEach { weekly += day(it.groupValues[1]) }
            }
        }
        return when {
            date.dayOfWeek in weekly || monthlyHit -> Today.CLOSED
            weekly.isNotEmpty() || monthly -> Today.OPEN_BY_NOTICE
            else -> Today.UNKNOWN
        }
    }

    /** "8세 이상", "만 6세 이상", "24개월 이상", "전 연령" → 최소 나이(세). 모르면 null, 전 연령은 0. */
    fun minAge(text: String?): Int? {
        val t = text ?: return null
        if (t.contains("전 연령") || t.contains("전연령") || t.contains("전체")) return 0
        Regex("([0-9]+)\\s*개월\\s*이상").find(t)?.let { return it.groupValues[1].toInt() / 12 }
        Regex("([0-9]+)\\s*세\\s*이상").find(t)?.let { return it.groupValues[1].toInt() }
        if (t.contains("초등학생 이상") || t.contains("초등 이상")) return 7
        return null
    }

    /** 연령대의 나이 범위(세). */
    fun yearsOf(a: AgeGroup): IntRange = when (a) {
        AgeGroup.INFANT -> 0..2
        AgeGroup.PRESCHOOL -> 3..6
        AgeGroup.SCHOOL -> 7..12
    }
}
