package com.luminara.player

import com.luminara.player.library.KoreanSearch
import org.junit.Assert.*
import org.junit.Test

class KoreanSearchTest {
    @Test fun `hangul initial search matches`() { assertTrue(KoreanSearch.matches("ㅇㅇ", "아이유")) }
    @Test fun `case and spaces are ignored`() { assertTrue(KoreanSearch.matches("love wins", "Love Wins All")); assertTrue(KoreanSearch.matches("lovewins", "Love Wins All")) }
    @Test fun `searches artist and album`() { assertTrue(KoreanSearch.matches("태연", "곡", "태연", "앨범")) }
}
