package com.blankgrid.aione.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把成果正文(Markdown)导出成 Word / PowerPoint / PDF。
 *
 * <p>为什么不用第三方转换器:整条生产线是单机部署的,不能指望机器上装了 Office 或
 * LibreOffice。POI 与 OpenPDF 都是纯 Java,打成一个 jar 就能跑。
 *
 * <p>渲染策略是「够用就好」:模型写的是结构化 Markdown(标题、要点、表格、代码块),
 * 按块渲染就足够形成可交付的文档,不做像素级复刻。
 */
@Service
public class DocExportService {

    private static final Logger log = LoggerFactory.getLogger(DocExportService.class);

    public static final List<String> FORMATS = List.of("md", "docx", "pptx", "pdf");

    /** 正文字体:Windows/WPS 都有;Linux 上缺字体时阅读器会自行替换,不会变豆腐块 */
    private static final String BODY_FONT = "宋体";
    private static final String HEAD_FONT = "微软雅黑";

    private final SettingsService settings;

    public DocExportService(SettingsService settings) {
        this.settings = settings;
    }

    public static boolean supports(String format) {
        return format != null && FORMATS.contains(format.toLowerCase(Locale.ROOT));
    }

    public static String extension(String format) {
        String f = format == null ? "md" : format.toLowerCase(Locale.ROOT);
        return supports(f) ? f : "md";
    }

    /** 统一入口:格式不认识就退回 Markdown 原文,不抛异常 —— 导出失败比导出一份 md 更糟。 */
    public byte[] render(String title, String markdown, String format) throws IOException {
        String f = format == null ? "md" : format.toLowerCase(Locale.ROOT);
        String md = markdown == null ? "" : markdown;
        switch (f) {
            case "docx": return toDocx(title, md);
            case "pptx": return toPptx(title, md);
            case "pdf": return toPdf(title, md);
            default: return md.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // ==================== Word ====================

    public byte[] toDocx(String title, String markdown) throws IOException {
        List<Block> blocks = parse(markdown);
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 封面标题
            XWPFParagraph tp = doc.createParagraph();
            tp.setSpacingAfter(80);
            XWPFRun tr = tp.createRun();
            styled(tr, HEAD_FONT, 22, true);
            tr.setText(nz(title));

            XWPFParagraph sub = doc.createParagraph();
            sub.setSpacingAfter(240);
            XWPFRun sr = sub.createRun();
            styled(sr, BODY_FONT, 9, false);
            sr.setColor("888888");
            sr.setText("由空白格 AI 公司自动生成 · " + LocalDate.now());

            for (Block b : blocks) {
                switch (b.kind) {
                    case HEADING -> {
                        XWPFParagraph p = doc.createParagraph();
                        p.setSpacingBefore(b.level <= 2 ? 220 : 160);
                        p.setSpacingAfter(80);
                        XWPFRun r = p.createRun();
                        styled(r, HEAD_FONT, b.level == 1 ? 17 : b.level == 2 ? 14 : 12, true);
                        r.setText(b.text);
                    }
                    case BULLET -> {
                        XWPFParagraph p = doc.createParagraph();
                        p.setIndentationLeft(360 + b.level * 300);
                        p.setSpacingAfter(40);
                        XWPFRun r = p.createRun();
                        styled(r, BODY_FONT, 10, false);
                        r.setText("· " + b.text);
                    }
                    case NUMBERED -> {
                        XWPFParagraph p = doc.createParagraph();
                        p.setIndentationLeft(360 + b.level * 300);
                        p.setSpacingAfter(40);
                        XWPFRun r = p.createRun();
                        styled(r, BODY_FONT, 10, false);
                        r.setText(b.index + ". " + b.text);
                    }
                    case CODE -> {
                        for (String line : b.text.split("\n", -1)) {
                            XWPFParagraph p = doc.createParagraph();
                            p.setIndentationLeft(360);
                            p.setSpacingAfter(0);
                            XWPFRun r = p.createRun();
                            styled(r, "Consolas", 9, false);
                            r.setColor("444444");
                            r.setText(line.isEmpty() ? " " : line);
                        }
                        spacer(doc, 100);
                    }
                    case QUOTE -> {
                        XWPFParagraph p = doc.createParagraph();
                        p.setIndentationLeft(360);
                        p.setSpacingAfter(60);
                        XWPFRun r = p.createRun();
                        styled(r, BODY_FONT, 10, false);
                        r.setColor("666666");
                        r.setItalic(true);
                        r.setText(b.text);
                    }
                    case TABLE -> addDocxTable(doc, b.rows);
                    case HR -> {
                        XWPFParagraph p = doc.createParagraph();
                        p.setSpacingAfter(120);
                        XWPFRun r = p.createRun();
                        styled(r, BODY_FONT, 8, false);
                        r.setColor("CCCCCC");
                        r.setText("────────────────────────");
                    }
                    default -> {
                        XWPFParagraph p = doc.createParagraph();
                        p.setSpacingAfter(100);
                        XWPFRun r = p.createRun();
                        styled(r, BODY_FONT, 10, false);
                        r.setText(b.text);
                    }
                }
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    private void addDocxTable(XWPFDocument doc, List<List<String>> rows) {
        if (rows.isEmpty()) return;
        int cols = rows.stream().mapToInt(List::size).max().orElse(1);
        org.apache.poi.xwpf.usermodel.XWPFTable table = doc.createTable(rows.size(), cols);
        table.setWidth("100%");
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < cols; c++) {
                String text = c < row.size() ? row.get(c) : "";
                var cell = table.getRow(r).getCell(c);
                cell.removeParagraph(0);
                XWPFParagraph p = cell.addParagraph();
                XWPFRun run = p.createRun();
                styled(run, r == 0 ? HEAD_FONT : BODY_FONT, 9, r == 0);
                run.setText(text);
            }
        }
    }

    private void styled(XWPFRun run, String font, int sizePt, boolean bold) {
        run.setFontFamily(font);
        run.setFontFamily(font, XWPFRun.FontCharRange.eastAsia);
        run.setFontSize(sizePt);
        run.setBold(bold);
    }

    private void spacer(XWPFDocument doc, int after) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(after);
        p.createRun().setText("");
    }

    // ==================== PowerPoint ====================

    public byte[] toPptx(String title, String markdown) throws IOException {
        List<Block> blocks = parse(markdown);
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ppt.setPageSize(new Dimension(1280, 720));

            // 封面
            XSLFSlide cover = ppt.createSlide();
            addBox(cover, 80, 240, 1120, 120, nz(title), 40, true, new Color(0x1A, 0x1D, 0x22));
            addBox(cover, 80, 370, 1120, 60, "由空白格 AI 公司自动生成 · " + LocalDate.now(),
                    16, false, new Color(0x77, 0x7D, 0x88));
            addBox(cover, 80, 470, 1120, 40, "目标 → 拆解 → 执行 → 评审 → 归档", 14, false,
                    new Color(0x3D, 0x9E, 0x8A));

            // 按一级/二级标题分页
            List<Slide> slides = new ArrayList<>();
            Slide current = null;
            for (Block b : blocks) {
                if (b.kind == Kind.HEADING && b.level <= 2) {
                    current = new Slide(b.text);
                    slides.add(current);
                } else if (b.kind == Kind.TABLE) {
                    if (current == null) {
                        current = new Slide("数据");
                        slides.add(current);
                    }
                    current.lines.add(tableToLine(b.rows));
                } else if (b.kind == Kind.BULLET || b.kind == Kind.NUMBERED
                        || b.kind == Kind.PARAGRAPH || b.kind == Kind.QUOTE) {
                    if (current == null) {
                        current = new Slide(nz(title));
                        slides.add(current);
                    }
                    String prefix = b.kind == Kind.BULLET ? "· " : b.kind == Kind.NUMBERED ? b.index + ". " : "";
                    current.lines.add(prefix + b.text);
                }
            }
            if (slides.isEmpty()) {
                Slide s = new Slide(nz(title));
                s.lines.add("（正文为空）");
                slides.add(s);
            }

            int perSlide = 6;
            for (Slide s : slides) {
                List<List<String>> chunks = chunk(s.lines, perSlide);
                for (int i = 0; i < chunks.size(); i++) {
                    XSLFSlide slide = ppt.createSlide();
                    String heading = i == 0 ? s.title : s.title + "（续）";
                    addBox(slide, 70, 50, 1140, 70, heading, 26, true, new Color(0x1A, 0x1D, 0x22));
                    addBox(slide, 70, 130, 1140, 6, "", 6, false, new Color(0x3D, 0x9E, 0x8A));
                    XSLFTextBox body = slide.createTextBox();
                    body.setAnchor(new Rectangle(70, 165, 1140, 500));
                    body.setWordWrap(true);
                    List<String> lines = chunks.get(i);
                    int size = lines.size() > 4 ? 16 : 18;
                    long chars = lines.stream().mapToInt(String::length).sum();
                    if (chars > 460) size = 13;
                    for (String line : lines) {
                        XSLFTextParagraph p = body.addNewTextParagraph();
                        p.setBullet(false);
                        p.setSpaceAfter(10.0);
                        XSLFTextRun r = p.addNewTextRun();
                        r.setText(line);
                        r.setFontSize((double) size);
                        r.setFontFamily(HEAD_FONT);
                        r.setFontColor(new Color(0x2B, 0x2F, 0x36));
                    }
                }
            }
            ppt.write(out);
            return out.toByteArray();
        }
    }

    private void addBox(XSLFSlide slide, int x, int y, int w, int h, String text,
                        int size, boolean bold, Color color) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle(x, y, w, h));
        box.setWordWrap(true);
        // clearText() 会把段落清空,之后必须自己补一个,直接 get(0) 会越界
        box.clearText();
        XSLFTextParagraph p = box.addNewTextParagraph();
        p.setTextAlign(TextParagraph.TextAlign.LEFT);
        XSLFTextRun r = p.addNewTextRun();
        r.setText(text);
        r.setFontSize((double) size);
        r.setFontFamily(HEAD_FONT);
        r.setBold(bold);
        r.setFontColor(color);
    }

    private List<List<String>> chunk(List<String> lines, int size) {
        List<List<String>> out = new ArrayList<>();
        if (lines.isEmpty()) {
            out.add(List.of("（本节无正文）"));
            return out;
        }
        for (int i = 0; i < lines.size(); i += size) {
            out.add(new ArrayList<>(lines.subList(i, Math.min(lines.size(), i + size))));
        }
        return out;
    }

    private static final class Slide {
        final String title;
        final List<String> lines = new ArrayList<>();
        Slide(String title) { this.title = title; }
    }

    private String tableToLine(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : rows) sb.append(String.join(" | ", row)).append("    ");
        return sb.toString().trim();
    }

    // ==================== PDF ====================

    public byte[] toPdf(String title, String markdown) throws IOException {
        List<Block> blocks = parse(markdown);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String fontPath = resolveCjkFont();
        Document doc = new Document(PageSize.A4, 56, 56, 56, 56);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont bf = loadBaseFont(fontPath);
            Font h1 = new Font(bf, 18, Font.BOLD, new Color(0x1A, 0x1D, 0x22));
            Font h2 = new Font(bf, 14, Font.BOLD, new Color(0x1A, 0x1D, 0x22));
            Font h3 = new Font(bf, 12, Font.BOLD, new Color(0x33, 0x38, 0x40));
            Font body = new Font(bf, 10.5f, Font.NORMAL, new Color(0x22, 0x22, 0x22));
            Font small = new Font(bf, 9, Font.NORMAL, new Color(0x55, 0x55, 0x55));

            Paragraph t = new Paragraph(nz(title), new Font(bf, 20, Font.BOLD, new Color(0x1A, 0x1D, 0x22)));
            t.setSpacingAfter(6);
            doc.add(t);
            Paragraph meta = new Paragraph("由空白格 AI 公司自动生成 · " + LocalDate.now(), small);
            meta.setSpacingAfter(18);
            doc.add(meta);

            for (Block b : blocks) {
                switch (b.kind) {
                    case HEADING -> {
                        Paragraph p = new Paragraph(b.text, b.level == 1 ? h1 : b.level == 2 ? h2 : h3);
                        p.setSpacingBefore(b.level <= 2 ? 14 : 10);
                        p.setSpacingAfter(6);
                        doc.add(p);
                    }
                    case BULLET -> doc.add(indent(new Paragraph("· " + b.text, body), b.level));
                    case NUMBERED -> doc.add(indent(new Paragraph(b.index + ". " + b.text, body), b.level));
                    case QUOTE -> {
                        Paragraph p = new Paragraph(b.text, new Font(bf, 10, Font.ITALIC, new Color(0x66, 0x66, 0x66)));
                        p.setIndentationLeft(18);
                        p.setSpacingAfter(8);
                        doc.add(p);
                    }
                    case CODE -> {
                        for (String line : b.text.split("\n", -1)) {
                            Paragraph p = new Paragraph(line.isEmpty() ? " " : line, new Font(bf, 8.5f, Font.NORMAL, new Color(0x44, 0x44, 0x44)));
                            p.setIndentationLeft(18);
                            p.setSpacingAfter(0);
                            doc.add(p);
                        }
                    }
                    case TABLE -> doc.add(pdfTable(b.rows, bf));
                    case HR -> {
                        Paragraph p = new Paragraph(" ", small);
                        p.setSpacingAfter(10);
                        doc.add(p);
                    }
                    default -> {
                        Paragraph p = new Paragraph(b.text, body);
                        p.setSpacingAfter(8);
                        p.setLeading(17);
                        doc.add(p);
                    }
                }
            }
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("PDF 生成失败,退回纯文本:{}", e.getMessage());
            try { if (doc.isOpen()) doc.close(); } catch (Exception ignored) {}
            throw new IOException("PDF 生成失败:" + e.getMessage(), e);
        }
    }

    private Paragraph indent(Paragraph p, int level) {
        p.setIndentationLeft(16 + level * 14);
        p.setSpacingAfter(4);
        p.setLeading(16);
        return p;
    }

    private PdfPTable pdfTable(List<List<String>> rows, BaseFont bf) {
        int cols = rows.stream().mapToInt(List::size).max().orElse(1);
        PdfPTable table = new PdfPTable(cols);
        table.setWidthPercentage(100);
        table.setSpacingBefore(6);
        table.setSpacingAfter(10);
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < cols; c++) {
                String text = c < row.size() ? row.get(c) : "";
                Font f = new Font(bf, 9, r == 0 ? Font.BOLD : Font.NORMAL, new Color(0x22, 0x22, 0x22));
                PdfPCell cell = new PdfPCell(new Phrase(text, f));
                cell.setPadding(5);
                cell.setBorderColor(new Color(0xDD, 0xDD, 0xDD));
                if (r == 0) cell.setBackgroundColor(new Color(0xF2, 0xF4, 0xF3));
                table.addCell(cell);
            }
        }
        return table;
    }

    private BaseFont loadBaseFont(String path) throws Exception {
        if (path != null) {
            try {
                return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception e) {
                log.warn("指定的 PDF 字体不可用({}),改用内置西文字体 —— 中文会显示为方块:{}", path, e.getMessage());
            }
        }
        // 没有可嵌入的中文字体时,至少不要 500:用 Helvetica,英文数字仍可读
        return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
    }

    /** 找一个能嵌入的中文字体:配置 > 常见系统路径 */
    String resolveCjkFont() {
        String configured = settings.get().pdfFontPath;
        if (configured != null && !configured.isBlank() && Files.isReadable(Path.of(configured))) {
            return configured;
        }
        String[] candidates = {
                "C:/Windows/Fonts/msyh.ttc,0",
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/simsun.ttc,0",
                "C:/Windows/Fonts/Deng.ttf",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc,0",
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc,0",
                "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
                "/System/Library/Fonts/PingFang.ttc,0",
                "/System/Library/Fonts/STHeiti Light.ttc,0"
        };
        for (String c : candidates) {
            String file = c.contains(",") ? c.substring(0, c.indexOf(',')) : c;
            if (Files.isReadable(Path.of(file))) return c;
        }
        return null;
    }

    // ==================== Markdown 解析 ====================

    /** 供测试用:把 Markdown 拆成块。包内可见即可,不对外暴露渲染细节。 */
    enum Kind { HEADING, BULLET, NUMBERED, PARAGRAPH, CODE, QUOTE, TABLE, HR }

    static final class Block {
        Kind kind;
        int level;
        int index;
        String text = "";
        List<List<String>> rows = new ArrayList<>();

        static Block of(Kind kind, String text) {
            Block b = new Block();
            b.kind = kind;
            b.text = text;
            return b;
        }
    }

    /** 面向「模型写出来的 Markdown」的极简解析:标题/要点/数字列表/表格/代码块/引用/分隔线/段落。 */
    List<Block> parse(String md) {
        List<Block> out = new ArrayList<>();
        if (md == null || md.isBlank()) return out;
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean inCode = false;
        StringBuilder code = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.stripTrailing();
            String trimmed = line.strip();

            if (trimmed.startsWith("```")) {
                if (inCode) {
                    Block b = Block.of(Kind.CODE, code.toString().stripTrailing());
                    out.add(b);
                    code.setLength(0);
                    inCode = false;
                } else {
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                code.append(line).append("\n");
                continue;
            }

            if (trimmed.isEmpty()) continue;

            if (trimmed.matches("^[-*_]{3,}$")) {
                out.add(Block.of(Kind.HR, ""));
                continue;
            }

            int level = headingLevel(trimmed);
            if (level > 0) {
                Block b = Block.of(Kind.HEADING, inline(trimmed.substring(level).trim()));
                b.level = level;
                out.add(b);
                continue;
            }

            if (trimmed.startsWith(">")) {
                out.add(Block.of(Kind.QUOTE, inline(trimmed.replaceFirst("^>\\s?", ""))));
                continue;
            }

            // 表格:本行是 | ... |,下一行是 |---|---|
            if (trimmed.startsWith("|") && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
                List<List<String>> rows = new ArrayList<>();
                rows.add(splitRow(trimmed));
                int j = i + 2;
                while (j < lines.length && lines[j].strip().startsWith("|")) {
                    rows.add(splitRow(lines[j].strip()));
                    j++;
                }
                Block b = Block.of(Kind.TABLE, "");
                b.rows = rows;
                out.add(b);
                i = j - 1;
                continue;
            }

            String bullet = bulletText(trimmed);
            if (bullet != null) {
                Block b = Block.of(Kind.BULLET, inline(bullet));
                b.level = indentLevel(raw);
                out.add(b);
                continue;
            }

            String numbered = numberedText(trimmed);
            if (numbered != null) {
                Block b = Block.of(Kind.NUMBERED, inline(numbered));
                b.level = indentLevel(raw);
                b.index = numberedIndex(trimmed);
                out.add(b);
                continue;
            }

            out.add(Block.of(Kind.PARAGRAPH, inline(trimmed)));
        }
        if (inCode && !code.isEmpty()) out.add(Block.of(Kind.CODE, code.toString().stripTrailing()));
        return out;
    }

    private static boolean isTableSeparator(String line) {
        String s = line.strip();
        return s.startsWith("|") && s.replaceAll("[|:\\-\\s]", "").isEmpty() && s.contains("-");
    }

    private static List<String> splitRow(String line) {
        String s = line.strip();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String c : s.split("\\|", -1)) cells.add(inlineStatic(c.trim()));
        return cells;
    }

    private static int headingLevel(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') n++;
        if (n >= 1 && n <= 6 && n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) return n;
        return 0;
    }

    private static String bulletText(String line) {
        if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) return line.substring(2);
        // 全角/圆点符号也当要点
        if (line.startsWith("·") || line.startsWith("•")) return line.substring(1).strip();
        return null;
    }

    private static String numberedText(String line) {
        int i = 0;
        while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
        if (i == 0 || i >= line.length()) return null;
        char c = line.charAt(i);
        if ((c == '.' || c == '、' || c == ')') && i + 1 < line.length()) return line.substring(i + 1).strip();
        return null;
    }

    private static int numberedIndex(String line) {
        int i = 0;
        while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
        try { return Integer.parseInt(line.substring(0, i)); } catch (NumberFormatException e) { return 1; }
    }

    private static int indentLevel(String raw) {
        int spaces = 0;
        for (char c : raw.toCharArray()) {
            if (c == ' ') spaces++;
            else if (c == '\t') spaces += 4;
            else break;
        }
        return Math.min(3, spaces / 2);
    }

    /** 去掉行内 Markdown 标记:粗体、斜体、行内代码、链接、图片 */
    private static String inline(String s) {
        return inlineStatic(s);
    }

    private static String inlineStatic(String s) {
        if (s == null) return "";
        String t = s;
        t = t.replaceAll("!\\[([^\\]]*)]\\([^)]*\\)", "$1");
        t = t.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1");
        t = t.replaceAll("`{1,3}([^`]*)`{1,3}", "$1");
        t = t.replaceAll("\\*\\*\\*([^*]+)\\*\\*\\*", "$1");
        t = t.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        t = t.replaceAll("__([^_]+)__", "$1");
        t = t.replaceAll("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])", "$1");
        t = t.replaceAll("<[^>]+>", "");
        return t.trim();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** 当前是否找到了可用的中文字体(PDF 用) */
    public boolean cjkFontAvailable() {
        return resolveCjkFont() != null;
    }
}
