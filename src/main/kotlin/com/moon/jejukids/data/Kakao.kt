package com.moon.jejukids.data

import com.moon.jejukids.logic.KidFilter
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 카카오 로컬 키워드 검색으로 제주 키즈카페·실내놀이터를 모은다.
 * 한 번 검색에 최대 45곳(15개 × 3쪽)만 주므로 제주를 8칸으로 나눠 칸마다 검색한다.
 */
class KakaoApi(private val http: Http, private val key: () -> String) {

    /** 칸·검색어·쪽을 돌며 받은 장소를 하나의 JSON({"documents":[...]})으로 합친다. 이 문자열을 캐시한다. */
    fun kidsRaw(): String {
        val all = JSONArray()
        val seen = HashSet<String>()
        for (rect in cells()) for (query in QUERIES) {
            for (page in 1..3) {
                val body = http.get(
                    "https://dapi.kakao.com/v2/local/search/keyword.json?query=${URLEncoder.encode(query, "UTF-8")}" +
                        "&rect=$rect&size=15&page=$page",
                    mapOf("Authorization" to "KakaoAK ${key().trim()}"),
                )
                val root = JSONObject(body)
                val docs = root.optJSONArray("documents") ?: JSONArray()
                for (i in 0 until docs.length()) {
                    val d = docs.getJSONObject(i)
                    if (seen.add(d.optString("id"))) all.put(d)
                }
                if (root.optJSONObject("meta")?.optBoolean("is_end", true) != false) break
            }
        }
        return JSONObject().put("documents", all).toString()
    }

    /**
     * 주말 코스 점심·카페 후보: 한 지점 반경 [radiusM] 안의 음식점(FD6)·카페(CE7), 정확도 순 최대 45곳.
     * 카카오는 별점·후기를 주지 않으므로 아이 친화도는 블로그 후기로 따로 본다.
     */
    fun categoryRaw(code: String, lat: Double, lng: Double, radiusM: Int = 3000): String {
        val all = JSONArray()
        for (page in 1..3) {
            val body = http.get(
                "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=$code" +
                    "&x=${"%.5f".format(java.util.Locale.US, lng)}&y=${"%.5f".format(java.util.Locale.US, lat)}" +
                    "&radius=$radiusM&sort=accuracy&size=15&page=$page",
                mapOf("Authorization" to "KakaoAK ${key().trim()}"),
            )
            val root = JSONObject(body)
            val docs = root.optJSONArray("documents") ?: JSONArray()
            for (i in 0 until docs.length()) all.put(docs.get(i))
            if (root.optJSONObject("meta")?.optBoolean("is_end", true) != false) break
        }
        return JSONObject().put("documents", all).toString()
    }

    companion object {
        val QUERIES = listOf("키즈카페", "실내놀이터")

        /** 아이와 가기 어려운 곳: 술집·주점, 이름의 노키즈, 스터디·무인 카페. */
        private val notForKids = Regex("술집|주점|호프|포차|와인|칵테일|유흥|단란|노키즈|노 키즈|스터디카페|무인|BAR|Bar|펍|오마카세|이자카야|파인다이닝|바 *$")
        private val fastFood = Regex("편의점|패스트푸드|도시락")

        fun parseFood(body: String, tag: PlaceTag): List<Place> {
            val docs = JSONObject(body).optJSONArray("documents") ?: return emptyList()
            return (0 until docs.length()).mapNotNull { i ->
                val d = docs.getJSONObject(i)
                val name = d.optString("place_name").trim()
                val category = d.optString("category_name")
                val addr = d.optString("road_address_name").ifBlank { d.optString("address_name") }
                if (name.isEmpty() || !addr.contains("제주")) return@mapNotNull null
                if (notForKids.containsMatchIn(category) || notForKids.containsMatchIn(name) || fastFood.containsMatchIn(category)) return@mapNotNull null
                // 키즈카페는 따로 모아 코스 장소로 쓰므로 카페 칸에서는 뺀다.
                if (tag == PlaceTag.CAFE && category.contains("키즈카페")) return@mapNotNull null
                // 점심 칸에 빵집·간식집이 뽑혔다(2026-10-07) → 끼니 되는 곳만.
                if (tag == PlaceTag.FOOD && Regex("제과|베이커리|간식|디저트|떡|아이스크림|도넛|카페").containsMatchIn(category)) return@mapNotNull null
                Place(
                    id = "f" + d.optString("id"),
                    typeId = KidFilter.TYPE_KAKAO,
                    title = name,
                    addr = addr,
                    lat = d.optString("y").toDoubleOrNull(),
                    lng = d.optString("x").toDoubleOrNull(),
                    image = null,
                    tel = d.optString("phone").takeIf { it.isNotBlank() },
                    setting = Setting.INDOOR,
                    tags = setOf(tag),
                    kidScore = 0,
                    link = d.optString("place_url").takeIf { it.isNotBlank() },
                    category = category.substringAfterLast(">").trim().takeIf { it.isNotEmpty() },
                )
            }.distinctBy { it.id }
        }

        /** 제주 본섬(+우도) 범위를 가로 4 × 세로 2 칸으로 나눈 "왼쪽X,아래Y,오른쪽X,위Y" 목록. */
        fun cells(): List<String> {
            val (west, east, south, north) = listOf(126.14, 126.98, 33.18, 33.58)
            val cols = 4
            val rows = 2
            return (0 until cols).flatMap { c ->
                (0 until rows).map { r ->
                    val x1 = west + (east - west) * c / cols
                    val x2 = west + (east - west) * (c + 1) / cols
                    val y1 = south + (north - south) * r / rows
                    val y2 = south + (north - south) * (r + 1) / rows
                    "%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.US, x1, y1, x2, y2)
                }
            }
        }

        private val kidsCategory = listOf("키즈카페", "실내놀이터", "놀이시설", "놀이방", "플레이타임")
        /** 이름에 '키즈'가 있어도 주차장·교통시설 같은 부속 장소는 뺀다(실제로 "OO키즈카페 주차장"이 잡혔다). */
        private val notPlaces = listOf("교통,수송", "주차장")
        private val kidsName = listOf("키즈", "놀이터", "플레이", "kids", "KIDS", "Kids")

        fun parse(body: String): List<Place> {
            val docs = JSONObject(body).optJSONArray("documents") ?: return emptyList()
            return (0 until docs.length()).mapNotNull { i ->
                val d = docs.getJSONObject(i)
                val name = d.optString("place_name").trim()
                val category = d.optString("category_name")
                val addr = d.optString("road_address_name").ifBlank { d.optString("address_name") }
                if (name.isEmpty() || !addr.contains("제주")) return@mapNotNull null
                val looksKids = kidsCategory.any { category.contains(it) } || kidsName.any { name.contains(it) }
                if (!looksKids || KidFilter.isExcluded(name)) return@mapNotNull null
                if (notPlaces.any { category.contains(it) || name.endsWith(it) }) return@mapNotNull null
                Place(
                    id = "k" + d.optString("id"),
                    typeId = KidFilter.TYPE_KAKAO,
                    title = name,
                    addr = addr,
                    lat = d.optString("y").toDoubleOrNull(),
                    lng = d.optString("x").toDoubleOrNull(),
                    image = null,
                    tel = d.optString("phone").takeIf { it.isNotBlank() },
                    setting = Setting.INDOOR,
                    tags = setOf(PlaceTag.KIDSCAFE),
                    // 키즈카페가 실내 추천을 독차지하지 않도록 박물관·체험관과 비슷한 점수를 준다.
                    kidScore = 4,
                    link = d.optString("place_url").takeIf { it.isNotBlank() },
                )
            }
        }
    }
}
