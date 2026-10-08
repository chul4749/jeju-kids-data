package com.moon.jejukids.data

import com.moon.jejukids.logic.AgeGroup
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 중앙 수집(GitHub Actions)이 만든 JSON 파일과 앱이 주고받는 형식.
 * 수집기와 앱이 이 파일 하나를 같이 써서 양쪽 형식이 어긋나지 않게 한다.
 * 형식을 바꾸면 [VERSION]을 올리고 파일 경로(v1/…)도 함께 바꾼다.
 */
object Wire {
    const val VERSION = 1

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
    private fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }
    private fun JSONObject.int(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
    private fun JSONObject.putOpt2(k: String, v: Any?): JSONObject = if (v == null) this else put(k, v)

    // ---- 행사 ----
    fun festival(f: Festival): JSONObject = JSONObject()
        .put("id", f.id).put("title", f.title).put("addr", f.addr)
        .putOpt2("lat", f.lat).putOpt2("lng", f.lng).putOpt2("image", f.image).putOpt2("tel", f.tel)
        .put("start", f.start.toString()).put("end", f.end.toString()).put("family", f.familyFriendly)
        .put("source", f.source).putOpt2("link", f.link).putOpt2("venue", f.venue).putOpt2("fee", f.fee)
        .putOpt2("time", f.time).putOpt2("category", f.category).putOpt2("status", f.status)
        .putOpt2("apply", f.apply).putOpt2("targets", f.targets)

    fun festival(o: JSONObject): Festival = Festival(
        id = o.getString("id"), title = o.getString("title"), addr = o.optString("addr"),
        lat = o.dbl("lat"), lng = o.dbl("lng"), image = o.str("image"), tel = o.str("tel"),
        start = LocalDate.parse(o.getString("start")), end = LocalDate.parse(o.getString("end")),
        familyFriendly = o.optBoolean("family"), source = o.optString("source"), link = o.str("link"),
        venue = o.str("venue"), fee = o.str("fee"), time = o.str("time"), category = o.str("category"),
        status = o.str("status"), apply = o.str("apply"), targets = o.str("targets"),
    )

    // ---- 장소 ----
    fun place(p: Place): JSONObject = JSONObject()
        .put("id", p.id).put("type", p.typeId).put("title", p.title).put("addr", p.addr)
        .putOpt2("lat", p.lat).putOpt2("lng", p.lng).putOpt2("image", p.image).putOpt2("tel", p.tel)
        .put("setting", p.setting.name).put("tags", JSONArray(p.tags.map { it.name })).put("kid", p.kidScore)
        .putOpt2("link", p.link).putOpt2("category", p.category)

    fun place(o: JSONObject): Place = Place(
        id = o.getString("id"), typeId = o.optInt("type"), title = o.getString("title"), addr = o.optString("addr"),
        lat = o.dbl("lat"), lng = o.dbl("lng"), image = o.str("image"), tel = o.str("tel"),
        setting = runCatching { Setting.valueOf(o.optString("setting")) }.getOrDefault(Setting.MIXED),
        tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { runCatching { PlaceTag.valueOf(a.getString(it)) }.getOrNull() }.toSet() }.orEmpty(),
        kidScore = o.optInt("kid"), link = o.str("link"), category = o.str("category"),
    )

    // ---- 블로그 신호 ----
    private fun post(p: BlogPost) = JSONObject().put("t", p.title).put("u", p.link).put("s", p.snippet)
    private fun post(o: JSONObject) = BlogPost(o.optString("t"), o.optString("u"), o.optString("s"))
    private fun pairs(l: List<Pair<String, Int>>) = JSONArray(l.map { JSONArray().put(it.first).put(it.second) })
    private fun pairs(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getJSONArray(i).let { p -> p.getString(0) to p.getInt(1) } } }.orEmpty()

    fun signal(s: KidSignal): JSONObject = JSONObject()
        .put("no", s.noKidsMentions).put("noPosts", JSONArray(s.noKidsPosts.map(::post))).put("kidCount", s.kidPostCount)
        .put("kw", pairs(s.keywords)).put("caution", pairs(s.cautions))
        .put("ages", JSONObject(s.ageCounts.mapKeys { it.key.name }))
        .put("quotes", JSONArray(s.quotes.map(::post))).putOpt2("thumb", s.thumb).putOpt2("photo", s.photo).put("own", s.ownPosts)

    fun signal(o: JSONObject): KidSignal = KidSignal(
        noKidsMentions = o.optInt("no"),
        noKidsPosts = o.optJSONArray("noPosts")?.let { a -> (0 until a.length()).map { post(a.getJSONObject(it)) } }.orEmpty(),
        kidPostCount = o.optInt("kidCount"),
        keywords = pairs(o.optJSONArray("kw")), cautions = pairs(o.optJSONArray("caution")),
        ageCounts = o.optJSONObject("ages")?.let { a -> a.keys().asSequence().mapNotNull { k -> runCatching { AgeGroup.valueOf(k) to a.getInt(k) }.getOrNull() }.toMap() }.orEmpty(),
        quotes = o.optJSONArray("quotes")?.let { a -> (0 until a.length()).map { post(a.getJSONObject(it)) } }.orEmpty(),
        thumb = o.str("thumb"), photo = o.str("photo"), ownPosts = o.optInt("own"),
    )

    // ---- 사진 ----
    fun image(i: FoundImage): JSONObject = JSONObject().put("thumb", i.thumb).putOpt2("photo", i.photo)
    fun image(o: JSONObject): FoundImage = FoundImage(o.getString("thumb"), o.str("photo"))

    // ---- 날씨·미세먼지 ----
    fun weather(w: Weather): JSONObject = JSONObject()
        .put("region", w.region.name).putOpt2("tempNow", w.tempNow).put("sky", w.sky.name).put("rainNow", w.rainNow)
        .put("rainSoon", w.rainSoon).put("popMax", w.popMax).putOpt2("tempMin", w.tempMin).putOpt2("tempMax", w.tempMax)
        .put("days", JSONArray(w.days.map { d ->
            JSONObject().put("date", d.date.toString()).put("popAm", d.popAm).put("popPm", d.popPm).put("rain", d.rain)
                .put("sky", d.sky.name).putOpt2("tempMin", d.tempMin).putOpt2("tempMax", d.tempMax)
        }))

    fun weather(o: JSONObject): Weather = Weather(
        region = Region.valueOf(o.getString("region")), tempNow = o.int("tempNow"),
        sky = runCatching { Sky.valueOf(o.optString("sky")) }.getOrDefault(Sky.UNKNOWN),
        rainNow = o.optBoolean("rainNow"), rainSoon = o.optBoolean("rainSoon"), popMax = o.optInt("popMax"),
        tempMin = o.int("tempMin"), tempMax = o.int("tempMax"),
        days = o.optJSONArray("days")?.let { a ->
            (0 until a.length()).map { i ->
                val d = a.getJSONObject(i)
                DayWeather(LocalDate.parse(d.getString("date")), d.optInt("popAm"), d.optInt("popPm"), d.optBoolean("rain"),
                    runCatching { Sky.valueOf(d.optString("sky")) }.getOrDefault(Sky.UNKNOWN), d.int("tempMin"), d.int("tempMax"))
            }
        }.orEmpty(),
    )

    fun air(a: Air): JSONObject = JSONObject().put("region", a.region.name).putOpt2("pm10", a.pm10).putOpt2("pm25", a.pm25)
        .put("grade", a.grade).put("time", a.dataTime)

    fun air(o: JSONObject): Air = Air(Region.valueOf(o.getString("region")), o.int("pm10"), o.int("pm25"), o.optInt("grade"), o.optString("time"))

    // ---- 상세(관광공사) ----
    fun detail(d: Detail): JSONObject = JSONObject().putOpt2("overview", d.overview).putOpt2("homepage", d.homepage)
        .put("facts", JSONArray(d.facts.map { JSONArray().put(it.first).put(it.second) })).put("photos", JSONArray(d.photos))

    fun detail(o: JSONObject): Detail = Detail(
        o.str("overview"), o.str("homepage"),
        o.optJSONArray("facts")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getString(0) to it.getString(1) } } }.orEmpty(),
        o.optJSONArray("photos")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
    )

    // ---- 묶음 파일 ----
    fun <T> list(items: List<T>, f: (T) -> JSONObject): JSONArray = JSONArray(items.map(f))
    fun <T> list(a: JSONArray, f: (JSONObject) -> T): List<T> = (0 until a.length()).mapNotNull { runCatching { f(a.getJSONObject(it)) }.getOrNull() }
    fun <T> map(m: Map<String, T>, f: (T) -> JSONObject): JSONObject = JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, f(v)) } }
    fun <T> map(o: JSONObject, f: (JSONObject) -> T): Map<String, T> =
        o.keys().asSequence().mapNotNull { k -> runCatching { k to f(o.getJSONObject(k)) }.getOrNull() }.toMap()
}
