package com.moon.jejukids.logic

import com.moon.jejukids.data.Air
import com.moon.jejukids.data.Festival
import com.moon.jejukids.data.Place
import com.moon.jejukids.data.Region
import com.moon.jejukids.data.Setting
import com.moon.jejukids.data.Weather
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class Mode(val label: String) { INDOOR("실내 추천"), OUTDOOR("야외 추천"), ANY("어디든 좋아요") }

data class Advice(val mode: Mode, val headline: String, val reasons: List<String>)

object Recommender {

    /** 날씨·미세먼지로 오늘 실내/야외 중 어디가 나은지 정한다. 정보가 없으면 ANY. */
    fun advise(weather: Weather?, air: Air?): Advice {
        val reasons = mutableListOf<String>()
        var indoor = false
        if (weather != null) {
            if (weather.rainNow) { indoor = true; reasons += "지금 비·눈 예보가 있어요" }
            else if (weather.rainSoon) { indoor = true; reasons += "6시간 안에 비 소식 (강수확률 최대 ${weather.popMax}%)" }
            val t = weather.tempNow
            if (t != null && t >= 31) { indoor = true; reasons += "폭염 (${t}°C) — 한낮 야외는 피하세요" }
            if (t != null && t <= 3) { indoor = true; reasons += "추워요 (${t}°C)" }
        }
        if (air != null && air.grade >= 3) { indoor = true; reasons += "미세먼지 ${air.gradeLabel}" }

        return when {
            weather == null && air == null -> Advice(Mode.ANY, "날씨 정보를 불러오지 못했어요", reasons)
            indoor -> Advice(Mode.INDOOR, "오늘은 실내 나들이 추천", reasons)
            else -> {
                if (weather != null) reasons += "${weather.sky.label}, 강수확률 최대 ${weather.popMax}%"
                if (air != null) reasons += "미세먼지 ${air.gradeLabel}"
                Advice(Mode.OUTDOOR, "야외 나들이 좋은 날", reasons)
            }
        }
    }

    fun fits(setting: Setting, mode: Mode): Boolean = when (mode) {
        Mode.ANY -> true
        Mode.INDOOR -> setting != Setting.OUTDOOR
        Mode.OUTDOOR -> setting != Setting.INDOOR
    }

    /**
     * 추천 장소 고르기. 점수 = 아이 관련도 + 가까움 + 아이랑 후기 수 + 날짜(와 [shuffle])로 섞는 값.
     * 2026-10-07 사용자 지적("거의 고정"): 가까움이 최대 3점이라 늘 집 근처 같은 곳이 뽑혔다 →
     * 25km 안은 완만하게 최대 2점으로 낮추고, 섞는 값을 키우고, 비슷한 곳(감귤농장 두 곳 등)은 함께 뽑지 않는다.
     */
    fun pick(
        places: List<Place>,
        mode: Mode,
        region: Region,
        today: LocalDate,
        here: Pair<Double, Double>?,
        count: Int = 10,
        signals: Map<String, com.moon.jejukids.data.KidSignal> = emptyMap(),
        shuffle: Int = 0,
    ): List<Place> {
        val seed = today.toEpochDay() * 1_000_003L + shuffle * 7919L
        val ranked = places
            .filter { fits(it.setting, mode) }
            .sortedByDescending { p ->
                val rotation = mix(seed xor (p.id.hashCode().toLong() shl 20))
                val near = if (here != null && p.lat != null && p.lng != null) {
                    val km = distanceKm(here.first, here.second, p.lat, p.lng)
                    (25.0 - km).coerceAtLeast(0.0) / 25.0 * 2.0
                } else if (p.region == region) 1.0 else 0.0
                val posts = signals[p.id]?.kidPostCount ?: 0
                val popular = kotlin.math.log10(1.0 + posts).coerceAtMost(4.0) * 0.4
                minOf(p.kidScore, 8) * 0.4 + near + popular + rotation * 6
            }
        // 비슷한 곳은 한 번만(같은 분류·같은 종류 이름). 모자라면 나머지로 채운다.
        val chosen = mutableListOf<Place>()
        val groups = mutableSetOf<String>()
        for (p in ranked) {
            if (chosen.size >= count) break
            val g = group(p)
            if (g in groups) continue
            chosen += p
            groups += g
        }
        if (chosen.size < count) chosen += ranked.filter { it !in chosen }.take(count - chosen.size)
        return chosen
    }

    /** 0~1 사이로 고르게 섞는 값(SplitMix64). 날짜·"다른 곳" 횟수마다 순서가 확실히 달라지게. */
    private fun mix(x: Long): Double {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return (z ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    private val sameKind = listOf(
        Regex("감귤|귤") to "감귤", Regex("승마|목장") to "말", Regex("카트") to "카트", Regex("해수욕장|해변") to "해변",
        Regex("오름") to "오름", Regex("박물관") to "박물관", Regex("미술관|갤러리") to "미술관", Regex("휴양림|숲") to "숲",
        Regex("동물|농장") to "동물", Regex("잠수함") to "잠수함", Regex("도서관") to "도서관",
    )

    /** 비슷한 곳 묶음 이름: 이름의 종류 단어가 먼저, 없으면 첫 분류. */
    fun group(p: Place): String = sameKind.firstOrNull { it.first.containsMatchIn(p.title) }?.second
        ?: p.tags.firstOrNull()?.name ?: p.setting.name

    fun ongoing(f: Festival, today: LocalDate) = !f.start.isAfter(today) && !f.end.isBefore(today)

    /** 이번 주말(토·일). 오늘이 주말이면 오늘이 속한 주말. */
    fun weekend(today: LocalDate): Pair<LocalDate, LocalDate> {
        val sat = when (today.dayOfWeek) {
            DayOfWeek.SATURDAY -> today
            DayOfWeek.SUNDAY -> today.minusDays(1)
            else -> today.plusDays((DayOfWeek.SATURDAY.value - today.dayOfWeek.value).toLong())
        }
        return sat to sat.plusDays(1)
    }

    fun onWeekend(f: Festival, today: LocalDate): Boolean {
        val (sat, sun) = weekend(today)
        val from = maxOf(sat, today)
        return !f.start.isAfter(sun) && !f.end.isBefore(from)
    }

    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
