package com.moon.jejukids.data

import com.moon.jejukids.logic.KidFilter
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * 키 없이 받을 수 있는 제주 행사 출처 두 곳.
 * 둘 다 공식 오픈API가 아니라 각 사이트가 화면에 쓰는 공개 JSON이다 → 사이트가 바뀌면 깨질 수 있다.
 * (참고: github.com/oessol-jeju/jeju-events 가 같은 방식으로 수집한다.)
 */

private const val UA = "Mozilla/5.0 (Linux; Android 14) JejuKids"

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private fun JSONObject.str(name: String): String? = optString(name, "").trim().takeIf { it.isNotEmpty() && it != "null" }

private fun https(url: String?) = url?.replaceFirst("http://", "https://")

/** 가족 대상으로 보이지만 실제로는 성인 대상인 경우(예: "그림책 자서전[50세 이상]"). */
private val adultOnly = Regex("성인|장년|50세|60세|시니어|어르신|19세")

/** 플레이제주(제주 공연·전시·행사 포털). '아동/가족' 메뉴가 따로 있다. */
class PlayJejuApi(private val http: Http) {

    fun raw(today: LocalDate): String {
        val all = JSONArray()
        for (page in 1..3) {
            val params = listOf(
                "pagination[page]" to page.toString(),
                "pagination[pageSize]" to "100",
                "filters[tenant][documentId][\$eq]" to TENANT,
                "filters[end_date][\$gte]" to today.toString(),
                "filters[start_date][\$lte]" to today.plusDays(90).toString(),
                "sort[0]" to "start_date:asc",
            )
            val url = "https://api.playx.kr/api/cms/events?" + params.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
            val root = JSONObject(http.get(url, mapOf("User-Agent" to UA)))
            val data = root.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) all.put(data.get(i))
            val pageCount = root.optJSONObject("meta")?.optJSONObject("pagination")?.optInt("pageCount", 1) ?: 1
            if (page >= pageCount) break
        }
        return JSONObject().put("data", all).toString()
    }

    companion object {
        /** 플레이제주 테넌트(같은 API에 광주 등 다른 지역이 섞여 있다). */
        const val TENANT = "fp5itcy1jmnsh9xnkvju713c"

        fun parse(body: String, today: LocalDate): List<Festival> {
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            return (0 until data.length()).mapNotNull { i ->
                val o = data.getJSONObject(i)
                val title = o.str("title") ?: return@mapNotNull null
                val start = o.str("start_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                val end = o.str("end_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: start
                if (end.isBefore(today)) return@mapNotNull null
                // 시작일이 아주 오래된 상시 게시물은 행사 목록을 어지럽혀 뺀다.
                if (start.isBefore(today.minusYears(1))) return@mapNotNull null
                val menus = o.optJSONArray("menus") ?: JSONArray()
                val menuSlugs = (0 until menus.length()).map { menus.getJSONObject(it).optString("slug") }
                val menuTitle = (0 until menus.length()).map { menus.getJSONObject(it).optString("title") }.firstOrNull { it.isNotBlank() }
                // 세부 장르(terms)에 "축제"가 있으면 축제로 분류한다(메뉴는 "체험/행사"로만 나온다).
                val terms = o.optJSONArray("terms") ?: JSONArray()
                val isFestivalTerm = (0 until terms.length()).any { terms.getJSONObject(it).optJSONObject("term")?.optString("name") == "축제" }
                val rating = o.str("audience_rating")
                val region = o.optJSONObject("administrative_region")?.str("name")
                val venue = o.str("venue_detail")
                val family = ("kids_family" in menuSlugs || rating == "family" || KidFilter.isFamilyFestival(title)) &&
                    !adultOnly.containsMatchIn(title) && rating != "r19" && rating != "adult"
                Festival(
                    id = "pj" + (o.str("documentId") ?: return@mapNotNull null),
                    title = title,
                    addr = listOfNotNull("제주특별자치도", region?.takeIf { it != "제주특별자치도" }, venue).joinToString(" "),
                    lat = null, lng = null,
                    image = https(o.optJSONObject("poster_image")?.str("url")),
                    tel = null,
                    start = start, end = end,
                    familyFriendly = family,
                    source = "플레이제주",
                    link = o.str("slug")?.let { "https://www.playjeju.co.kr/events/$it" },
                    venue = venue,
                    // price_text 에 연락처가 들어오는 경우가 있어 금액·무료 표기일 때만 쓴다.
                    fee = o.str("price_text")?.takeIf { it.contains("원") || it.contains("무료") },
                    category = if (isFestivalTerm) "축제" else menuTitle,
                )
            }
        }
    }
}

/**
 * 제주인놀다(제주문화예술재단). 사이트 검색 엔드포인트가 "그날 열리는 행사"만 주므로 날짜별로 묻고 합친다.
 * 실내/야외·유무료·좌표까지 들어 있다.
 */
class JejuNoldaApi(private val http: Http) {

    fun raw(today: LocalDate, days: Int = 21): String {
        val bySeq = LinkedHashMap<String, JSONObject>()
        for (d in 0 until days) {
            val day = today.plusDays(d.toLong())
            val root = JSONObject(
                http.get(
                    "https://www.jejunolda.com/event/progress.htm?act=search&format=json&pageSize=100&page=1&indayString=$day",
                    mapOf("User-Agent" to UA),
                )
            )
            val list = root.optJSONArray("eventList") ?: continue
            for (i in 0 until list.length()) {
                val e = list.getJSONObject(i)
                bySeq[e.optString("seq")] = e
            }
        }
        val all = JSONArray()
        bySeq.values.forEach { all.put(it) }
        return JSONObject().put("eventList", all).toString()
    }

    companion object {
        /** 날짜가 UTC 자정·KST 자정(15:00Z)으로 섞여 저장돼 있어, 9시간 더한 날짜를 쓰면 둘 다 맞는다. */
        fun kstDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms + 9 * 3_600_000L).atZone(ZoneOffset.UTC).toLocalDate()

        fun parse(body: String, today: LocalDate): List<Festival> {
            val list = JSONObject(body).optJSONArray("eventList") ?: return emptyList()
            return (0 until list.length()).mapNotNull { i ->
                val e = list.getJSONObject(i)
                val title = e.str("name")?.replace(Regex("\\s+"), " ") ?: return@mapNotNull null
                val startMs = e.optLong("start", 0L).takeIf { it > 0 } ?: return@mapNotNull null
                val start = kstDate(startMs)
                val end = e.optLong("end", 0L).takeIf { it > 0 }?.let(::kstDate) ?: start
                if (end.isBefore(today)) return@mapNotNull null
                // 제주 작가의 다른 지역 전시(서울·대구·전주 주소)도 올라온다 → 제주 밖은 뺀다(2026-10-07 점검).
                if (e.str("addr1")?.let { !it.startsWith("제주") } == true) return@mapNotNull null
                Festival(
                    id = "jn" + (e.str("seq") ?: return@mapNotNull null),
                    title = title,
                    addr = e.str("addr1") ?: e.str("regName")?.let { "제주특별자치도 $it" } ?: "제주특별자치도",
                    // 이 사이트는 x에 위도, y에 경도를 넣는다.
                    lat = e.optDouble("x").takeIf { !it.isNaN() && it in 33.0..34.0 },
                    lng = e.optDouble("y").takeIf { !it.isNaN() && it in 126.0..127.0 },
                    image = https(e.str("poster")),
                    tel = e.str("tel"),
                    start = start, end = end,
                    familyFriendly = KidFilter.isFamilyFestival(title) && !adultOnly.containsMatchIn(title),
                    source = "제주인놀다",
                    link = https(e.str("link")) ?: "https://www.jejunolda.com/event/progress.htm",
                    venue = e.str("instituteName") ?: e.str("addr2"),
                    fee = e.str("payName"),
                    time = e.str("time"),
                    category = e.str("categoryName")?.removeSuffix("(사용안함)"),
                )
            }
        }
    }
}

/**
 * 제목 비교용: 연도·회차·기호·공백만 지운다. 같은 행사가 여러 출처에 올라오는 경우가 많다.
 * 괄호 안 내용은 남긴다 — "탐라도서관 <길 위의 인문학>"과 "탐라도서관 <사건의 지평선…>"은 다른 행사다(실데이터).
 */
/** 제목에 자주 섞이는 한자 → 한글("제주, 神들이 노래하는 땅" ↔ "제주, 신들이 노래하는 땅"). */
private val hanja = mapOf('神' to '신', '夜' to '야', '蘭' to '난', '才' to '재', '馬' to '마', '展' to '전', '島' to '도', '多' to '다', '海' to '해')

fun eventKey(title: String): String = title.map { hanja[it] ?: it }.joinToString("")
    .replace(Regex("20\\d\\d년?|제\\s*\\d+\\s*회"), "")
    .replace(Regex("[^가-힣A-Za-z0-9]"), "")
    .lowercase()

/**
 * 제주특별자치도교육청 통합예약시스템(org.jje.go.kr). 유아교육진흥원·학생문화원·교육박물관·
 * 교육청 공공도서관·외국문화학습관·과학탐구체험관 등의 체험·강좌·공연 신청을 한곳에 모아 둔 곳이다.
 * 공식 API가 없어 목록 표(HTML)를 읽는다. 서버가 느릴 때가 있어 페이지 사이를 조금 띄운다.
 */
class JjeReserveApi(private val http: Http, private val pause: () -> Unit = { Thread.sleep(500) }) {

    /** (메뉴 경로, menuCd, 검색 파라미터 접두어, 분류 이름) */
    private val menus = listOf(
        listOf("jjeExperience", "DOM_000000501001000000", "experience", "견학/체험"),
        listOf("jjeEducation", "DOM_000000502001000000", "education", "교육/강좌"),
        listOf("jjeEvent", "DOM_000000503001000000", "event", "공연/행사"),
    )

    /** 운영일이 오늘~60일 뒤인 것만, 분류마다 최대 8쪽(80건). 결과는 {분류: [html…]} 묶음으로 캐시한다. */
    fun raw(today: LocalDate): String {
        val root = JSONObject()
        for ((path, menu, prefix, label) in menus) {
            val pages = JSONArray()
            for (page in 1..8) {
                val url = "https://org.jje.go.kr/$path/list.jje?menuCd=$menu&searChType=2" +
                    "&${prefix}Sdate=$today&${prefix}Edate=${today.plusDays(60)}&startPage=$page"
                val html = http.get(url, mapOf("User-Agent" to UA))
                val body = html.substringAfter("<tbody>", "").substringBefore("</tbody>")
                pages.put(body)
                if (rowRegex.findAll(body).count() < 10) break
                pause()
            }
            root.put(label, pages)
        }
        return root.toString()
    }

    companion object {
        private val rowRegex = Regex("<tr>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)

        private fun cell(row: String, header: String): String {
            val m = Regex("data-cell-header=\"(?:$header)[^\"]*\"[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).find(row) ?: return ""
            return m.groupValues[1].replace(Regex("<br\\s*/?>"), " ").replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&#034;", "\"").replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace(Regex("\\s+"), " ").trim()
        }

        /** 단체·학교 예약, 시설 이용, 성인·중고등 대상은 가족이 신청할 수 없거나 해당이 없어 뺀다. */
        private val notForFamilies = Regex("단체|어린이집/유치원|학교자율|이용 신청|대관|성인|20~30대|고등학교|중학생|교직원 연수|학부모 역량")

        fun parse(body: String, today: LocalDate): List<Festival> {
            val root = JSONObject(body)
            val out = mutableListOf<Festival>()
            for (label in root.keys()) {
                val pages = root.optJSONArray(label) ?: continue
                for (p in 0 until pages.length()) {
                    for (m in rowRegex.findAll(pages.optString(p))) {
                        val row = m.groupValues[1]
                        if (!row.contains("data-cell-header")) continue
                        val title = cell(row, "체험명|교육/강좌명|공연/행사명")
                        if (title.isEmpty()) continue
                        val inst = cell(row, "기관명")
                        val targets = cell(row, "체험대상|교육대상|참여대상")
                        val status = cell(row, "예약상태")
                        if (status == "마감") continue
                        // 유아·가족·학생 대상만. 학생만 대상이면서 청소년 프로그램인 것은 뺀다.
                        if (!Regex("유아|가족|학생").containsMatchIn(targets)) continue
                        if (notForFamilies.containsMatchIn(title)) continue
                        if (!Regex("유아|가족").containsMatchIn(targets) && Regex("청소년|중등|고교").containsMatchIn(title)) continue
                        val (startS, endS) = cell(row, "운영기간").split("~").map { it.trim() }.let { it.getOrNull(0) to it.getOrNull(1) }
                        val start = startS?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
                        val end = endS?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: start
                        if (end.isBefore(today)) continue
                        val href = Regex("href=\"([^\"]+)\"").find(row)?.groupValues?.get(1)?.replace("&amp;", "&")
                        val sid = href?.let { Regex("(?:experience|education|event)Sid=([A-Z]{2}_\\d+)").find(it)?.groupValues?.get(1) } ?: continue
                        val city = if (inst.contains("서귀포")) "서귀포시" else "제주시"
                        out += Festival(
                            id = "je$sid",
                            title = title,
                            addr = "제주특별자치도 $city $inst",
                            lat = null, lng = null, image = null, tel = null,
                            start = start, end = end,
                            familyFriendly = true,
                            source = "교육청 예약",
                            link = "https://org.jje.go.kr$href",
                            venue = inst,
                            category = label,
                            status = status,
                            apply = cell(row, "접수기간"),
                            targets = targets,
                        )
                    }
                }
            }
            return out.distinctBy { it.id }
        }
    }
}
