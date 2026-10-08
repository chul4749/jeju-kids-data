package com.moon.jejukids.data

import com.moon.jejukids.logic.KidFilter
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 비짓제주 행사 목록의 한 건(날짜는 아직 모름). */
data class VjEvent(
    val cid: String,
    val title: String,
    val addr: String,
    val lat: Double?,
    val lng: Double?,
    val image: String?,
    val tags: String,
    val intro: String?,
    val phone: String?,
)

/** 행사 쪽 웹페이지에서 읽은 기간·시간·요금·주최. */
data class VjDates(val start: LocalDate, val end: LocalDate, val time: String?, val fee: String?, val host: String?)

/**
 * 비짓제주(제주관광공사 공식 포털). 주최 기관이 직접 올리는 행사가 많다(예: 제주MBC 제주야페스타, 마사회 제주목장 행사).
 * - 목록: 공식 오픈API(키 필요). 행사 958건 안팎이 오지만 **기간 필드가 없다**(활용가이드 V1.1 확인).
 * - 기간: 행사마다 공개 웹페이지(압축 약 134KB)의 Nuxt 데이터에서 stday/fnsday 를 읽는다 → 조금씩 나눠 받고 캐시.
 * (사이트 내부 월별 API는 외부 앱 요청을 막아 두었기 때문에 쓰지 않는다.)
 */
class VisitJejuApi(private val http: Http, private val key: () -> String) {

    private val ua = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) JejuKids")

    /** 축제/행사(c5) 전체 목록 — 페이지(100건)들을 {"items":[...]} 하나로 묶어 캐시한다. */
    fun listRaw(): String {
        val all = JSONArray()
        var page = 1
        while (page <= 20) {
            val body = http.get(
                "https://api.visitjeju.net/vsjApi/contents/searchList?apiKey=${URLEncoder.encode(key().trim(), "UTF-8")}&locale=kr&category=c5&page=$page", ua,
            )
            val root = JSONObject(body)
            if (root.optString("result") != "200") throw ApiException("비짓제주: ${root.optString("resultMessage", "응답 오류")}")
            val items = root.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) all.put(items.get(i))
            if (page >= root.optInt("pageCount", 1)) break
            page++
        }
        return JSONObject().put("items", all).toString()
    }

    fun pageRaw(cid: String): String = http.get("https://www.visitjeju.net/kr/festival/view?contentsid=$cid", ua)

    companion object {
        private fun JSONObject.s(name: String) = optString(name, "").trim().takeIf { it.isNotEmpty() && it != "null" && it != "*" }

        fun parseList(body: String): List<VjEvent> {
            val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { i ->
                val o = items.getJSONObject(i)
                val photo = o.optJSONObject("repPhoto")?.optJSONObject("photoid")
                VjEvent(
                    cid = o.s("contentsid") ?: return@mapNotNull null,
                    title = o.s("title")?.replace(Regex("\\s+"), " ") ?: return@mapNotNull null,
                    addr = o.s("roadaddress") ?: o.s("address") ?: "제주특별자치도",
                    lat = o.optDouble("latitude").takeIf { !it.isNaN() && it in 33.0..34.0 },
                    lng = o.optDouble("longitude").takeIf { !it.isNaN() && it in 126.0..127.0 },
                    image = (photo?.s("imgpath") ?: photo?.s("thumbnailpath"))?.replaceFirst("http://", "https://"),
                    tags = listOfNotNull(o.s("tag"), o.s("alltag")).joinToString(","),
                    intro = o.s("introduction"),
                    phone = o.s("phoneno"),
                )
            }.distinctBy { it.cid }
        }

        private val years = Regex("20\\d\\d")

        /**
         * 날짜를 확인할 대상: 제목 속 연도가 모두 올해보다 이전이면(예: "2025년 제주마축제") 끝난 행사로 보고 뺀다.
         * 나머지는 최근 등록(아이디 큰 것)부터 — 새로 올라온 행사가 지금·앞으로의 행사일 가능성이 높다.
         */
        fun candidates(all: List<VjEvent>, today: LocalDate): List<VjEvent> = all
            .filter { e -> years.findAll(e.title).map { it.value.toInt() }.toList().let { ys -> ys.isEmpty() || ys.max() >= today.year } }
            // 제목에 연도가 없는 행사 중 오래전 등록분(번호가 작은 것)은 이미 끝난 행사였다(2026-10 실측: 진행 중인 것은 14174번 이후).
            .filter { e -> years.containsMatchIn(e.title) || cidNumber(e.cid) >= OLD_CID_LIMIT }
            .sortedWith(
                // 제목에 올해·내년이 적힌 행사 먼저, 그다음 최근 등록 순.
                compareByDescending<VjEvent> { e -> years.findAll(e.title).any { it.value.toInt() >= today.year } }
                    .thenByDescending { cidNumber(it.cid) },
            )

        private fun cidNumber(cid: String) = cid.filter(Char::isDigit).toLongOrNull() ?: 0L
        private const val OLD_CID_LIMIT = 300000000014000L

        private val ymd = DateTimeFormatter.ofPattern("yyyyMMdd")

        /** 웹페이지의 __NUXT_DATA__ 에서 행사 정보(festivalcontents)를 찾아 기간 등을 읽는다. 못 찾으면 null. */
        fun parsePage(html: String): VjDates? {
            val data = Regex("__NUXT_DATA__[^>]*>(\\[.*?])</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return null
            val idx = Regex("\"festivalcontents\"\\s*:\\s*(\\d+)").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val arr = runCatching { JSONArray(data) }.getOrNull() ?: return null
            // Nuxt 데이터는 값 대신 배열 위치(숫자)로 서로를 가리킨다 — 따라가서 실제 값을 얻는다.
            // 배열/객체 안의 숫자는 위치를 가리키고, 그 위치에 있는 숫자·문자는 실제 값이다.
            fun at(i: Any?, depth: Int = 0): Any? {
                if (depth > 6 || i !is Int || i !in 0 until arr.length()) return null
                return when (val v = arr.opt(i)) {
                    is JSONArray -> (0 until v.length()).map { at(v.opt(it), depth + 1) }
                    is JSONObject -> v.keys().asSequence().associateWith { k -> at(v.opt(k), depth + 1) }
                    JSONObject.NULL -> null
                    else -> v
                }
            }
            var node = at(idx)
            while (node is List<*> && node.isNotEmpty()) node = node.first()
            val m = node as? Map<*, *> ?: return null
            fun str(k: String) = (m[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val start = str("stday")?.let { runCatching { LocalDate.parse(it, ymd) }.getOrNull() } ?: return null
            val end = str("fnsday")?.let { runCatching { LocalDate.parse(it, ymd) }.getOrNull() } ?: start
            // 공모전처럼 시간이 의미 없는 행사는 00:00 으로 들어 있다 → 시간 표시 안 함.
            val time = if (str("sttime") == "00:00") null else listOfNotNull(str("sttime"), str("fnstime")).joinToString("~").ifEmpty { null }
            val priceType = ((m["pricetype"] as? Map<*, *>)?.get("label") as? String)?.trim()
            val price = (m["price"] as? Number)?.toInt()
            val fee = when {
                priceType == "유료" && price != null && price > 0 -> "%,d원".format(price)
                !priceType.isNullOrBlank() -> priceType
                else -> null
            }
            return VjDates(start, end, time, fee, str("host"))
        }

        /** 캐시해 둘 때 쓰는 짧은 JSON(웹페이지 전체를 저장하지 않으려고). */
        fun datesToJson(d: VjDates?): String = if (d == null) "{}" else JSONObject()
            .put("s", d.start.toString()).put("e", d.end.toString()).put("t", d.time ?: "").put("f", d.fee ?: "").put("h", d.host ?: "").toString()

        fun datesFromJson(body: String): VjDates? {
            val o = JSONObject(body)
            val s = o.optString("s").takeIf { it.isNotEmpty() } ?: return null
            return VjDates(LocalDate.parse(s), LocalDate.parse(o.optString("e", s)),
                o.optString("t").ifEmpty { null }, o.optString("f").ifEmpty { null }, o.optString("h").ifEmpty { null })
        }

        private val familyTag = Regex("아이|어린이|가족|키즈|유아|체험")

        fun toFestival(e: VjEvent, d: VjDates): Festival = Festival(
            id = "vj" + e.cid,
            title = e.title,
            addr = e.addr,
            lat = e.lat, lng = e.lng,
            image = e.image,
            tel = e.phone,
            start = d.start, end = d.end,
            familyFriendly = (KidFilter.isFamilyFestival(e.title) || familyTag.containsMatchIn(e.tags)) && !Regex("성인|19세|맥주|비어|와인").containsMatchIn(e.title),
            source = "비짓제주",
            link = "https://www.visitjeju.net/kr/festival/view?contentsid=${e.cid}",
            venue = null,
            fee = d.fee,
            time = d.time,
            category = if (e.title.contains("축제") || e.tags.contains("축제")) "축제" else "행사",
        )
    }
}
