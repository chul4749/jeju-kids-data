package com.moon.jejukids.logic

import com.moon.jejukids.data.KidSignal
import com.moon.jejukids.data.Place
import com.moon.jejukids.data.PlaceTag
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 주말 코스 사이에 끼울 점심 식당·오후 카페 고르기 — 규칙 기반(AI 아님).
 * 위치: 점심은 오전 장소와 오후 장소의 중간, 카페는 오후 장소 근처.
 * 후보: 카카오 장소 검색(그 지점 반경 3km 음식점·카페). 별점이 없으므로
 * 블로그 "아이랑" 후기 수(제목에 가게 이름이 있는 글)와 놀이방·유아의자 같은 말, 코스에서의 거리로 순위를 매긴다.
 * 노키즈존 언급이 있는 곳은 뺀다.
 */
object FoodPicker {

    enum class Kind(val tag: PlaceTag, val code: String, val label: String) {
        LUNCH(PlaceTag.FOOD, "FD6", "점심"),
        CAFE(PlaceTag.CAFE, "CE7", "카페"),
    }

    /** 후보를 찾을 지점. 같은 칸(약 2km 격자)이면 검색 결과를 함께 쓴다. */
    data class Slot(val date: LocalDate, val kind: Kind, val lat: Double, val lng: Double) {
        val cellLat: Double get() = (lat * GRID).roundToInt() / GRID
        val cellLng: Double get() = (lng * GRID).roundToInt() / GRID
        /** 검색 캐시 키(날짜와 무관). */
        val searchKey: String get() = "${kind.code}_${(lat * GRID).roundToInt()}_${(lng * GRID).roundToInt()}"
    }

    private const val GRID = 50.0 // 1/50도 ≈ 위도 2.2km

    private fun coords(item: PlanItem?): Pair<Double, Double>? = when (item) {
        is PlanItem.AtPlace -> item.place.lat?.let { la -> item.place.lng?.let { la to it } }
        is PlanItem.AtEvent -> item.event.lat?.let { la -> item.event.lng?.let { la to it } }
        null -> null
    }

    /** 하루 코스에서 점심·카페 지점. 좌표가 있는 장소가 하나도 없으면 빈 목록. */
    fun slots(plan: DayPlan): List<Slot> {
        val am = coords(plan.morning)
        val pm = coords(plan.afternoon)
        val lunchAt = when {
            // 두 곳이 멀면(제주시↔서귀포) 중간은 한라산 자락이라 식당이 거의 없다(실데이터 1~3곳) → 오전 장소 근처에서 먹는다.
            am != null && pm != null && Recommender.distanceKm(am.first, am.second, pm.first, pm.second) > 12 -> am
            am != null && pm != null -> (am.first + pm.first) / 2 to (am.second + pm.second) / 2
            else -> am ?: pm
        }
        val cafeAt = pm ?: am
        return listOfNotNull(
            lunchAt?.let { Slot(plan.date, Kind.LUNCH, it.first, it.second) },
            cafeAt?.let { Slot(plan.date, Kind.CAFE, it.first, it.second) },
        )
    }

    /** 아이와 가기 편하다는 말(식당·카페에서 의미 있는 것만). */
    private val kidWords = listOf("놀이방", "유아의자", "아기의자", "좌식", "수유실", "기저귀 갈이대", "유모차 OK", "넓어요")

    /** 블로그를 먼저 확인해 볼 후보(카카오 정확도 순 앞쪽 + 가까운 곳). */
    fun toCheck(cands: List<Place>, slot: Slot, max: Int = 8): List<Place> =
        cands.take(15).sortedBy { dist(it, slot) }.take(max)

    fun dist(p: Place, slot: Slot): Double =
        if (p.lat == null || p.lng == null) 9.0 else Recommender.distanceKm(slot.lat, slot.lng, p.lat, p.lng)

    data class Pick(val place: Place, val why: String)

    /**
     * 순위: 후기를 확인한 곳 중 아이랑 후기가 있는 곳 → 아직 확인 못한 곳 → 후기가 없는 곳.
     * 같은 무리 안에서는 (후기 수·아이 편의 말) − 거리.
     */
    fun rank(cands: List<Place>, signals: Map<String, KidSignal>, slot: Slot, exclude: Set<String> = emptySet()): List<Pick> =
        cands.filter { it.id !in exclude && signals[it.id]?.warn != true && dist(it, slot) <= 6.5 }
            .map { p ->
                val sig = signals[p.id]
                val kw = sig?.keywords.orEmpty().filter { (k, n) -> k in kidWords && n >= 1 }.map { it.first }
                val d = dist(p, slot)
                // 노키즈존 언급이 1건(경고 전 단계)만 있어도 첫 무리에서는 뺀다.
                val group = when {
                    sig == null -> 1
                    sig.ownPosts >= 3 && sig.noKidsMentions == 0 -> 0
                    else -> 2
                }
                val score = (sig?.ownPosts ?: 0).coerceAtMost(15) * 2 + kw.size * 4 - d * 3 - (sig?.noKidsMentions ?: 0) * 5
                Triple(p, group to score, why(sig, kw, d))
            }
            .sortedWith(compareBy<Triple<Place, Pair<Int, Double>, String>> { it.second.first }.thenByDescending { it.second.second })
            .map { Pick(it.first, it.third) }

    private fun why(sig: KidSignal?, kw: List<String>, d: Double): String {
        val parts = mutableListOf<String>()
        if (sig != null && sig.ownPosts >= 2) parts += "아이랑 후기 ${sig.ownPosts}건"
        parts += kw.take(2)
        parts += when {
            d < 0.15 -> "코스 바로 옆"
            d < 1 -> "코스에서 ${(d * 1000).roundToInt() / 100 * 100}m"
            else -> "코스에서 ${"%.1f".format(d)}km"
        }
        return parts.joinToString(" · ")
    }
}
