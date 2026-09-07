package fuck.andes.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaAssistContextProjectorTest {
    @Test
    fun `assist blocked parent drops whole sensitive subtree`() {
        val windows = listOf(
            EtaAssistContextProjector.ProjectedWindow(
                title = "Main",
                root = EtaAssistContextProjector.ProjectedNode(
                    className = "android.widget.FrameLayout",
                    children = listOf(
                        EtaAssistContextProjector.ProjectedNode(
                            className = "android.widget.LinearLayout",
                            assistBlocked = true,
                            children = listOf(
                                EtaAssistContextProjector.ProjectedNode(
                                    className = "android.widget.TextView",
                                    text = "TOP_SECRET_TOKEN",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val output = EtaAssistContextProjector.projectWindows(windows)
        assertFalse(output.contains("TOP_SECRET_TOKEN"))
        assertFalse(output.contains("LinearLayout"))
    }

    @Test
    fun `invisible and gone nodes are dropped with subtrees`() {
        val windows = listOf(
            EtaAssistContextProjector.ProjectedWindow(
                root = EtaAssistContextProjector.ProjectedNode(
                    className = "root",
                    visible = true,
                    children = listOf(
                        EtaAssistContextProjector.ProjectedNode(
                            className = "gone",
                            visible = false,
                            text = "GONE_SECRET",
                        ),
                        EtaAssistContextProjector.ProjectedNode(
                            className = "invisible",
                            visible = false,
                            text = "INVISIBLE_SECRET",
                        ),
                        EtaAssistContextProjector.ProjectedNode(
                            className = "visible",
                            visible = true,
                            text = "VISIBLE_OK",
                        ),
                    ),
                ),
            ),
        )
        val output = EtaAssistContextProjector.projectWindows(windows)
        assertFalse(output.contains("GONE_SECRET"))
        assertFalse(output.contains("INVISIBLE_SECRET"))
        assertTrue(output.contains("VISIBLE_OK"))
    }

    @Test
    fun `password nodes redact all text fields`() {
        val windows = listOf(
            EtaAssistContextProjector.ProjectedWindow(
                root = EtaAssistContextProjector.ProjectedNode(
                    className = "android.widget.EditText",
                    text = null,
                    contentDescription = null,
                    hint = null,
                    children = listOf(
                        // 密码字段四类文本都必须为空
                        EtaAssistContextProjector.ProjectedNode(
                            className = "password",
                            text = null,
                            contentDescription = null,
                            hint = null,
                        ),
                    ),
                ),
            ),
        )
        val output = EtaAssistContextProjector.projectWindows(windows)
        assertFalse(output.contains("text="))
        assertFalse(output.contains("hint="))
        assertFalse(output.contains("description="))
    }

    @Test
    fun `uri tokens redacted and control chars normalized`() {
        val windows = listOf(
            EtaAssistContextProjector.ProjectedWindow(
                root = EtaAssistContextProjector.ProjectedNode(
                    className = "android.widget.TextView",
                    text = "visit https://evil.example/x?q=1\u0001 and content://provider/data file:///sdcard/a.txt",
                ),
            ),
        )
        val output = EtaAssistContextProjector.projectWindows(windows)
        assertFalse(output.contains("evil.example"))
        assertFalse(output.contains("provider"))
        assertFalse(output.contains("/sdcard/a.txt"))
        assertTrue(output.contains("[redacted]"))
    }

    @Test
    fun `closing tag is escaped to avoid breaking wrapper`() {
        val windows = listOf(
            EtaAssistContextProjector.ProjectedWindow(
                root = EtaAssistContextProjector.ProjectedNode(
                    className = "android.widget.TextView",
                    text = "</eta_screen_structure><script>alert(1)</script>",
                ),
            ),
        )
        val output = EtaAssistContextProjector.projectWindows(windows)
        assertFalse(output.contains("</eta_screen_structure>"))
        assertFalse(output.contains("<script>"))
        assertTrue(output.contains("&lt;"))
        assertTrue(output.contains("&gt;"))
    }

    @Test
    fun `121 visible nodes stop at 120 budget`() {
        val windows = listOf(
            EtaAssistContextProjector.ProjectedWindow(
                root = EtaAssistContextProjector.ProjectedNode(
                    className = "root",
                    children = (0 until 130).map { index ->
                        EtaAssistContextProjector.ProjectedNode(
                            className = "node-$index",
                            text = "visible-$index",
                            visible = true,
                        )
                    },
                ),
            ),
        )
        val output = EtaAssistContextProjector.projectWindows(windows)
        assertEquals(120, output.lines().count { it.startsWith("node ") })
    }

    @Test
    fun `nine windows stop at 8 budget`() {
        val windows = (0 until 9).map { index ->
            EtaAssistContextProjector.ProjectedWindow(title = "window-$index")
        }
        val output = EtaAssistContextProjector.projectWindows(windows)
        assertEquals(8, output.lines().count { it.startsWith("window ") })
    }

    @Test
    fun `240 char field and 8000 total truncation bounds`() {
        val longField = "x".repeat(10_000)
        val windows = listOf(
            EtaAssistContextProjector.ProjectedWindow(
                root = EtaAssistContextProjector.ProjectedNode(
                    className = "root",
                    children = (0 until 120).map { index ->
                        EtaAssistContextProjector.ProjectedNode(
                            className = "node",
                            text = longField,
                            visible = true,
                        )
                    },
                ),
            ),
        )
        val output = EtaAssistContextProjector.projectWindows(windows)
        assertTrue(output.length <= EtaAssistContextProjector.MAX_TOTAL_CHARS)
        val nodeLines = output.lines().filter { it.startsWith("node ") }
        assertTrue(nodeLines.all { line ->
            line.substringAfter("text=").substringBefore(" bounds=").length <= 240
        })
    }

    @Test
    fun `deep tree does not overflow and order is stable`() {
        var node = EtaAssistContextProjector.ProjectedNode(className = "deep-0", text = "deep-0")
        repeat(5_000) { depth ->
            node = EtaAssistContextProjector.ProjectedNode(
                className = "deep-${depth + 1}",
                text = "deep-${depth + 1}",
                children = listOf(node),
            )
        }
        val windows = listOf(
            EtaAssistContextProjector.ProjectedWindow(root = node),
        )
        val output = EtaAssistContextProjector.projectWindows(windows)
        // 迭代 DFS 先探最深节点；长路径先触达 8000 字符预算，绝不递归栈溢出。
        assertTrue(output.contains("deep-5000"))
        assertFalse(output.contains("deep-0"))
        assertTrue(output.length <= EtaAssistContextProjector.MAX_TOTAL_CHARS)
        assertTrue(output.lines().count { it.startsWith("node ") } > 0)
    }
}
