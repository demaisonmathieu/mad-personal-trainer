package com.mathieu.madtrainer

import com.mathieu.madtrainer.data.WebLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebLinksTest {
    @Test
    fun normalizeInput() {
        assertEquals("https://m.twitch.tv/zerator", WebLinks.normalize(" ZeratoR "))
        assertEquals("https://youtube.com", WebLinks.normalize("youtube.com"))
        assertEquals("https://www.twitch.tv/zerator", WebLinks.normalize("https://www.twitch.tv/zerator"))
        assertNull(WebLinks.normalize("deux mots"))
        assertNull(WebLinks.normalize(""))
    }

    @Test
    fun labelsAndRecent() {
        assertEquals("Twitch · zerator", WebLinks.label("https://m.twitch.tv/zerator"))
        assertEquals("youtube.com", WebLinks.label("https://www.youtube.com/watch?v=abc"))
        assertEquals(listOf("b", "a"), WebLinks.pushRecent(listOf("a", "b"), "b"))
        assertEquals(6, WebLinks.pushRecent(List(6) { "u$it" }, "x").size)
    }
}
