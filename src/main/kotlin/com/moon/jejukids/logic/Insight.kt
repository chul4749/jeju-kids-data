package com.moon.jejukids.logic

import com.moon.jejukids.data.BlogPost
import com.moon.jejukids.data.Festival
import com.moon.jejukids.data.KidSignal
import com.moon.jejukids.data.Place
import com.moon.jejukids.data.PlaceTag
import com.moon.jejukids.data.Setting

/** 연령대. 데이터가 직접 알려주는 경우는 드물어 이름·후기에서 추정한다. */
enum class AgeGroup(val label: String, val short: String) {
    INFANT("영아 0~2세", "영아"),
    PRESCHOOL("유아 3~6세", "유아"),
    SCHOOL("초등", "초등"),
}

fun Set<AgeGroup>.label(): String? {
    if (isEmpty()) return null
    val sorted = sortedBy { it.ordinal }
    return if (sorted.size == 1) sorted.first().short else "${sorted.first().short}~${sorted.last().short}"
}

/**
 * 블로그 요약문(아이랑 후기 50건)에서 AI 없이 뽑는 정보:
 * 자주 나온 말(장점·주의), 연령 언급, 대표 문장, 그리고 규칙으로 조립한 한 줄 설명.
 * 2026-10-06 실데이터(아침미소목장·에코랜드·넥슨컴퓨터박물관·노리파크)로 사전을 맞췄다.
 */
object Insight {

    /** 장점 쪽 키워드 → 정규식. 화면에 이 이름 그대로 나간다. */
    val positives = linkedMapOf(
        "우유주기" to Regex("우유\\s*주"),
        "먹이주기" to Regex("먹이\\s*주"),
        "모래놀이" to Regex("모래\\s*놀이"),
        "물놀이" to Regex("물\\s*놀이"),
        "수유실" to Regex("수유실"),
        "기저귀 갈이대" to Regex("기저귀"),
        "유모차 OK" to Regex("유모차\\s*(로|대여|가능|이동|끌)"),
        "그늘" to Regex("그늘"),
        // 식당·카페용.
        "놀이방" to Regex("놀이방|키즈\\s*(존|룸|공간|놀이)"),
        "유아의자" to Regex("유아\\s*의자|아기\\s*체어"),
        "좌식" to Regex("좌식"),
        "주차 편함" to Regex("주차\\s*(편|넓|여유|무료|가능)"),
        "비 오는 날" to Regex("비\\s*(오는|올\\s*때|가\\s*와)"),
        "넓어요" to Regex("넓(어|고|은|직)"),
        "깨끗해요" to Regex("깨끗"),
        "아기의자" to Regex("아기\\s*의자"),
        "무료" to Regex("무료\\s*(입장|체험|관람)"),
    )

    /** 주의 쪽 키워드. */
    val cautions = linkedMapOf(
        "웨이팅" to Regex("웨이팅|대기\\s*(가|시간|줄)"),
        "사람 많음" to Regex("사람\\s*(이\\s*)?(정말|너무|엄청|진짜)?\\s*많"),
        "비싸요" to Regex("비싸|비쌈|가격\\s*대비"),
        // "더운 날 가기 좋은 실내"는 칭찬이라 빼고, 실제로 더웠다는 말만.
        "더워요" to Regex("더웠|덥더라|더워서\\s*(힘들|땀)|(안|실내|내부)(이|가|는)?\\s*(좀|조금|많이|너무)?\\s*더워"),
        "좁아요" to Regex("좁(아|고|은)"),
        "벌레" to Regex("벌레|모기"),
    )

    private val ageRegex = mapOf(
        AgeGroup.INFANT to Regex("\\d{1,2}\\s*개월|돌\\s*(아기|아가|지난|쟁이|전후)|아기띠|두\\s*돌|신생아|백일"),
        AgeGroup.PRESCHOOL to Regex("(?<![0-9])[3-7]\\s*살|(네|다섯|여섯|일곱)\\s*살|유치원|어린이집|미취학"),
        AgeGroup.SCHOOL to Regex("초등|(?<![0-9])(8|9|10|11|12)\\s*살|(여덟|아홉|열)\\s*살"),
    )

    private val adWords = Regex("협찬|원고료|제공\\s*받|소정의|광고|체험단|서포터즈")
    private val sentenceSplit = Regex("""\.\.\.|[.!?\n\x{200B}]|\s{3,}""")

    fun counts(text: String, dict: Map<String, Regex>): List<Pair<String, Int>> =
        dict.map { (k, r) -> k to r.findAll(text).count() }.filter { it.second > 0 }.sortedByDescending { it.second }

    fun ageCounts(text: String): Map<AgeGroup, Int> = ageRegex.mapValues { (_, r) -> r.findAll(text).count() }

    /** 글 수로 센다: 그 말이 나온 글이 몇 개인지. */
    fun postCounts(posts: List<BlogPost>, dict: Map<String, Regex>): List<Pair<String, Int>> =
        dict.map { (k, r) -> k to posts.count { r.containsMatchIn(it.title + " " + it.snippet) } }
            .filter { it.second > 0 }.sortedByDescending { it.second }

    fun agePostCounts(posts: List<BlogPost>): Map<AgeGroup, Int> =
        ageRegex.mapValues { (_, r) -> posts.count { r.containsMatchIn(it.title + " " + it.snippet) } }

    /** 인용으로 쓰기 어려운 문장: 블로그 인사·자기소개, 가격·주차 나열(검색용 제목 문구), 할인 홍보. */
    private val notQuote = Regex(
        "안녕하세요|주인장|포스팅|블로그|이웃님|소개해\\s*(드릴|볼)|입장료\\s*주차|총\\s*정리|할인|쿠폰|예약\\s*방법" +
            // 운영시간·입장 규정 안내문은 후기가 아니다.
            "|\\d{1,2}:\\d{2}|필참|증빙|증명|서류|입장\\s*시",
    )

    /** 문장이 끝난 모양인지("…좋아요", "…높았다", "…있겠죠"). "…더욱 기분"처럼 잘린 것은 아니다. 끝의 이모지·기호는 무시. */
    private fun endsLikeSentence(s: String): Boolean {
        val t = s.replace(Regex("[^\\p{L}\\p{N}]+$"), "")
        return listOf("요", "다", "죠", "네", "음", "함", "임", "듯", "ㅎ", "ㅋ", "곳", "까").any { t.endsWith(it) }
    }

    /** 앞 문장에 이어지는 조각("에요~ 외관도…", "생각이 들었지만, 막상…"): 어미·연결어로 시작하거나 쉼표 두 개로 끝난다. */
    private val midSentence = Regex("^(에요|이에요|어요|아요|해요|예요|요[~ ]|죠|네요|구요|고\\s|며\\s|지만|는데|다가|생각이|했는데|해서|있으니|있어서|이라서|놓기가)|,,\\s*$")

    /**
     * 후기에서 연령대를 고른다: 언급이 2번 이상이고, 가장 많이 언급된 연령의 1/3 이상인 것.
     * 한두 번 지나가는 언급은 무시한다.
     */
    fun agesFromCounts(counts: Map<AgeGroup, Int>): Set<AgeGroup> {
        val max = counts.values.maxOrNull() ?: 0
        if (max < 2) return emptySet()
        return counts.filter { it.value >= 2 && it.value * 3 >= max }.keys
    }

    /**
     * 대표 문장: 아이 관련 표현이 많고, 광고 표시가 없고, 너무 짧거나 길지 않은 문장.
     * 문장을 그대로 인용하고 출처 글을 링크한다(요약·생성 아님).
     */
    fun quotes(docs: List<BlogPost>, max: Int = 2): List<BlogPost> {
        data class Cand(val text: String, val post: BlogPost, val score: Int)
        val cands = docs.flatMap { post ->
            if (adWords.containsMatchIn(post.title + post.snippet)) return@flatMap emptyList()
            // 블로그 요약문은 본문 중간에서 잘라 온다 → 첫 조각("에요~~~ 외관도…")과
            // 마침표 없이 끝나는 마지막 조각("…더욱 기분")은 문장 중간이라 쓰지 않는다.
            val parts = sentenceSplit.split(post.snippet).map { it.trim() }.dropWhile { it.isEmpty() }
            val complete = parts.indices.filter { i ->
                i > 0 && (i < parts.size - 1 || post.snippet.trimEnd().lastOrNull()?.let { it in ".!?" } == true)
            }.map { parts[it] }
            complete.filter { it.length in 18..90 && !notQuote.containsMatchIn(it) && !midSentence.containsMatchIn(it) && endsLikeSentence(it) }.map { s ->
                val score = positives.values.count { it.containsMatchIn(s) } * 2 +
                    ageRegex.values.count { it.containsMatchIn(s) } +
                    (if (Regex("좋(았|아|은)|추천|만족|재밌|즐거|신나").containsMatchIn(s)) 2 else 0) -
                    (if (Regex("[#@]|https?:|▶|☎|\\d{2,4}-\\d{3,4}-\\d{4}").containsMatchIn(s)) 5 else 0)
                Cand(s, post, score)
            }
        }
        return cands.filter { it.score >= 3 }.sortedByDescending { it.score }
            .distinctBy { it.post.link }.take(max).map { it.post.copy(snippet = it.text) }
    }

    /**
     * 아이 추천(지도·장소 목록 기본값): 키즈카페, 아이 관련 단어가 뚜렷한 곳(관련도 3 이상),
     * 또는 아이와 다녀온 블로그 글이 많은 곳. 일반 명소(오름·해안도로·사찰 등)는 "전부"에서만 보인다.
     */
    fun kidFriendly(p: Place, signal: KidSignal?): Boolean =
        PlaceTag.KIDSCAFE in p.tags || p.kidScore >= 3 || (signal?.kidPostCount ?: 0) >= 500

    // ---- 연령 추정 ----

    private val nameInfant = Regex("베이비|아기|영아|토들러|짐보리|베베")
    private val nameSchool = Regex("점핑|트램폴린|방방|과학|천문|컴퓨터|항공우주|카트|승마|레일바이크|서핑|카약|어드벤처|볼링")
    private val titleInfant = Regex("영아|아기|베이비|[0-2]\\s*세|\\d{1,2}\\s*개월|0~2")
    private val titlePreschool = Regex("유아|미취학|어린이집|유치원|[3-7]\\s*세|(?<![0-9])[3-7]\\s*살")
    private val titleSchool = Regex("초등|초\\.|[8-9]\\s*세|1[0-3]\\s*세|청소년")

    /** 장소의 연령대: 후기 언급이 있으면 그걸 쓰고, 없으면 이름 규칙으로 보탠다. */
    fun agesFor(place: Place, signal: KidSignal?): Set<AgeGroup> {
        val fromBlog = signal?.let { agesFromCounts(it.ageCounts) }.orEmpty()
        if (fromBlog.isNotEmpty()) return fromBlog
        val t = place.title
        return when {
            nameInfant.containsMatchIn(t) -> setOf(AgeGroup.INFANT, AgeGroup.PRESCHOOL)
            nameSchool.containsMatchIn(t) -> setOf(AgeGroup.PRESCHOOL, AgeGroup.SCHOOL)
            PlaceTag.KIDSCAFE in place.tags -> setOf(AgeGroup.INFANT, AgeGroup.PRESCHOOL)
            else -> emptySet()
        }
    }

    /** "관람 24개월 이상" → 2, "만 4세 이상" → 4, "전체관람가" → 0. 모르면 null. */
    fun kopisMinAge(targets: String?): Int? {
        val t = targets ?: return null
        Regex("([0-9]+) *개월").find(t)?.let { return it.groupValues[1].toInt() / 12 }
        Regex("([0-9]+) *세").find(t)?.let { return it.groupValues[1].toInt() }
        if (t.contains("전체")) return 0
        return null
    }

    /** 행사의 연령대: 제목에 나온 표현으로만 정한다. 어린이 공연은 유아~초등으로 본다. */
    fun agesFor(f: Festival): Set<AgeGroup> {
        val t = f.title
        val set = mutableSetOf<AgeGroup>()
        if (titleInfant.containsMatchIn(t)) set += AgeGroup.INFANT
        if (titlePreschool.containsMatchIn(t)) set += AgeGroup.PRESCHOOL
        if (titleSchool.containsMatchIn(t)) set += AgeGroup.SCHOOL
        // 교육청 예약은 대상 칸이 따로 있다: 유아 → 유아(3~6), 학생 → 초등(제목에 중·고 표시가 없으면).
        f.targets?.let { tg ->
            if (tg.contains("유아")) set += AgeGroup.PRESCHOOL
            if (tg.contains("학생") && !Regex("중학|고등|청소년").containsMatchIn(t)) set += AgeGroup.SCHOOL
        }
        // KOPIS 아이 공연: "관람 24개월 이상", "만 4세 이상" → 그 나이부터(초등까지).
        if (f.source == "KOPIS" && f.familyFriendly) kopisMinAge(f.targets)?.let { min ->
            if (min <= 2) set += AgeGroup.INFANT
            if (min <= 6) set += AgeGroup.PRESCHOOL
            set += AgeGroup.SCHOOL
        }
        if (set.isEmpty() && Regex("어린이|키즈|동화|그림책|인형극").containsMatchIn(t)) set += setOf(AgeGroup.PRESCHOOL, AgeGroup.SCHOOL)
        return set
    }

    /**
     * 화면에 보일 장점 키워드. 야외 장소의 "비 오는 날"은 "비 오는 날 갈만한 곳" 모음 글에서 세어진 것이라 뺀다
     * (스누피가든·레일바이크에서 확인).
     */
    fun visibleKeywords(place: Place, signal: KidSignal?): List<Pair<String, Int>> =
        signal?.keywords.orEmpty().filter { !(it.first == "비 오는 날" && place.setting == Setting.OUTDOOR) }

    /** 식당·카페 한 줄: 분류 + 아이랑 후기 수 + 아이 편의 말(놀이방·아기의자 등). "비 오는 날" 같은 나들이 문구는 쓰지 않는다. */
    private fun foodSummary(place: Place, signal: KidSignal?): String {
        val kind = place.category ?: place.tags.first().label
        val parts = mutableListOf<String>()
        signal?.ownPosts?.takeIf { it >= 2 }?.let { parts += "아이랑 다녀온 후기 ${it}건(최근 50건 중)." }
        val kidWords = signal?.keywords.orEmpty().map { it.first }
            .filter { it in setOf("놀이방", "유아의자", "아기의자", "좌식", "수유실", "기저귀 갈이대", "유모차 OK", "넓어요", "주차 편함") }.take(3)
        if (kidWords.isNotEmpty()) parts += kidWords.joinToString("·") { "'$it'" } + " 이야기가 있어요."
        if (signal?.warn == true) parts += "일부 공간은 노키즈존이라는 글이 있어요."
        if (parts.isEmpty()) parts += "아이랑 후기는 아직 적어요. 카카오맵 후기를 함께 확인하세요."
        return "$kind · " + parts.joinToString(" ")
    }

    // ---- 규칙으로 조립하는 한 줄 설명 ----

    /** 예: "야외 체험 · 영아~유아 아이와 다녀온 후기가 많아요. '우유주기'·'먹이주기' 이야기가 가장 많아요. 일부 공간은 노키즈존이라는 글이 있어요." */
    fun summary(place: Place, signal: KidSignal?, ages: Set<AgeGroup>): String {
        if (PlaceTag.FOOD in place.tags || PlaceTag.CAFE in place.tags) return foodSummary(place, signal)
        val kind = place.tags.firstOrNull()?.label ?: "명소"
        val sentences = mutableListOf<String>()
        val fromReviews = signal?.let { agesFromCounts(it.ageCounts) }.orEmpty().isNotEmpty()
        ages.label()?.let { sentences += if (fromReviews) "$it 아이와 다녀온 후기가 많아요." else "$it 대상으로 보여요(이름 기준 추정)." }
        val top = visibleKeywords(place, signal).filter { it.second >= 2 }.take(2)
        if (top.isNotEmpty()) sentences += top.joinToString("·") { "'${it.first}'" } + " 이야기가 가장 많아요."
        val caution = signal?.cautions.orEmpty().firstOrNull { it.second >= 2 }
        when {
            signal?.warn == true -> sentences += "일부 공간은 노키즈존이라는 글이 있어요."
            caution != null -> sentences += "'${caution.first}' 언급도 있어요."
            place.setting == Setting.INDOOR && top.none { it.first == "비 오는 날" } -> sentences += "비 오는 날 가기 좋아요."
        }
        val head = "${place.setting.label} $kind"
        return if (sentences.isEmpty()) head else "$head · " + sentences.joinToString(" ")
    }
}
