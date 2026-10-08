package com.moon.jejukids.data

import com.moon.jejukids.logic.KidFilter
import com.moon.jejukids.logic.Recommender
import java.time.LocalDate

/**
 * 인증키가 없을 때 화면을 확인하기 위한 샘플. 화면에 "샘플"로 표시된다.
 * 장소 이름은 실제 제주 명소지만 좌표는 대략값이고, 행사는 가상의 예시다.
 */
object SampleData {

    private fun place(id: String, title: String, addr: String, lat: Double, lng: Double, typeId: Int) = Place(
        id = id, typeId = typeId, title = title, addr = addr, lat = lat, lng = lng, image = null, tel = null,
        setting = KidFilter.setting(title, typeId), tags = KidFilter.tags(title),
        kidScore = KidFilter.kidScore(title, typeId).coerceAtLeast(1), sample = true,
    )

    val places = listOf(
        place("s1", "아쿠아플라넷 제주", "제주특별자치도 서귀포시 성산읍", 33.433, 126.928, KidFilter.TYPE_SPOT),
        place("s2", "제주항공우주박물관", "제주특별자치도 서귀포시 안덕면", 33.305, 126.290, KidFilter.TYPE_CULTURE),
        place("s3", "넥슨컴퓨터박물관", "제주특별자치도 제주시 노형동", 33.470, 126.487, KidFilter.TYPE_CULTURE),
        place("s4", "에코랜드 테마파크", "제주특별자치도 제주시 조천읍", 33.457, 126.667, KidFilter.TYPE_SPOT),
        place("s5", "절물자연휴양림", "제주특별자치도 제주시 봉개동", 33.438, 126.628, KidFilter.TYPE_SPOT),
        place("s6", "함덕해수욕장", "제주특별자치도 제주시 조천읍", 33.543, 126.670, KidFilter.TYPE_SPOT),
        place("s7", "국립제주박물관", "제주특별자치도 제주시 일도이동", 33.513, 126.549, KidFilter.TYPE_CULTURE),
        place("s8", "제주민속촌", "제주특별자치도 서귀포시 표선면", 33.322, 126.842, KidFilter.TYPE_SPOT),
        Place("s9", KidFilter.TYPE_KAKAO, "[샘플] OO 키즈카페", "제주특별자치도 제주시 연동", 33.489, 126.493, null, null,
            Setting.INDOOR, setOf(PlaceTag.KIDSCAFE), 4, sample = true),
    )

    fun festivals(today: LocalDate) = listOf(
        Festival("f1", "[샘플] 어린이 가족 체험 한마당", "제주특별자치도 제주시", 33.499, 126.531, null, null,
            today.minusDays(2), today.plusDays(5), familyFriendly = true, sample = true),
        Festival("f2", "[샘플] 감귤 따기 축제", "제주특별자치도 서귀포시", 33.254, 126.560, null, null,
            today.plusDays(3), today.plusDays(10), familyFriendly = false, sample = true),
        Festival("f3", "[샘플] 바닷가 별빛 음악회", "제주특별자치도 제주시 조천읍", 33.543, 126.670, null, null,
            today.plusDays(20), today.plusDays(21), familyFriendly = false, sample = true, category = "공연"),
        Festival("f4", "[샘플] 가족 인형극 〈해녀 할망〉", "제주특별자치도 제주시", null, null, null, null,
            Recommender.weekend(today).first, Recommender.weekend(today).first, familyFriendly = true, sample = true, category = "공연"),
        Festival("f5", "[샘플] 토요가족체험 프로그램", "제주특별자치도 서귀포시 OO유아교육진흥원", null, null, null, null,
            today.plusDays(11), today.plusDays(11), familyFriendly = true, sample = true, source = "교육청 예약",
            venue = "OO유아교육진흥원", status = "접수중", apply = "신청: 오늘 09:00 ~ 사흘 뒤 16:00", targets = "유아 가족", category = "견학/체험"),
        Festival("f6", "[샘플] 그림책 동화구연", "제주특별자치도 제주시 OO도서관", null, null, null, null,
            today.plusDays(4), today.plusDays(25), familyFriendly = true, sample = true, source = "교육청 예약",
            venue = "공공도서관 (OO도서관)", status = "예정", targets = "유아", category = "교육/강좌"),
    )

    fun weather(region: Region): Weather {
        val today = LocalDate.now()
        val (sat, sun) = Recommender.weekend(today)
        // 샘플: 토요일 비, 일요일 맑음 — 주말 코스가 실내/야외로 갈리는 모습을 보여 준다.
        val days = (0L..6L).map { today.plusDays(it) }.map { d ->
            when (d) {
                sat -> DayWeather(d, 60, 80, rain = true, sky = Sky.OVERCAST, tempMin = 17, tempMax = 20)
                sun -> DayWeather(d, 10, 20, rain = false, sky = Sky.CLEAR, tempMin = 16, tempMax = 23)
                else -> DayWeather(d, 20, 20, rain = false, sky = Sky.CLOUDY, tempMin = 17, tempMax = 23)
            }
        }
        return Weather(region, 21, Sky.CLOUDY, rainNow = false, rainSoon = false, popMax = 20, tempMin = 17, tempMax = 23, sample = true, days = days)
    }

    fun air(region: Region) = Air(region, 28, 12, 1, "샘플", sample = true)

    /** 키 없이 볼 때 주말 코스 점심·카페 자리에 보여줄 예시(지점 바로 옆). */
    fun food(slot: com.moon.jejukids.logic.FoodPicker.Slot): List<Place> {
        val (name, cat) = if (slot.kind == com.moon.jejukids.logic.FoodPicker.Kind.LUNCH) "[샘플] 아이랑 국수집" to "국수" else "[샘플] 정원 카페" to "카페"
        return listOf(
            Place("sf_${slot.kind.code}", KidFilter.TYPE_KAKAO, name, "제주특별자치도 제주시", slot.lat + 0.002, slot.lng + 0.002, null, null,
                Setting.INDOOR, setOf(slot.kind.tag), 0, sample = true, category = cat),
        )
    }
}
