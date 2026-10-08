package com.moon.jejukids.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * KOPIS(공연예술통합전산망, 예술경영지원센터) 공식 오픈API — 제주 공연 목록.
 * 아이 공연(겨울왕국·백설공주·호두까기인형 등)은 원래 민간 사이트(플레이제주)에만 있었는데,
 * 2026-10-08 확인 결과 KOPIS에 모두 있고 관람 연령·가격·회차·예매 링크·공연장 좌표(놀이방·수유실 여부)까지 준다.
 * 목록 1번 + 공연마다 상세 1번 + 공연장마다 1번(캐시). 응답은 XML.
 */
class KopisApi(private val http: Http, private val key: () -> String) {

    private fun get(path: String, query: String = "") =
        http.get("http://kopis.or.kr/openApi/restful/$path?service=${key().trim()}$query", mapOf("User-Agent" to "Mozilla/5.0 JejuKids"))

    private val ymd = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** 오늘부터 120일 안에 하는 제주 공연 전체(아이 공연 표시는 상세에서). */
    fun listRaw(today: LocalDate): String {
        val sb = StringBuilder()
        for (page in 1..3) {
            if (page > 1) Thread.sleep(800) // 몰아서 부르면 막힌다
            val body = get("pblprfr", "&stdate=${today.format(ymd)}&eddate=${today.plusDays(120).format(ymd)}&cpage=$page&rows=100&signgucode=50")
            sb.append(body)
            if (Regex("<db>").findAll(body).count() < 100) break
        }
        return sb.toString()
    }

    fun detailRaw(id: String): String = get("pblprfr/$id")
    fun placeRaw(id: String): String = get("prfplc/$id")

    companion object {
        private fun tag(xml: String, t: String): String? =
            Regex("<$t>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
                ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.trim()?.takeIf { it.isNotEmpty() }

        private fun dbs(xml: String) = Regex("<db>(.*?)</db>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }.toList()

        private val dot = DateTimeFormatter.ofPattern("yyyy.MM.dd")

        data class Item(val id: String, val title: String, val start: LocalDate, val end: LocalDate, val venue: String?, val poster: String?, val genre: String?)

        /** "겨울왕국 [제주]" → "겨울왕국". 지역 꼬리표는 다른 출처와 묶을 때 방해가 된다. */
        fun cleanTitle(t: String) = t.replace(Regex("\\s*\\[제주[^]]*]\\s*$"), "").trim()

        fun parseList(xml: String): List<Item> = dbs(xml).mapNotNull { d ->
            Item(
                id = tag(d, "mt20id") ?: return@mapNotNull null,
                title = cleanTitle(tag(d, "prfnm") ?: return@mapNotNull null),
                start = tag(d, "prfpdfrom")?.let { runCatching { LocalDate.parse(it, dot) }.getOrNull() } ?: return@mapNotNull null,
                end = tag(d, "prfpdto")?.let { runCatching { LocalDate.parse(it, dot) }.getOrNull() } ?: return@mapNotNull null,
                venue = tag(d, "fcltynm"),
                poster = tag(d, "poster"), // https로 바꾸면 http로 되돌려 보낸다(301) → 그대로 둔다
                genre = tag(d, "genrenm"),
            )
        }.distinctBy { it.id }

        data class Detail(
            val child: Boolean, val age: String?, val price: String?, val time: String?, val runtime: String?,
            val placeId: String?, val ticket: String?,
        )

        fun parseDetail(xml: String): Detail? {
            val d = dbs(xml).firstOrNull() ?: return null
            return Detail(
                child = tag(d, "child") == "Y",
                age = tag(d, "prfage"),
                price = tag(d, "pcseguidance"),
                time = tag(d, "dtguidance"),
                runtime = tag(d, "prfruntime"),
                placeId = tag(d, "mt10id"),
                ticket = tag(d, "relateurl"),
            )
        }

        data class Place(val name: String?, val addr: String?, val lat: Double?, val lng: Double?, val nursery: Boolean, val playroom: Boolean)

        fun parsePlace(xml: String): Place? {
            val d = dbs(xml).firstOrNull() ?: return null
            return Place(
                name = tag(d, "fcltynm"), addr = tag(d, "adres"),
                lat = tag(d, "la")?.toDoubleOrNull()?.takeIf { it in 33.0..34.0 },
                lng = tag(d, "lo")?.toDoubleOrNull()?.takeIf { it in 126.0..127.0 },
                nursery = tag(d, "suyu") == "Y", playroom = tag(d, "nolibang") == "Y",
            )
        }

        private val kidTitle = Regex("어린이|가족|키즈|아동|인형극|동화|겨울왕국|백설|호두까기|쥬쥬|뽀로로|타요|핑크퐁|공룡|마술")

        fun toFestival(i: Item, d: Detail?, p: Place?): Festival = Festival(
            id = "kp" + i.id,
            title = i.title,
            addr = p?.addr ?: "제주특별자치도",
            lat = p?.lat, lng = p?.lng,
            image = i.poster,
            tel = null,
            start = i.start, end = i.end,
            familyFriendly = d?.child == true || kidTitle.containsMatchIn(i.title),
            source = "KOPIS",
            link = d?.ticket ?: "https://www.kopis.or.kr/por/db/pblprfr/pblprfrView.do?menuId=MNU_00020&mt20Id=${i.id}",
            venue = i.venue,
            fee = d?.price,
            time = listOfNotNull(d?.time, d?.runtime?.let { "공연 $it" }).joinToString(" · ").ifEmpty { null },
            category = i.genre,
            targets = listOfNotNull(
                d?.age?.let { "관람 $it" },
                "수유실".takeIf { p?.nursery == true },
                "놀이방".takeIf { p?.playroom == true },
            ).joinToString(" · ").ifEmpty { null },
        )
    }
}
