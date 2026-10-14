package com.phonemcp.server.service

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Test

class TileStateTest {

    @Test
    fun runningMapsActive() {
        assertEquals(Tile.STATE_ACTIVE, McpServerTile.tileState(true))
    }

    @Test
    fun stoppedMapsInactive() {
        assertEquals(Tile.STATE_INACTIVE, McpServerTile.tileState(false))
    }
}
