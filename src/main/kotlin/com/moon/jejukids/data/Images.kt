package com.moon.jejukids.data

import org.json.JSONObject
import java.net.URLEncoder

/** 검색으로 찾은 사진: 목록용 작은 썸네일 + 상세용 원본. */
data class FoundImage(val thumb: String, val photo: String?)

/**
 * 사진을 찾을 대상. [titleKey]가 있으면(행사) 제목에 그 말이 들어간 블로그 글의 사진만 쓴다.
 * 없으면(장소) 이미지 검색 결과를 모양 기준으로 거른다.
 */
data class ImageTarget(val id: String, val queries: List<String>, val titleKey: String? = null)

/**
 * 사진이 없는 행사·장소에 다음 검색 사진을 붙인다(카카오 키 사용, 30일 캐시).
 * 2026-10-07 실데이터 점검: 이미지 검색 1번째 결과를 그대로 쓰면 기관 인증 현판·지도 캡처·공지 글자·
 * 엉뚱한 인물 사진이 많았다 → 행사는 "제목에 행사 이름이 들어간 블로그 글"의 사진만, 장소는 모양으로 거르고
 * 키즈카페는 "놀이시설"을 붙여 찾는다(메뉴판·전단 대신 실내 사진이 나왔다). 못 찾으면 아이콘을 보여준다.
 */
class ImageSearchApi(private val http: Http, private val key: () -> String) {

    private fun kakao(path: String, query: String, size: Int) = http.get(
        "https://dapi.kakao.com/v2/search/$path?query=${URLEncoder.encode(query, "UTF-8")}&size=$size&sort=accuracy",
        mapOf("Authorization" to "KakaoAK ${key().trim()}"),
    )

    fun raw(query: String): String = kakao("image", query, 10)

    /** 행사용: 이미지 검색 + 블로그 검색을 묶는다 {"key","img","blog"}. */
    fun eventRaw(query: String, titleKey: String): String = JSONObject()
        .put("key", titleKey)
        .put("img", JSONObject(kakao("image", query, 15)))
        .put("blog", JSONObject(kakao("blog", query, 15)))
        .toString()

    companion object {
        /** 너무 작거나, 가로로 아주 긴(배너·지도 캡처) / 세로로 아주 긴(휴대폰 화면 캡처) 이미지는 뺀다. */
        fun usable(d: JSONObject): Boolean {
            val w = d.optInt("width"); val h = d.optInt("height")
            if (w < 300 || h < 200) return false
            val ratio = w.toDouble() / h
            return ratio in 0.5..2.0
        }

        private fun https(u: String?) = u?.takeIf { it.isNotBlank() }?.replaceFirst("http://", "https://")

        /** 캐시된 응답 하나를 읽는다(행사 묶음이든 이미지 검색이든). */
        fun parseAny(body: String): FoundImage? = if (JSONObject(body).has("blog")) parseEvent(body) else parse(body)

        /** 장소용: 모양이 괜찮은 첫 이미지. */
        fun parse(body: String): FoundImage? {
            val docs = JSONObject(body).optJSONArray("documents") ?: return null
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                if (!usable(d)) continue
                val thumb = d.optString("thumbnail_url").takeIf { it.isNotBlank() } ?: continue
                return FoundImage(thumb, https(d.optString("image_url")))
            }
            return null
        }

        private fun cleanTitle(s: String) = s.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), "")
        private fun normUrl(u: String) = u.replace(Regex("^https?://(m\\.)?"), "").trimEnd('/')

        /**
         * 행사용: 제목에 행사 이름(key)이 들어간 블로그 글만 믿는다.
         * 그 글에 실린 원본 크기 이미지가 이미지 검색에 있으면 그것을, 없으면 그 글의 작은 대표 사진을 쓴다.
         */
        fun parseEvent(body: String): FoundImage? {
            val root = JSONObject(body)
            val key = root.optString("key").takeIf { it.length >= 3 } ?: return null
            val posts = root.optJSONObject("blog")?.optJSONArray("documents") ?: return null
            val good = (0 until posts.length()).map { posts.getJSONObject(it) }.filter { cleanTitle(it.optString("title")).contains(key) }
            if (good.isEmpty()) return null
            val urls = good.map { normUrl(it.optString("url")) }.toSet()
            val imgs = root.optJSONObject("img")?.optJSONArray("documents")
            if (imgs != null) for (i in 0 until imgs.length()) {
                val d = imgs.getJSONObject(i)
                if (usable(d) && normUrl(d.optString("doc_url")) in urls) {
                    val thumb = d.optString("thumbnail_url").takeIf { it.isNotBlank() } ?: continue
                    return FoundImage(thumb, https(d.optString("image_url")))
                }
            }
            return good.firstNotNullOfOrNull { p -> https(p.optString("thumbnail"))?.let { FoundImage(it, null) } }
        }

        private val bracketPrefix = Regex("^\\s*[\\[(（][^\\])）]*[\\])）]\\s*")
        private val anyBracket = Regex("[\\[(（<《〈「『][^\\])）>》〉」』]*[\\])）>》〉」』]")

        /**
         * 블로그 글 제목에 있어야 할 행사 이름 조각(띄어쓰기 없이 앞 6자).
         * 괄호 속·연도·회차·지역 이름을 빼고 남은 말로 만든다. "[꿈다락 문화예술교육] 우리 동네 그림책" → "우리동네그림".
         */
        fun titleKey(title: String): String? =
            // 괄호 밖이 너무 짧으면(「제주 문섬 바다를 찾아온 손님」특별전) 괄호 속까지 쓴다.
            keyOf(title.replace(anyBracket, " "))?.takeIf { it.length >= 4 }
                ?: keyOf(title.replace(Regex("[\\[\\]()（）<>《》〈〉「」『』]"), " "))

        private fun keyOf(t: String): String? {
            val words = t.replace(Regex("20\\d\\d년?|제\\s*\\d+\\s*회|제주|서귀포"), " ")
                .replace(Regex("[^\\p{L}\\p{N}]"), " ")
                .split(Regex("\\s+")).filter { it.isNotEmpty() }
            return words.joinToString("").take(6).takeIf { it.length >= 3 }
        }

        /** 행사 사진 검색어: 제목(앞 [머리말]·연도 제거) + 제주. 머리말을 빼면 남는 게 없으면 머리말 속 말을 쓴다. */
        fun eventQuery(f: Festival): String {
            val t = f.title.replace(bracketPrefix, "").replace(Regex("20\\d\\d년?"), "").trim()
                .ifBlank { f.title.replace(Regex("[\\[\\]()（）]"), " ").replace(Regex("\\s+"), " ").trim() }
            return if (t.contains("제주") || t.contains("서귀포")) t else "제주 $t"
        }

        /** 첫 검색어로 못 찾았을 때: 제목 앞부분(기호 앞까지). ("한글날 기념 - 대한민국 …"는 결과가 없었다) */
        fun fallbackQueries(f: Festival): List<String> {
            val short = f.title.replace(bracketPrefix, "").split(Regex("[-–:<〈《「『(]")).first()
                .replace(Regex("20\\d\\d년?"), "").trim()
            return listOfNotNull(short.takeIf { it.length >= 3 }?.let { "제주 $it" })
        }

        fun eventTarget(f: Festival): ImageTarget? {
            val key = titleKey(f.title) ?: return null
            return ImageTarget(f.id, (listOf(eventQuery(f)) + fallbackQueries(f)).distinct(), key)
        }

        /** 장소: 키즈카페는 "놀이시설"을 붙여(메뉴판·가격표 대신 실내 사진), 안 나오면 이름만. */
        fun placeTarget(p: Place): ImageTarget {
            val q = BlogSignalApi.queryName(p.title)
            val qs = if (PlaceTag.KIDSCAFE in p.tags) listOf("$q 놀이시설", q) else listOf(q)
            return ImageTarget(p.id, qs)
        }
    }
}
