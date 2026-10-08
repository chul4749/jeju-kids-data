package com.moon.jejukids.data

import org.json.JSONObject
import java.net.URLEncoder

/**
 * 좌표가 없는 행사(2026-10-07 실측 197건 중 166건)에 위치를 붙인다 — 카카오 주소·장소 검색.
 * 거리 표시, 주말 동선 지도, "가까운 행사" 점수가 모두 좌표에 기대기 때문.
 * 검색어 후보를 차례로 시도한다: 도로명 주소 → "시 + 장소 이름" → 장소 이름만.
 */
class GeoApi(private val http: Http, private val key: () -> String) {

    private fun get(path: String, q: String) = http.get(
        "https://dapi.kakao.com/v2/local/search/$path.json?query=${URLEncoder.encode(q, "UTF-8")}&size=3",
        mapOf("Authorization" to "KakaoAK ${key().trim()}"),
    )

    /** 후보를 차례로 찾아 처음 좌표가 나온 응답을 돌려준다(끝까지 없으면 마지막 응답). */
    fun raw(queries: List<GeoQuery>): String {
        var last = JSONObject().put("body", JSONObject())
        for (q in queries) {
            last = JSONObject().put("kind", q.kind).put("text", q.text).put("body", JSONObject(get(q.kind, q.text)))
            if (parse(last.toString()) != null) break
        }
        return last.toString()
    }

    companion object {
        private val roadAddr = Regex("[가-힣0-9]+(로|길)\\s*\\d+(-\\d+)?")
        /** 장소 이름 뒤 꼬리: 쉼표 뒤, 숫자로 시작하는 말(층·전시실 번호), 전시실·강의실 등, "및/일원/일대/출구". */
        private val tail = Regex(
            "\\s*[,，].*$|\\s+(지하\\s*)?\\d.*$|\\s*(제?\\d*\\s*(전시실|강의실|프로그램실|영상실|상설전시실|기획전시실|세미나실|소극장|대극장|공연장|강당|로비|본원)).*$" +
                "|\\s*(및|일원|일대|출구|내\\s).*$",
        )
        private val paren = Regex("[(（][^)）]*[)）]")
        private val useless = Regex("^(\\d+층|제주특별자치도|제주시|서귀포시|전시실|대극장|소극장|로비)$")

        /** 행사 → 검색어 후보(앞이 우선). 쓸 만한 정보가 없으면 빈 목록. */
        fun queriesFor(f: Festival): List<GeoQuery> {
            val city = when {
                f.addr.contains("서귀포") -> "서귀포시"
                f.addr.contains("제주시") -> "제주시"
                else -> ""
            }
            val out = mutableListOf<GeoQuery>()
            // 장소 칸 괄호 속 주소("이디홀 (제주시 도공로 54 지하 1층)").
            f.venue?.let { v -> paren.find(v)?.value }?.let { p -> roadAddr.find(p) }?.let { out += GeoQuery("address", "제주 $city ${it.value}".replace("  ", " ")) }
            // 주소 칸의 도로명 주소.
            val addr = f.addr.replace(paren, "").trim()
            if (addr.startsWith("제주")) roadAddr.find(addr)?.let { out += GeoQuery("address", addr.substring(0, it.range.last + 1)) }
            // 장소 이름.
            val rawName = f.venue?.replace(paren, "")?.trim()
                ?: addr.removePrefix("제주특별자치도").trim().removePrefix("제주시").removePrefix("서귀포시").trim()
            val name = rawName.replace(tail, "").trim()
            if (name.length >= 3 && !useless.matches(name) && roadAddr.find(name) == null) {
                if (city.isNotEmpty()) out += GeoQuery("keyword", "$city $name")
                out += GeoQuery("keyword", "제주 $name")
            }
            return out.distinct()
        }

        /** 응답 → 제주 안의 첫 좌표. */
        fun parse(body: String): Pair<Double, Double>? {
            val root = JSONObject(body)
            val docs = root.optJSONObject("body")?.optJSONArray("documents") ?: return null
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                val addr = d.optString("road_address_name").ifBlank { d.optString("address_name") }
                val lat = d.optString("y").toDoubleOrNull() ?: continue
                val lng = d.optString("x").toDoubleOrNull() ?: continue
                if (lat in 33.0..33.7 && lng in 126.1..127.0 && (addr.isEmpty() || addr.contains("제주"))) return lat to lng
            }
            return null
        }
    }
}

data class GeoQuery(val kind: String, val text: String)
