package com.moon.jejukids.data

import com.moon.jejukids.logic.KidFilter
import java.time.LocalDate
import java.time.LocalDateTime

/** 결과 + 언제 받은 정보인지 + (있다면) 새로 받기 실패 사유. */
data class Loaded<T>(val data: T, val savedAt: Long?, val error: String? = null)

/**
 * 앱을 열 때마다 공공 API에서 직접 받아오되, 너무 자주 부르지 않도록 종류별 유효시간 동안은 캐시를 쓴다.
 * 받기에 실패하면 마지막 캐시를 보여주고 실패 사유를 함께 알린다.
 */
class Repository(
    private val http: Http,
    private val cache: ResponseCache,
    private val key: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val kakaoKey: () -> String = { "" },
    private val visitJejuKey: () -> String = { "" },
    private val kopisKey: () -> String = { "" },
    /** 민간 사이트(플레이제주)를 출처에 넣을지 — 공개 배포에서는 뺀다. */
    private val playJejuEnabled: Boolean = true,
    /** true면 네트워크 없이 저장해 둔 자료만 읽는다(기한이 지났어도). */
    private val cacheOnlyMode: Boolean = false,
    /** 행사 출처 하나를 이만큼 기다려도 안 오면 저장된 자료로 대신한다. */
    private val slowSourceMs: Long = 8_000,
) {
    /** 앱을 열자마자 보여줄 용도: 같은 캐시를 쓰되 네트워크는 쓰지 않는 저장소. */
    fun cacheOnly(): Repository = Repository(http, cache, key, clock, kakaoKey, visitJejuKey, kopisKey, playJejuEnabled, cacheOnlyMode = true, slowSourceMs = slowSourceMs)

    private val visitJeju = VisitJejuApi(http, visitJejuKey)
    private val kopis = KopisApi(http, kopisKey)
    val hasKopisKey: Boolean get() = kopisKey().isNotBlank()
    private val geoApi = GeoApi(http, kakaoKey)
    val hasVisitJejuKey: Boolean get() = visitJejuKey().isNotBlank()
    private val kakao = KakaoApi(http, kakaoKey)
    val hasKakaoKey: Boolean get() = kakaoKey().isNotBlank()
    private val blogs = BlogSignalApi(http, kakaoKey)
    private val playJeju = PlayJejuApi(http)
    private val jejuNolda = JejuNoldaApi(http)
    private val jje = JjeReserveApi(http)
    private val jejusi = JejusiEventApi(http)
    private val imageApi = ImageSearchApi(http, kakaoKey)
    private val seogwipo = SeogwipoEventApi(http)

    private val tour = TourApi(http, key)
    private val weatherApi = WeatherApi(http, key)
    private val airApi = AirApi(http, key)

    val hasKey: Boolean get() = key().isNotBlank()

    private fun <T> cached(
        cacheKey: String,
        ttlMs: Long,
        force: Boolean,
        fetch: () -> String,
        parse: (String) -> T,
    ): Loaded<T> {
        val old = cache.read(cacheKey)
        if (cacheOnlyMode) {
            old ?: throw ApiException("저장된 정보 없음")
            return Loaded(parse(old.body), old.savedAt)
        }
        if (!force && old != null && clock() - old.savedAt < ttlMs) {
            val fresh = runCatching { parse(old.body) }
            if (fresh.isSuccess) return Loaded(fresh.getOrThrow(), old.savedAt)
        }
        return try {
            val body = fetch()
            val parsed = parse(body) // 파싱이 성공한 응답만 캐시에 남긴다.
            val at = clock()
            cache.write(cacheKey, body, at)
            Loaded(parsed, at)
        } catch (e: Exception) {
            val message = (e as? ApiException)?.message ?: "인터넷 연결을 확인해 주세요"
            val stale = old?.let { runCatching { parse(it.body) } }
            if (old == null || stale == null || stale.isFailure) throw ApiException(message)
            Loaded(stale.getOrThrow(), old.savedAt, message)
        }
    }

    /**
     * 행사 = 관광공사(큰 축제) + 플레이제주(공연·아동/가족) + 제주인놀다(문화예술 행사).
     * 한 곳이 실패해도 나머지로 보여준다. 같은 행사가 여러 곳에 올라오면 제목으로 묶는다.
     */
    fun festivals(today: LocalDate, force: Boolean = false, waitAll: Boolean = false): Loaded<List<Festival>> {
        if (!hasKey) return Loaded(SampleData.festivals(today), null)
        val jobs = eventSources(today, force)
        // 출처끼리 기다리지 않게 동시에 받는다. 한 곳이 오래 걸리면(교육청 서버 등) 저장해 둔 자료로 먼저 채우고,
        // 받기는 뒤에서 계속해 다음번에 쓰도록 캐시에 남긴다.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(jobs.size)
        val sources = try {
            jobs.map { (name, job) -> name to pool.submit<Result<Loaded<List<Festival>>>> { runCatching(job) } }
                .map { (name, f) ->
                    name to if (waitAll) f.get() else try {
                        f.get(if (cacheOnlyMode) 60_000 else slowSourceMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: java.util.concurrent.TimeoutException) {
                        val stale = runCatching { cacheOnly().eventSources(today, false).first { it.first == name }.second() }
                        if (stale.isSuccess) stale else f.get() // 저장된 자료가 없으면(첫 실행) 끝까지 기다린다.
                    }
                }
        } finally {
            pool.shutdown()
        }
        val ok = sources.mapNotNull { it.second.getOrNull() }
        if (ok.isEmpty()) throw sources.first().second.exceptionOrNull()!!
        return Loaded(
            data = withGeo(com.moon.jejukids.logic.EventQuality.normalize(mergeEvents(ok.flatMap { it.data }))),
            savedAt = ok.mapNotNull { it.savedAt }.minOrNull(),
            error = if (cacheOnlyMode) null else sources.firstNotNullOfOrNull { (name, r) ->
                (r.getOrNull()?.error ?: r.exceptionOrNull()?.message)?.let { "$name: $it" }
            },
        )
    }

    /** 점검용: 합치기 전 출처별 원본(캐시 기준). */
    fun rawEventsForAudit(today: LocalDate): List<Festival> =
        eventSources(today, false).flatMap { runCatching { it.second().data }.getOrDefault(emptyList()) }

    private fun eventSources(today: LocalDate, force: Boolean): List<Pair<String, () -> Loaded<List<Festival>>>> {
        return listOf(
            // 이미 시작해 진행 중인 행사도 잡도록 시작일 기준을 90일 앞당겨 조회한다.
            "관광공사" to { cached("festivals", 3 * HOUR, force, { tour.festivalsRaw(today.minusDays(90)) }) { TourApi.parseFestivals(it, today) } },
            "플레이제주" to { cached("events_playjeju", 3 * HOUR, force, { playJeju.raw(today) }) { PlayJejuApi.parse(it, today) } },
            // 날짜마다 한 번씩(21회) 불러야 해서 조금 더 길게 캐시한다.
            "제주인놀다" to { cached("events_jejunolda", 6 * HOUR, force, { jejuNolda.raw(today) }) { JejuNoldaApi.parse(it, today) } },
            // 교육청 서버는 느릴 때가 있어(실측 타임아웃) 6시간 캐시.
            "교육청 예약" to { cached("events_jje", 6 * HOUR, force, { jje.raw(today) }) { JjeReserveApi.parse(it, today) } },
            // 시청 공식 행사 목록: 동·읍 단위 축제는 여기에만 올라온다.
            "제주시" to { cached("events_jejusi", 6 * HOUR, force, { jejusi.raw() }) { JejusiEventApi.parse(it, today) } },
            "서귀포시" to { cached("events_seogwipo", 6 * HOUR, force, { seogwipo.raw(today) }) { SeogwipoEventApi.parse(it, today) } },
        ).filter { playJejuEnabled || it.first != "플레이제주" } +
            (if (hasVisitJejuKey) listOf<Pair<String, () -> Loaded<List<Festival>>>>("비짓제주" to { visitJejuFestivals(today, force) }) else emptyList()) +
            (if (hasKopisKey) listOf<Pair<String, () -> Loaded<List<Festival>>>>("공연(KOPIS)" to { kopisFestivals(today, force) }) else emptyList())
    }

    // ---- KOPIS 공연: 목록 6시간 캐시, 공연 상세 3일·공연장 90일 캐시(상세는 조금씩 채움) ----

    private fun kopisList(today: LocalDate, force: Boolean) = cached("kopis_list", 6 * HOUR, force, { kopis.listRaw(today) }) { KopisApi.parseList(it) }

    /** 상세·공연장 정보가 캐시에 있으면 붙이고(관람 연령·가격·회차·좌표), 없으면 목록 정보만으로. */
    private fun kopisFestivals(today: LocalDate, force: Boolean): Loaded<List<Festival>> {
        val list = kopisList(today, force)
        val events = list.data.filter { !it.end.isBefore(today) }.map { item ->
            val d = cache.read("kpd_" + item.id)?.let { runCatching { KopisApi.parseDetail(it.body) }.getOrNull() }
            val p = d?.placeId?.let { pid -> cache.read("kpp_$pid")?.let { runCatching { KopisApi.parsePlace(it.body) }.getOrNull() } }
            KopisApi.toFestival(item, d, p)
        }
        return Loaded(events, list.savedAt, list.error)
    }

    /**
     * 상세가 없거나 오래된 공연을 최대 [limit]건 받는다(공연장 정보도 함께).
     * KOPIS는 짧은 시간에 요청이 몰리면 접속을 막는다(2026-10-08 실측: 수십 건 연속 → 400 Request Blocked) → 한 건마다 쉬어 간다.
     */
    fun fillKopis(today: LocalDate, limit: Int): Int {
        if (!hasKopisKey || cacheOnlyMode) return 0
        val items = runCatching { kopisList(today, false).data }.getOrNull() ?: return 0
        var done = 0
        for (item in items.filter { !it.end.isBefore(today) }.sortedBy { it.start }) {
            if (done >= limit) break
            val e = cache.read("kpd_" + item.id)
            val detail = if (e != null && clock() - e.savedAt < 14 * 24 * HOUR) e.body else {
                val body = runCatching { cached("kpd_" + item.id, 14 * 24 * HOUR, true, { kopis.detailRaw(item.id) }) { it }.data }.getOrNull() ?: break
                done++
                Thread.sleep(KOPIS_PAUSE_MS)
                body
            }
            val pid = KopisApi.parseDetail(detail)?.placeId ?: continue
            val pe = cache.read("kpp_$pid")
            if (pe == null || clock() - pe.savedAt > 90 * 24 * HOUR) {
                runCatching { cached("kpp_$pid", 90 * 24 * HOUR, true, { kopis.placeRaw(pid) }) { it } }
                done++
                Thread.sleep(KOPIS_PAUSE_MS)
            }
        }
        return done
    }

    // ---- 비짓제주: 목록은 오픈API(하루 캐시), 기간은 행사 페이지에서 읽어 행사별로 캐시 ----

    private fun vjDatesKey(cid: String) = "vjd_" + cid.filter { it.isLetterOrDigit() }

    private fun vjList(force: Boolean) = cached("vj_list", 24 * HOUR, force, visitJeju::listRaw, VisitJejuApi::parseList)

    /** 기간을 이미 읽어 둔 행사 중 아직 안 끝난 것만. */
    private fun visitJejuFestivals(today: LocalDate, force: Boolean): Loaded<List<Festival>> {
        val list = vjList(force)
        val events = list.data.mapNotNull { e ->
            val d = cache.read(vjDatesKey(e.cid))?.let { runCatching { VisitJejuApi.datesFromJson(it.body) }.getOrNull() } ?: return@mapNotNull null
            if (d.end < today || d.start > today.plusDays(180)) null else VisitJejuApi.toFestival(e, d)
        }
        return Loaded(events, list.savedAt, list.error)
    }

    /**
     * 기간을 모르는(또는 오래된) 비짓제주 행사 페이지를 최대 [limit]개 읽는다.
     * 끝난 행사는 다시 읽지 않고, 진행·예정 행사는 7일마다, 기간을 못 찾은 페이지는 30일마다 다시 본다.
     */
    fun fillVisitJeju(today: LocalDate, limit: Int): Int {
        if (!hasVisitJejuKey || cacheOnlyMode) return 0
        val list = runCatching { vjList(false).data }.getOrNull() ?: return 0
        var done = 0
        for (e in VisitJejuApi.candidates(list, today)) {
            if (done >= limit) break
            val old = cache.read(vjDatesKey(e.cid))
            if (old != null) {
                val d = runCatching { VisitJejuApi.datesFromJson(old.body) }.getOrNull()
                val age = clock() - old.savedAt
                if (d != null && (d.end < today || age < 7 * 24 * HOUR)) continue
                if (d == null && age < SIGNAL_TTL) continue
            }
            val page = runCatching { visitJeju.pageRaw(e.cid) }.getOrNull() ?: break // 연결 문제면 다음에.
            done++
            // 행사 데이터가 빠진 채 온 페이지(사이트 쪽 일시 오류)는 저장하지 않고 다음에 다시 본다.
            if (!page.contains("\"festivalcontents\"")) continue
            cache.write(vjDatesKey(e.cid), VisitJejuApi.datesToJson(runCatching { VisitJejuApi.parsePage(page) }.getOrNull()), clock())
            Thread.sleep(200) // 사이트에 부담 주지 않게 천천히.
        }
        return done
    }

    fun places(force: Boolean = false): Loaded<List<Place>> {
        if (!hasKey) return Loaded(SampleData.places, null)
        val types = listOf(KidFilter.TYPE_SPOT, KidFilter.TYPE_CULTURE, KidFilter.TYPE_LEISURE)
        val results = types.map { type ->
            runCatching { cached("places_$type", 24 * HOUR, force, { tour.placesRaw(type) }, TourApi::parsePlaces) }
        }
        val kids = if (hasKakaoKey) runCatching { cached("kakao_kids", 3 * 24 * HOUR, force, kakao::kidsRaw, KakaoApi::parse) } else null
        val ok = results.mapNotNull { it.getOrNull() } + listOfNotNull(kids?.getOrNull())
        if (ok.isEmpty()) throw results.first().exceptionOrNull()!!
        return Loaded(
            data = ok.flatMap { it.data }.distinctBy { it.id },
            savedAt = ok.mapNotNull { it.savedAt }.minOrNull(),
            error = ok.firstNotNullOfOrNull { it.error }
                ?: results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
                ?: kids?.exceptionOrNull()?.message?.let { "키즈카페: $it" },
        )
    }

    /**
     * 주말 코스 점심·카페 후보(카카오 장소 검색). 약 2km 칸마다 한 번 검색해 14일 캐시한다.
     * 네트워크 없이 볼 때(cacheOnly)는 저장된 것만.
     */
    fun food(slot: com.moon.jejukids.logic.FoodPicker.Slot): List<Place> {
        if (!hasKey) return SampleData.food(slot)
        if (!hasKakaoKey) return emptyList()
        val fetch = {
            // 시골 쪽은 3km 안에 몇 곳 없어서 적으면 6km로 넓혀 다시 찾는다.
            val near = kakao.categoryRaw(slot.kind.code, slot.cellLat, slot.cellLng)
            if (KakaoApi.parseFood(near, slot.kind.tag).size >= 8) near else kakao.categoryRaw(slot.kind.code, slot.cellLat, slot.cellLng, 6000)
        }
        return cached("food2_" + slot.searchKey, 14 * 24 * HOUR, false, fetch) {
            KakaoApi.parseFood(it, slot.kind.tag)
        }.data
    }

    // ---- 행사 좌표 채우기(카카오 주소·장소 검색, 90일 캐시) ----

    private fun geoKey(qs: List<GeoQuery>) = "geo2_" + Integer.toHexString(qs.joinToString("|") { it.kind + ":" + it.text }.hashCode())

    /** 캐시에 있는 좌표를 좌표 없는 행사에 붙인다(네트워크 없음). */
    fun withGeo(list: List<Festival>): List<Festival> = list.map { f ->
        if (f.lat != null && f.lng != null) return@map f
        val q = GeoApi.queriesFor(f).takeIf { it.isNotEmpty() } ?: return@map f
        val c = cache.read(geoKey(q))?.let { runCatching { GeoApi.parse(it.body) }.getOrNull() } ?: return@map f
        f.copy(lat = c.first, lng = c.second)
    }

    /** 좌표 없는 행사의 위치를 최대 [limit]건 찾는다. 못 찾은 것도 저장해 90일 동안 다시 묻지 않는다. */
    fun fillGeo(list: List<Festival>, limit: Int): Int {
        if (!hasKakaoKey || cacheOnlyMode) return 0
        var done = 0
        for (q in list.filter { it.lat == null && !it.sample }.map(GeoApi::queriesFor).filter { it.isNotEmpty() }.distinct()) {
            if (done >= limit) break
            val e = cache.read(geoKey(q))
            if (e != null && clock() - e.savedAt < 90 * 24 * HOUR) continue
            if (runCatching { cached(geoKey(q), 90 * 24 * HOUR, true, { geoApi.raw(q) }) { it } }.isFailure) break
            done++
        }
        return done
    }

    fun weather(region: Region, now: LocalDateTime, force: Boolean = false): Loaded<Weather> {
        if (!hasKey) return Loaded(SampleData.weather(region), null)
        return cached("weather_${region.name}", HOUR, force, { weatherApi.raw(region, now) }) {
            WeatherApi.parse(it, region, now)
        }
    }

    fun air(region: Region, force: Boolean = false): Loaded<Air?> {
        if (!hasKey) return Loaded(SampleData.air(region), null)
        return cached("air", 30 * MINUTE, force, { airApi.raw() }) { AirApi.parse(it, region) }
    }

    /** 상세(소개·이용정보·사진). 자주 바뀌지 않아 7일 캐시(중앙 수집 시 하루 한도 때문). */
    fun detail(id: String, typeId: Int): Detail? {
        // 관광공사 항목(숫자 id)만 상세 API가 있다.
        if (!hasKey || typeId == KidFilter.TYPE_KAKAO || !id.all { it.isDigit() }) return null
        val common = cached("common_$id", 7 * 24 * HOUR, false, { tour.commonRaw(id) }) { portalItems(it); it }.data
        val intro = runCatching { cached("intro_$id", 7 * 24 * HOUR, false, { tour.introRaw(id, typeId) }) { portalItems(it); it }.data }.getOrNull()
        val photos = runCatching {
            cached("photos_$id", 7 * 24 * HOUR, false, { tour.imagesRaw(id) }) { TourApi.parsePhotos(it) }.data
        }.getOrDefault(emptyList())
        return TourApi.parseDetail(common, intro).copy(photos = photos)
    }

    /** 같은 행사(제목 기준)는 하나로 합친다. 관광공사 > 플레이제주 > 제주인놀다 순으로 남기고 가족 표시는 합친다. */
    /** 같은 행사는 하나로 묶는다(제목 유사도 + 날짜 근접, logic/EventMerge). */
    private fun mergeEvents(all: List<Festival>): List<Festival> = com.moon.jejukids.logic.EventMerge.merge(all)

    // ---- 사진 없는 항목용 이미지 검색 (id → 검색어). 같은 검색어(같은 기관 등)는 캐시를 함께 쓴다. ----

    /** 사진 없는 항목 → 사진 찾기 대상. 행사(가까운 날짜 순)를 먼저, 그다음 장소. */
    fun imageTargets(events: List<Festival>, places: List<Place>): List<ImageTarget> =
        events.filter { it.image == null && !it.sample }.sortedBy { it.start }.mapNotNull(ImageSearchApi::eventTarget) +
            places.filter { it.image == null && !it.sample }.map(ImageSearchApi::placeTarget)

    // v0.14: 찾는 방식을 바꿔 예전 캐시(img_)는 쓰지 않는다.
    private fun imageKey(t: ImageTarget) = "img3_" + Integer.toHexString((t.queries.first() + "|" + t.titleKey).hashCode())

    /** 네트워크 없이 캐시에 있는 이미지만. */
    fun cachedImages(items: List<ImageTarget>): Map<String, FoundImage> = items.mapNotNull { t ->
        cache.read(imageKey(t))?.let { e -> runCatching { ImageSearchApi.parseAny(e.body) }.getOrNull()?.let { t.id to it } }
    }.toMap()

    /**
     * 캐시가 없거나 30일 지난 항목을 앞에서부터 최대 [limit]개 새로 찾는다.
     * 검색어를 차례로 시도해 쓸 만한 사진이 나온 응답을 저장한다(끝까지 없으면 마지막 응답 → 아이콘 표시).
     */
    fun fillImages(items: List<ImageTarget>, limit: Int): Int {
        if (!hasKakaoKey || cacheOnlyMode) return 0
        var done = 0
        for (t in items.distinctBy { imageKey(it) }) {
            if (done >= limit) break
            val e = cache.read(imageKey(t))
            if (e != null && clock() - e.savedAt < SIGNAL_TTL) continue
            val fetch = {
                var body = ""
                for (q in t.queries) {
                    body = if (t.titleKey != null) imageApi.eventRaw(q, t.titleKey) else imageApi.raw(q)
                    if (ImageSearchApi.parseAny(body) != null) break
                }
                body
            }
            if (runCatching { cached(imageKey(t), SIGNAL_TTL, true, fetch) { it } }.isFailure) break
            done++
        }
        return done
    }

    // ---- 블로그 신호(노키즈존 언급 · 아이와 후기 수), 다음 블로그 검색 ----

    // v0.5부터 후기 50건·이미지까지 담으므로 키를 바꿔 예전(노키즈존만 담은) 캐시를 새로 채운다.
    private fun signalKey(id: String) = "blog2_" + id.filter { it.isLetterOrDigit() }

    /** 장소 하나의 신호. 30일 동안은 다시 묻지 않는다. */
    fun signal(place: Place, force: Boolean = false): KidSignal? {
        if (!hasKakaoKey || place.sample) return null
        return cached(signalKey(place.id), SIGNAL_TTL, force, { blogs.signalRaw(place.title, wantImage = place.image == null) }, BlogSignalApi::parse).data
    }

    /** 네트워크 없이 캐시에 있는 신호만 읽는다(목록 배지용). */
    fun cachedSignals(places: List<Place>): Map<String, KidSignal> = places.mapNotNull { p ->
        cache.read(signalKey(p.id))?.let { e -> runCatching { BlogSignalApi.parse(e.body) }.getOrNull()?.let { p.id to it } }
    }.toMap()

    /**
     * 아직 신호가 없거나 오래된 장소를 앞에서부터 최대 [limit]곳 채운다(장소당 호출 2번).
     * 카카오 하루 한도를 여러 사용자가 나눠 쓰므로 조금씩 나눠 채운다.
     */
    fun fillSignals(places: List<Place>, limit: Int): Int {
        if (!hasKakaoKey || cacheOnlyMode) return 0
        var done = 0
        for (p in places) {
            if (done >= limit) break
            if (p.sample) continue
            val e = cache.read(signalKey(p.id))
            if (e != null && clock() - e.savedAt < SIGNAL_TTL) continue
            if (runCatching { signal(p, force = true) }.isFailure) break // 한도·키 문제면 그만둔다.
            done++
        }
        return done
    }

    companion object {
        /** KOPIS 요청 사이 쉬는 시간. */
        const val KOPIS_PAUSE_MS = 800L
        const val SIGNAL_TTL = 30 * 24 * 60 * 60_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
