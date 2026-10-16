package com.moon.jejukids.logic

import com.moon.jejukids.data.Air
import com.moon.jejukids.data.Detail
import com.moon.jejukids.data.KidSignal
import com.moon.jejukids.data.Place
import com.moon.jejukids.data.PlaceTag
import com.moon.jejukids.data.Region
import com.moon.jejukids.data.Setting
import com.moon.jejukids.data.Weather
import java.time.LocalDate
import kotlin.math.log10
import kotlin.math.roundToInt

/** 홈 맨 위에서 고르는 "오늘 상황". 무료·저비용은 요금 데이터가 거의 없어(272곳 중 0곳) 아직 넣지 않았다. */
enum class Situation(val label: String) {
    WEATHER("오늘 날씨 추천"),
    RAIN("비 오는 날 실내"),
    ENERGY("에너지 발산"),
    WALK("가볍게 산책"),
    LEARN("체험·교육"),
    SHORT("짧게 놀기"),
}

/** 추천 한 곳: 역할(왜 이 자리인지)과 근거 문장들. 근거는 데이터에 있는 것만 쓴다. */
data class Suggestion(val place: Place, val role: String, val reasons: List<String>, val km: Double?)

/**
 * 홈 추천(규칙 기반, AI 아님): ① 확실히 안 맞는 곳을 먼저 빼고 ② 상황 적합도·연령 근거·거리·정보 신뢰도로 점수를 매긴 뒤
 * ③ 날짜로 섞어(같은 날에는 그대로) ④ 최근 며칠 홈에 나왔던 곳은 뒤로 미룬다.
 *
 * 빼는 기준은 "확실할 때만": 노키즈 언급, 안내된 최소 연령이 우리 아이보다 높음, 쉬는 날 문장상 오늘 정기 휴무,
 * 상황과 맞지 않음, 위치를 알 때 40km 넘음(남는 곳이 3곳보다 적으면 거리 조건은 푼다).
 */
object Suggest {

    const val FAR_KM = 40.0

    private val energyWords = Regex("놀이|점핑|트램폴린|방방|카트|모래|물놀이|어드벤처|레일바이크|승마|해수욕장|테마파크|파크|체육|수영|썰매|짚라인|미로")
    private val walkWords = Regex("숲|오름|올레|산책|공원|곶자왈|곶|해변|해안|정원|수목원|휴양림|길|폭포|습지|목장")
    private val learnWords = Regex("박물관|과학|체험|미술관|천문|도서관|전시|교육|역사|기념관|아쿠아|생태|농장")
    private val energyKeywords = setOf("모래놀이", "물놀이", "넓어요")
    private val walkKeywords = setOf("유모차 OK", "그늘")
    private val learnKeywords = setOf("먹이주기", "우유주기")

    /** 0이면 이 상황에 맞지 않음. 클수록 잘 맞음(최대 3). */
    fun fit(p: Place, sit: Situation, mode: Mode, km: Double?, signal: KidSignal?): Int {
        val kw = signal?.keywords.orEmpty().filter { it.second >= 2 }.map { it.first }.toSet()
        return when (sit) {
            Situation.WEATHER -> if (Recommender.fits(p.setting, mode)) 2 else 0
            Situation.RAIN -> when (p.setting) { Setting.INDOOR -> 3; Setting.MIXED -> 1; Setting.OUTDOOR -> 0 }
            Situation.ENERGY -> {
                var f = 0
                if (PlaceTag.THEME in p.tags || PlaceTag.BEACH in p.tags) f += 2
                if (energyWords.containsMatchIn(p.title)) f += 2
                if (kw.any { it in energyKeywords }) f += 1
                if (p.setting == Setting.OUTDOOR && PlaceTag.EXPERIENCE in p.tags) f += 1
                if (PlaceTag.MUSEUM in p.tags || PlaceTag.LIBRARY in p.tags) f = 0
                f.coerceAtMost(3)
            }
            Situation.WALK -> {
                if (p.setting == Setting.INDOOR) return 0
                var f = 0
                if (PlaceTag.NATURE in p.tags || PlaceTag.BEACH in p.tags) f += 2
                if (walkWords.containsMatchIn(p.title)) f += 1
                if (kw.any { it in walkKeywords }) f += 1
                if (PlaceTag.THEME in p.tags) f -= 1
                f.coerceIn(0, 3)
            }
            Situation.LEARN -> {
                var f = 0
                if (PlaceTag.MUSEUM in p.tags || PlaceTag.LIBRARY in p.tags) f += 2
                if (PlaceTag.EXPERIENCE in p.tags) f += 1
                if (learnWords.containsMatchIn(p.title)) f += 1
                if (kw.any { it in learnKeywords }) f += 1
                // 카트·승마·점핑처럼 몸으로 노는 체험은 "에너지 발산" 쪽(실데이터: 카트테마파크가 체험·교육에 나왔다).
                if (energyWords.containsMatchIn(p.title) && !learnWords.containsMatchIn(p.title)) f = 0
                f.coerceAtMost(3)
            }
            // 짧게: 위치를 알면 가까운 곳(15km), 모르면 같은 시 안의 도서관·공원·작은 전시처럼 머무는 시간이 짧은 곳.
            Situation.SHORT -> if (km != null) when {
                km <= 7 -> 3
                km <= 15 -> 2
                else -> 0
            } else if (PlaceTag.LIBRARY in p.tags || PlaceTag.NATURE in p.tags || PlaceTag.MUSEUM in p.tags) 1 else 0
        }
    }

    /**
     * 이름에 철이 드러나는 곳(벚꽃거리·수국길·워터파크 등)은 철이 아니면 추천하지 않는다.
     * 2026-10-16 실데이터: 10월에 "전농로 벚꽃거리", "종달리 수국길"이 홈 추천에 나왔다.
     */
    private val seasons = listOf(
        Regex("벚꽃|왕벚") to (3..4), Regex("유채") to (3..4), Regex("수국") to (6..7),
        Regex("핑크뮬리|억새") to (9..11), Regex("동백") to (12..14), Regex("워터파크|워터월드|물놀이장") to (6..9),
    )

    /** 먹거리 골목(흑돼지거리 등)은 나들이 장소가 아니라 식당 묶음이라 홈 추천에서 뺀다. */
    private val foodStreet = Regex("(흑돼지|맛집|먹자|음식|횟집) *(거리|골목)")

    fun offSeason(p: Place, today: LocalDate): Boolean {
        val m = today.monthValue
        return seasons.any { (r, months) -> r.containsMatchIn(p.title) && m !in months && m + 12 !in months }
    }

    /** 상세의 "체험 가능 연령"·"관람 연령"에서 최소 나이. */
    fun minAge(d: Detail?): Int? = d?.facts?.firstOrNull { it.first == "체험 가능 연령" || it.first == "관람 연령" }?.let { Hours.minAge(it.second) }

    fun restText(d: Detail?): String? = d?.facts?.firstOrNull { it.first == "쉬는 날" }?.second

    data class Input(
        val places: List<Place>,
        val situation: Situation,
        val weather: Weather?,
        val air: Air?,
        val region: Region,
        val today: LocalDate,
        val here: Pair<Double, Double>?,
        val ages: Set<AgeGroup>,
        val signals: Map<String, KidSignal> = emptyMap(),
        val details: Map<String, Detail> = emptyMap(),
        /** 지난 며칠(오늘 제외) 홈 추천에 나온 곳. */
        val recent: Set<String> = emptySet(),
        val shuffle: Int = 0,
    )

    private fun km(i: Input, p: Place): Double? {
        val h = i.here ?: return null
        if (p.lat == null || p.lng == null) return null
        return Recommender.distanceKm(h.first, h.second, p.lat, p.lng)
    }

    /** 확실히 맞지 않는 곳을 뺀 뒤 점수 순. 홈 3곳과 "더보기" 목록이 같은 순서를 쓴다. */
    fun ranked(i: Input): List<Place> {
        val mode = Recommender.advise(i.weather, i.air).mode
        val maxAge = i.ages.maxOfOrNull { Hours.yearsOf(it).last }
        val base = i.places.filter { p ->
            val sig = i.signals[p.id]
            val d = i.details[p.id]
            sig?.warn != true && !offSeason(p, i.today) && !foodStreet.containsMatchIn(p.title) &&
                !(maxAge != null && (minAge(d) ?: 0) > maxAge) &&
                Hours.today(restText(d), i.today) != Hours.Today.CLOSED &&
                fit(p, i.situation, mode, km(i, p), sig) > 0
        }
        val near = base.filter { p -> km(i, p)?.let { it <= FAR_KM } ?: true }
        val pool = if (near.size >= 3) near else base
        val seed = i.today.toEpochDay() * 1_000_003L + i.shuffle * 7919L + i.situation.ordinal * 131L
        return pool.sortedByDescending { p -> score(i, p, mode, seed) }
    }

    private fun score(i: Input, p: Place, mode: Mode, seed: Long): Double {
        val sig = i.signals[p.id]
        val d = i.details[p.id]
        val km = km(i, p)
        var s = fit(p, i.situation, mode, km, sig) * 1.2
        // 연령 근거: 후기에서 우리 아이 연령 언급이 많음 / 안내 연령이 맞음.
        val reviewAges = sig?.let { Insight.agesFromCounts(it.ageCounts) }.orEmpty()
        if (i.ages.isNotEmpty() && reviewAges.any { it in i.ages }) s += 1.0
        if (i.ages.isNotEmpty() && minAge(d) != null) s += 0.4
        // 거리(위치를 알 때 25km 안에서 최대 2점), 모르면 같은 시.
        s += if (km != null) (25.0 - km).coerceAtLeast(0.0) / 25.0 * 2.0 else if (p.region == i.region) 0.8 else 0.0
        // 신뢰도: 이곳 이름이 제목에 있는 아이랑 후기, 관광공사 상세, 사진.
        s += ((sig?.ownPosts ?: 0) / 50.0) * 1.5
        s += log10(1.0 + (sig?.kidPostCount ?: 0)).coerceAtMost(4.0) * 0.2
        if (d != null) s += 0.3
        if (p.image != null || sig?.thumb != null) s += 0.4
        // 날마다 섞기(같은 날·같은 상황이면 같은 결과) + 최근에 보여준 곳은 뒤로.
        s += mix(seed xor (p.id.hashCode().toLong() shl 20)) * 3.5
        if (p.id in i.recent) s -= 3.0
        return s
    }

    /** 맨 위 3곳: ① 가장 잘 맞는 곳 ② 종류가 다른 곳 ③ (위치를 알면) 가장 가까운 곳. 근거는 데이터에 있는 것만. */
    fun top3(i: Input): List<Suggestion> {
        val r = ranked(i)
        if (r.isEmpty()) return emptyList()
        val picked = mutableListOf<Pair<Place, String>>()
        picked += r.first() to "가장 잘 맞아요"
        val g0 = Recommender.group(r.first())
        r.drop(1).firstOrNull { Recommender.group(it) != g0 && it.tags.firstOrNull() != r.first().tags.firstOrNull() }
            ?.let { picked += it to "다른 종류" }
        val rest = r.filter { p -> picked.none { it.first.id == p.id } }
        // 가까운 곳: 8km 안에서 순위가 가장 높은 곳(늘 제일 가까운 한 곳만 나오지 않게). 없으면 가장 가까운 곳.
        val withKm = if (i.here != null) rest.take(15).filter { km(i, it) != null } else emptyList()
        val nearest = withKm.firstOrNull { km(i, it)!! <= 8.0 } ?: withKm.minByOrNull { km(i, it)!! }
        if (nearest != null && picked.none { km(i, it.first) != null && km(i, it.first)!! <= km(i, nearest)!! }) picked += nearest to "가까운 곳"
        // 모자라면 종류가 겹치지 않는 다음 순위로.
        for (p in rest) {
            if (picked.size >= 3) break
            if (picked.any { it.first.id == p.id }) continue
            if (picked.any { Recommender.group(it.first) == Recommender.group(p) } && rest.size > 3) continue
            picked += p to "함께 추천"
        }
        for (p in rest) { if (picked.size >= 3) break; if (picked.none { it.first.id == p.id }) picked += p to "함께 추천" }
        return picked.take(3).map { (p, role) -> Suggestion(p, role, reasons(i, p), km(i, p)) }
    }

    /** 왜 추천했는지(최대 3줄). 추측 없이 데이터 근거만. */
    fun reasons(i: Input, p: Place): List<String> {
        val sig = i.signals[p.id]
        val d = i.details[p.id]
        val out = mutableListOf<String>()
        val kind = p.tags.firstOrNull()?.label ?: "명소"
        when (i.situation) {
            Situation.WEATHER -> {
                val mode = Recommender.advise(i.weather, i.air).mode
                out += when (mode) {
                    Mode.INDOOR -> "${p.setting.label} $kind · 날씨 영향 적음"
                    Mode.OUTDOOR -> "${p.setting.label} $kind"
                    Mode.ANY -> "${p.setting.label} $kind"
                }
            }
            Situation.RAIN -> out += if (p.setting == Setting.INDOOR) "실내 $kind" else "실내·야외가 함께 있는 곳(일부 야외)"
            Situation.ENERGY -> out += "$kind · 몸으로 노는 곳" + (sig?.keywords.orEmpty().firstOrNull { it.first in energyKeywords && it.second >= 2 }?.let { " ('${it.first}' 후기 ${it.second}건)" } ?: "")
            Situation.WALK -> out += "${p.setting.label} $kind" + (sig?.keywords.orEmpty().firstOrNull { it.first in walkKeywords && it.second >= 2 }?.let { " · '${it.first}' 후기 ${it.second}건" } ?: "")
            Situation.LEARN -> out += if (PlaceTag.EXPERIENCE in p.tags && PlaceTag.MUSEUM !in p.tags) "$kind · 직접 해보는 곳" else "$kind · 보고 배우는 곳"
            Situation.SHORT -> {}
        }
        km(i, p)?.let { out += "${fmtKm(it)} 거리" }
        val reviewAges = sig?.let { Insight.agesFromCounts(it.ageCounts) }.orEmpty()
        val ageHit = reviewAges.filter { it in i.ages }
        when {
            ageHit.isNotEmpty() -> out += "${ageHit.toSet().label()} 후기 많음"
            minAge(d) == 0 -> out += "안내 연령: 전 연령"
            (sig?.ownPosts ?: 0) >= 5 -> out += "아이랑 후기 ${sig!!.ownPosts}/50건"
        }
        if (Hours.today(restText(d), i.today) == Hours.Today.OPEN_BY_NOTICE && out.size < 3) out += "오늘 정기휴무 아님(안내 기준)"
        return out.take(3)
    }

    fun fmtKm(km: Double): String = if (km < 10) "${(km * 10).roundToInt() / 10.0}km" else "${km.roundToInt()}km"

    /** 0~1(SplitMix64). */
    fun mix(x: Long): Double {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return (z ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    /**
     * "아이랑 가기 좋은 곳" 묶음 순서: 예전엔 후기 수로 고정 정렬해 매일 같은 곳이 앞에 나왔다(2026-10-16 사용자 지적).
     * 후기 많은 곳이 조금 유리하되, 날마다 섞고 최근 홈에 나온 곳은 뒤로 보낸다.
     */
    fun dailyOrder(places: List<Place>, signals: Map<String, KidSignal>, today: LocalDate, recent: Set<String>): List<Place> {
        val seed = today.toEpochDay() * 7_000_003L + 17
        return places.sortedByDescending { p ->
            val sig = signals[p.id]
            log10(1.0 + (sig?.kidPostCount ?: 0)).coerceAtMost(4.0) * 0.5 + ((sig?.ownPosts ?: 0) / 50.0) +
                (if (p.image != null || sig?.thumb != null) 1.0 else 0.0) - (if (offSeason(p, today)) 5.0 else 0.0) +
                mix(seed xor (p.id.hashCode().toLong() shl 20)) * 3.0 - (if (p.id in recent) 2.0 else 0.0)
        }
    }
}
