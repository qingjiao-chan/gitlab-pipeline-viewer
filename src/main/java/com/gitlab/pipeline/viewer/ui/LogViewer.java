package com.gitlab.pipeline.viewer.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可搜索的构建日志查看器：
 * - ANSI 颜色渲染：解析 SGR 转义（\u001B[..m），把带颜色的日志按前景色/加粗渲染，其余转义忽略
 * - 大日志不卡界面：ANSI 解析与样式文档构建在后台线程完成，EDT 上仅交换文档
 * - 关键字搜索：基于与文档模型逐字符一致的纯文本缓存匹配（坐标系即高亮器坐标系），
 * 全部匹配高亮 + 当前匹配高亮 + 上一处/下一处 + 计数；切换日志后保留关键字并重新匹配
 */
public class LogViewer extends JPanel {

    private static final int[] ANSI_FG = {30, 31, 32, 33, 34, 35, 36, 37, 90, 91, 92, 93, 94, 95, 96, 97};
    /**
     * 16 色 ANSI 调色板（普通 30-37 / 亮色 90-97），在浅色与深色主题下都尽量可读
     */
    private static final Color[] ANSI_COLORS = {
            new Color(0x1F1F1F), new Color(0xCC0000), new Color(0x008800), new Color(0x9A6700),
            new Color(0x0000CC), new Color(0xCC00CC), new Color(0x0086B3), new Color(0x808080),
            new Color(0x666666), new Color(0xFF3333), new Color(0x33AA33), new Color(0xFFC300),
            new Color(0x6666FF), new Color(0xFF33FF), new Color(0x33CCFF), new Color(0xFFFFFF)
    };

    private final JTextPane textPane = new JTextPane();
    private final JBScrollPane scrollPane;
    private final Highlighter.HighlightPainter matchPainter;
    private final Highlighter.HighlightPainter currentPainter;

    private final List<int[]> matches = new ArrayList<>();
    private int currentIndex = -1;
    /**
     * 当前搜索关键字，切换日志后自动重新匹配
     */
    private String currentSearch = "";
    /**
     * 纯文本缓存：与文档模型逐字符一致（换行就是 '\n'），搜索与高亮共用这套坐标
     */
    private String plainText = "";
    /**
     * 日志构建版本号：后台构建完成的文档只有最新一次 setLog 的才会被应用
     */
    private final AtomicInteger logGeneration = new AtomicInteger();
    /**
     * 全量高亮上限：超过后只高亮当前项（计数仍是真实值），避免海量高亮拖垮 EDT
     */
    private static final int MAX_PAINTED_MATCHES = 1000;

    public LogViewer() {
        super(new BorderLayout());
        textPane.setEditable(false);
        textPane.setFocusable(true);
        textPane.setFont(JBUI.Fonts.create("Monospaced", JBUI.scale(12)));
        textPane.setBackground(UIManager.getColor("TextArea.background"));
        // 右键菜单：复制选中文本 / 全选（不可编辑状态下默认右键菜单可能没有复制项）
        JPopupMenu logPopup = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("复制");
        copyItem.addActionListener(e -> textPane.copy());
        logPopup.add(copyItem);
        JMenuItem selectAllItem = new JMenuItem("全选");
        selectAllItem.addActionListener(e -> textPane.selectAll());
        logPopup.add(selectAllItem);
        textPane.setComponentPopupMenu(logPopup);

        Color matchColor = EditorColorsManager.getInstance().getGlobalScheme()
                .getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)
                .getBackgroundColor();
        if (matchColor == null) {
            matchColor = new Color(0xFFFFB0);
        }
        Color currentColor = new Color(0xFFA500);
        matchPainter = new DefaultHighlighter.DefaultHighlightPainter(matchColor);
        currentPainter = new DefaultHighlighter.DefaultHighlightPainter(currentColor);

        scrollPane = new JBScrollPane(textPane);
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * 设置日志文本（解析 ANSI 颜色，保留搜索匹配）。
     * 解析与文档构建在后台线程完成，EDT 上仅交换文档：
     * MB 级日志的渲染（数百 ms 到秒级）不再阻塞界面，自动刷新期间也不会卡顿。
     */
    public void setLog(String log) {
        final int gen = logGeneration.incrementAndGet();
        if (log == null || log.isEmpty()) {
            applyDocument(gen, new DefaultStyledDocument(), "");
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            StyledDocument doc = new DefaultStyledDocument();
            // 终端式渲染：\r 按回车覆盖、\e[K 按清行处理（见 appendColored），
            // 不做简单的 \r->\n 替换，否则文档模型会残留被覆盖的内容，
            // 导致搜索高亮位置与肉眼看到的文本错位。
            appendColored(doc, log);
            String plain = documentText(doc);
            ApplicationManager.getApplication().invokeLater(() -> applyDocument(gen, doc, plain));
        });
    }

    /**
     * 文档自身的纯文本（换行就是文档里的 '\n'），与高亮器共用同一坐标系
     */
    private static String documentText(StyledDocument doc) {
        try {
            return doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    /**
     * 在 EDT 上把后台构建好的文档换进组件，并按当前关键字重新匹配（不滚动，避免自动刷新打断阅读）
     */
    private void applyDocument(int gen, StyledDocument doc, String text) {
        if (gen != logGeneration.get()) {
            return; // 已有更新的 setLog，本次构建过期，直接丢弃
        }
        textPane.getHighlighter().removeAllHighlights();
        textPane.setDocument(doc);
        plainText = text;
        textPane.setCaretPosition(0);
        rematch(false);
    }

    public void setSearch(String text) {
        currentSearch = text == null ? "" : text;
        // 用户输入搜索时跳到第一个匹配，便于定位
        rematch(true);
    }

    public int getMatchCount() {
        return matches.size();
    }

    /**
     * 当前匹配序号（1 起），无匹配返回 0
     */
    public int getCurrentIndex() {
        return matches.isEmpty() ? 0 : currentIndex + 1;
    }

    public void findNext() {
        if (matches.isEmpty()) {
            return;
        }
        currentIndex = (currentIndex + 1) % matches.size();
        highlightAll();
        scrollToCurrent();
    }

    public void findPrev() {
        if (matches.isEmpty()) {
            return;
        }
        currentIndex = (currentIndex - 1 + matches.size()) % matches.size();
        highlightAll();
        scrollToCurrent();
    }

    public void scrollToTop() {
        textPane.setCaretPosition(0);
    }

    // ---------------------------------------------------------------- ANSI 解析

    /**
     * 按终端语义把日志解析进样式文档：
     * - 普通字符写入「当前行」缓冲；遇 \n 提交当前行（换行）；
     * - \r 把写入位置移回行首，后续字符从行首覆盖（进度类日志反复重画同一行）；
     * - \u001B[..m：SGR（前景色/加粗/重置），只影响后续字符样式；
     * - \u001B[..K：清行（0K=光标到行尾 / 1K=行首到光标 / 2K=整行）；
     * - 其余转义（光标移动、OSC 标题、ESC+单字符等）：整段跳过；
     * - 孤立 BEL（\u0007）：控制字符，不渲染。
     * 保证文档模型文本与终端/GitLab 界面看到的内容完全一致，
     * 搜索高亮位置不会与肉眼看到的文字错位。
     */
    private void appendColored(StyledDocument doc, String log) {
        Line line = new Line();
        int fg = -1;      // ANSI 色号索引 0-15，-1 表示默认前景色
        boolean bold = false;
        int i = 0;
        int n = log.length();
        while (i < n) {
            char ch = log.charAt(i);
            if (ch == '\r') {          // 回车：光标回到行首，后续字符覆盖本行
                line.setCursor(0);
                i++;
            } else if (ch == '\n') {   // 换行：提交当前行，另起一行
                line.flush(doc);
                i++;
            } else if (ch == '\u001B') {
                int[] state = new int[]{fg, bold ? 1 : 0};
                i += consumeEscape(log, i, state, line);
                fg = state[0];
                bold = state[1] == 1;
            } else if (ch == '\u0007') { // BEL 控制符：不渲染
                i++;
            } else {
                line.appendChar(ch, fg, bold);
                i++;
            }
        }
        // 末尾若还有未提交的「不完整行」（无 \n 结尾）才需提交；
        // 若日志以 \n 结尾，最后的换行已在上文 flush，这里不应再提交出多余的空白行
        if (line.hasContent()) {
            line.flush(doc);
        }
    }

    /**
     * 消耗从 log.charAt(pos) == ESC 开始的一个完整转义序列，返回消耗的字符数（至少 1）：
     * - CSI（ESC [ 参数… 终符）：参数/私有字节为 0x20-0x3F，终符为 0x40-0x7E；
     * 终符 m 按 SGR 解析颜色，终符 K 按清行处理，其余（光标移动等）整段丢弃。
     * - OSC（ESC ] … BEL / ESC ] … ESC \）：常用于设置终端标题，整段丢弃。
     * - ESC + 单字符（如 ESC7、ESC=）：丢弃这两个字符。
     */
    private int consumeEscape(String log, int pos, int[] state, Line line) {
        int n = log.length();
        if (pos + 1 >= n) {
            return 1;
        }
        char c = log.charAt(pos + 1);
        if (c == '[') {
            int j = pos + 2;
            while (j < n && isCsiByte(log.charAt(j))) {
                j++;
            }
            if (j >= n) {
                return n - pos; // 序列未闭合，丢弃剩余部分
            }
            char terminator = log.charAt(j);
            String params = log.substring(pos + 2, j);
            if (terminator == 'm') {
                applySgr(params, state);
            } else if (terminator == 'K') {
                applyEraseLine(params, line);
            }
            return j + 1 - pos;
        }
        if (c == ']') {
            int j = pos + 2;
            while (j < n) {
                char d = log.charAt(j);
                if (d == '\u0007') {
                    return j + 1 - pos;
                }
                if (d == '\u001B' && j + 1 < n && log.charAt(j + 1) == '\\') {
                    return j + 2 - pos;
                }
                j++;
            }
            return n - pos;
        }
        return Math.min(2, n - pos); // ESC + 单字符
    }

    /**
     * 清行序列：ESC[K / ESC[0K（光标到行尾）、ESC[1K（行首到光标）、ESC[2K（整行）
     */
    private void applyEraseLine(String params, Line line) {
        String p = params.isEmpty() ? "0" : params;
        int mode = 0;
        try {
            mode = Integer.parseInt(p);
        } catch (NumberFormatException ignored) {
        }
        switch (mode) {
            case 1:
                line.eraseFromStart();
                break;
            case 2:
                line.clearLine();
                break;
            default: // 0 / 缺省：光标到行尾
                line.eraseToEnd();
                break;
        }
    }

    /**
     * 终端式单行缓冲：支持 \r 行首覆盖、\e[K 清行，提交时按样式分段插入文档。
     * 文本与样式都保存在行缓冲里，只有整行写完才写入 JTextPane，
     * 从而保证文档模型 == 终端可见文本，搜索偏移不错位。
     */
    private static final class Line {
        /**
         * 同一样式的一段文本；用 StringBuilder 追加，避免逐字符拼接的 O(n^2)
         */
        private static final class Seg {
            final StringBuilder text = new StringBuilder();
            final int fg;
            final boolean bold;

            Seg(int fg, boolean bold) {
                this.fg = fg;
                this.bold = bold;
            }
        }

        private final List<Seg> segs = new ArrayList<>();
        /**
         * 下一个字符的写入位置（相对行首的字符偏移）
         */
        private int cursor = 0;

        /**
         * 是否还有未提交的内容（用于末尾判断是否存在不完整行）
         */
        boolean hasContent() {
            return !segs.isEmpty();
        }

        /**
         * 整行字符数
         */
        private int length() {
            int len = 0;
            for (Seg s : segs) {
                len += s.text.length();
            }
            return len;
        }

        void setCursor(int col) {
            cursor = Math.max(0, col);
        }

        void appendChar(char ch, int fg, boolean bold) {
            int len = length();
            if (cursor >= len) {
                // 追加到行尾：与上一段同样式则合并，否则新开一段
                Seg last = segs.isEmpty() ? null : segs.get(segs.size() - 1);
                if (last != null && last.fg == fg && last.bold == bold) {
                    last.text.append(ch);
                } else {
                    Seg s = new Seg(fg, bold);
                    s.text.append(ch);
                    segs.add(s);
                }
                cursor = len + 1;
            } else {
                // 覆盖写：定位 cursor 所在段，改写该字符
                int pos = 0;
                for (Seg s : segs) {
                    int end = pos + s.text.length();
                    if (cursor < end) {
                        s.text.setCharAt(cursor - pos, ch);
                        break;
                    }
                    pos = end;
                }
                cursor++;
            }
        }

        /**
         * ESC[0K / ESC[K：删除光标到行尾
         */
        void eraseToEnd() {
            if (cursor >= length()) {
                return;
            }
            truncateTo(cursor);
        }

        /**
         * ESC[1K：删除行首到光标（保留部分整体左移到行首）
         */
        void eraseFromStart() {
            if (cursor <= 0) {
                return;
            }
            int keepFrom = cursor;
            List<Seg> kept = new ArrayList<>();
            int pos = 0;
            for (Seg s : segs) {
                int end = pos + s.text.length();
                if (end <= keepFrom) {
                    // 整段丢弃
                } else if (pos >= keepFrom) {
                    kept.add(s);
                } else {
                    Seg ns = new Seg(s.fg, s.bold);
                    ns.text.append(s.text, keepFrom - pos, s.text.length());
                    kept.add(ns);
                }
                pos = end;
            }
            segs.clear();
            segs.addAll(kept);
            setCursor(0);
        }

        /**
         * ESC[2K：清空整行
         */
        void clearLine() {
            segs.clear();
            cursor = 0;
        }

        /**
         * 保留 [0, col)，删除其余部分
         */
        private void truncateTo(int col) {
            List<Seg> kept = new ArrayList<>();
            int pos = 0;
            for (Seg s : segs) {
                int end = pos + s.text.length();
                if (end <= col) {
                    kept.add(s);
                } else if (pos >= col) {
                    // 整段丢弃
                } else {
                    Seg ns = new Seg(s.fg, s.bold);
                    ns.text.append(s.text, 0, col - pos);
                    kept.add(ns);
                }
                pos = end;
            }
            segs.clear();
            segs.addAll(kept);
        }

        /**
         * 提交当前行到文档：按段插入带样式文本，行尾补一个换行，然后清空本行缓冲
         */
        void flush(StyledDocument doc) {
            for (Seg s : segs) {
                if (s.text.isEmpty()) {
                    continue;
                }
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                if (s.fg >= 0 && s.fg < ANSI_COLORS.length) {
                    StyleConstants.setForeground(attrs, ANSI_COLORS[s.fg]);
                }
                if (s.bold) {
                    StyleConstants.setBold(attrs, true);
                }
                try {
                    doc.insertString(doc.getLength(), s.text.toString(), attrs);
                } catch (BadLocationException ignored) {
                }
            }
            try {
                doc.insertString(doc.getLength(), "\n", null);
            } catch (BadLocationException ignored) {
            }
            // 关键：换行后本行已提交，必须清空缓冲并归零光标，
            // 否则下一行字符会被追加到已提交段落里，导致每次 flush 重复插入之前所有行 -> 文档二次增长 -> 大日志卡死。
            segs.clear();
            cursor = 0;
        }
    }

    /**
     * CSI 参数/私有字节范围 0x20-0x3F（数字、分号、问号等），其后紧跟终符 0x40-0x7E
     */
    private static boolean isCsiByte(char c) {
        return c >= 0x20 && c <= 0x3F;
    }

    /**
     * 解析 SGR 参数（\u001B[..m 中间的参数段），更新前景色/加粗状态
     */
    private void applySgr(String codes, int[] state) {
        if (codes.isEmpty()) { // ESC[m 等价于 ESC[0m：重置
            state[0] = -1;
            state[1] = 0;
            return;
        }
        for (String part : codes.split(";")) {
            if (part.isEmpty()) {
                continue;
            }
            int code;
            try {
                code = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                continue;
            }
            if (code == 0) {
                state[0] = -1;
                state[1] = 0;
            } else if (code == 1) {
                state[1] = 1;
            } else if (code == 22) {
                state[1] = 0;
            } else if (code == 39) {
                state[0] = -1;
            } else {
                for (int idx = 0; idx < ANSI_FG.length; idx++) {
                    if (code == ANSI_FG[idx]) {
                        state[0] = idx;
                        break;
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 搜索

    /**
     * 按 currentSearch 重新匹配并高亮。
     * <p>
     * 搜索基于 {@link #plainText}（文档模型自身的纯文本），而不是 textPane.getText()：
     * 后者会把 '\n' 展开为平台行分隔符（Windows 上是 "\r\n"），其偏移与文档/高亮器
     * 的坐标系每行错开 1 个字符 —— 第 2 行起所有高亮整体后移，肉眼看到
     * 「高亮出来的子串和输入的关键字不一样」。匹配为区分大小写的字面查找，
     * 高亮子串必然与输入逐字符一致。
     *
     * @param scrollToFirst true=滚动到第一个匹配（用户输入搜索时）；
     *                      false=保持当前阅读位置（日志刷新/自动刷新后重匹配时）
     */
    private void rematch(boolean scrollToFirst) {
        matches.clear();
        currentIndex = -1;
        textPane.getHighlighter().removeAllHighlights();
        String needle = currentSearch;
        if (!needle.isEmpty()) {
            int idx = 0;
            while ((idx = plainText.indexOf(needle, idx)) >= 0) {
                matches.add(new int[]{idx, idx + needle.length()});
                idx += needle.length();
            }
        }
        if (!matches.isEmpty()) {
            currentIndex = 0;
            highlightAll();
            if (scrollToFirst) {
                scrollToCurrent();
            }
        }
    }

    /**
     * 重画全部高亮。匹配数超过 {@link #MAX_PAINTED_MATCHES} 时只画当前项
     * （计数照常显示真实值）：短关键字在大日志里可能命中几万处，
     * 全量 addHighlight 每次按键都要上百毫秒，必然拖垮 EDT。
     */
    private void highlightAll() {
        Highlighter h = textPane.getHighlighter();
        h.removeAllHighlights();
        if (matches.isEmpty()) {
            return;
        }
        try {
            int painted = Math.min(matches.size(), MAX_PAINTED_MATCHES);
            for (int i = 0; i < painted; i++) {
                int[] m = matches.get(i);
                h.addHighlight(m[0], m[1], i == currentIndex ? currentPainter : matchPainter);
            }
            if (painted < matches.size()) {
                // 超上限被裁剪的场景：当前项可能不在前 MAX_PAINTED_MATCHES 个里，单独补画
                int[] m = matches.get(currentIndex);
                h.addHighlight(m[0], m[1], currentPainter);
            }
        } catch (BadLocationException ignored) {
        }
    }

    private void scrollToCurrent() {
        if (currentIndex < 0 || currentIndex >= matches.size()) {
            return;
        }
        int[] m = matches.get(currentIndex);
        try {
            Rectangle r = textPane.modelToView2D(m[0]).getBounds();
            scrollPane.getViewport().scrollRectToVisible(r);
            textPane.setCaretPosition(m[0]);
        } catch (Exception ignored) {
        }
    }
}
