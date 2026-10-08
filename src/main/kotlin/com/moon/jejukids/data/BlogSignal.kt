package com.moon.jejukids.data

import com.moon.jejukids.logic.AgeGroup
import com.moon.jejukids.logic.Insight
import org.json.JSONObject
import java.net.URLEncoder

/** 블로그 글 하나(노키즈존 언급 근거로 보여준다). */
data class BlogPost(val title: String, val link: String, val snippet: String)

/**
 * 블로그에서 모은 아이 관련 신호.
 * 블로그 언급을 센 것이지 사실 확인이 아니다 — 화면에서도 "언급"으로만 표시한다.
 */
data class KidSignal(
    /** 장소 이름 바로 곁에서 "노키즈존"을 부정 없이 언급한 글 수. */
    val noKidsMentions: Int,
    val noKidsPosts: List<BlogPost>,
    /** "제주 장소명 아이랑" 검색 결과 총 건수(대략적인 인기 지표). */
    val kidPostCount: Int,
    /** 아이랑 후기 50건에서 자주 나온 장점 키워드(많은 순). */
    val keywords: List<Pair<String, Int>> = emptyList(),
    /** 주의 키워드(웨이팅·사람 많음 등). */
    val cautions: List<Pair<String, Int>> = emptyList(),
    val ageCounts: Map<AgeGroup, Int> = emptyMap(),
    /** 후기에서 그대로 옮긴 대표 문장(snippet)과 출처 글. */
    val quotes: List<BlogPost> = emptyList(),
    /** 사진이 없는 장소용 이미지 검색 결과: 작은 썸네일(목록)·원본(상세). */
    val thumb: String? = null,
    val photo: String? = null,
    /** 제목에 이 장소 이름이 들어간 "아이랑" 글 수(최대 50건 중) — 실제로 아이와 다녀온 후기 수에 가깝다. */
    val ownPosts: Int = 0,
) {
    /** 글 1건은 근처 가게 이야기일 때가 많아(실데이터 확인) 2건 이상일 때만 경고한다. */
    val warn: Boolean get() = noKidsMentions >= 2
}

/**
 * 다음(카카오) 블로그 검색으로 노키즈존 언급·아이와 후기 수를 모은다. 카카오 REST 키를 같이 쓴다.
 * 네이버 블로그 글도 함께 검색된다.
 * (네이버 검색 API는 2026-07-31부터 개발자센터 신규 발급이 끝나 쓰지 않는다.)
 */
class BlogSignalApi(private val http: Http, private val key: () -> String) {

    private fun search(query: String, size: Int): String = http.get(
        "https://dapi.kakao.com/v2/search/blog?query=${URLEncoder.encode(query, "UTF-8")}&size=$size&sort=accuracy",
        mapOf("Authorization" to "KakaoAK ${key().trim()}"),
    )

    private fun searchImage(query: String): String = http.get(
        "https://dapi.kakao.com/v2/search/image?query=${URLEncoder.encode(query, "UTF-8")}&size=5&sort=accuracy",
        mapOf("Authorization" to "KakaoAK ${key().trim()}"),
    )

    /**
     * 응답들을 하나로 묶어 캐시한다: {"name", "no", "kid", "img"?}
     * 장소당 2~3번 호출: 노키즈존 언급, 아이랑 후기 50건, (사진이 없을 때만) 이미지 검색.
     */
    fun signalRaw(name: String, wantImage: Boolean): String {
        val q = queryName(name)
        val no = search("$q 노키즈존", 50)
        val kid = search("$q 아이랑", 50)
        val root = JSONObject().put("name", name).put("no", JSONObject(no)).put("kid", JSONObject(kid))
        if (wantImage) root.put("img", JSONObject(searchImage(q)))
        return root.toString()
    }

    companion object {
        /** "아쿠아플라넷 제주" → "아쿠아플라넷", "함덕해수욕장 (함덕 서우봉 해변)" → "함덕해수욕장". */
        fun coreName(name: String): String {
            val noParen = name.replace(Regex("\\(.*?\\)|\\[.*?]"), " ").trim()
            val words = noParen.split(Regex("\\s+")).filter { it.isNotBlank() && it != "제주" && it != "제주점" }
            // 지점명(OO점)은 떼되, 이름이 한 단어뿐이면 그대로 둔다.
            val trimmed = if (words.size > 1 && words.last().endsWith("점")) words.dropLast(1) else words
            return trimmed.joinToString(" ").ifBlank { name }
        }

        /**
         * 글에 이 장소 이야기인지 확인할 때 쓰는 이름 조각(띄어쓰기 없음).
         * "우리끼리키즈카페 블럭마을"처럼 여러 단어면 앞 단어만 — 글에는 보통 브랜드 이름만 쓴다.
         */
        fun nameKey(core: String): String {
            val words = core.split(" ").filter { it.isNotBlank() }
            return (if (words.size >= 2 && words.first().length >= 4) words.first() else core).replace(" ", "")
        }

        /** 다른 지역 같은 이름의 지점이 섞이지 않게 "제주"를 붙인다. */
        fun queryName(name: String): String = coreName(name).let { if (it.contains("제주")) it else "제주 $it" }

        private fun clean(s: String) = s.replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'")
            // 해시태그(#제주노키즈 등)는 본문 내용이 아니라서 뺀다.
            .replace(Regex("#\\S+"), " ")

        private val noKids = Regex("노\\s*키즈")

        /** "노키즈존 아니에요", "노키즈존 X", "노키즈존이 아닌" 같은 부정 표현. 블로그 문장이 다양해 완벽하지 않다. */
        private val negated = Regex("노\\s*키즈\\s*(존)?\\s*(이|가|은|는)?\\s*(아니|아님|아닙|아닌|아냐|x|X|❌|해제|없|폐지)")

        fun mentionsNoKids(text: String): Boolean {
            val t = clean(text)
            val all = noKids.findAll(t).count()
            if (all == 0) return false
            return all > negated.findAll(t).count()
        }

        /** 블로그 요약문은 여러 문단을 "..."로 이어 붙이므로 문장·나열 단위로 자른다. */
        private val segmentSplit = Regex("""\.\.\.|[.!?\n\x{200B}|/·•✔☑]""")

        /** 이름과 "노키즈" 사이에 다른 가게가 끼면 그 가게 이야기다(예: "협재해수욕장 근처 카페 베릴 노키즈존"). */
        private val otherBusiness = Regex("카페|식당|맛집|호텔|수영장|리조트|베이커리|커피|레스토랑|펜션|숙소|오마카세|버거|국수|흑돼지|횟집")
        private val listOrNearby = Regex("[,&→>~+]|근처|주변|가까운|옆|앞")
        private val nearbyWords = Regex("근처|주변|가까운")

        /**
         * 같은 문장 안에서, 부정되지 않은 "노키즈"가 장소 이름과 [window]자 안에 붙어 있는지.
         * 사이에 다른 가게 이름이나 나열(쉼표·근처)이 끼어 있으면 세지 않는다. 띄어쓰기는 무시한다.
         * 2026-10-06 실제 블로그 39곳으로 확인: 넓게 보면(60자) 근처 카페 이야기가 대부분 잡혔다.
         */
        fun mentionsNear(text: String, name: String, window: Int = 30): Boolean {
            val key = name.replace(" ", "")
            if (key.isEmpty()) return false
            val ownBusiness = otherBusiness.containsMatchIn(key)
            for (segment in segmentSplit.split(clean(text))) {
                val t = segment.replace(" ", "")
                if (nearbyWords.containsMatchIn(t)) continue
                for (k in noKids.findAll(t)) {
                    if (negated.matchesAt(t, k.range.first)) continue
                    for (n in Regex(Regex.escape(key)).findAll(t)) {
                        if (kotlin.math.abs(k.range.first - n.range.first) > window) continue
                        val between = if (n.range.first < k.range.first) t.substring(n.range.last + 1, k.range.first)
                        else t.substring(k.range.last + 1, n.range.first)
                        // "아침미소목장 카페 2층은 노키즈존"처럼 이름 바로 뒤 "카페"는 그 장소 안의 카페다.
                        val rest = if (n.range.first < k.range.first) between.removePrefix("카페") else between
                        if (!ownBusiness && otherBusiness.containsMatchIn(rest)) continue
                        if (listOrNearby.containsMatchIn(between)) continue
                        return true
                    }
                }
            }
            return false
        }

        fun parse(body: String): KidSignal {
            val root = JSONObject(body)
            val name = coreName(root.optString("name"))
            val docs = root.optJSONObject("no")?.optJSONArray("documents")
            val posts = mutableListOf<BlogPost>()
            if (docs != null) for (i in 0 until docs.length()) {
                val o = docs.getJSONObject(i)
                val title = clean(o.optString("title")).trim()
                val desc = clean(o.optString("contents")).trim()
                val text = "$title $desc"
                // 제주 글이면서, 장소 이름 가까이에 노키즈 언급이 있는 글만.
                if (!text.contains("제주")) continue
                if (mentionsNear(text, name)) posts += BlogPost(title, o.optString("url"), desc)
            }
            val kidRoot = root.optJSONObject("kid")
            val kidTotal = kidRoot?.optJSONObject("meta")?.optInt("total_count", 0) ?: 0
            val kidDocs = kidRoot?.optJSONArray("documents")
            val kidPosts = (0 until (kidDocs?.length() ?: 0)).map { i ->
                val o = kidDocs!!.getJSONObject(i)
                BlogPost(clean(o.optString("title")).trim(), o.optString("url"), clean(o.optString("contents")).trim())
            }.filter { post ->
                // 제주 글이면서 이 장소 이름이 실제로 나오는 글만 — 작은 키즈카페는 검색 결과 대부분이 다른 곳 이야기였다(실데이터).
                val t = post.title + post.snippet
                t.contains("제주") && t.replace(" ", "").contains(nameKey(name))
            }
            // 후기 내용(키워드·연령·인용)은 제목에 이 장소 이름이 있는 글만 쓴다 — 본문에만 이름이 나오는 글은
            // "제주 아이랑 가볼만한 곳 10선" 같은 모음 글이라 다른 곳 이야기가 섞였다(2026-10-07 점검).
            val key = nameKey(name)
            val ownPosts = kidPosts.filter { it.title.replace(" ", "").contains(key) }
            // 이미지: 너무 작거나 모양이 이상한(배너·화면 캡처) 것은 건너뛰고 첫 번째 쓸 만한 것.
            val img = root.optJSONObject("img")?.optJSONArray("documents")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { ImageSearchApi.usable(it) }
            }
            return KidSignal(
                noKidsMentions = posts.size,
                noKidsPosts = posts.take(5),
                kidPostCount = kidTotal,
                // 글 하나에서 같은 말이 여러 번 나와도 1번으로 센다(한 글이 결과를 좌우하지 않게).
                keywords = Insight.postCounts(ownPosts, Insight.positives),
                cautions = Insight.postCounts(ownPosts, Insight.cautions),
                ageCounts = Insight.agePostCounts(ownPosts),
                quotes = Insight.quotes(ownPosts),
                ownPosts = ownPosts.size,
                thumb = img?.optString("thumbnail_url")?.takeIf { it.isNotBlank() },
                photo = img?.optString("image_url")?.takeIf { it.isNotBlank() }?.replaceFirst("http://", "https://"),
            )
        }
    }
}
