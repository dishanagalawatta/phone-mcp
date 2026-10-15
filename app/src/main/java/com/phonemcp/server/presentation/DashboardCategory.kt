package com.phonemcp.server.presentation

/**
 * High-level category filters for the dashboard.
 * Defaults to ALL to ensure complete hierarchy visibility for both users and automated tests.
 */
enum class DashboardCategory(val title: String) {
    ALL("Overview"),
    SERVER("Server & Auth"),
    GUARDRAILS("Guardrails"),
    NETWORK("Network"),
    CONSOLE("Console"),
    SETTINGS("Settings"),
}
