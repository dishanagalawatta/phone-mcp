package com.phonemcp.server.data

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.service.PhoneMcpAccessibilityService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Accessibility node-tree operations: dump serialization, hit-testing and
 * selectors. All node ownership/recycling lives here; [UiProvider] keeps only
 * tool dispatch, capture and gestures. Every method recycles what it owns —
 * only the returned node (if any) stays alive for the caller to recycle.
 */
internal object UiTree {
    fun dump(service: PhoneMcpAccessibilityService, includeSensitive: Boolean): ToolCallResult {
        val root = service.rootInActiveWindow
            ?: return ToolCallResult.error("No active window (wake the device and retry)")
        val nodes = ArrayList<JsonObject>(256)
        val owned = ArrayList<AccessibilityNodeInfo>(256)
        owned.add(root)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var truncated = false
        try {
            while (queue.isNotEmpty()) {
                if (nodes.size >= UiProvider.MAX_NODES) {
                    truncated = true
                    break
                }
                val (node, depth) = queue.removeFirst()
                if (depth > UiProvider.MAX_DEPTH) continue
                nodes.add(nodeJson(node, nodes.size, includeSensitive))
                if (depth < UiProvider.MAX_DEPTH) {
                    for (i in 0 until node.childCount) {
                        try {
                            node.getChild(i)?.let {
                                owned.add(it)
                                queue.add(it to depth + 1)
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } finally {
            for (n in owned) {
                try {
                    n.recycle()
                } catch (_: Exception) {
                }
            }
        }
        return ToolCallResult.text(
            buildJsonObject {
                put("count", nodes.size)
                put("truncated", truncated)
                put("nodes", JsonArray(nodes))
            }.toString(),
        )
    }

    fun nodeJson(node: AccessibilityNodeInfo, index: Int, includeSensitive: Boolean): JsonObject {
        val bounds = Rect()
        try {
            node.getBoundsInScreen(bounds)
        } catch (_: Exception) {
        }
        val sensitive = UiProvider.isSensitive(node)
        val text = try {
            node.text?.toString()
        } catch (_: Exception) {
            null
        }
        return buildJsonObject {
            put("i", index)
            if (text != null && (!sensitive || includeSensitive)) put("text", text.take(300))
            if (sensitive) put("redacted", true)
            try {
                node.contentDescription?.toString()?.let { put("desc", it.take(300)) }
            } catch (_: Exception) {
            }
            try {
                node.viewIdResourceName?.let { put("id", it.take(200)) }
            } catch (_: Exception) {
            }
            put(
                "bounds",
                buildJsonObject {
                    put("l", bounds.left)
                    put("t", bounds.top)
                    put("r", bounds.right)
                    put("b", bounds.bottom)
                },
            )
            put("clickable", node.isClickable)
            put("editable", node.isEditable)
            put("focused", node.isFocused)
        }
    }

    fun findAt(service: PhoneMcpAccessibilityService, x: Int, y: Int): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val owned = ArrayList<AccessibilityNodeInfo>()
        owned.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestDepth = -1
        try {
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(root to 0)
            val rect = Rect()
            var visited = 0
            while (queue.isNotEmpty() && visited < UiProvider.MAX_NODES) {
                val (node, depth) = queue.removeFirst()
                visited++
                try {
                    node.getBoundsInScreen(rect)
                } catch (_: Exception) {
                    continue
                }
                if (rect.contains(x, y) && depth >= bestDepth &&
                    (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                ) {
                    best = node
                    bestDepth = depth
                }
                for (i in 0 until node.childCount) {
                    try {
                        node.getChild(i)?.let {
                            owned.add(it)
                            queue.add(it to depth + 1)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            return best
        } catch (_: Exception) {
            try {
                best?.recycle()
            } catch (_: Exception) {
            }
            return null
        } finally {
            for (n in owned) {
                if (n !== best) {
                    try {
                        n.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun findByText(service: PhoneMcpAccessibilityService, text: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return try {
            root.findAccessibilityNodeInfosByText(text).firstOrNull { it.isVisibleToUser }
                ?: root.findAccessibilityNodeInfosByText(text).firstOrNull()
        } catch (_: Exception) {
            null
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    fun findById(service: PhoneMcpAccessibilityService, id: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return try {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
        } catch (_: Exception) {
            null
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    fun describeNode(node: AccessibilityNodeInfo): String {        val label = try {
            node.text?.toString()?.take(40) ?: node.contentDescription?.toString()?.take(40)
        } catch (_: Exception) {
            null
        }
        return (label ?: node.viewIdResourceName?.takeLast(40) ?: "element").toString()
    }
}
