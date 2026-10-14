package com.phonemcp.server.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiArgsTest {

    private fun args(vararg pairs: Pair<String, Any>): kotlinx.serialization.json.JsonObject =
        buildJsonObject {
            for ((k, v) in pairs) {
                when (v) {
                    is String -> put(k, v)
                    is Int -> put(k, v.toLong())
                    is Long -> put(k, v)
                    is Number -> put(k, v.toDouble())
                    is Boolean -> put(k, v)
                }
            }
        }

    @Test
    fun clickByPoint() {
        val target = UiProvider.parseClickArgs(args("x" to 100, "y" to 200))
        assertEquals(UiProvider.ClickTarget.Point(100, 200), target)
    }

    @Test
    fun clickByText() {
        val target = UiProvider.parseClickArgs(args("text" to "Show"))
        assertEquals(UiProvider.ClickTarget.Text("Show"), target)
    }

    @Test
    fun clickById() {
        val target = UiProvider.parseClickArgs(args("resourceId" to "com.x:id/y"))
        assertEquals(UiProvider.ClickTarget.Id("com.x:id/y"), target)
    }

    @Test(expected = IllegalArgumentException::class)
    fun clickRejectsNone() {
        UiProvider.parseClickArgs(buildJsonObject { })
    }

    @Test(expected = IllegalArgumentException::class)
    fun clickRejectsTwo() {
        UiProvider.parseClickArgs(args("x" to 1, "y" to 2, "text" to "hi"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun clickRejectsHalfPoint() {
        UiProvider.parseClickArgs(args("x" to 1))
    }

    @Test
    fun swipeParsesAndClamps() {
        val swipe = UiProvider.parseSwipeArgs(
            args("x1" to 1, "y1" to 2, "x2" to 3, "y2" to 4, "durationMs" to 99999),
        )
        assertEquals(UiProvider.SwipeRequest(1, 2, 3, 4, 2000), swipe)
    }

    @Test(expected = IllegalArgumentException::class)
    fun swipeRequiresCoords() {
        UiProvider.parseSwipeArgs(args("x1" to 1))
    }

    @Test
    fun typeParses() {
        val request = UiProvider.parseTypeArgs(args("text" to "hello", "selectorText" to "Name"))
        assertEquals(UiProvider.TypeRequest("hello", "Name", null), request)
    }

    @Test(expected = IllegalArgumentException::class)
    fun typeRequiresText() {
        UiProvider.parseTypeArgs(buildJsonObject { })
    }

    @Test
    fun keyParses() {
        assertEquals(UiProvider.UiKey.BACK, UiProvider.parseKeyArg(args("key" to "back")))
        assertEquals(UiProvider.UiKey.HOME, UiProvider.parseKeyArg(args("key" to "HOME")))
        assertEquals(UiProvider.UiKey.RECENTS, UiProvider.parseKeyArg(args("key" to "recents")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun keyRejectsUnknown() {
        UiProvider.parseKeyArg(args("key" to "power"))
    }

    @Test
    fun dumpDefaultsClosed() {
        assertEquals(false, UiProvider.parseDumpArgs(buildJsonObject { }))
        assertEquals(true, UiProvider.parseDumpArgs(args("includeSensitive" to true)))
    }

    @Test
    fun sensitiveDetection() {
        val pw = android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
        val plain = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT
        assertTrue(UiProvider.isSensitiveInput(pw, false))
        assertTrue(UiProvider.isSensitiveInput(plain, true))
        assertEquals(false, UiProvider.isSensitiveInput(plain, false))
    }
}
