package com.moon.jejukids.data

import java.time.LocalDate

/** 제주를 크게 두 행정시로 나눈다. 날씨 격자·미세먼지 측정소 선택에 쓴다. */
enum class Region(val label: String) {
    JEJU("제주시"),
    SEOGWIPO("서귀포시");

    companion object {
        fun fromAddress(addr: String): Region = if (addr.contains("서귀포")) SEOGWIPO else JEJU
    }
}

/** 실내/야외 구분. 날씨 추천에 쓴다. */
enum class Setting(val label: String) { INDOOR("실내"), OUTDOOR("야외"), MIXED("실내·야외") }

enum class PlaceTag(val label: String) {
    KIDSCAFE("키즈카페"),
    MUSEUM("박물관·전시"),
    EXPERIENCE("체험"),
    ANIMAL("동물·농장"),
    BEACH("바다·해변"),
    NATURE("숲·공원"),
    THEME("테마파크"),
    LIBRARY("도서관"),
    /** 주말 코스의 점심·카페(카카오 장소 검색). 장소 목록 필터에는 나오지 않는다. */
    FOOD("식당"),
    CAFE("카페"),
}

data class Place(
    val id: String,
    val typeId: Int,
    val title: String,
    val addr: String,
    val lat: Double?,
    val lng: Double?,
    val image: String?,
    val tel: String?,
    val setting: Setting,
    val tags: Set<PlaceTag>,
    /** 아이와 관련도. 0이면 목록에 넣지 않는다. */
    val kidScore: Int,
    val sample: Boolean = false,
    /** 카카오 장소 페이지(사진·영업시간·후기). 관광공사 장소는 null. */
    val link: String? = null,
    /** 식당·카페 세부 분류(카카오 "한식", "해물,생선" 등). */
    val category: String? = null,
) {
    val region: Region get() = Region.fromAddress(addr)
}

data class Festival(
    val id: String,
    val title: String,
    val addr: String,
    val lat: Double?,
    val lng: Double?,
    val image: String?,
    val tel: String?,
    val start: LocalDate,
    val end: LocalDate,
    /** 가족·어린이 대상인지(플레이제주 '아동/가족' 분류, 또는 제목의 가족·어린이 단어). */
    val familyFriendly: Boolean,
    val sample: Boolean = false,
    /** 출처 이름: 관광공사 · 플레이제주 · 제주인놀다. */
    val source: String = "관광공사",
    /** 원문 페이지. 관광공사 행사는 상세 API로 보여주므로 null. */
    val link: String? = null,
    val venue: String? = null,
    val fee: String? = null,
    val time: String? = null,
    val category: String? = null,
    /** 교육청 예약: 접수 상태(접수중·예정·대기접수), 신청 기간, 대상(유아·가족·학생…). */
    val status: String? = null,
    val apply: String? = null,
    val targets: String? = null,
) {
    val region: Region get() = Region.fromAddress(addr)

    /** 큰 축제: 관광공사에 올라온 행사이거나 제목·분류에 "축제"가 있는 것. 가족 분류가 아니어도 앞에 보여준다. */
    /**
     * 축제인지. 2026-10-07 점검: 출처의 '축제' 분류에 "도서관 AI 프로그램 참여자 모집", "(성인) 다문화 수업",
     * "전국체육대회"까지 들어 있었다 → 모집·수업·대회류는 빼고, 어른 행사(맥주 축제 등)도 뺀다.
     */
    val isFestival: Boolean get() = !isAdult && !notFestival.containsMatchIn(title) &&
        (source == "관광공사" || festivalWord.containsMatchIn(title) || category?.contains("축제") == true)

    /** 맥주·와인 축제, 성인 대상 행사 — 아이 앱의 추천 목록에서는 뺀다. */
    val isAdult: Boolean get() = adultWord.containsMatchIn(title)

    private companion object {
        val festivalWord = Regex("축제|페스티벌|페스타|문화제|박람회|한마당")
        val notFestival = Regex("모집|수업|강좌|강의|교실|세미나|포럼|과정|체육대회|전국체전|설명회")
        val adultWord = Regex("비어|맥주|와인|막걸리|소주|주류|칵테일|위스키|[(]성인[)]|성인 *대상|19금|나이트")
    }
}

enum class Sky(val label: String) { CLEAR("맑음"), CLOUDY("구름많음"), OVERCAST("흐림"), UNKNOWN("-") }

/** 하루 예보 요약(주말 계획용). 오전 = 6~12시, 오후 = 12~19시. */
data class DayWeather(
    val date: LocalDate,
    val popAm: Int,
    val popPm: Int,
    /** 낮(6~19시)에 비·눈 예보가 한 번이라도 있는지. */
    val rain: Boolean,
    val sky: Sky,
    val tempMin: Int?,
    val tempMax: Int?,
) {
    val popMax: Int get() = maxOf(popAm, popPm)
    /** 비 걱정이 큰 날: 강수확률 60% 이상이거나 강수 예보. */
    val rainy: Boolean get() = rain || popMax >= 60
}

data class Weather(
    val region: Region,
    val tempNow: Int?,
    val sky: Sky,
    /** 지금 시각 예보에 비·눈이 있는지. */
    val rainNow: Boolean,
    /** 앞으로 6시간 안에 강수확률 60% 이상이거나 강수 형태가 있는지. */
    val rainSoon: Boolean,
    /** 오늘 남은 시간 중 최대 강수확률. */
    val popMax: Int,
    val tempMin: Int?,
    val tempMax: Int?,
    val sample: Boolean = false,
    /** 오늘부터 예보가 있는 날까지(보통 3~4일) 하루 요약. */
    val days: List<DayWeather> = emptyList(),
)

/** 미세먼지 등급: 1 좋음, 2 보통, 3 나쁨, 4 매우나쁨. */
data class Air(
    val region: Region,
    val pm10: Int?,
    val pm25: Int?,
    val grade: Int,
    val dataTime: String,
    val sample: Boolean = false,
) {
    val gradeLabel: String get() = gradeLabel(grade)

    companion object {
        fun gradeLabel(grade: Int) = when (grade) {
            1 -> "좋음"
            2 -> "보통"
            3 -> "나쁨"
            4 -> "매우나쁨"
            else -> "-"
        }
    }
}

/** 상세 화면에서 보여줄 항목(이름-값). 이용시간·휴무·유모차 등. */
data class Detail(
    val overview: String?,
    val homepage: String?,
    val facts: List<Pair<String, String>>,
    /** 관광공사 사진 원본들(크게 보기·넘겨 보기). */
    val photos: List<String> = emptyList(),
)

class ApiException(message: String) : Exception(message)
