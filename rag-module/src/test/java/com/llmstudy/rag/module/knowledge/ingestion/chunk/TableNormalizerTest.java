package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableNormalizerTest {

    @Test
    void standardTableBecomesPipeRows() {
        String text = TableNormalizer.normalizeHtml(
                "<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>");
        assertEquals("A | B\n1 | 2", text);
        assertFalse(text.contains("<"));
    }

    @Test
    void nestedTagsEntitiesEmptyCellsAndFormula() {
        String text = TableNormalizer.normalizeHtml(
                "<table><tr><th>A</th><th></th><th>C</th></tr>"
                        + "<tr><td><b>38.96M</b></td><td>a&nbsp;b &amp; $x^2$</td><td>3</td></tr></table>");
        assertTrue(text.contains("38.96M"));
        assertTrue(text.contains("a b & $x^2$"));
        assertTrue(text.contains("A |  | C"));
    }

    @Test
    void expandsRowspanAndColspan() {
        String text = TableNormalizer.normalizeHtml("""
                <table>
                  <tr><td rowspan="2">Method</td><td>DSC</td><td>ASD</td></tr>
                  <tr><td colspan="2">Avg.</td></tr>
                  <tr><td>UNet</td><td>9.39</td><td>1.75</td></tr>
                </table>
                """);
        String[] rows = text.split("\n");
        assertEquals(3, rows.length);
        assertEquals("Method | DSC | ASD", rows[0]);
        assertEquals("Method | Avg. | Avg.", rows[1]);
        assertEquals("UNet | 9.39 | 1.75", rows[2]);
    }

    @Test
    void keepsCaptionNumbersFormulaAndFootnoteText() {
        String text = TableNormalizer.normalizeHtml(
                "<table><caption>Table 1 Results</caption>"
                        + "<tr><td>38.96M</td><td>$n=3$</td></tr>"
                        + "<tr><td colspan=\"2\">*p&lt;0.05</td></tr></table>");
        assertTrue(text.startsWith("Table 1 Results\n"));
        assertTrue(text.contains("38.96M"));
        assertTrue(text.contains("$n=3$"));
        assertTrue(text.contains("*p<0.05"));
    }

    @Test
    void malformedFallsBackToVisibleText() {
        String text = TableNormalizer.normalizeHtml("<table><td>0.94M");
        assertFalse(text.isBlank());
        assertTrue(text.contains("0.94M"));
        assertFalse(text.contains("<"));
    }

    @Test
    void nsclcTableKeepsParamCounts() {
        String html = """
                <table><tr><td rowspan="2">Method</td><td rowspan="2"># of Params</td>\
                <td>DSC (%) ↑</td><td>Jaccard (%) ↑</td><td>95HD (mm) ↓</td><td>ASD (mm) ↓</td></tr>\
                <tr><td colspan="4">Avg.</td></tr>\
                <tr><td>MedSAM-2 (Ma et al., 2025a)</td><td>38.96M</td>\
                <td>71.00</td><td>59.36</td><td>17.81</td><td>4.93</td></tr>\
                <tr><td>ECT-3DMedSAM (Ours)</td><td>0.94M</td>\
                <td>72.31</td><td>57.55</td><td>6.59</td><td>1.75</td></tr></table>
                """;
        String text = TableNormalizer.normalizeHtml(html);
        assertFalse(text.contains("<"));
        assertTrue(text.contains("38.96M"));
        assertTrue(text.contains("0.94M"));
        assertTrue(text.contains("MedSAM-2"));
        assertTrue(text.contains("ECT-3DMedSAM (Ours)"));
    }

    @Test
    void gfmNormalizesWhitespaceAndEscapes() {
        String text = TableNormalizer.normalizeGfm(
                "| Metric \\| Name |  Value  |\n| --- | --- |\n| Dice | 0.91 |");
        assertTrue(text.contains("| Metric | Name | Value |"));
        assertTrue(text.contains("| Dice | 0.91 |"));
        assertTrue(text.contains("| --- | --- |"));
    }

    @Test
    void blankHtmlDoesNotInventText() {
        assertEquals("", TableNormalizer.normalizeHtml("   "));
        assertEquals("", TableNormalizer.normalizeGfm("\n"));
    }
}
