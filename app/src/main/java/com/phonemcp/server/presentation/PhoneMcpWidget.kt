package com.phonemcp.server.presentation

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.phonemcp.server.R
import com.phonemcp.server.service.McpServerService

/**
 * Home-screen widget: server status + Start/Stop. Start uses a foreground-service
 * PendingIntent (works when the app was recently visible; background-start limits
 * apply on API 34+ — the dashboard Start button is the fallback).
 */
class PhoneMcpWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            refreshAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.phonemcp.server.REFRESH_WIDGET"

        fun updateOne(context: Context, manager: AppWidgetManager, id: Int) {
            val running = McpServerService.running
            val views = RemoteViews(context.packageName, R.layout.widget_phone_mcp).apply {
                setTextViewText(
                    R.id.widget_status,
                    if (running) "MCP server: running" else "MCP server: stopped",
                )
                val start = Intent(context, McpServerService::class.java).setAction(McpServerService.ACTION_START)
                setOnClickPendingIntent(
                    R.id.widget_start,
                    PendingIntent.getForegroundService(context, 1, start, PendingIntent.FLAG_IMMUTABLE),
                )
                val stop = Intent(context, McpServerService::class.java).setAction(McpServerService.ACTION_STOP)
                setOnClickPendingIntent(
                    R.id.widget_stop,
                    PendingIntent.getService(context, 2, stop, PendingIntent.FLAG_IMMUTABLE),
                )
            }
            manager.updateAppWidget(id, views)
        }

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PhoneMcpWidget::class.java))
            for (id in ids) updateOne(context, manager, id)
        }

        /** Broadcast that refreshes every placed widget (call on service start/stop). */
        fun refreshIntent(context: Context): Intent =
            Intent(context, PhoneMcpWidget::class.java).setAction(ACTION_REFRESH)
    }
}
