package com.moon.jejukids.data

import java.io.File

/**
 * API 원본 응답을 파일로 보관한다. 꺼내 쓸 때 다시 파싱하므로 모델 직렬화가 필요 없고,
 * 통신이 안 될 때도 마지막으로 받은 정보를 보여줄 수 있다.
 */
class ResponseCache(private val dir: File) {
    data class Entry(val body: String, val savedAt: Long)

    private fun file(key: String) = File(dir, "api_$key.json")

    fun read(key: String): Entry? {
        val f = file(key)
        if (!f.exists()) return null
        return runCatching { Entry(f.readText(Charsets.UTF_8), f.lastModified()) }.getOrNull()
    }

    fun write(key: String, body: String, savedAt: Long) {
        dir.mkdirs()
        val tmp = File(dir, "api_$key.tmp")
        tmp.writeText(body, Charsets.UTF_8)
        val f = file(key)
        if (!tmp.renameTo(f)) { f.writeText(body, Charsets.UTF_8); tmp.delete() }
        f.setLastModified(savedAt)
    }
}
