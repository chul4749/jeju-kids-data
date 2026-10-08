package com.moon.jejukids.logic

import com.moon.jejukids.data.DayWeather
import com.moon.jejukids.data.Festival
import com.moon.jejukids.data.KidSignal
import com.moon.jejukids.data.Place
import com.moon.jejukids.data.Region
import com.moon.jejukids.data.Setting
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 코스의 한 칸: 장소 또는 행사. */
sealed interface PlanItem {
    data class AtPlace(val place: Place, val why: String) : PlanItem
    data class AtEvent(val event: Festival, val why: String) : PlanItem
}

data class DayPlan(
    val date: LocalDate,
    val weather: DayWeather?,
    val mode: Mode,
    /** "토요일 비 70% · 실내 위주로 골랐어요" 같은 한 줄. */
    val headline: String,
    val morning: PlanItem?,
    val afternoon: PlanItem?,
    /** 그날 열리는 축제·가족 행사(코스에 넣은 것 제외). */
    val events: List<Festival>,
)

/**
 * 주말 코스 짜기 — 규칙 기반(AI 아님).
 * 오전: 날씨에 맞는 장소(아이 연령·거리·후기 반영, 날마다 바뀜).
 * 오후: 그날 열리는 축제·가족 행사가 있으면 그것, 없으면 오전과 다른 분류의 장소.
 * 노키즈 언급 장소, 신청이 필요한 교육청 프로그램은 코스에서 뺀다(따로 "신청 마감 임박"으로 보여준다).
 */
object Planner {

    private val adultEvent = Regex("비어|맥주|와인|막걸리|소주|주류|칵테일|위스키|성인|19금|나이트")

    /** 계획할 날: 이번 주말 토·일(주말이면 남은 날만). */
    fun weekendDays(today: LocalDate): List<LocalDate> {
        val (sat, sun) = Recommender.weekend(today)
        return listOf(sat, sun).filter { !it.isBefore(today) }
    }

    fun mode(w: DayWeather?): Mode = when {
        w == null -> Mode.ANY
        w.rainy -> Mode.INDOOR
        (w.tempMax ?: 0) >= 31 -> Mode.INDOOR
        (w.tempMax ?: 99) <= 5 -> Mode.INDOOR
        else -> Mode.OUTDOOR
    }

    private val dayName = DateTimeFormatter.ofPattern("E요일", java.util.Locale.KOREAN)

    fun headline(date: LocalDate, w: DayWeather?, mode: Mode): String {
        val d = date.format(dayName)
        return when {
            w == null -> "$d 예보는 아직이에요 · 실내·야외 섞어서 골랐어요"
            w.rain || w.popMax >= 60 -> "$d 비 ${w.popMax}% · 실내 위주로 골랐어요"
            mode == Mode.INDOOR -> "$d ${w.tempMax}° · 실내 위주로 골랐어요"
            else -> "$d ${w.sky.label}, 비 ${w.popMax}% · 야외 나들이 좋아요"
        }
    }

    /** 아이 연령 조건: 고른 연령이 없거나, 항목 연령을 모르면 통과(모르는 곳까지 빼면 너무 적어진다). */
    fun ageOk(itemAges: Set<AgeGroup>, wanted: Set<AgeGroup>) = wanted.isEmpty() || itemAges.isEmpty() || itemAges.any { it in wanted }

    fun eventsOn(day: LocalDate, all: List<Festival>, ages: Set<AgeGroup>): List<Festival> =
        all.filter { !it.start.isAfter(day) && !it.end.isBefore(day) }
            .filter { it.isFestival || it.familyFriendly }
            // 맥주·와인 축제 같은 어른 행사는 아이 코스에 넣지 않는다("2026 비어가든"이 오후 코스로 뽑혔다).
            .filter { !it.isAdult && !adultEvent.containsMatchIn(it.title) }
            .filter { ageOk(Insight.agesFor(it), ages) }
            // 이번에만 하는 짧은 행사 → 축제 → 가족 행사 순.
            .sortedWith(compareBy<Festival> { ChronoUnit.DAYS.between(it.start, it.end) > 14 }.thenByDescending { it.isFestival })

    fun plan(
        days: List<LocalDate>,
        weather: List<DayWeather>,
        places: List<Place>,
        events: List<Festival>,
        signals: Map<String, KidSignal>,
        ages: Set<AgeGroup>,
        region: Region,
        here: Pair<Double, Double>?,
        shuffle: Int = 0,
        /** 사용자가 고정한 칸: (날짜, "오전"/"오후") → 그 항목. 다른 코스를 봐도 그대로 둔다. */
        locked: Map<Pair<LocalDate, String>, PlanItem> = emptyMap(),
        /** 칸마다 따로 누른 "이것만 다시" 횟수: (날짜, "오전"/"오후") → 횟수. */
        stepShift: Map<Pair<LocalDate, String>, Int> = emptyMap(),
    ): List<DayPlan> {
        val used = mutableSetOf<String>()
        // 고정한 곳은 다른 칸에 또 나오지 않게 먼저 "사용함"으로.
        locked.values.forEach { used += itemId(it) }
        val usable = places.filter { signals[it.id]?.warn != true }.filter { ageOk(Insight.agesFor(it, signals[it.id]), ages) }
        return days.map { day ->
            val w = weather.firstOrNull { it.date == day }
            val mode = mode(w)
            val dayEvents = eventsOn(day, events, ages).filter { it.status == null } // 신청형은 코스에서 제외
            // 오전: 서로 다른 종류의 상위 3곳 중 "다른 코스 보기" 횟수에 따라 하나(같은 감귤농장만 반복되지 않게).
            fun pickPlace(m: Mode, exclude: Set<String>): Place? =
                (shuffle + (stepShift[day to "오전"] ?: 0)).let { sm ->
                    Recommender.pick(usable.filter { it.id !in used && it.id !in exclude }, m, region, day, here, count = 3, signals = signals, shuffle = sm / 3)
                        .let { top -> top.getOrNull(sm % top.size.coerceAtLeast(1)) }
                }

            val lockedAm = locked[day to "오전"]
            val morningPlace = if (lockedAm != null) null else pickPlace(mode, emptySet())
            morningPlace?.let { used += it.id }
            val morning: PlanItem? = lockedAm ?: morningPlace?.let { PlanItem.AtPlace(it, why(it, mode)) }
            val amPlace = (morning as? PlanItem.AtPlace)?.place
            // 오후 후보: 그날 행사(점수 상위 3) 와 장소(상위 3, 오전과 다른 종류)를 번갈아 둔다.
            // "다른 코스 보기"를 누를 때마다 다음 후보로. 첫 후보는, 이번에만 하는 가까운 축제·가족 행사가 있으면 그 행사, 아니면 장소.
            // (전에는 행사가 늘 먼저이고 장소는 네 번째에야 나와 "행사 위주로 고정"으로 보였다.)
            val from = amPlace?.let { p -> p.lat?.let { la -> p.lng?.let { la to it } } } ?: here ?: regionCenter(region)
            val eventOptions = dayEvents.filter { it.id !in used }.sortedByDescending { eventScore(it, from) }.take(3)
            val tagsAm = amPlace?.tags.orEmpty()
            val amGroup = amPlace?.let { Recommender.group(it) }
            val placeOptions = Recommender.pick(
                usable.filter { it.id !in used && (tagsAm.isEmpty() || it.tags.none { t -> t in tagsAm }) && Recommender.group(it) != amGroup },
                mode, region, day.plusDays(3), here, count = 3, signals = signals, shuffle = shuffle / 2,
            )
            val evItems = eventOptions.map { PlanItem.AtEvent(it, if (it.isFestival) "그날 열리는 축제" else "그날 열리는 가족 행사") }
            val plItems = placeOptions.map { PlanItem.AtPlace(it, why(it, mode)) }
            val leadEvent = eventOptions.firstOrNull()?.let { special(it, from) } == true
            val options: List<PlanItem> = interleave(if (leadEvent) evItems else plItems, if (leadEvent) plItems else evItems)
            val pmShift = shuffle + (stepShift[day to "오후"] ?: 0)
            val afternoon = locked[day to "오후"] ?: options.getOrNull(pmShift % options.size.coerceAtLeast(1))
            when (afternoon) {
                is PlanItem.AtEvent -> used += afternoon.event.id
                is PlanItem.AtPlace -> used += afternoon.place.id
                null -> {}
            }
            DayPlan(
                date = day, weather = w, mode = mode, headline = headline(day, w, mode),
                morning = morning,
                afternoon = afternoon,
                events = dayEvents.filter { it.id !in used }.take(6),
            )
        }
    }

    /** 첫 후보로 둘 만한 행사: 3일 이하로 짧게 열리는 축제·가족 행사이고, 20km 안(위치를 모르면 통과). */
    private fun special(f: Festival, from: Pair<Double, Double>): Boolean {
        if (ChronoUnit.DAYS.between(f.start, f.end) > 3 || !(f.isFestival || f.familyFriendly)) return false
        return f.lat == null || f.lng == null || Recommender.distanceKm(from.first, from.second, f.lat, f.lng) <= 20
    }

    private fun <T> interleave(a: List<T>, b: List<T>): List<T> =
        (0 until maxOf(a.size, b.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i)) }

    fun itemId(i: PlanItem) = when (i) {
        is PlanItem.AtPlace -> i.place.id
        is PlanItem.AtEvent -> i.event.id
    }

    private fun regionCenter(r: Region) = when (r) {
        Region.JEJU -> 33.4996 to 126.5312
        Region.SEOGWIPO -> 33.2541 to 126.5600
    }

    /**
     * 오후 행사 점수: 짧게 열리는 행사(이번에만) · 축제 · 가족 행사일수록, 오전 장소(또는 내 위치)에서 가까울수록 높다.
     * 좌표 없는 행사는 거리 점수를 조금 깎는다.
     */
    fun eventScore(f: Festival, from: Pair<Double, Double>): Double {
        val days = ChronoUnit.DAYS.between(f.start, f.end)
        val shortness = when {
            days <= 3 -> 3.0
            days <= 14 -> 1.5
            else -> 0.0
        }
        val km = if (f.lat != null && f.lng != null) Recommender.distanceKm(from.first, from.second, f.lat, f.lng) else 25.0
        return shortness + (if (f.isFestival) 1.0 else 0.0) + (if (f.familyFriendly) 1.5 else 0.0) - km / 12.0
    }

    private fun why(p: Place, mode: Mode): String = when {
        mode == Mode.INDOOR && p.setting != Setting.OUTDOOR -> "비 와도 괜찮은 실내"
        mode == Mode.OUTDOOR && p.setting != Setting.INDOOR -> "날씨 좋은 날 야외"
        else -> p.tags.firstOrNull()?.label ?: "아이랑 가기 좋은 곳"
    }

    private val applyEnd = Regex("~\\s*(\\d{4}-\\d{2}-\\d{2})\\s*(\\d{2}):(\\d{2})")

    /** "2026-10-05 09:00 ~ 2026-10-15 16:00" → 접수 마감 시각. */
    fun applyDeadline(f: Festival): LocalDateTime? {
        val m = applyEnd.find(f.apply ?: return null) ?: return null
        return runCatching { LocalDate.parse(m.groupValues[1]).atTime(m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
    }

    /** 신청 마감 임박: 접수중이면서 [withinDays]일 안에 마감. 마감 빠른 순. */
    fun closingSoon(all: List<Festival>, now: LocalDateTime, ages: Set<AgeGroup>, withinDays: Long = 4): List<Pair<Festival, LocalDateTime>> =
        all.filter { it.status == "접수중" }
            .filter { ageOk(Insight.agesFor(it), ages) }
            .mapNotNull { f -> applyDeadline(f)?.let { f to it } }
            .filter { (_, end) -> end.isAfter(now) && end.isBefore(now.plusDays(withinDays)) }
            .sortedBy { it.second }

    fun deadlineLabel(end: LocalDateTime, now: LocalDateTime): String {
        val hours = ChronoUnit.HOURS.between(now, end)
        return when {
            end.toLocalDate() == now.toLocalDate() -> "오늘 ${end.hour}시 마감"
            end.toLocalDate() == now.toLocalDate().plusDays(1) -> "내일 ${end.hour}시 마감"
            else -> "${ChronoUnit.DAYS.between(now.toLocalDate(), end.toLocalDate())}일 뒤 마감"
        }.let { if (hours in 0..2) "곧 마감 · $it" else it }
    }

    fun isWeekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
}
