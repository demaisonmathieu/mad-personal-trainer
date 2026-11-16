package com.mathieu.madtrainer

import com.mathieu.madtrainer.media.SpotifyLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyLinksTest {
    @Test
    fun normalizesShareLinks() {
        assertEquals(
            "spotify:playlist:37i9dQZF1DX76Wlfdnj7AP",
            SpotifyLinks.normalize("https://open.spotify.com/playlist/37i9dQZF1DX76Wlfdnj7AP?si=abc123"),
        )
        assertEquals(
            "spotify:playlist:37i9dQZF1DX76Wlfdnj7AP",
            SpotifyLinks.normalize("  https://open.spotify.com/intl-fr/playlist/37i9dQZF1DX76Wlfdnj7AP  "),
        )
        assertEquals("spotify:album:4aawyAB9vmqN3uQ7FjRGTy", SpotifyLinks.normalize("open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy"))
        assertEquals("spotify:playlist:abc", SpotifyLinks.normalize("spotify:playlist:abc"))
    }

    @Test
    fun rejectsOtherLinks() {
        assertNull(SpotifyLinks.normalize(""))
        assertNull(SpotifyLinks.normalize("https://www.deezer.com/fr/playlist/123"))
        assertNull(SpotifyLinks.normalize("https://open.spotify.com/"))
        assertNull(SpotifyLinks.normalize("spotify:user"))
    }

    @Test
    fun webUrl() {
        assertEquals("https://open.spotify.com/playlist/abc", SpotifyLinks.webUrl("spotify:playlist:abc"))
    }
}
