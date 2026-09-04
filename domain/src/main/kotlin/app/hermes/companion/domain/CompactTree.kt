package app.hermes.companion.domain

import app.hermes.companion.model.ScreenSafeArea
import app.hermes.companion.model.SnapshotNode

/** Compact a11y snapshot. Refs are session-local until the next snapshot. */
object CompactTree {
    const val MAX_NODES = 80

    fun ref(index: Int): String = "e${index + 1}"

    fun clip(nodes: List<SnapshotNode>): List<SnapshotNode> = nodes.take(MAX_NODES)

    fun json(
        nodes: List<SnapshotNode>,
        app: String = "",
        activity: String = "",
        width: Int = 0,
        height: Int = 0,
        safeArea: ScreenSafeArea = ScreenSafeArea(),
    ): String {
        val clipped = clip(nodes)
        val rows = clipped.joinToString(",") { node ->
            val bounds = node.bounds.take(4).joinToString(",")
            """{"ref":${q(node.ref)},"role":${q(node.role)},"text":${q(node.text)},"clickable":${node.clickable},"bounds":[$bounds]}"""
        }
        val safeJson = if (safeArea.top > 0 || safeArea.bottom > 0 || safeArea.left > 0 || safeArea.right > 0) {
            ""","safe_area":{"top":${safeArea.top},"bottom":${safeArea.bottom},"left":${safeArea.left},"right":${safeArea.right}}"""
        } else ""
        return """{"app":${q(app)},"activity":${q(activity)},"size":{"w":$width,"h":$height}$safeJson,"nodes":[$rows]}"""
    }

    fun refsOf(nodes: List<SnapshotNode>): Set<String> = clip(nodes).map { it.ref }.toSet()

    private fun q(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }
}
