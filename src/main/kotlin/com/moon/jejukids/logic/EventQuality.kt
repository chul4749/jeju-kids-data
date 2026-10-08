package com.moon.jejukids.logic

import com.moon.jejukids.data.Festival

/**
 * 출처마다 다른 "가족 행사" 표시를 한 번 더 다듬는다(합친 뒤).
 * - 어른 행사(맥주 축제 등)는 가족 표시를 끈다.
 * - 아이 캐릭터·아이 공연 말이 있는데 가족 표시가 없으면 켠다(2026-10-07: "시크릿쥬쥬" 뮤지컬이 빠져 있었다).
 */
object EventQuality {
    private val kidWords = Regex(
        "쥬쥬|뽀로로|타요|티니핑|핑크퐁|아기상어|브레드이발소|신비아파트|카봇|코코몽|콩순이|번개맨|공룡|마술|매직|인형극|그림책|동화|" +
            "어린이|키즈|유아|아동|가족|패밀리|아이랑",
    )

    fun normalize(list: List<Festival>): List<Festival> = list.map { f ->
        when {
            f.isAdult && f.familyFriendly -> f.copy(familyFriendly = false)
            !f.isAdult && !f.familyFriendly && kidWords.containsMatchIn(f.title) -> f.copy(familyFriendly = true)
            else -> f
        }
    }
}
