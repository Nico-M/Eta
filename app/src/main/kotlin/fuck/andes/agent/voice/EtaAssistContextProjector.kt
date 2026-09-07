package fuck.andes.agent.voice

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View

/**
 * 将系统 AssistStructure 压缩为单次 run 的瞬态屏幕结构摘要。
 *
 * 遍历使用受预算控制的迭代 DFS，禁止先递归物化整棵树。
 * 纯格式化逻辑在 [projectWindows] 中，便于不依赖 framework 的单元测试。
 * 只输出结构正文，不加 XML 标签；`<eta_screen_structure>` 包装仍由 [AgentPromptBuilder] 添加。
 */
internal object EtaAssistContextProjector {
    const val MAX_WINDOWS = 8
    const val MAX_NODES = 120
    const val MAX_FIELD_CHARS = 240
    const val MAX_TOTAL_CHARS = 8_000

    /** 与 framework 无关的投影模型；测试可直接构造。 */
    data class ProjectedNode(
        val className: String = "",
        val idEntry: String? = null,
        val text: String? = null,
        val contentDescription: String? = null,
        val hint: String? = null,
        val isClickable: Boolean = false,
        val isCheckable: Boolean = false,
        val isChecked: Boolean = false,
        val isFocusable: Boolean = false,
        val isFocused: Boolean = false,
        val isSelected: Boolean = false,
        val isEnabled: Boolean = true,
        val visible: Boolean = true,
        val assistBlocked: Boolean = false,
        val left: Int = 0,
        val top: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val scrollX: Int = 0,
        val scrollY: Int = 0,
        val children: List<ProjectedNode> = emptyList(),
    )

    data class ProjectedWindow(
        val title: String? = null,
        val left: Int = 0,
        val top: Int = 0,
        val root: ProjectedNode? = null,
    )

    fun project(structure: AssistStructure?): String {
        if (structure == null) return ""
        val windowCount = minOf(structure.windowNodeCount, MAX_WINDOWS)
        val windows = ArrayList<ProjectedWindow>(windowCount)
        for (windowIndex in 0 until windowCount) {
            val window = structure.getWindowNodeAt(windowIndex) ?: continue
            windows += ProjectedWindow(
                title = window.title?.toString(),
                left = window.left,
                top = window.top,
                root = readNode(window.rootViewNode),
            )
        }
        return projectWindows(windows)
    }

    fun projectWindows(windows: List<ProjectedWindow>): String {
        val output = StringBuilder()
        var nodeCount = 0
        for ((windowIndex, window) in windows.take(MAX_WINDOWS).withIndex()) {
            if (nodeCount >= MAX_NODES || output.length >= MAX_TOTAL_CHARS) break
            val title = sanitize(window.title)
            appendLine(output, "window ${windowIndex + 1}${title?.let { ": $it" }.orEmpty()}")
            nodeCount = appendNode(
                output = output,
                root = window.root,
                windowIndex = windowIndex,
                windowLeft = window.left,
                windowTop = window.top,
                nodeCount = nodeCount,
            )
        }
        return output.toString().trimEnd()
    }

    private fun readNode(node: AssistStructure.ViewNode?): ProjectedNode? {
        if (node == null) return null
        return ProjectedNode(
            className = node.className ?: "",
            idEntry = node.idEntry?.takeIf { it.isNotBlank() },
            text = if (isPassword(node)) null else node.text?.toString(),
            contentDescription = if (isPassword(node)) null else node.contentDescription?.toString(),
            hint = if (isPassword(node)) null else node.hint,
            isClickable = node.isClickable,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isFocusable = node.isFocusable,
            isFocused = node.isFocused,
            isSelected = node.isSelected,
            isEnabled = node.isEnabled,
            visible = node.visibility == View.VISIBLE,
            assistBlocked = node.isAssistBlocked,
            left = node.left,
            top = node.top,
            width = node.width,
            height = node.height,
            scrollX = node.scrollX,
            scrollY = node.scrollY,
            children = (0 until node.childCount).mapNotNull { index ->
                readNode(node.getChildAt(index))
            },
        )
    }

    /**
     * 迭代 DFS：受预算控制的遍历，禁止递归。
     *
     * assistBlocked 节点整棵不入栈；INVISIBLE/GONE 节点及子树不输出。
     * children 逆序入栈以保持 framework 顺序。
     */
    private fun appendNode(
        output: StringBuilder,
        root: ProjectedNode?,
        windowIndex: Int,
        windowLeft: Int,
        windowTop: Int,
        nodeCount: Int,
    ): Int {
        if (root == null || nodeCount >= MAX_NODES || output.length >= MAX_TOTAL_CHARS) {
            return nodeCount
        }
        // Frame: node, path, parentScreenX, parentScreenY, parentScrollX, parentScrollY
        data class Frame(
            val node: ProjectedNode,
            val path: String,
            val parentScreenX: Int,
            val parentScreenY: Int,
            val parentScrollX: Int,
            val parentScrollY: Int,
        )

        val stack = ArrayDeque<Frame>()
        stack.addLast(
            Frame(
                node = root,
                path = "${windowIndex + 1}",
                parentScreenX = windowLeft,
                parentScreenY = windowTop,
                parentScrollX = 0,
                parentScrollY = 0,
            )
        )
        var count = nodeCount

        while (stack.isNotEmpty() && count < MAX_NODES && output.length < MAX_TOTAL_CHARS) {
            val frame = stack.removeLast()
            val node = frame.node

            // assistBlocked 整棵跳过
            if (node.assistBlocked) continue
            // INVISIBLE/GONE 节点及子树不输出
            if (!node.visible) continue

            count++
            val screenX = frame.parentScreenX + node.left - frame.parentScrollX
            val screenY = frame.parentScreenY + node.top - frame.parentScrollY

            val fields = buildList {
                sanitize(node.className)?.let { add("class=$it") }
                sanitize(node.idEntry)?.let { add("id=$it") }
                sanitize(node.text)?.let { add("text=$it") }
                sanitize(node.contentDescription)?.let { add("description=$it") }
                sanitize(node.hint)?.let { add("hint=$it") }
                if (node.isClickable) add("clickable=true")
                if (node.isCheckable) add("checkable=true")
                if (node.isChecked) add("checked=true")
                if (node.isFocusable) add("focusable=true")
                if (node.isFocused) add("focused=true")
                if (node.isSelected) add("selected=true")
                if (!node.isEnabled) add("enabled=false")
                add("bounds=[$screenX,$screenY,${screenX + node.width},${screenY + node.height}]")
            }
            if (fields.isNotEmpty()) {
                appendLine(output, "node ${frame.path} ${fields.joinToString(" ")}")
            }

            // children 逆序入栈
            for (index in node.children.indices.reversed()) {
                if (count >= MAX_NODES || output.length >= MAX_TOTAL_CHARS) break
                stack.addLast(
                    Frame(
                        node = node.children[index],
                        path = "${frame.path}.$index",
                        parentScreenX = screenX,
                        parentScreenY = screenY,
                        parentScrollX = node.scrollX,
                        parentScrollY = node.scrollY,
                    )
                )
            }
        }
        return count
    }

    private fun isPassword(node: AssistStructure.ViewNode): Boolean {
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun sanitize(value: String?): String? {
        val normalized = value
            ?.replace(Regex("[\\u0000-\\u001f\\u007f]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_FIELD_CHARS)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return normalized
            .replace(Regex("(?i)https?://\\S+|content://\\S+|file://\\S+"), "[redacted]")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .take(MAX_FIELD_CHARS)
    }

    private fun appendLine(output: StringBuilder, line: String) {
        if (output.length >= MAX_TOTAL_CHARS) return
        val remaining = MAX_TOTAL_CHARS - output.length
        output.append(line.take(remaining.coerceAtLeast(0)))
        if (output.length < MAX_TOTAL_CHARS) output.append('\n')
    }
}