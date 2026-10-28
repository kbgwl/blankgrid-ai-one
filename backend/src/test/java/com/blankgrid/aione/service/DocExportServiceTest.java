package com.blankgrid.aione.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多格式导出:三种格式都要出真正的文件,不是把 Markdown 改个后缀。
 */
class DocExportServiceTest {

    @TempDir
    Path dir;

    private static final String MD = """
            # 品牌视觉方案

            这是给「空白格」做的视觉方向说明。

            ## 定位

            - 冷静、克制、留白
            - 以墨绿为主色,金色只做点缀

            ## 色板

            | 颜色 | 用途 |
            |---|---|
            | 墨绿 | 主色 |
            | 米白 | 背景 |

            ## 交付清单

            1. 首页版式
            2. 图标规范

            ```css
            --bg: #0e1116;
            ```

            > 结论:先做官网首页,再扩展到产品页。

            ---
            """;

    private DocExportService svc() {
        return new DocExportService(new SettingsService(dir.toString()));
    }

    @Test
    @DisplayName("Word 导出:是 docx(PK 开头),内容真的写进去了")
    void docxMagic() throws Exception {
        byte[] b = svc().toDocx("测试项目", MD);
        assertTrue(b.length > 2000, "docx 太小说明没写进内容");
        assertEquals('P', (char) b[0]);
        assertEquals('K', (char) b[1]);
    }

    @Test
    @DisplayName("PPT 导出:是 pptx,且至少三页(封面 + 章节)")
    void pptxMagic() throws Exception {
        byte[] b = svc().toPptx("测试项目", MD);
        assertEquals('P', (char) b[0]);
        assertEquals('K', (char) b[1]);
        try (org.apache.poi.xslf.usermodel.XMLSlideShow ppt =
                     new org.apache.poi.xslf.usermodel.XMLSlideShow(new java.io.ByteArrayInputStream(b))) {
            assertTrue(ppt.getSlides().size() >= 3, "封面 + 三个章节,实际 " + ppt.getSlides().size());
        }
    }

    @Test
    @DisplayName("PDF 导出:%PDF- 文件头,页数大于 0")
    void pdfMagic() throws Exception {
        byte[] b = svc().toPdf("测试项目", MD);
        assertEquals("%PDF-", new String(b, 0, 5, StandardCharsets.ISO_8859_1));
        try (com.lowagie.text.pdf.PdfReader r = new com.lowagie.text.pdf.PdfReader(b)) {
            assertTrue(r.getNumberOfPages() >= 1);
        }
    }

    @Test
    @DisplayName("格式不认识就退回 Markdown 原文,不抛异常")
    void unknownFormatFallsBack() throws Exception {
        byte[] b = svc().render("标题", MD, "xlsx");
        assertEquals(MD, new String(b, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Markdown 解析:标题/要点/表格/代码/引用都认得")
    void parseBlocks() {
        var blocks = svc().parse(MD);
        assertTrue(blocks.stream().anyMatch(b -> b.kind == DocExportService.Kind.HEADING));
        assertTrue(blocks.stream().anyMatch(b -> b.kind == DocExportService.Kind.BULLET));
        assertTrue(blocks.stream().anyMatch(b -> b.kind == DocExportService.Kind.TABLE));
        assertTrue(blocks.stream().anyMatch(b -> b.kind == DocExportService.Kind.CODE));
        assertTrue(blocks.stream().anyMatch(b -> b.kind == DocExportService.Kind.QUOTE));
        assertTrue(blocks.stream().filter(b -> b.kind == DocExportService.Kind.TABLE)
                .findFirst().map(b -> b.rows.size() >= 3).orElse(false), "表头 + 两行数据");
    }

    @Test
    @DisplayName("行内标记被剥掉,文档里不会出现 ** 或反引号")
    void inlineStripped() {
        var blocks = svc().parse("**加粗** 与 `代码` 还有 [链接](https://x.com)");
        String text = blocks.get(0).text;
        assertFalse(text.contains("**"));
        assertFalse(text.contains("`"));
        assertTrue(text.contains("链接"));
        assertFalse(text.contains("https://"), "链接只留文字,不留裸地址");
    }

    @Test
    @DisplayName("空正文也能导出,不抛异常")
    void emptyBody() throws Exception {
        assertTrue(svc().toDocx("标题", "").length > 1000);
        assertTrue(svc().toPdf("标题", "").length > 100);
    }

    @Test
    @DisplayName("Word 里真的能读回中文,不是一堆问号")
    void docxKeepsChinese() throws Exception {
        byte[] b = svc().toDocx("品牌视觉方案", "## 定位\n\n冷静、克制、留白。\n");
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(b))) {
            String text = doc.getParagraphs().stream()
                    .map(p -> p.getText() == null ? "" : p.getText())
                    .reduce("", (a, c) -> a + c);
            assertTrue(text.contains("冷静、克制、留白"), "正文缺失:" + text);
            assertTrue(text.contains("品牌视觉方案"), "标题缺失:" + text);
        }
    }

    @Test
    @DisplayName("PPT 里真的能读回中文")
    void pptxKeepsChinese() throws Exception {
        byte[] b = svc().toPptx("品牌视觉方案", "## 定位\n\n- 冷静克制\n- 墨绿主色\n");
        try (org.apache.poi.xslf.usermodel.XMLSlideShow ppt =
                     new org.apache.poi.xslf.usermodel.XMLSlideShow(new java.io.ByteArrayInputStream(b))) {
            StringBuilder all = new StringBuilder();
            ppt.getSlides().forEach(s -> s.getShapes().forEach(sh -> {
                if (sh instanceof org.apache.poi.xslf.usermodel.XSLFTextShape ts) all.append(ts.getText());
            }));
            assertTrue(all.toString().contains("冷静克制"), "要点缺失:" + all);
            assertTrue(all.toString().contains("品牌视觉方案"), "标题缺失:" + all);
        }
    }

    @Test
    @DisplayName("PDF 嵌入了中文字体(否则中文会变方块)")
    void pdfEmbedsCjkFont() throws Exception {
        byte[] b = svc().toPdf("品牌视觉方案", "冷静、克制、留白。\n");
        String raw = new String(b, StandardCharsets.ISO_8859_1);
        assertTrue(raw.contains("FontFile2") || raw.contains("FontFile3"), "没有嵌入字体文件");
        assertTrue(raw.contains("ToUnicode"), "没有 ToUnicode 映射,复制出来会是乱码");
    }

    @Test
    @DisplayName("PDF 文字层能取出中文:复制粘贴不是乱码")
    void pdfTextLayer() throws Exception {
        byte[] b = svc().toPdf("品牌视觉方案", "## 定位\n\n冷静、克制、留白。\n");
        String text;
        try (com.lowagie.text.pdf.PdfReader r = new com.lowagie.text.pdf.PdfReader(b)) {
            text = new com.lowagie.text.pdf.parser.PdfTextExtractor(r).getTextFromPage(1);
        }
        assertTrue(text.contains("冷静") || text.contains("定位"),
                "PDF 文字层取不出中文,复制粘贴会是乱码:" + text.substring(0, Math.min(80, text.length())));
    }
}
