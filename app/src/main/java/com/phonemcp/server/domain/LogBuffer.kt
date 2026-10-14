package com.phonemcp.server.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** In-process ring buffer of server events, rendered by the dashboard log tail. */
@Singleton
class LogBuffer @Inject constructor() {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun append(event: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _lines.update { (it + "[$stamp] $event").takeLast(MAX_LINES) }
    }

    companion object {
        const val MAX_LINES = 200
    }
}
