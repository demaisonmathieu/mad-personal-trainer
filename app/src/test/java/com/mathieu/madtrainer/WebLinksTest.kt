package com.mathieu.madtrainer

import com.mathieu.madtrainer.data.VideoEmbed
import com.mathieu.madtrainer.data.WebLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun recognizesEmbeddableVideos() {
        assertEquals(VideoEmbed.TwitchChannel("zerator"), WebLinks.embedOf("https://m.twitch.tv/ZeratoR"))
        assertEquals(VideoEmbed.TwitchChannel("zerator"), WebLinks.embedOf("https://www.twitch.tv/zerator?sr=a"))
        assertEquals(VideoEmbed.TwitchVod("2312345678"), WebLinks.embedOf("https://www.twitch.tv/videos/2312345678"))
        assertNull(WebLinks.embedOf("https://www.twitch.tv/directory"))
        assertNull(WebLinks.embedOf("https://www.twitch.tv/zerator/clips"))
        assertEquals(VideoEmbed.YouTube("dQw4w9WgXcQ"), WebLinks.embedOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))
        assertEquals(VideoEmbed.YouTube("dQw4w9WgXcQ"), WebLinks.embedOf("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
        assertEquals(VideoEmbed.YouTube("dQw4w9WgXcQ"), WebLinks.embedOf("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals(VideoEmbed.YouTube("dQw4w9WgXcQ"), WebLinks.embedOf("https://www.youtube.com/live/dQw4w9WgXcQ"))
        assertNull(WebLinks.embedOf("https://www.youtube.com/@chaine"))
        assertNull(WebLinks.embedOf("https://lequipe.fr/direct"))
    }

    @Test
    fun playerPageDeclaresParentAndControls() {
        val twitch = WebLinks.playerHtml(VideoEmbed.TwitchChannel("zerator"))
        assertTrue(twitch, "channel:\"zerator\"" in twitch)
        assertTrue(twitch, "parent:[\"${WebLinks.EMBED_HOST}\"]" in twitch)
        assertTrue("function madPause" in twitch)
        val vod = WebLinks.playerHtml(VideoEmbed.TwitchVod("123"))
        assertTrue(vod, "video:\"v123\"" in vod)
        val yt = WebLinks.playerHtml(VideoEmbed.YouTube("dQw4w9WgXcQ"))
        assertTrue(yt, "videoId:\"dQw4w9WgXcQ\"" in yt)
        assertTrue("function madPlay" in yt)
    }
}
