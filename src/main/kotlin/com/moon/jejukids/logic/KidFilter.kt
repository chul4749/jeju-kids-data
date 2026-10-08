package com.moon.jejukids.logic

import com.moon.jejukids.data.PlaceTag
import com.moon.jejukids.data.Setting

/**
 * 관광공사 데이터에는 아이와 무관한 곳(골프장·주점·카지노 등)도 섞여 있어서
 * 이름에 들어간 단어 + 관광공사 신분류체계 코드(lclsSystm3)로 아이 관련도·실내외·분류를 추정한다.
 * 추정이라 틀릴 수 있다 — 화면에서도 "추정"으로 다룬다.
 * 코드 표는 2026-10-06 실제 제주 데이터(관광지 557·문화시설 98·레포츠 138곳)를 보고 정했다.
 */
object KidFilter {

    private val excluded = listOf(
        "골프", "컨트리클럽", "카지노", "러브랜드", "성박물관", "성 박물관", "술박물관", "술도가", "주류", "양조",
        "와이너리", "브루어리", "맥주", "호텔", "리조트", "펜션", "게스트하우스", "글램핑", "카라반", "캠핑", "야영장",
        "사격", "수렵", "경마", "포차", "노래", "[제주올레", "[하영올레", "[한라산 둘레길", "여객선", "대합실",
    )

    /**
     * 신분류체계 코드(앞부분 일치) → 가산점. 이름에 아이 단어가 없어도 이 분류면 넣는다.
     * 예: VE020100 테마공원(스누피가든·더마파크), EX070100 잠수함·유람선, NA010300 폭포.
     */
    private val codeScore = listOf(
        "VE0201" to 3, // 테마공원
        "VE0202" to 3, // 워터파크
        "EX0701" to 3, // 잠수함·유람선
        "EX0702" to 2, // 이색 체험시설
        "EX0302" to 2, // 목장·동물 체험
        "EX0303" to 1, // 관광농원
        "EX0610" to 1, // 테마 농원·정원
        "NA0407" to 1, // 식물원·정원
        "NA0103" to 1, // 폭포
        "NA0205" to 1, // 섬(우도·가파도·마라도)
        "LS0103" to 2, // 레저 체험(카트 등)
        "LS0107" to 1, // 승마
        "LS0117" to 2, // 어드벤처
        "LS0201" to 1, // 서핑
        "LS0202" to 2, // 투명카약
        "LS0207" to 2, // 수영장
        "LS0214" to 2, // 보트
    )

    /** 이 분류는 이름과 상관없이 실내/야외로 본다. */
    private val indoorCodes = listOf("VE07", "VE0202", "EX0701", "LS0207")
    private val outdoorCodes = listOf("NA", "VE0201", "EX03", "EX0610", "LS01", "LS0202", "LS0214")

    /** 단어별 가중치. 아이 전용 단어일수록 높다. */
    private val kidWords = mapOf(
        "어린이" to 5, "키즈" to 5, "유아" to 5, "아이" to 3, "놀이" to 4, "동화" to 4, "장난감" to 4,
        "공룡" to 4, "테디베어" to 4, "캐릭터" to 3, "아쿠아" to 4, "수족관" to 4, "동물" to 4,
        "과학" to 3, "체험" to 3, "미로" to 3, "기차" to 3, "자동차" to 2, "테마파크" to 4, "랜드" to 2,
        "목장" to 3, "농장" to 3, "승마" to 2, "감귤" to 2, "귤" to 1, "박물관" to 2, "뮤지엄" to 2,
        "전시관" to 1, "미술관" to 1, "해수욕장" to 2, "해변" to 1, "공원" to 2, "휴양림" to 2,
        "수목원" to 2, "생태" to 2, "곶자왈" to 1, "도서관" to 3, "천문" to 3, "별빛" to 1, "레일바이크" to 3,
        "카트" to 2, "썰매" to 3, "물놀이" to 4, "워터" to 3, "잠수함" to 3, "카약" to 2, "폭포" to 1,
        "키티" to 4, "프렌즈" to 3, "캔디" to 3, "초콜릿" to 2, "서커스" to 3, "노루" to 3, "관찰" to 2, "트릭아트" to 3,
    )

    private val indoorWords = listOf(
        "박물관", "뮤지엄", "미술관", "전시", "아쿠아", "수족관", "과학관", "체험관", "키즈", "실내",
        "도서관", "기념관", "갤러리", "센터", "영화", "볼링", "카페", "테디베어", "천문",
    )
    private val outdoorWords = listOf(
        "해수욕장", "해변", "공원", "오름", "숲", "휴양림", "목장", "농장", "올레", "폭포", "수목원",
        "정원", "승마", "곶자왈", "해안", "포구", "레일바이크", "카트",
    )

    private val tagWords = mapOf(
        PlaceTag.MUSEUM to listOf("박물관", "뮤지엄", "미술관", "전시", "기념관", "갤러리", "과학관"),
        PlaceTag.EXPERIENCE to listOf("체험", "만들기", "공방", "목장", "농장", "승마", "카트", "레일바이크"),
        PlaceTag.ANIMAL to listOf("동물", "아쿠아", "수족관", "목장", "농장", "승마"),
        PlaceTag.BEACH to listOf("해수욕장", "해변", "해안", "포구"),
        PlaceTag.NATURE to listOf("공원", "숲", "휴양림", "수목원", "오름", "정원", "곶자왈", "생태", "폭포"),
        PlaceTag.THEME to listOf("테마파크", "랜드", "미로", "월드", "파크"),
        PlaceTag.LIBRARY to listOf("도서관"),
    )

    fun isExcluded(title: String): Boolean = excluded.any { title.contains(it) }

    /**
     * 0이면 아이와 무관하다고 보고 뺀다.
     * 문화시설(14)은 단어가 없어도 1점을 준다 — 박물관·전시관 이름이 다양해서.
     */
    fun kidScore(title: String, typeId: Int, code: String = ""): Int {
        if (isExcluded(title)) return 0
        val words = kidWords.entries.sumOf { (word, w) -> if (title.contains(word)) w else 0 }
        val bonus = codeScore.firstOrNull { code.startsWith(it.first) }?.second ?: 0
        val score = words + bonus
        return if (score == 0 && typeId == TYPE_CULTURE) 1 else score
    }

    fun setting(title: String, typeId: Int, code: String = ""): Setting {
        val indoor = indoorWords.any { title.contains(it) } || indoorCodes.any { code.startsWith(it) }
        val outdoor = outdoorWords.any { title.contains(it) } || outdoorCodes.any { code.startsWith(it) }
        return when {
            indoor && !outdoor -> Setting.INDOOR
            outdoor && !indoor -> Setting.OUTDOOR
            indoor && outdoor -> Setting.MIXED
            typeId == TYPE_CULTURE -> Setting.INDOOR
            typeId == TYPE_SPOT -> Setting.OUTDOOR
            else -> Setting.MIXED
        }
    }

    fun tags(title: String): Set<PlaceTag> =
        tagWords.filter { (_, words) -> words.any { title.contains(it) } }.keys

    private val familyWords = listOf("어린이", "가족", "아이", "키즈", "유아", "동화", "체험", "놀이", "청소년")

    fun isFamilyFestival(title: String): Boolean = familyWords.any { title.contains(it) }

    /** 카카오 장소검색에서 온 키즈카페(관광공사 유형이 없다). */
    const val TYPE_KAKAO = 0
    const val TYPE_SPOT = 12
    const val TYPE_CULTURE = 14
    const val TYPE_FESTIVAL = 15
    const val TYPE_LEISURE = 28
}
