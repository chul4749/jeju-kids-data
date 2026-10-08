package com.moon.jejukids.logic

import com.moon.jejukids.data.Region

/**
 * 내 위치 → 동네 이름을 **휴대폰 안에서만** 계산한다(위치를 서버·다른 회사로 보내지 않음).
 * 위치정보법 해설서(방통위 2022, 59~60쪽): 개인위치정보를 단말기에서만 활용하고 사업자 시스템으로 전송하지 않으면 신고 대상 아님.
 *
 * 방법: 제주를 약 1km 칸(시내는 약 280m 칸)으로 나눠 칸마다 행정동을 미리 적어 둔 표.
 * 표는 2026-10-08 개발 중 한 번, 칸 중심 좌표로 카카오 행정구역 조회를 해서 만들었다(사용자 위치와 무관).
 * 실제 위치 300곳으로 비교한 정확도 약 94% — 틀리는 곳은 동 경계 수백 m 안쪽.
 * 표 밖(추자도 등)은 가장 가까운 읍·면·동 사무소로 정한다.
 */
object Dongs {
    data class Dong(val city: String, val name: String) {
        val region: Region get() = if (city == "서귀포시") Region.SEOGWIPO else Region.JEJU
    }

    private class Grid(val lat0: Double, val lng0: Double, val dLat: Double, val dLng: Double, val rows: List<String>) {
        fun at(r: Int, c: Int): Char? = rows.getOrNull(r)?.getOrNull(c)?.takeIf { it != '.' }
        fun cell(lat: Double, lng: Double) = Math.round((lat - lat0) / dLat).toInt() to Math.round((lng - lng0) / dLng).toInt()
    }

    private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** "시|동" 이름 표(칸 글자의 순번). */
    private val names = listOf(
        "서귀포시|남원읍",
        "서귀포시|대륜동",
        "서귀포시|대정읍",
        "서귀포시|대천동",
        "서귀포시|동홍동",
        "서귀포시|서홍동",
        "서귀포시|성산읍",
        "서귀포시|송산동",
        "서귀포시|안덕면",
        "서귀포시|영천동",
        "서귀포시|예래동",
        "서귀포시|정방동",
        "서귀포시|중문동",
        "서귀포시|중앙동",
        "서귀포시|천지동",
        "서귀포시|표선면",
        "서귀포시|효돈동",
        "제주시|건입동",
        "제주시|구좌읍",
        "제주시|노형동",
        "제주시|도두동",
        "제주시|봉개동",
        "제주시|삼도1동",
        "제주시|삼도2동",
        "제주시|삼양동",
        "제주시|아라동",
        "제주시|애월읍",
        "제주시|연동",
        "제주시|오라동",
        "제주시|외도동",
        "제주시|용담1동",
        "제주시|용담2동",
        "제주시|우도면",
        "제주시|이도1동",
        "제주시|이도2동",
        "제주시|이호동",
        "제주시|일도1동",
        "제주시|일도2동",
        "제주시|조천읍",
        "제주시|추자면",
        "제주시|한경면",
        "제주시|한림읍",
        "제주시|화북동",
    )

    /** 시내 촘촘한 표(제주시 원도심~노형, 서귀포 시내). */
    private val fine = listOf(
        Grid(33.465, 126.465, 0.0025, 0.003, listOf(
            "TTTTTTTTbbbbccccccZZZZZZZZZZZZZZZZZZZZZZVVVVVVVVVV",
            "TTTTTTTTbbbbbcccccZZZZZZZZZZZZZZZZZZZZZZVVVVVVVVVV",
            "TTTTTTTTbbbbbccccZZZZZZZZZZZZZZZZZZZZZZZVVVVVVVVVV",
            "TTTTTTTTbbbbbccccZZZZZZZZZZZZZZZZZZZZZZZVVVVVVVVVV",
            "TTTTTTTTbbbbbcccccZZZZZZZZZZZZZZZZZZZZVVVVVVVVVVVV",
            "TTTTTTTTbbbbcccccciiiiZZZZZZZZZZZZZZZZVVVVVVVVVVVV",
            "TTTTTTTTbbbbcccccciiiiiZZZZZZZZZZZZZZZZVVVVVVVVVVV",
            "TTTTTTTTbbbbbccccciiiiiZZZZZZZZZZZZZZZZZVVVVVVVVVV",
            "TTTTTTTTbbbbbccccciiiiiZZZZZZZZZZZZZZZZZZZVVVVVVVV",
            "TTTTTTTbbbbbbccccciiiiZiiZZZZZZqqqZZZZZZZVVVVVVVVV",
            "jTTTTTbbbbbbbccccciiiiiiiiiZZZqqqZZZZZZZZVVVVVVVVV",
            "jjUTTbbbbbbbbcccccciiiiiiiZiiiqqqZZZZZZZVVVVVVVVVV",
            "jjUUTbbbUfbbbcccccciiiiiiiiiiiqqqqqZZZZVVVYVVVVVVV",
            "jUUUUUUUUffbccccccciiiiiiiiiiiqqqqqqqZZYYYYYYVVVVV",
            "UUUUUUUUUfffffcccWWWiiiiiiilllqqqqqqqqYYYYYYYYVVVV",
            "UUUUUUUUUUffffffeWWWhhhiiilllllqqqqqqqYYYYYYYVVVVV",
            "UUUUUUUUUfffffffeWWXXhhllllllRRqqqqqqYYYYYYYYVVVVV",
            "UUUUUUUUUfffffffeWWXhhlllllRRRRqqqqqqYYYYYYYYYYYVV",
            "UUUUUUUUffffffffeeWXhklllllRRqqqqqqqqqYYYYYYYYYYYV",
            "UUUUUUfffffffffffeeXhkkllllRRqqqqqqqqqYYYYYYYYYYYV",
            "UUUUUffffffffffffeXXkkRRRRRRRqqqqqqqqqYYYYYYYYYYYV",
            "UUUUUffffffffffffXXXRRRRRRRRRqqqqqqqqqYYYYYYYYYYYm",
            "UUUUffffffffffffXXXXXRRRRRRRRqqqqqqqqqYYYYYYYYYmmm",
            "UUUfffffffffffffXXXXXRRRRRRRRqqqqqqqqYYYYYYYYYmmmm",
            "UUfffffffffffffXXXXXRRRRRRRRRqqqqqqqqYYYYYYYYmmmmm",
            "UUfffffffffffffXXXXXRRRRRRRRRqqqqqqqYYYYYYYYYmmmmm",
            "UffffffffffffffXXXXXRRRRRRRRRRqqqqqqYYYYYYYYYYmmmm",
            "UffffffffffffffRRRRRRRRRRRRRRRqqqqqqYYYYYYYYYYYmmm",
            "fffffffffffffffRRRRRRRRRRRRRRRqqqqqqYYYYYYYYYYmmmm",
            "fffffffffffffffRRRRRRRRRRRRRRRqqqqqqYYYYYYYYYYYmmm",
        )),
        Grid(33.225, 126.495, 0.0025, 0.003, listOf(
            "DDDDBBBBBBBBBBBBBOHHHHHHHHHHHHHHHH",
            "DDDDBBBBBBBBBBBBBOOHHHHHHHHHHHHHHH",
            "DDDDBBBBBBBBBBBBBOOHHHHHHHHHHHHHHH",
            "DDDDBBBBBBBBBBBBBOOHHHHHHHHHHHHHHH",
            "DDDDDBBBBBBBBBBBBOOOHHHHHHHHHHHHHH",
            "DDDDDBBBBBBBBBBBBOOOOHHHHHHHHHHHHH",
            "DDDDDBBBBBBBBBBBBOOOOOHHHHHHHHHHHH",
            "DDDDBBBBBBBBBBBBBOOOOOHHHHHHHHHHHH",
            "DDDBBBBBBBBBBBBBBOOOOOLLHLEHHHHHHH",
            "DDDDDBBBBBBBBBBBBOOOOOOLLLEEHHHJHH",
            "DDDDDBBBBBBBBBBBBFFOOOONNEEEJJJJHH",
            "DDDDDBBBBBBBBBBBBFFFFOONEEEEJJJJHH",
            "DDDDDBBBBBBBBBBBBFFFFFEEEEEEJJJJHH",
            "DDDDDDBBBBBBBBBBFFFFFFEEEEEEJJJJJJ",
            "DDDDDDBBBBBBBBBBFFFFFFEEEEEEJJJJJQ",
            "DDDDDBBBBBBBBBBBFFFFFFEEEEEEJJJJJJ",
            "DDDDDBBBBBBBBBBBFFFFFFEEEEEEEJJJJJ",
            "DDDDDBBBBBBBBBBBFFFFFFEEEEEEJJJJJJ",
            "DDDDDBBBBBBBBBBFFFFFFEEEEEEJJJJJJJ",
            "DDDDDBBBBBBBBBBFFFFFFEEEEEEJJJJJJJ",
            "DDDDDDBBBBBBBBBFFFFFFEEEEEEJJJJJJJ",
            "DDDDDBBBBBBBBBFFFFFFFEEEJJJJJJJJJJ",
        )),
    )

    /** 제주 본섬 전체 약 1km 표. */
    private val coarse = Grid(33.10, 126.14, 0.01, 0.0125, listOf(
            "CCCCCCCCCCCCCCCCCCCCCCCDDDBBBBBBBBBHHHAAAAA.........................",
            "CCCCCCCCCCCCCCCCCCCCCCCDDDBBBBBBBBHHHHAAAAAAA.......................",
            "CCCCCCCCCCCCCCCCCCCCCCMDDDBBBBBBBBHHHHAAAAAAAAA.....................",
            "CCCCCCCCCCCCCCCCCCCCIIBBBBBBBBBBBBBBBAAAAAAAAAAAA...................",
            "CCCCCCCCCCCCCCCCCCCIIIBBBBBBBBBBBBBBAAAAAAAAAAAAAAA.................",
            "CCCCCCCCCCCCCCCCCCIIIIBBBBBBBBBBBBBBAAAAAAAAAAAAAAAAA...............",
            "CCCCCCCCCCCCCCCCCIIIKKMMDDDBBBBBBBHHHHHAAAAAAAAAAAAAAA..............",
            "CCCCCCCCCCCCCCCCIIIKKKMMDBBBBBBBBBHHHHAAAAAAAAAAAAAAAAAA............",
            "CCCCCCCCCCCCCCCIIIIKKKMMDBBBBBBBBBHHHHAAAAAAAAAAAAAAAAAAPP..........",
            "CCCCCCCCCCCCCCIIIIIKKKMMMDDBBBBBBHHHHHHAAAAAAAAAAAAAAAAAPPPP........",
            "CCCCCCCCCCCCCCIIIIIKKKMMMDDBBBBBBHHHHHHAAAAAAAAAAAAAAAAAPPPP........",
            "CCCCCCCCCCCCCCIIIIIKKKMMMDDDBBBBBHHHHHHHAAAAAAAAAAAAAAAPPPPPP.......",
            "CCCCCCCCCCCCCIIIIIIKKKMMMDDDDBBBBHHHHHHHAAAAAAAAAAAAAAPPPPPPPP......",
            "CCCCCCCCCCCCIIIIIIIKKKMMMDDDDDBBBHHHHHHQAAAAAAAAAAAAAAPPPPPPPPP.....",
            "CCCCCCCCCCCCIIIIIIIKKKKMMMDDDDBBBOHHHHHQAAAAAAAAAAAAAAPPPPPPPPP.....",
            "CCCCCCCCCCCCCIIIIIIKKKKMMMDDDDBBBONEHHQAAAAAAAAAAAAAAPPPPPPPPPPP....",
            "oCCCCCCCCCCCCIIIIIIKKKKMMMMDDDBBBFEEJQQAAAAAAAAAAAAAAPPPPPPPPPPPP...",
            "ooCCCCCCCCCCCIIIIIIKKKKMMMMDDDBBFFEJJQQAAAAAAAAAAAAAPPPPPPPPPPPPPP..",
            "ooCCCCCCoCCCCIIIIIIKKKKMMMMDDDBBFFJJJJJAAAAAAAAAAAAAPPPPPPPPPPPPPP..",
            "oooCCCCooCCCCIIIIIIKKKKMMMMDDDBBFEJJJJAAAAAAAAAAAAAAPPPPPPPPPPPPPPG.",
            "ooooooooooooIIIIIIIIIIKMMMMDDDBBFEJJJAAAAAAAAAAAAAAAPPPPPPPPPPPPPGGG",
            "oooooooooooooIIIIIIIIIIMMMMDDDDFEJJJJAAAAAAAAAAAAAAPPPPPPPPPPPGGGGGG",
            "oooooooooooooIIIIIIIIIIKMMMMDDDFEJJJJAAAAAAAAAAAAAPPPPPPPPPPGGGGGGGG",
            "oooooooooooopppIIIIIIIIKMMMMDDDFJJJJAAAAAAAAAAAAAAPPPPPPPPGGGGGGGGGG",
            "ooooooooooooppppIIIIIIIIKMMMDDDEJJJAAAAAAAAAAAAAAAPPPPPPPGGGGGGGGGGG",
            "ooooooooppppppppppaaaaaaKKMMMDDEJJAAAAAAAAAAAAAAAPPPPPPPPGGGGGGGGGGG",
            "oooooooopppppppppaaaaaaaaaMaaaDFJJAAAAAAAAAAAAAAPPPPPPPPGGGGGGGGGGGG",
            "ooooooopppppppppaaaaaaaaaaaaaaacZAAAAAAAAAAAAAAPPPPPPPPGGGGGGGGGGGGG",
            "oooooopppppppppaaaaaaaaaaaaaaabcZZZmmAAAAAAPPPPPPPPPPGGGGGGGGGGGGGGG",
            "oooooppppppppppaaaaaaaaaaaaTabcccZZZmmmAAAPPPPPPPPPPPPGGGGGGGGGGGGGG",
            "ooopppppppppppaaaaaaaaaaaaTbbbcccZZZZVmmmmPPPPPPPPPPPPGGGGGGGGGGGGGG",
            "ooppppppppppppaaaaaaaaaaaTTbbbccZZZZZVVmmmmmPPPPPPPPPPGGGGGGGGGGGGGG",
            ".oppppppppppppaaaaaaaaaaaTTTTbccZZZZZVVmmmmmmPPPPSPPPPPGGGGGGGGGGGGG",
            ".ppppppppppppaaaaaaaaaaaaTTTTbccZZZZZVVVmmmmmmmmSSSPPPPGGGGGGGGGGGGG",
            "...ppppppppppaaaaaaaaaaaaTTTbccZZZZZVVVVVmmmmmmSSSSSPPGGGGGGGGGGGGGG",
            "....pppppppppaaaaaaaaaaaaTTTbccZZZZZVVVVVmmmmmmSSSSSSSSGGGGGGGGGGGGG",
            "......pppppppaaaaaaaaaaaaTTTbccZZZZZVVVVVmmmmmmSSSSSSSSSSGGGGGGGGGGG",
            ".......pppppaaaaaaaaaaaaadTTbccZZZZZVVVVmmmmmmmSSSSSSSSSSSSGGGGGGGgg",
            ".........ppaaaaaaaaaaaadddTTbcciZZZVVVVVmmmmmmmmSSSSSSSSSSSSGGGGGggg",
            "..........aaaaaaaaaaaaaddjjTbcciiZZZVVVmmmmmmmmmSSSSSSSSSSSSSSSSgggg",
            "...........aaaaaaaaaaadddjUUUfciilqqYVVmmmmmmmmmSSSSSSSSSSSSSSSSgggg",
            ".............aaaaaaaaadddjUUffeklqqYYYVmmmmmmmmmSSSSSSSSSSSSSSSSgggg",
            "..............aaaaaaadddjUUfffXRRqqqYYmmmmmmmmmSSSSSSSSSSSSSSSSSgggg",
            "................aaaaaddjjUUfffXRRRqYYmmmmmmmmmSSSSSSSSSSSSSSSSSSgggg",
            ".................aaadddjUUfffRRRRRqYYYmmmmmmmmSSSSSSSSSSSSSSSSSggggg",
            "...................adddjUUfffRRRRRqYYYmmmmmmmmSSSSSSSSSSSSSSSSSggggg",
            "....................adjUUffRRRRRRRqYYYmmmmmmmmSSSSSSSSSSSSSSSSSggggg",
            "........................UffRRRRRRRYYYmmmmmmmmmSSSSSSSSSSSSSSSSSggggg",
            "............................RRRRRRYYYmmmmmmmmmSSSSSSSSSSSSSSSSSggggg",
    ))

    /** 읍·면·동 사무소 위치(표 밖일 때). */
    private val offices = listOf(
        "제주시|한림읍" to (33.41058 to 126.26679),
        "제주시|애월읍" to (33.46219 to 126.32897),
        "제주시|구좌읍" to (33.52252 to 126.85205),
        "제주시|조천읍" to (33.5344 to 126.63413),
        "제주시|한경면" to (33.35013 to 126.18415),
        "제주시|추자면" to (33.96372 to 126.29612),
        "제주시|우도면" to (33.50645 to 126.95298),
        "제주시|일도1동" to (33.51502 to 126.52699),
        "제주시|일도2동" to (33.51163 to 126.53829),
        "제주시|이도1동" to (33.50694 to 126.52701),
        "제주시|이도2동" to (33.49705 to 126.53529),
        "제주시|삼도1동" to (33.50412 to 126.51736),
        "제주시|삼도2동" to (33.51174 to 126.52221),
        "제주시|용담1동" to (33.5091 to 126.51327),
        "제주시|용담2동" to (33.51147 to 126.51168),
        "제주시|건입동" to (33.51503 to 126.53152),
        "제주시|화북동" to (33.52022 to 126.56547),
        "제주시|삼양동" to (33.52189 to 126.5856),
        "제주시|봉개동" to (33.49172 to 126.59469),
        "제주시|아라동" to (33.47634 to 126.54527),
        "제주시|오라동" to (33.49513 to 126.51158),
        "제주시|연동" to (33.48816 to 126.49689),
        "제주시|노형동" to (33.48308 to 126.47719),
        "제주시|외도동" to (33.49286 to 126.43218),
        "제주시|이호동" to (33.49895 to 126.45787),
        "제주시|도두동" to (33.5029 to 126.46823),
        "서귀포시|대정읍" to (33.22668 to 126.25184),
        "서귀포시|남원읍" to (33.27988 to 126.72067),
        "서귀포시|성산읍" to (33.44223 to 126.91101),
        "서귀포시|안덕면" to (33.25763 to 126.33067),
        "서귀포시|표선면" to (33.32672 to 126.83098),
        "서귀포시|송산동" to (33.24502 to 126.56601),
        "서귀포시|정방동" to (33.24587 to 126.56525),
        "서귀포시|중앙동" to (33.25068 to 126.56508),
        "서귀포시|천지동" to (33.24782 to 126.56128),
        "서귀포시|효돈동" to (33.2633 to 126.61556),
        "서귀포시|영천동" to (33.26876 to 126.58664),
        "서귀포시|동홍동" to (33.2579 to 126.56887),
        "서귀포시|서홍동" to (33.25596 to 126.56018),
        "서귀포시|대륜동" to (33.24811 to 126.51136),
        "서귀포시|대천동" to (33.25052 to 126.47777),
        "서귀포시|중문동" to (33.25333 to 126.43364),
        "서귀포시|예래동" to (33.25476 to 126.39999),
    )

    private fun dong(ch: Char): Dong? = names.getOrNull(ALPHA.indexOf(ch))?.let { Dong(it.substringBefore('|'), it.substringAfter('|')) }

    /** 내 위치의 읍·면·동. 제주에서 30km 넘게 떨어져 있으면(섬 밖) null. */
    fun nearest(lat: Double, lng: Double): Dong? {
        for (g in fine) {
            val (r, c) = g.cell(lat, lng)
            g.at(r, c)?.let { ch -> return dong(ch) }
        }
        val (r, c) = coarse.cell(lat, lng)
        for (rad in 0..2) for (dr in -rad..rad) for (dc in -rad..rad) coarse.at(r + dr, c + dc)?.let { ch -> return dong(ch) }
        val best = offices.minByOrNull { Recommender.distanceKm(lat, lng, it.second.first, it.second.second) } ?: return null
        if (Recommender.distanceKm(lat, lng, best.second.first, best.second.second) > 30) return null
        return Dong(best.first.substringBefore('|'), best.first.substringAfter('|'))
    }
}
