package com.moon.jejukids.data

import com.moon.jejukids.logic.KidFilter
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 테스트에서 가짜 응답을 넣을 수 있게 HTTP를 분리한다. */
fun interface Http {
    fun get(url: String, headers: Map<String, String>): String
}

fun Http.get(url: String): String = get(url, emptyMap())

object UrlHttp : Http {
    override fun get(url: String, headers: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code == 401 || code == 403) throw ApiException(authMessage(body))
            if (code !in 200..299) throw ApiException("서버 응답 오류 ($code)")
            return body
        } finally {
            conn.disconnect()
        }
    }
}

/** 카카오는 키 문제를 401/403 + JSON 메시지로 알려준다. */
private fun authMessage(body: String): String = when {
    body.contains("OPEN_MAP_AND_LOCAL") || body.contains("disabled") ->
        "카카오 개발자 사이트에서 이 앱의 [카카오맵] 사용 설정을 켜 주세요."
    else -> "인증키를 확인해 주세요."
}

private val ymd = DateTimeFormatter.ofPattern("yyyyMMdd")

/**
 * 공공데이터포털 인증키. 포털은 Encoding 키(이미 %인코딩됨)와 Decoding 키를 둘 다 주는데,
 * 어느 쪽을 붙여넣어도 동작하도록 '%'가 있으면 그대로, 없으면 인코딩한다.
 */
fun encodeServiceKey(key: String): String {
    val k = key.trim()
    return if (k.contains('%')) k else URLEncoder.encode(k, "UTF-8")
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/**
 * 공공데이터포털 응답 공통 처리.
 * 키 오류는 JSON을 요청해도 XML로 오기 때문에 XML이면 메시지를 뽑아 예외로 던진다.
 * 결과가 0건이면 items가 빈 문자열로 오므로 빈 배열로 바꾼다.
 */
internal fun portalItems(body: String): JSONArray {
    val text = body.trim()
    if (text.startsWith("<")) {
        val msg = Regex("<returnAuthMsg>(.*?)</returnAuthMsg>").find(text)?.groupValues?.get(1)
            ?: Regex("<resultMsg>(.*?)</resultMsg>").find(text)?.groupValues?.get(1)
            ?: "알 수 없는 오류"
        throw ApiException(friendly(msg))
    }
    val root = JSONObject(text)
    val response = root.optJSONObject("response")
    val header = response?.optJSONObject("header") ?: root
    val code = header.optString("resultCode", "00")
    if (code != "00" && code != "0000") {
        throw ApiException(friendly(header.optString("resultMsg", code)))
    }
    val items = response?.optJSONObject("body")?.opt("items") ?: return JSONArray()
    if (items is JSONArray) return items // 에어코리아는 배열로 바로 준다.
    if (items !is JSONObject) return JSONArray()
    return when (val item = items.opt("item")) {
        is JSONArray -> item
        is JSONObject -> JSONArray().put(item)
        else -> JSONArray()
    }
}

private fun friendly(msg: String): String = when {
    msg.contains("SERVICE_KEY_IS_NOT_REGISTERED") || msg.contains("SERVICE KEY IS NOT REGISTERED") ->
        "인증키가 등록되지 않았어요. 활용신청 직후라면 1~2시간 뒤 다시 시도해 주세요."
    msg.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS") -> "오늘 호출 한도를 넘었어요. 내일 다시 갱신됩니다."
    msg.contains("SERVICE_ACCESS_DENIED") -> "이 API에 대한 활용신청이 필요해요."
    msg.contains("NO_DATA") || msg.contains("NODATA") -> "데이터가 없어요."
    else -> msg
}

private fun JSONObject.str(name: String): String? = optString(name, "").trim().takeIf { it.isNotEmpty() }

/** 관광공사 이미지 주소는 http로 오는 경우가 있어 https로 바꾼다. */
internal fun httpsImage(url: String?): String? = url?.replaceFirst("http://", "https://")

/** 한국관광공사 국문 관광정보 서비스(TourAPI 4.0, KorService2). */
class TourApi(private val http: Http, private val key: () -> String) {

    private val base = "https://apis.data.go.kr/B551011/KorService2"

    private fun url(op: String, params: Map<String, String>): String {
        val q = buildString {
            append("serviceKey=").append(encodeServiceKey(key()))
            append("&MobileOS=AND&MobileApp=").append(enc("JejuKids")).append("&_type=json")
            params.forEach { (k, v) -> append('&').append(k).append('=').append(enc(v)) }
        }
        return "$base/$op?$q"
    }

    /** 제주 법정동 시도코드. 옛 지역코드(areaCode=39)는 폐지 예정이라 쓰지 않는다. */
    private val jeju = mapOf("lDongRegnCd" to "50")

    fun festivalsRaw(from: LocalDate): String = http.get(
        url("searchFestival2", jeju + mapOf("eventStartDate" to from.format(ymd), "numOfRows" to "500", "pageNo" to "1", "arrange" to "A"))
    )

    fun placesRaw(typeId: Int): String = http.get(
        url("areaBasedList2", jeju + mapOf("contentTypeId" to typeId.toString(), "numOfRows" to "1000", "pageNo" to "1", "arrange" to "Q"))
    )

    fun commonRaw(id: String): String = http.get(url("detailCommon2", mapOf("contentId" to id)))

    /** 사진 목록(원본 주소 포함). imageYN=Y: 장소 사진(N은 음식점 메뉴 사진). */
    fun imagesRaw(id: String): String = http.get(url("detailImage2", mapOf("contentId" to id, "imageYN" to "Y")))

    fun introRaw(id: String, typeId: Int): String =
        http.get(url("detailIntro2", mapOf("contentId" to id, "contentTypeId" to typeId.toString())))

    companion object {
        fun parseFestivals(body: String, today: LocalDate): List<Festival> {
            val items = portalItems(body)
            return (0 until items.length()).mapNotNull { i ->
                val o = items.getJSONObject(i)
                val addr = o.str("addr1").orEmpty()
                if (addr.isNotEmpty() && !addr.contains("제주")) return@mapNotNull null
                val start = o.str("eventstartdate")?.let { runCatching { LocalDate.parse(it, ymd) }.getOrNull() }
                    ?: return@mapNotNull null
                val end = o.str("eventenddate")?.let { runCatching { LocalDate.parse(it, ymd) }.getOrNull() } ?: start
                if (end.isBefore(today)) return@mapNotNull null
                val title = o.str("title") ?: return@mapNotNull null
                Festival(
                    id = o.str("contentid") ?: return@mapNotNull null,
                    title = title,
                    addr = addr,
                    lat = o.str("mapy")?.toDoubleOrNull(),
                    lng = o.str("mapx")?.toDoubleOrNull(),
                    image = httpsImage(o.str("firstimage") ?: o.str("firstimage2")),
                    tel = o.str("tel"),
                    start = start,
                    end = end,
                    familyFriendly = KidFilter.isFamilyFestival(title),
                )
            }.distinctBy { it.id }.sortedWith(compareBy({ it.start }, { it.title }))
        }

        fun parsePlaces(body: String): List<Place> {
            val items = portalItems(body)
            return (0 until items.length()).mapNotNull { i ->
                val o = items.getJSONObject(i)
                val title = o.str("title") ?: return@mapNotNull null
                val typeId = o.str("contenttypeid")?.toIntOrNull() ?: return@mapNotNull null
                val addr = o.str("addr1").orEmpty()
                if (addr.isNotEmpty() && !addr.contains("제주")) return@mapNotNull null
                val code = o.str("lclsSystm3").orEmpty()
                val score = KidFilter.kidScore(title, typeId, code)
                if (score == 0) return@mapNotNull null
                Place(
                    id = o.str("contentid") ?: return@mapNotNull null,
                    typeId = typeId,
                    title = title,
                    addr = addr,
                    lat = o.str("mapy")?.toDoubleOrNull(),
                    lng = o.str("mapx")?.toDoubleOrNull(),
                    image = httpsImage(o.str("firstimage") ?: o.str("firstimage2")),
                    tel = o.str("tel"),
                    setting = KidFilter.setting(title, typeId, code),
                    tags = KidFilter.tags(title),
                    kidScore = score,
                )
            }
        }

        /** 상세: 개요·홈페이지 + 유형별 소개 항목(이용시간·휴무·유모차·체험연령 등). */
        /** detailImage2 → 원본 사진 주소들(https). */
        fun parsePhotos(body: String): List<String> {
            val items = portalItems(body)
            return (0 until items.length()).mapNotNull { i ->
                val o = items.getJSONObject(i)
                httpsImage(o.str("originimgurl") ?: o.str("smallimageurl"))
            }.distinct()
        }

        fun parseDetail(commonBody: String, introBody: String?): Detail {
            val common = portalItems(commonBody).optJSONObject(0)
            val overview = common?.str("overview")?.let(::stripHtml)
            val homepage = common?.str("homepage")?.let { Regex("href=\"([^\"]+)\"").find(it)?.groupValues?.get(1) ?: stripHtml(it) }
            val intro = introBody?.let { runCatching { portalItems(it).optJSONObject(0) }.getOrNull() }
            val facts = if (intro == null) emptyList() else introLabels.mapNotNull { (field, label) ->
                intro.str(field)?.let(::stripHtml)?.takeIf { it.isNotBlank() }?.let { label to it }
            }.distinctBy { it.first }
            return Detail(overview, homepage?.takeIf { it.isNotBlank() }, facts)
        }

        /** detailIntro2 필드 → 화면 이름. 관광지/문화시설/축제/레포츠 필드가 이름만 달라서 함께 매핑한다. */
        private val introLabels = listOf(
            "eventplace" to "장소",
            // 시작일·종료일은 위 "기간"에 이미 나온다(20261009 같은 날짜 그대로 보이던 문제).
            "playtime" to "공연·운영 시간",
            "usetimefestival" to "요금",
            "agelimit" to "관람 연령",
            "program" to "프로그램",
            "usetime" to "이용 시간",
            "usetimeculture" to "이용 시간",
            "usetimeleports" to "이용 시간",
            "opentime" to "이용 시간",
            "restdate" to "쉬는 날",
            "restdateculture" to "쉬는 날",
            "restdateleports" to "쉬는 날",
            "usefee" to "요금",
            "usefeeleports" to "요금",
            "expagerange" to "체험 가능 연령",
            "expagerangeleports" to "체험 가능 연령",
            "expguide" to "체험 안내",
            "chkbabycarriage" to "유모차",
            "chkbabycarriageculture" to "유모차",
            "chkbabycarriageleports" to "유모차",
            "parking" to "주차",
            "parkingculture" to "주차",
            "parkingleports" to "주차",
            "parkingfee" to "주차 요금",
            "infocenter" to "문의",
            "infocenterculture" to "문의",
            "infocenterleports" to "문의",
            "sponsor1tel" to "문의",
            "spendtime" to "관람 소요 시간",
            "spendtimefestival" to "관람 소요 시간",
        )

        fun stripHtml(s: String): String = s
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}

/** 기상청 단기예보(VilageFcstInfoService_2.0). */
class WeatherApi(private val http: Http, private val key: () -> String) {

    fun raw(region: Region, now: LocalDateTime): String {
        val (date, time) = baseTime(now)
        val (nx, ny) = grid(region)
        val url = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst" +
            "?serviceKey=${encodeServiceKey(key())}&pageNo=1&numOfRows=1000&dataType=JSON" +
            "&base_date=$date&base_time=$time&nx=$nx&ny=$ny"
        return http.get(url)
    }

    companion object {
        /** 격자 좌표: 제주시청 부근(53,38), 서귀포시청 부근(52,33). */
        fun grid(region: Region) = when (region) {
            Region.JEJU -> 53 to 38
            Region.SEOGWIPO -> 52 to 33
        }

        private val baseHours = listOf(2, 5, 8, 11, 14, 17, 20, 23)

        /** 발표 시각(02·05·…·23시) 10분 뒤부터 조회 가능하므로 15분 여유를 두고 가장 최근 발표를 고른다. */
        fun baseTime(now: LocalDateTime): Pair<String, String> {
            val t = now.minusMinutes(15)
            val hour = baseHours.lastOrNull { it <= t.hour }
            return if (hour == null) {
                t.toLocalDate().minusDays(1).format(ymd) to "2300"
            } else {
                t.toLocalDate().format(ymd) to "%02d00".format(hour)
            }
        }

        fun parse(body: String, region: Region, now: LocalDateTime): Weather {
            val items = portalItems(body)
            // (예보시각) → (분류 → 값)
            val byTime = sortedMapOf<LocalDateTime, MutableMap<String, String>>()
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val date = o.optString("fcstDate")
                val time = o.optString("fcstTime")
                val at = runCatching {
                    LocalDateTime.of(LocalDate.parse(date, ymd), java.time.LocalTime.of(time.take(2).toInt(), 0))
                }.getOrNull() ?: continue
                byTime.getOrPut(at) { mutableMapOf() }[o.optString("category")] = o.optString("fcstValue")
            }
            val hourNow = now.withMinute(0).withSecond(0).withNano(0)
            val upcoming = byTime.filterKeys { !it.isBefore(hourNow) }
            val first = upcoming.values.firstOrNull()
            val today = upcoming.filterKeys { it.toLocalDate() == now.toLocalDate() }.values
            val next6 = upcoming.filterKeys { it.isBefore(hourNow.plusHours(6)) }.values

            fun pty(m: Map<String, String>) = (m["PTY"]?.toIntOrNull() ?: 0) > 0
            fun pop(m: Map<String, String>) = m["POP"]?.toIntOrNull() ?: 0
            val temps = (today.ifEmpty { listOfNotNull(first) }).mapNotNull { it["TMP"]?.toDoubleOrNull()?.toInt() }

            return Weather(
                region = region,
                tempNow = first?.get("TMP")?.toDoubleOrNull()?.toInt(),
                sky = when (first?.get("SKY")) {
                    "1" -> Sky.CLEAR
                    "3" -> Sky.CLOUDY
                    "4" -> Sky.OVERCAST
                    else -> Sky.UNKNOWN
                },
                rainNow = first?.let(::pty) ?: false,
                rainSoon = next6.any { pty(it) || pop(it) >= 60 },
                popMax = (today.ifEmpty { listOfNotNull(first) }).maxOfOrNull(::pop) ?: 0,
                tempMin = temps.minOrNull(),
                tempMax = temps.maxOrNull(),
                days = dailySummary(byTime, now.toLocalDate()),
            )
        }

        /** 시간별 예보를 날짜별 낮 시간 요약으로 묶는다(오늘 지난 시간 포함 — 하루 전체 성격을 보려고). */
        private fun dailySummary(byTime: Map<LocalDateTime, Map<String, String>>, today: LocalDate): List<DayWeather> =
            byTime.entries.filter { !it.key.toLocalDate().isBefore(today) && it.key.hour in 6..19 }
                .groupBy { it.key.toLocalDate() }
                .filter { (_, hours) -> hours.size >= 4 } // 발표 시각에 따라 끝날은 몇 시간만 있을 수 있다
                .map { (date, hours) ->
                    fun pop(range: IntRange) = hours.filter { it.key.hour in range }.maxOfOrNull { it.value["POP"]?.toIntOrNull() ?: 0 } ?: 0
                    val skies = hours.mapNotNull { it.value["SKY"]?.toIntOrNull() }
                    val temps = hours.mapNotNull { it.value["TMP"]?.toDoubleOrNull()?.toInt() }
                    DayWeather(
                        date = date,
                        popAm = pop(6..11),
                        popPm = pop(12..19),
                        rain = hours.any { (it.value["PTY"]?.toIntOrNull() ?: 0) > 0 },
                        sky = when (skies.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key) {
                            1 -> Sky.CLEAR
                            3 -> Sky.CLOUDY
                            4 -> Sky.OVERCAST
                            else -> Sky.UNKNOWN
                        },
                        tempMin = temps.minOrNull(),
                        tempMax = temps.maxOrNull(),
                    )
                }.sortedBy { it.date }
    }
}

/** 에어코리아 대기오염정보(시도별 실시간 측정정보). */
class AirApi(private val http: Http, private val key: () -> String) {

    fun raw(): String = http.get(
        "https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getCtprvnRltmMesureDnsty" +
            "?serviceKey=${encodeServiceKey(key())}&returnType=json&numOfRows=100&pageNo=1&sidoName=${enc("제주")}&ver=1.0"
    )

    companion object {
        /** 서귀포시 쪽 측정소 이름(일부). 목록에 없는 측정소는 제주시로 본다. */
        private val seogwipoStations = setOf("동홍동", "성산읍", "대정읍", "남원읍", "표선면", "안덕면", "중문동", "대천동", "서귀포", "강정동", "색달동")

        fun parse(body: String, region: Region): Air? {
            val items = portalItems(body)
            data class Row(val station: String, val pm10: Int?, val pm25: Int?, val time: String)
            val rows = (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                Row(
                    station = o.optString("stationName"),
                    pm10 = o.optString("pm10Value").toIntOrNull(),
                    pm25 = o.optString("pm25Value").toIntOrNull(),
                    time = o.optString("dataTime"),
                )
            }.filter { it.pm10 != null || it.pm25 != null }
            if (rows.isEmpty()) return null
            val mine = rows.filter { (it.station in seogwipoStations) == (region == Region.SEOGWIPO) }.ifEmpty { rows }
            val pm10 = mine.mapNotNull { it.pm10 }.takeIf { it.isNotEmpty() }?.average()?.toInt()
            val pm25 = mine.mapNotNull { it.pm25 }.takeIf { it.isNotEmpty() }?.average()?.toInt()
            return Air(region, pm10, pm25, grade(pm10, pm25), mine.maxOf { it.time })
        }

        /** 환경부 예보 등급 기준. 둘 중 나쁜 쪽을 따른다. */
        fun grade(pm10: Int?, pm25: Int?): Int {
            val g10 = pm10?.let { when { it <= 30 -> 1; it <= 80 -> 2; it <= 150 -> 3; else -> 4 } } ?: 0
            val g25 = pm25?.let { when { it <= 15 -> 1; it <= 35 -> 2; it <= 75 -> 3; else -> 4 } } ?: 0
            return maxOf(g10, g25)
        }
    }
}
