package collector

import com.moon.jejukids.data.FoundImage
import com.moon.jejukids.data.KidSignal
import com.moon.jejukids.data.Place
import com.moon.jejukids.data.PlaceTag
import com.moon.jejukids.data.Region
import com.moon.jejukids.data.Repository
import com.moon.jejukids.data.ResponseCache
import com.moon.jejukids.data.UrlHttp
import com.moon.jejukids.data.Wire
import com.moon.jejukids.logic.FoodPicker
import com.moon.jejukids.logic.Insight
import com.moon.jejukids.logic.KidFilter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * 중앙 수집기: 1시간마다 GitHub Actions에서 돈다.
 * 앱과 같은 코드(data·logic)로 모든 출처를 한 번만 부르고, 결과를 JSON 파일로 남긴다 → GitHub Pages로 공개.
 * 앱은 이 파일들만 받으므로 키가 앱에 없고, 사용자 수와 관계없이 API 호출은 여기서 한 번이다.
 *
 * 무거운 일(행사 기간·위치·공연 상세·블로그 후기·사진·상세·식당)은 매번 조금씩 채우고 캐시(cache/)를 다음 실행에 넘긴다.
 * 한 번에 몰아 부르지 않는 것은 출처별 하루 한도(공공데이터 개발계정 등)와 차단(KOPIS) 때문.
 *
 * 사용: run <출력 폴더(site/v1)> <캐시 폴더(cache)>. 키는 환경변수 DATA_GO_KR_KEY, KAKAO_REST_KEY, VISITJEJU_KEY, KOPIS_KEY.
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "site/v${Wire.VERSION}" }).apply { mkdirs() }
    val cacheDir = File(args.getOrElse(1) { "cache" }).apply { mkdirs() }
    fun env(k: String) = System.getenv(k).orEmpty()
    /** 한 번 돌 때 새로 채울 양. 처음 며칠은 이 양만큼씩 늘어난다. */
    val budget = env("BUDGET").toIntOrNull() ?: 1
    /**
     * 카카오(블로그·이미지·장소 검색) 몫은 따로 크게: 하루 한도가 공공데이터 개발계정(약 1,000건)보다 훨씬 커서
     * 사진·후기·식당을 빨리 채울 수 있다. 처음 채울 때는 수동 실행으로 크게(KAKAO_BUDGET=20 안팎) 한 번 돌린다.
     */
    val kakaoBudget = env("KAKAO_BUDGET").toIntOrNull() ?: 3
    val cache = ResponseCache(cacheDir)
    val repo = Repository(
        UrlHttp, cache, { env("DATA_GO_KR_KEY") },
        kakaoKey = { env("KAKAO_REST_KEY") }, visitJejuKey = { env("VISITJEJU_KEY") },
        kopisKey = { env("KOPIS_KEY") }, playJejuEnabled = false,
    )
    val zone = ZoneId.of("Asia/Seoul")
    val today = LocalDate.now(zone)
    val now = LocalDateTime.now(zone)
    val log = StringBuilder()
    fun step(name: String, block: () -> Any?) {
        val t = System.currentTimeMillis()
        val r = runCatching(block)
        val line = "$name: ${r.getOrNull() ?: r.exceptionOrNull()?.let { "실패 ${it.message}" }} (${(System.currentTimeMillis() - t) / 1000}s)"
        println(line); log.appendLine(line)
    }

    // 1) 행사: 기간·공연 상세를 조금 채우고, 합친 뒤 위치를 채운다.
    step("비짓제주 기간") { repo.fillVisitJeju(today, 60 * budget) }
    step("KOPIS 상세") { repo.fillKopis(today, 30 * budget) }
    var events = repo.festivals(today, force = false, waitAll = true)
    step("행사 위치") { repo.fillGeo(events.data, 120 * kakaoBudget) }
    events = events.copy(data = repo.withGeo(events.data))

    // 2) 장소 + 블로그 신호(찜 대신 아이 관련도 순) + 사진.
    val places = repo.places(false)
    val ordered = places.data.sortedWith(
        compareByDescending<Place> { PlaceTag.KIDSCAFE in it.tags }.thenByDescending { it.image == null }.thenByDescending { it.kidScore },
    )
    step("장소 블로그 신호") { repo.fillSignals(ordered, 60 * kakaoBudget) }
    val targets = repo.imageTargets(events.data, places.data)
    step("검색 사진") { repo.fillImages(targets, 60 * kakaoBudget) }

    // 3) 관광공사 장소 상세(소개·이용시간·사진): 7일 캐시, 한 번에 조금씩.
    var newDetails = 0
    val details = JSONObject()
    for (p in places.data.filter { it.id.all(Char::isDigit) && it.typeId != KidFilter.TYPE_KAKAO }) {
        val fresh = cache.read("common_${p.id}") != null
        if (!fresh) {
            if (newDetails >= 8 * budget) continue // 공공데이터 개발계정 하루 1,000건: 8곳×3호출×24회 ≈ 580
            newDetails++
        }
        runCatching { repo.detail(p.id, p.typeId) }.getOrNull()?.let { details.put(p.id, Wire.detail(it)) }
    }
    println("상세: ${details.length()}곳 (새로 $newDetails)")

    // 4) 주말 코스 식당·카페 후보: 아이 장소·행사가 있는 약 2km 칸과 그 이웃 칸.
    val cells = mutableSetOf<Pair<Int, Int>>()
    val coords = places.data.filter { Insight.kidFriendly(it, null) }.mapNotNull { p -> p.lat?.let { la -> p.lng?.let { la to it } } } +
        events.data.mapNotNull { f -> f.lat?.let { la -> f.lng?.let { la to it } } }
    for ((la, lg) in coords) {
        val r = (la * 50).roundToInt(); val c = (lg * 50).roundToInt()
        for (dr in -1..1) for (dc in -1..1) cells += (r + dr) to (c + dc)
    }
    val food = JSONObject()
    var newFood = 0
    var newFoodSignals = 0
    val foodSignals = mutableMapOf<String, KidSignal>()
    for ((r, c) in cells.sortedBy { it.first * 10000 + it.second }) for (kind in FoodPicker.Kind.entries) {
        val slot = FoodPicker.Slot(today, kind, r / 50.0, c / 50.0)
        val cached = cache.read("food2_" + slot.searchKey) != null
        if (!cached && newFood >= 25 * kakaoBudget) continue
        val cands = runCatching { repo.food(slot) }.getOrNull() ?: continue
        if (!cached) newFood++
        val top = cands.take(15)
        food.put(slot.searchKey, Wire.list(top, Wire::place))
        // 블로그 확인은 순위에 쓰는 앞쪽 몇 곳만(앱의 FoodPicker.toCheck와 같은 기준).
        for (p in FoodPicker.toCheck(top, slot)) {
            val have = cache.read("blog2_" + p.id.filter { it.isLetterOrDigit() }) != null
            if (!have && newFoodSignals >= 100 * kakaoBudget) continue
            runCatching { repo.signal(p) }.getOrNull()?.let { foodSignals[p.id] = it }
            if (!have) newFoodSignals++
        }
    }
    println("식당·카페 칸: ${food.length()} / ${cells.size * 2} (새로 $newFood), 후기 확인 새로 $newFoodSignals")

    // 5) 날씨·미세먼지(두 지역).
    val weather = JSONObject()
    val air = JSONObject()
    for (region in Region.entries) {
        runCatching { repo.weather(region, now, force = true) }.onSuccess { weather.put(region.name, Wire.weather(it.data)) }
            .onFailure { println("날씨 $region 실패: ${it.message}") }
        runCatching { repo.air(region, force = true) }.onSuccess { l -> l.data?.let { air.put(region.name, Wire.air(it)) } }
    }

    // 6) 파일 쓰기. 신호는 화면에 쓰는 만큼만(근거 글 3개, 인용 2개) 줄여서.
    fun slim(s: KidSignal) = s.copy(noKidsPosts = s.noKidsPosts.take(3), quotes = s.quotes.take(2))
    val signals = (repo.cachedSignals(places.data) + foodSignals).mapValues { slim(it.value) }
    val images: Map<String, FoundImage> = repo.cachedImages(targets)
    fun write(name: String, body: String) = File(out, name).writeText(body, Charsets.UTF_8)
    write("events.json", Wire.list(events.data, Wire::festival).toString())
    write("places.json", Wire.list(places.data, Wire::place).toString())
    write("signals.json", Wire.map(signals, Wire::signal).toString())
    write("images.json", Wire.map(images, Wire::image).toString())
    write("details.json", details.toString())
    write("food.json", food.toString())
    write("weather.json", weather.toString())
    write("air.json", air.toString())
    val meta = JSONObject()
        .put("version", Wire.VERSION)
        .put("updatedAt", ZonedDateTime.now(zone).toString())
        .put("counts", JSONObject().put("events", events.data.size).put("places", places.data.size).put("signals", signals.size)
            .put("images", images.size).put("details", details.length()).put("foodCells", food.length()))
        .put("errors", JSONArray(listOfNotNull(events.error, places.error)))
        .put("log", log.toString())
    write("meta.json", meta.toString(2))
    println(meta.toString(2))
}
