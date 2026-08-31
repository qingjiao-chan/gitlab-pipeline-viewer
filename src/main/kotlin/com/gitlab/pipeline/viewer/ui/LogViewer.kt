package com.gitlab.pipeline.viewer.ui

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagLayout
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.UIManager

/**
 * 基于 IDEA 自带 ConsoleView（Run 工具窗口同款）的构建日志查看器。
 * <p>
 * 相比自绘 JTextPane + StyledDocument，ConsoleView 以「追加式打印」渲染大日志：
 * - 性能好：IDEA 运行日志同款渲染，几十 MB 日志滚动不卡，天然自动跟随滚动；
 * - 动态追加：把新增增量直接 print 到控制台即可，不重建、不打断阅读位置与鼠标选中
 *   （追加只发生在文档末尾，追加后按「跟随底部与否」还原滚动/选中，像 IDEA 控制台一样
 *   流畅追加，用户上翻阅读或选中文本时不会被拽走）；
 * - 原生外观：等宽字体、编辑器右键菜单（复制/全选）、自带 Ctrl+F 查找等由 ConsoleView 提供。
 * <p>
 * 终端语义（\r 行首覆盖、ESC[K 清行、ANSI 前景色/加粗）仍在行缓冲里解析完成后才打印，
 * 保证「控制台显示内容」与肉眼看到的一致。查找交给 ConsoleView 自带 Ctrl+F，不再自实现。
 */
class LogViewer(private val project: Project) : JPanel(BorderLayout()) {

    private val consoleView: ConsoleView = ConsoleViewImpl(project, false)

    private val editor
        get() = (consoleView as ConsoleViewImpl).editor

    /**
     * 加载占位：点击流水线/Job 后、数据返回前显示的「正在加载…」提示（spinner + 文案），
     * 覆盖在日志区之上；数据到达（[setLog]）或出错时自动隐藏。
     */
    private val loadingLabel: JBLabel = JBLabel(AnimatedIcon.Default.INSTANCE)
    private val loadingPanel: JPanel

    // ---------------------------------------------------------------- 日志构建状态

    /**
     * 日志构建版本号：后台解析完成的日志只有最新一次 setLog 的才会被应用
     */
    private val logGeneration = AtomicInteger()

    /**
     * 已打印进控制台的原始日志文本；用于判断下次 setLog 是否可增量追加
     */
    private var lastRenderedLog: String = ""

    /**
     * 增量追加的持久终端状态：末尾未完成行缓冲 + ANSI 前景色/加粗（跨刷新保留，
     * 使 \r 进度行覆盖、多行追加都能与已有内容连续渲染）
     */
    private val pendingLine = Line()
    private var pendingFg = -1
    private var pendingBold = false

    /** ANSI 前景色 -> ConsoleViewContentType 缓存（16 色 × 加粗，首次用到时注册） */
    private val colorTypes = HashMap<Int, ConsoleViewContentType>()

    init {
        // 加载占位层：spinner + 文案居中覆盖在日志区之上（默认隐藏，加载时显示）
        loadingLabel.text = " 正在加载日志…"
        val loadingRow = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(loadingLabel)
        }
        loadingPanel = object : JPanel(GridBagLayout()) {
            init {
                isOpaque = true
                background = UIManager.getColor("TextArea.background")
            }
        }.apply {
            add(loadingRow)
            isVisible = false
        }
        val layer = object : JLayeredPane() {
            override fun doLayout() {
                for (c in components) {
                    c.setBounds(0, 0, width, height)
                }
            }
        }
        layer.add(consoleView.component, JLayeredPane.DEFAULT_LAYER)
        layer.add(loadingPanel, JLayeredPane.PALETTE_LAYER)
        add(layer, BorderLayout.CENTER)
    }

    /**
     * 释放底层 ConsoleView 资源（面板 dispose 时调用）
     */
    fun dispose() {
        Disposer.dispose(consoleView)
    }

    // ---------------------------------------------------------------- 加载占位

    /**
     * 显示「正在加载…」占位（spinner + 文案，覆盖在日志区上）。
     * 数据到达（[setLog]/[appendLog]）或出错时自动隐藏。
     */
    fun showLoading(message: String) {
        loadingLabel.text = "  $message"
        loadingPanel.isVisible = true
        loadingPanel.repaint()
    }

    /**
     * 隐藏加载占位。
     */
    fun hideLoading() {
        loadingPanel.isVisible = false
    }

    // ---------------------------------------------------------------- 日志内容

    /**
     * 设置日志文本（解析 ANSI 颜色）。
     *
     * 增量优先：新日志若以已打印日志为前缀（自动刷新时多行日志逐次追加、\r 进度行覆盖），
     * 只解析增量并追加到控制台 —— 控制台不重建，滚动位置、鼠标选中都不被打断。
     */
    fun setLog(log: String?) {
        val text = log ?: ""
        // 增量优先：自动刷新时服务端返回的多是「整份日志」（200 全量，Range 常被忽略），
        // 但只要新文本是已渲染内容的超集（前缀一致），就只追加增量 —— 不重建控制台，
        // 滚动位置、鼠标选中都不会被重置，呈现"日志不断追加"的动态加载效果。
        if (lastRenderedLog.isNotEmpty() && text.startsWith(lastRenderedLog)) {
            if (text.length == lastRenderedLog.length) return    // 内容无变化：跳过，避免每次轮询都重建闪屏
            val delta = text.substring(lastRenderedLog.length)
            lastRenderedLog = text
            appendParsed(delta)
            return
        }
        // 非增长（切换 Job / 内容变短 / 首屏）：全量重建
        lastRenderedLog = text
        val gen = logGeneration.incrementAndGet()
        if (text.isEmpty()) {
            applyParsed(gen, ParsedLog(emptyList(), TerminalState()))
            return
        }
        if (text.length > PARSE_BACKGROUND_THRESHOLD) {
            // 大日志：后台线程解析，EDT 只负责打印（打印期间 loading 覆盖在日志区上）
            ApplicationManager.getApplication().executeOnPooledThread {
                val parsed = parseToLines(text, TerminalState())
                ApplicationManager.getApplication().invokeLater {
                    if (gen != logGeneration.get()) return@invokeLater // 已有更新的 setLog，本次构建过期
                    applyParsed(gen, parsed)
                }
            }
        } else {
            applyParsed(gen, parseToLines(text, TerminalState()))
        }
    }

    /**
     * 追加一段日志增量（Range 增量拉取 / 自动刷新），解析并打印到控制台。
     * 从持久状态（上一轮的末尾未完成行）继续，\r 进度行覆盖与多行追加都能正确延续。
     */
    fun appendLog(delta: String) {
        // 同步推进原始文本缓存：lastRenderedLog 保持「已喂给查看器的全部原始日志」，
        // 否则后续 setLog(累积全文) 会被前缀判断误判为增量再次追加，导致日志重复
        lastRenderedLog += delta
        appendParsed(delta)
    }

    /**
     * 解析增量并打印：以持久终端状态为起点解析 [delta]，新完成的行打印到控制台。
     * 末尾未完成行保留在 [pendingLine]，等收到 \n 成为完整行后再打印。
     *
     * 追加只发生在文档末尾，既有文本的偏移/坐标都不变。追加前先快照阅读位置
     * （跟随底部与否、鼠标选中范围、滚动偏移），追加后还原 —— 参考 IDEA 控制台：
     * 用户没在看末尾就保持视口与选中不动；用户在看末尾才跟随新日志滚动。
     */
    private fun appendParsed(delta: String) {
        val editing = editor
        val sm = editing.selectionModel
        // 追加前快照：是否有鼠标选中、选中范围、caret 位置、是否在数据流末尾、滚动偏移
        val hadSelection = sm.hasSelection()
        val selStart = if (hadSelection) sm.selectionStart else -1
        val selEnd = if (hadSelection) sm.selectionEnd else -1
        val caretOffset = editing.caretModel.offset
        // 跟随底部判定：文档末尾那行的 Y 落在可视区下沿之内，即用户停留在末尾附近
        val visible = editing.scrollingModel.getVisibleArea()
        val maxVisibleY = visible.y + visible.height
        val endY = editing.offsetToXY(editing.document.textLength).y
        val followsBottom = endY <= maxVisibleY + editing.lineHeight / 2
        val savedScroll = verticalScrollOffset()

        val st = TerminalState().apply {
            line.replaceWith(pendingLine)
            fg = pendingFg
            bold = pendingBold
        }
        appendColored(delta, st) { line -> printLine(line) }
        // 保留末尾未完成行与 ANSI 状态，供下次增量延续（\r 覆盖、多行追加）
        pendingLine.replaceWith(st.line)
        pendingFg = st.fg
        pendingBold = st.bold

        // 还原选中：追加在末尾，选中偏移依然有效；直接重建选中，绝不因移动 caret 而清除，
        // 否则用户鼠标选中的内容会在每次刷新后被取消/跳走。
        if (hadSelection) {
            sm.setSelection(selStart, selEnd)
        } else if (!followsBottom && caretOffset <= editing.document.textLength) {
            // 无选中且未跟随底部：把 caret 还原回原位（不越界），保持阅读位置
            editing.caretModel.moveToOffset(caretOffset)
        }
        // 还原滚动：未跟随底部则把视口钉回原偏移（选中/阅读位置跳出视野的问题即源于此）；
        // 跟随底部则滚到最新末尾，呈现「日志不断追加」的顺畅效果。
        restoreScroll(savedScroll, followsBottom)
    }

    /**
     * 全量替换：清空控制台，打印解析出的所有行，并重建全文缓存与持久状态。
     */
    private fun applyParsed(gen: Int, parsed: ParsedLog) {
        if (gen != logGeneration.get()) return // 已有更新的 setLog，本次构建过期
        consoleView.clear()
        pendingFg = parsed.lastState.fg
        pendingBold = parsed.lastState.bold
        pendingLine.replaceWith(parsed.lastState.line)
        for (line in parsed.lines) {
            printLine(line)
        }
        hideLoading()
    }

    /**
     * 当前可视区垂直滚动偏移（像素）；找不到滚动条时返回 -1（不还原）
     */
    private fun verticalScrollOffset(): Int {
        val sb = scrollBar() ?: return -1
        return sb.value
    }

    /**
     * 还原滚动位置：未跟随底部 → 钉回原偏移（保持阅读位置/选中不跳出视野）；
     * 跟随底部 → 滚到内容最新末尾，呈现「日志不断追加」。
     * 追加发生在同一次 EDT 事件内，还原与打印之间不会有绘制，因此不会闪屏。
     */
    private fun restoreScroll(saved: Int, followsBottom: Boolean) {
        val sb = scrollBar() ?: return
        if (followsBottom) {
            // 跟到最新末尾；模型最大值可能还没在本次布局后刷新，推迟到下一帧执行更稳妥
            ApplicationManager.getApplication().invokeLater {
                sb.value = sb.maximum - sb.model.extent
            }
        } else if (saved >= 0 && sb.value != saved) {
            sb.value = saved
        }
    }

    /**
     * 从 consoleView 组件向上找到包裹的滚动条（ConsoleViewImpl 内部编辑器在 ScrollPane 里）
     */
    private fun scrollBar(): JScrollBar? {
        var c: java.awt.Component = consoleView.component
        while (true) {
            val p = c.parent ?: return null
            if (p is JScrollPane) return p.verticalScrollBar
            c = p
        }
    }

    /**
     * 把一行内容打印到控制台（按样式段 print）。
     */
    private fun printLine(line: Line) {
        line.eachSegment { text, fg, bold ->
            consoleView.print(text, contentType(fg, bold))
        }
        consoleView.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
    }

    /**
     * 后台解析日志：返回「已完成的若干行」+「末尾未完成行状态」
     */
    private fun parseToLines(log: String, st: TerminalState): ParsedLog {
        val lines = mutableListOf<Line>()
        // 关键：flushTo 提交行后立刻清空行缓冲，这里必须深拷贝，否则收集到的行最后全是空行
        appendColored(log, st) { line -> lines.add(line.snapshot()) }
        return ParsedLog(lines, st)
    }

    private data class ParsedLog(val lines: List<Line>, val lastState: TerminalState)

    /**
     * ANSI 前景色（含加粗）映射到 ConsoleViewContentType。
     * IDEA 的 ConsoleViewContentType 是全局注册的，这里按需懒注册（16 色 × 加粗，上限 32 种）。
     */
    private fun contentType(fg: Int, bold: Boolean): ConsoleViewContentType {
        if (fg < 0 || fg >= ANSI_COLORS.size) {
            return ConsoleViewContentType.NORMAL_OUTPUT
        }
        val key = fg * 2 + (if (bold) 1 else 0)
        return colorTypes.getOrPut(key) {
            val attrs = TextAttributes().apply {
                foregroundColor = ANSI_COLORS[fg]
                fontType = if (bold) Font.BOLD else Font.PLAIN
            }
            val attrKey = TextAttributesKey.createTextAttributesKey("gitlabPipeline.ansi.$key", attrs)
            // SDK 2023.2：registerNewConsoleViewType 的注册键是 Key<?>，不是 String
            val typeKey = Key.create<ConsoleViewContentType>("gitlabPipeline.ansi.$key")
            ConsoleViewContentType.registerNewConsoleViewType(typeKey, attrKey)
        }
    }

    // ---------------------------------------------------------------- ANSI 解析

    /**
     * 按终端语义解析日志：
     * - 普通字符写入「当前行」缓冲；遇 \n 通过 [onFlush] 提交当前行（换行）；
     * - \r 把写入位置移回行首，后续字符从行首覆盖（进度类日志反复重画同一行）；
     * - \u001B[..m：SGR（前景色/加粗/重置），只影响后续字符样式；
     * - \u001B[..K：清行（0K=光标到行尾 / 1K=行首到光标 / 2K=整行）；
     * - 其余转义（光标移动、OSC 标题、ESC+单字符等）：整段跳过；
     * - 孤立 BEL（\u0007）：控制字符，不渲染。
     * 保证控制台显示内容与终端/GitLab 界面看到的内容完全一致。
     *
     * 末尾若还有未提交的「不完整行」（无 \n 结尾）不提交，保留在 [st.line] 里，
     * 供增量追加跨刷新以 \r 覆盖延续 —— 与 [appendParsed] 的语义保持一致。
     */
    private fun appendColored(log: String, st: TerminalState, onFlush: (Line) -> Unit) {
        var i = 0
        val n = log.length
        while (i < n) {
            val ch = log[i]
            if (ch == '\r') {          // 回车：光标回到行首，后续字符覆盖本行
                st.line.cursor = 0
                i++
            } else if (ch == '\n') {   // 换行：提交当前行，另起一行
                st.line.flushTo(onFlush)
                i++
            } else if (ch == '\u001B') {
                val state = intArrayOf(st.fg, if (st.bold) 1 else 0)
                i += consumeEscape(log, i, state, st.line)
                st.fg = state[0]
                st.bold = state[1] == 1
            } else if (ch == '\u0007') { // BEL 控制符：不渲染
                i++
            } else {
                st.line.appendChar(ch, st.fg, st.bold)
                i++
            }
        }
    }

    /**
     * 终端解析的持久状态：当前行缓冲 + ANSI 前景色/加粗。
     * 全量重建在后台线程用局部实例解析，EDT 上再拷入 [pendingLine] 等实例字段。
     */
    private class TerminalState {
        val line = Line()
        var fg = -1
        var bold = false
    }

    /**
     * 消耗从 log[pos] == ESC 开始的一个完整转义序列，返回消耗的字符数（至少 1）：
     * - CSI（ESC [ 参数… 终符）：参数/私有字节为 0x20-0x3F，终符为 0x40-0x7E；
     *   终符 m 按 SGR 解析颜色，终符 K 按清行处理，其余（光标移动等）整段丢弃。
     * - OSC（ESC ] … BEL / ESC ] … ESC \）：常用于设置终端标题，整段丢弃。
     * - ESC + 单字符（如 ESC7、ESC=）：丢弃这两个字符。
     */
    private fun consumeEscape(log: String, pos: Int, state: IntArray, line: Line): Int {
        val n = log.length
        if (pos + 1 >= n) return 1
        val c = log[pos + 1]
        if (c == '[') {
            var j = pos + 2
            while (j < n && isCsiByte(log[j])) {
                j++
            }
            if (j >= n) {
                return n - pos // 序列未闭合，丢弃剩余部分
            }
            val terminator = log[j]
            val params = log.substring(pos + 2, j)
            if (terminator == 'm') {
                applySgr(params, state)
            } else if (terminator == 'K') {
                applyEraseLine(params, line)
            }
            return j + 1 - pos
        }
        if (c == ']') {
            var j = pos + 2
            while (j < n) {
                val d = log[j]
                if (d == '\u0007') return j + 1 - pos
                if (d == '\u001B' && j + 1 < n && log[j + 1] == '\\') return j + 2 - pos
                j++
            }
            return n - pos
        }
        return minOf(2, n - pos) // ESC + 单字符
    }

    /**
     * 清行序列：ESC[K / ESC[0K（光标到行尾）、ESC[1K（行首到光标）、ESC[2K（整行）
     */
    private fun applyEraseLine(params: String, line: Line) {
        val p = if (params.isEmpty()) "0" else params
        var mode = 0
        try {
            mode = p.toInt()
        } catch (_: NumberFormatException) {
        }
        when (mode) {
            1 -> line.eraseFromStart()
            2 -> line.clearLine()
            else -> line.eraseToEnd() // 0 / 缺省：光标到行尾
        }
    }

    /**
     * 终端式单行缓冲：支持 \r 行首覆盖、\e[K 清行，提交时按样式分段交给 [flushTo]。
     * 文本与样式都保存在行缓冲里，只有整行写完才打印，
     * 从而保证控制台显示内容 == 终端可见文本。
     */
    private class Line {
        /**
         * 同一样式的一段文本；用 StringBuilder 追加，避免逐字符拼接的 O(n^2)
         */
        private class Seg(val fg: Int, val bold: Boolean) {
            val text: StringBuilder = StringBuilder()
        }

        private val segs: MutableList<Seg> = mutableListOf()

        /**
         * 下一个字符的写入位置（相对行首的字符偏移）
         */
        var cursor: Int = 0

        /**
         * 逐段交给消费方（打印到控制台 + 维护全文缓存）
         */
        fun eachSegment(consumer: (text: String, fg: Int, bold: Boolean) -> Unit) {
            for (s in segs) {
                if (s.text.isNotEmpty()) consumer(s.text.toString(), s.fg, s.bold)
            }
        }

        /**
         * 整行字符数
         */
        private fun length(): Int {
            var len = 0
            for (s in segs) len += s.text.length
            return len
        }

        fun appendChar(ch: Char, fg: Int, bold: Boolean) {
            val len = length()
            if (cursor >= len) {
                // 追加到行尾：与上一段同样式则合并，否则新开一段
                val last = segs.lastOrNull()
                if (last != null && last.fg == fg && last.bold == bold) {
                    last.text.append(ch)
                } else {
                    val s = Seg(fg, bold)
                    s.text.append(ch)
                    segs.add(s)
                }
                cursor = len + 1
            } else {
                // 覆盖写：定位 cursor 所在段，改写该字符
                var pos = 0
                for (s in segs) {
                    val end = pos + s.text.length
                    if (cursor < end) {
                        s.text.setCharAt(cursor - pos, ch)
                        break
                    }
                    pos = end
                }
                cursor++
            }
        }

        /**
         * ESC[0K / ESC[K：删除光标到行尾
         */
        fun eraseToEnd() {
            if (cursor >= length()) return
            truncateTo(cursor)
        }

        /**
         * ESC[1K：删除行首到光标（保留部分整体左移到行首）
         */
        fun eraseFromStart() {
            if (cursor <= 0) return
            val keepFrom = cursor
            val kept: MutableList<Seg> = mutableListOf()
            var pos = 0
            for (s in segs) {
                val end = pos + s.text.length
                when {
                    end <= keepFrom -> { /* 整段丢弃 */
                    }

                    pos >= keepFrom -> kept.add(s)
                    else -> {
                        val ns = Seg(s.fg, s.bold)
                        ns.text.append(s.text, keepFrom - pos, s.text.length)
                        kept.add(ns)
                    }
                }
                pos = end
            }
            segs.clear()
            segs.addAll(kept)
            cursor = 0
        }

        /**
         * ESC[2K：清空整行
         */
        fun clearLine() {
            segs.clear()
            cursor = 0
        }

        /**
         * 保留 [0, col)，删除其余部分
         */
        private fun truncateTo(col: Int) {
            val kept: MutableList<Seg> = mutableListOf()
            var pos = 0
            for (s in segs) {
                val end = pos + s.text.length
                when {
                    end <= col -> kept.add(s)
                    pos >= col -> { /* 整段丢弃 */
                    }

                    else -> {
                        val ns = Seg(s.fg, s.bold)
                        ns.text.append(s.text, 0, col - pos)
                        kept.add(ns)
                    }
                }
                pos = end
            }
            segs.clear()
            segs.addAll(kept)
        }

        /**
         * 用另一行的内容整体替换本行（后台解析结果拷入持久状态用）
         */
        fun replaceWith(other: Line) {
            segs.clear()
            for (s in other.segs) {
                val ns = Seg(s.fg, s.bold)
                ns.text.append(s.text)
                segs.add(ns)
            }
            cursor = other.cursor
        }

        /**
         * 深拷贝本行内容。行缓冲在 flushTo 提交后立刻被清空复用，
         * 全量解析收集行时必须在清空之前调用本方法保存一份独立拷贝。
         */
        fun snapshot(): Line {
            val copy = Line()
            copy.replaceWith(this)
            return copy
        }

        /**
         * 提交当前行（换行）：交给 onFlush 处理，然后清空本行缓冲并归零光标。
         * 关键：换行后本行已提交，必须清空缓冲，否则下一行字符会被追加到已提交段落里，
         * 导致每次提交重复插入之前所有行 -> 内容二次增长 -> 大日志卡死。
         */
        fun flushTo(onFlush: (Line) -> Unit) {
            onFlush(this)
            segs.clear()
            cursor = 0
        }
    }

    /**
     * CSI 参数/私有字节范围 0x20-0x3F（数字、分号、问号等），其后紧跟终符 0x40-0x7E
     */
    private fun isCsiByte(c: Char): Boolean = c.code in 0x20..0x3F

    /**
     * 解析 SGR 参数（\u001B[..m 中间的参数段），更新前景色/加粗状态
     */
    private fun applySgr(codes: String, state: IntArray) {
        if (codes.isEmpty()) { // ESC[m 等价于 ESC[0m：重置
            state[0] = -1
            state[1] = 0
            return
        }
        for (part in codes.split(";")) {
            if (part.isEmpty()) continue
            val code: Int = try {
                part.toInt()
            } catch (_: NumberFormatException) {
                continue
            }
            when (code) {
                0 -> {
                    state[0] = -1
                    state[1] = 0
                }

                1 -> state[1] = 1
                22 -> state[1] = 0
                39 -> state[0] = -1
                else -> {
                    for (idx in ANSI_FG.indices) {
                        if (code == ANSI_FG[idx]) {
                            state[0] = idx
                            break
                        }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * 全量解析改走后台线程的阈值（低于此值在 EDT 直接解析打印，减少一次线程切换）
         */
        private const val PARSE_BACKGROUND_THRESHOLD = 64 * 1024

        /**
         * 16 色 ANSI 前景色码（30-37 普通 + 90-97 亮色）
         */
        private val ANSI_FG = intArrayOf(30, 31, 32, 33, 34, 35, 36, 37, 90, 91, 92, 93, 94, 95, 96, 97)

        /**
         * 16 色 ANSI 调色板（普通 30-37 / 亮色 90-97），在浅色与深色主题下都尽量可读
         */
        private val ANSI_COLORS = arrayOf(
            Color(0x1F1F1F), Color(0xCC0000), Color(0x008800), Color(0x9A6700),
            Color(0x0000CC), Color(0xCC00CC), Color(0x0086B3), Color(0x808080),
            Color(0x666666), Color(0xFF3333), Color(0x33AA33), Color(0xFFC300),
            Color(0x6666FF), Color(0xFF33FF), Color(0x33CCFF), Color(0xFFFFFF)
        )
    }
}
