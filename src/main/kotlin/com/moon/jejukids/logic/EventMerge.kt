package com.moon.jejukids.logic

import com.moon.jejukids.data.Festival
import com.moon.jejukids.data.eventKey
import java.time.temporal.ChronoUnit

/**
 * 출처 6곳에 같은 행사가 제목만 조금씩 다르게 올라온다. 예(2026-10 실데이터):
 *  - "금토금토 새연쇼"(서귀포시, 주마다 따로) / "새연교 주말 문화공연 '금토금토 새연쇼'"(관광공사) / "금토금토 세연쇼"(오타)
 *  - "서귀포칠십리축제" / "제32회 서귀포칠십리축제"
 * 제목이 같거나·한쪽이 다른 쪽을 품거나·글자쌍이 많이 겹치고, 날짜가 겹치거나 2주 안이면 같은 행사로 묶는다.
 */
object EventMerge {

    /** 남길 대표 출처 순서: 정보가 많고 공식적인 쪽이 앞. */
    private val priority = listOf("관광공사", "제주시", "서귀포시", "비짓제주", "KOPIS", "플레이제주", "제주인놀다", "교육청 예약")

    private fun bigrams(s: String) = s.windowed(2).groupingBy { it }.eachCount()

    /** 글자쌍(Dice) 유사도 0~1. */
    fun similarity(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val x = bigrams(a)
        val y = bigrams(b)
        val common = x.entries.sumOf { (k, v) -> minOf(v, y[k] ?: 0) }
        return 2.0 * common / ((a.length - 1) + (b.length - 1))
    }

    fun sameTitle(a: String, b: String): Boolean {
        val ka = eventKey(a)
        val kb = eventKey(b)
        if (ka.isEmpty() || kb.isEmpty()) return false
        if (ka == kb) return true
        val (short, long) = if (ka.length <= kb.length) ka to kb else kb to ka
        if (short.length >= 5 && long.contains(short)) return true
        if (similarity(ka, kb) >= 0.65) return true
        // 긴 제목 안에 짧은 제목이 오타째로 들어 있는 경우("…금토금토 새연쇼" ↔ "금토금토 세연쇼"):
        // 같은 길이 구간마다 비교해 아주 비슷한 곳이 있으면 같은 이름으로 본다.
        // 글자 하나가 다르면 글자쌍 2개가 어긋나 7글자 기준 0.67이 된다 → 6글자 이상에서 0.65.
        if (short.length >= 6 && long.length > short.length) {
            return long.windowed(short.length).any { similarity(it, short) >= 0.65 }
        }
        return false
    }

    fun closeInTime(a: Festival, b: Festival, days: Long = 14): Boolean {
        val gap = when {
            a.end.isBefore(b.start) -> ChronoUnit.DAYS.between(a.end, b.start)
            b.end.isBefore(a.start) -> ChronoUnit.DAYS.between(b.end, a.start)
            else -> 0
        }
        return gap <= days
    }

    private val quoted = Regex("""[<〈《「『\["']([^>〉》」』\]"']{2,})[>〉》」』\]"']""")

    /** 제목 속 괄호·따옴표 안 이름들. "우당도서관 … <다함께 만드는 연극>" → ["다함께 만드는 연극"]. */
    fun quotedNames(title: String): List<String> = quoted.findAll(title).map { eventKey(it.groupValues[1]) }.filter { it.length >= 2 }.toList()

    private val institution = Regex("[가-힣A-Za-z0-9]{1,12}(도서관|박물관|미술관|학습관|문화원|문화센터|예술의전당|기념관)")

    /** 제목에 나온 기관 이름들(띄어쓰기 없이). */
    fun institutions(title: String): Set<String> = institution.findAll(title.replace(" ", "")).map { it.value }.toSet()

    fun same(a: Festival, b: Festival): Boolean {
        // 교육청 예약은 회차·신청 단위라(10/17, 10/24 …, "영화제 어린이 심사위원 모집") 제목이 완전히 같을 때만 묶는다.
        if (a.source == "교육청 예약" || b.source == "교육청 예약") return eventKey(a.title) == eventKey(b.title) && a.start == b.start
        if (!closeInTime(a, b) || !sameTitle(a.title, b.title)) return false
        // 같은 이름이라도 장소가 10km 넘게 떨어져 있으면 다른 공연이다
        // ("호두까기인형" 서귀포예술의전당 12/12 ↔ "호두까기 인형" 설문대여성문화센터 12/13, 2026-10-08).
        if (a.lat != null && a.lng != null && b.lat != null && b.lng != null &&
            Recommender.distanceKm(a.lat, a.lng, b.lat, b.lng) > 10
        ) return false
        // 둘 다 괄호 속 이름이 있는데 서로 다르면 다른 프로그램이다(같은 도서관·시리즈의 다른 회).
        val qa = quotedNames(a.title)
        val qb = quotedNames(b.title)
        // 단, 한쪽 괄호 이름이 다른 쪽 제목 안에 그대로 있으면 같은 행사다("[도립예술단 합동공연] 제주, 신들이…" ↔ "합동공연 <제주, 신들이…>").
        val ka = eventKey(a.title)
        val kb = eventKey(b.title)
        val crossed = qa.any { it.length >= 5 && kb.contains(it) } || qb.any { it.length >= 5 && ka.contains(it) }
        if (!crossed && qa.isNotEmpty() && qb.isNotEmpty() && qa.none { x -> qb.any { y -> similarity(x, y) >= 0.6 } }) return false
        // 같은 이름의 전국 사업이 기관마다 열린다("탐라도서관 <길 위의 인문학>" ↔ "중앙도서관 「길 위의 인문학」").
        val ia = institutions(a.title)
        val ib = institutions(b.title)
        if (ia.isNotEmpty() && ib.isNotEmpty() && ia.intersect(ib).isEmpty()) return false
        return true
    }

    /** 같은 행사끼리 묶은 무리들(각 무리는 대표 출처 순으로 정렬). union-find, 250건 안팎이라 전부 비교해도 빠르다. */
    fun clusters(all: List<Festival>): List<List<Festival>> {
        val parent = IntArray(all.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in all.indices) for (j in i + 1 until all.size) {
            if (same(all[i], all[j])) parent[find(i)] = find(j)
        }
        return all.indices.groupBy(::find).values.map { idx ->
            idx.map { all[it] }.sortedBy { f -> priority.indexOf(f.source).let { if (it < 0) 99 else it } }
        }
    }

    fun merge(all: List<Festival>): List<Festival> =
        clusters(all).map { group ->
            val first = group.first()
            first.copy(
                start = group.minOf { it.start },
                end = group.maxOf { it.end },
                familyFriendly = group.any { it.familyFriendly },
                image = first.image ?: group.firstNotNullOfOrNull { it.image },
                lat = first.lat ?: group.firstNotNullOfOrNull { it.lat },
                lng = first.lng ?: group.firstNotNullOfOrNull { it.lng },
                link = first.link ?: group.firstNotNullOfOrNull { it.link },
                fee = first.fee ?: group.firstNotNullOfOrNull { it.fee },
                time = first.time ?: group.firstNotNullOfOrNull { it.time },
                venue = first.venue ?: group.firstNotNullOfOrNull { it.venue },
                tel = first.tel ?: group.firstNotNullOfOrNull { it.tel },
                targets = first.targets ?: group.firstNotNullOfOrNull { it.targets },
                category = if (group.any { it.isFestival }) "축제" else first.category,
            )
        }.sortedWith(compareBy({ it.start }, { it.title }))
}
