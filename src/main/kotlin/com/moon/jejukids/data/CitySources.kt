package com.moon.jejukids.data

import com.moon.jejukids.logic.KidFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/*
 * 시청이 직접 올리는 공식 행사 목록 두 곳. 동·읍 단위 축제(예: 고마로마문화축제)는 여기에만 올라온다.
 * 둘 다 API가 아니라 웹페이지(HTML)를 읽는다.
 */

private const val CITY_UA = "Mozilla/5.0 (Linux; Android 14) JejuKids"

private fun unescape(s: String) = s
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&#034;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&middot;", "·").replace("&nbsp;", " ")

private fun textOf(html: String) = unescape(html.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), ""))
    .lines().joinToString("\n") { it.trim() }.trim()

private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

/** "2026-10-09 ~ 2026-10-11" 또는 "2026-10-09" → (시작, 끝). */
internal fun parseRange(s: String): Pair<LocalDate, LocalDate>? {
    val dates = Regex("(\\d{4})[-.](\\d{1,2})[-.](\\d{1,2})").findAll(s).mapNotNull {
        runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull()
    }.toList()
    if (dates.isEmpty()) return null
    return dates.first() to (dates.getOrNull(1) ?: dates.first())
}

private val familyHint = Regex("어린이|아이|가족|키즈|유아|체험|동화|그림책|사생대회|놀이")
private val adultHint = Regex("성인|장년|시니어|어르신|19세|비어|맥주|와인|주류")

/** 제주시청 "문화행사 안내" 목록. 최근 등록순 10건씩. */
class JejusiEventApi(private val http: Http) {

    fun raw(): String {
        val pages = JSONArray()
        for (page in 1..4) {
            val html = http.get("https://www.jejusi.go.kr/field/culture/festival/list.do?currentPageNo=$page", mapOf("User-Agent" to CITY_UA))
            // 목록 부분만 남긴다(아래쪽 만족도 조사 폼 앞까지).
            val list = html.substringAfter("<ul class=\"event_list\">", "").substringBefore("<!--콘텐츠 관리")
            pages.put(list)
            // 등록순이라 오래된 쪽으로 갈수록 끝난 행사뿐이다 — 한 쪽 전체가 "종료"면 그만 넘긴다.
            val tags = Regex("<em class=\"tag[^\"]*\">([^<]+)</em>").findAll(list).map { it.groupValues[1].trim() }.toList()
            if (tags.isEmpty() || tags.all { it == "종료" }) break
        }
        return JSONObject().put("pages", pages).toString()
    }

    companion object {
        private val item = Regex("<li>\\s*<em class=\"tag[^\"]*\">([^<]*)</em>(.*?)</dl>", RegexOption.DOT_MATCHES_ALL)

        fun parse(body: String, today: LocalDate): List<Festival> {
            val pages = JSONObject(body).optJSONArray("pages") ?: return emptyList()
            val out = mutableListOf<Festival>()
            for (p in 0 until pages.length()) for (m in item.findAll(pages.optString(p))) {
                val status = m.groupValues[1].trim()
                val b = m.groupValues[2]
                val id = Regex("festival_id=(\\d+)").find(b)?.groupValues?.get(1) ?: continue
                val title = Regex("<dt[^>]*>(.*?)</dt>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let { oneLine(textOf(it)) } ?: continue
                val place = Regex("장소：</em><div>(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let { oneLine(textOf(it)) }
                val (start, end) = Regex("일정：</em><div>(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let(::parseRange) ?: continue
                if (end.isBefore(today) || status == "종료") continue
                val text = Regex("<div class=\"text\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let(::textOf).orEmpty()
                val img = Regex("<img src=\"([^\"]+)\"").find(b)?.groupValues?.get(1)?.let { if (it.startsWith("/")) "https://www.jejusi.go.kr$it" else it }
                val city = if (place?.contains("서귀포") == true) "서귀포시" else "제주시"
                out += Festival(
                    id = "js$id",
                    title = title,
                    addr = "제주특별자치도 $city ${place.orEmpty()}".trim(),
                    lat = null, lng = null,
                    image = img?.takeIf { !it.contains("no_image") },
                    tel = null,
                    start = start, end = end,
                    familyFriendly = familyHint.containsMatchIn(title + text) && !adultHint.containsMatchIn(title),
                    source = "제주시",
                    link = "https://www.jejusi.go.kr/field/culture/festival/list.do?mode=detail&festival_id=$id",
                    venue = place,
                    category = if (title.contains("축제") || title.contains("페스티벌") || title.contains("문화제")) "축제" else "행사",
                    // 개요에서 "프로그램:" 줄이 있으면 상세에 보여준다.
                    targets = Regex("프로그램\\s*[:：]\\s*(.+)").find(text)?.groupValues?.get(1)?.trim()?.let { "프로그램: $it" },
                )
            }
            return out.distinctBy { it.id }
        }
    }
}

/** 서귀포시청 "문화행사일정" 월별 달력. 항목마다 data-* 속성에 제목·기간·장소·좌표·연령·포스터가 다 들어 있다. */
class SeogwipoEventApi(private val http: Http) {

    fun raw(today: LocalDate): String {
        val months = JSONArray()
        for (d in listOf(today, today.plusMonths(1))) {
            val html = http.get(
                "https://www.seogwipo.go.kr/tourismculture/culture/schedule1.htm?year=${d.year}&month=${d.monthValue}",
                mapOf("User-Agent" to CITY_UA),
            )
            // 항목 블록만 남겨 캐시를 작게 한다.
            months.put(block.findAll(html).joinToString("\n") { it.value })
        }
        return JSONObject().put("months", months).toString()
    }

    companion object {
        private val block = Regex("<div class=\"list schedule-item\"(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
        private val attr = Regex("data-([a-z0-9-]+)=\"(.*?)\"", RegexOption.DOT_MATCHES_ALL)

        fun parse(body: String, today: LocalDate): List<Festival> {
            val months = JSONObject(body).optJSONArray("months") ?: return emptyList()
            val out = mutableListOf<Festival>()
            for (i in 0 until months.length()) for (m in block.findAll(months.optString(i))) {
                val a = attr.findAll(m.groupValues[1]).associate { it.groupValues[1] to oneLine(unescape(it.groupValues[2])) }
                val title = a["title"]?.takeIf { it.isNotBlank() } ?: continue
                val (start, end) = parseRange(a["date-range"].orEmpty().ifBlank { a["period"].orEmpty() }) ?: continue
                if (end.isBefore(today)) continue
                val type = a["type"].orEmpty()
                val image = a["image"]?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("/")) "https://www.seogwipo.go.kr$it" else it }
                val content = a["content"].orEmpty()
                out += Festival(
                    // 같은 행사가 주마다 따로 올라오므로 seq 에 시작일을 붙여 구분한다(합치기는 저장소에서).
                    id = "sg" + (a["seq"]?.takeIf { it.isNotBlank() } ?: title.hashCode().toString()) + "_" + start,
                    title = title,
                    addr = a["address"]?.takeIf { it.isNotBlank() }?.let { if (it.contains("제주")) it else "제주특별자치도 서귀포시 $it" }
                        ?: "제주특별자치도 서귀포시 ${a["place"].orEmpty()}",
                    lat = a["lat"]?.toDoubleOrNull()?.takeIf { it in 33.0..34.0 },
                    lng = a["lng"]?.toDoubleOrNull()?.takeIf { it in 126.0..127.0 },
                    image = image,
                    tel = a["contact"]?.takeIf { it.isNotBlank() },
                    start = start, end = end,
                    familyFriendly = (familyHint.containsMatchIn(title + content) || a["age"]?.contains("전체") == true) && !adultHint.containsMatchIn(title),
                    source = "서귀포시",
                    link = a["homepage"]?.takeIf { it.startsWith("http") } ?: "https://www.seogwipo.go.kr/tourismculture/culture/schedule1.htm",
                    venue = a["place"]?.takeIf { it.isNotBlank() },
                    time = a["time"]?.takeIf { it.isNotBlank() },
                    category = type.ifBlank { null },
                    targets = a["age"]?.takeIf { it.isNotBlank() }?.let { "관람 연령: $it" },
                )
            }
            return out.distinctBy { it.id }
        }
    }
}
