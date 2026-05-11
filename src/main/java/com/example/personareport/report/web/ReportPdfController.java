package com.example.personareport.report.web;

import com.example.personareport.report.service.ReportDataService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.xhtmlrenderer.pdf.ITextRenderer;

/**
 * 최종 리포트 PDF 다운로드 엔드포인트.
 * final_report.report_markdown 필드를 CommonMark로 HTML 변환 후 Flying Saucer(OpenPDF)로 PDF 렌더링.
 * report_markdown 이 비어 있을 경우 텍스트 섹션을 조합한 fallback HTML을 사용한다.
 */
@Controller
@RequestMapping("/admin/reports")
@RequiredArgsConstructor
public class ReportPdfController {

    private final ReportDataService reportDataService;

    private static final Parser MD_PARSER = Parser.builder().build();
    private static final HtmlRenderer MD_RENDERER = HtmlRenderer.builder().build();

    @GetMapping("/{orderId}/pdf")
    public void downloadPdf(@PathVariable Long orderId, HttpServletResponse response) throws Exception {
        List<Map<String, Object>> reports = reportDataService.findReportByOrderId(orderId);
        if (reports.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "리포트를 찾을 수 없습니다.");
            return;
        }
        Map<String, Object> report = reports.get(0);

        List<Map<String, Object>> orders = reportDataService.findOrderById(orderId);
        String projectName = orders.isEmpty() ? "분석 리포트" : String.valueOf(orders.get(0).get("project_name"));

        String markdown = (String) report.get("report_markdown");
        String bodyHtml = (markdown != null && !markdown.isBlank())
                ? markdownToHtml(markdown)
                : buildFallbackHtml(report);

        String xhtml = wrapXhtml(projectName, bodyHtml);

        byte[] pdfBytes = renderPdf(xhtml);

        String filename = "report_" + orderId + ".pdf";
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.setContentLength(pdfBytes.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(pdfBytes);
        }
    }

    // ── Markdown → HTML 변환 ──────────────────────────────────────────────────
    private String markdownToHtml(String markdown) {
        Node document = MD_PARSER.parse(markdown);
        return MD_RENDERER.render(document);
    }

    // ── report_markdown 없을 때 텍스트 섹션 조합 fallback ────────────────────
    private String buildFallbackHtml(Map<String, Object> report) {
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "한눈에 보는 결론",       report.get("executive_summary"));
        appendSection(sb, "최종 판단",             report.get("final_verdict"));
        appendSection(sb, "구매 가능성",            report.get("purchase_intent_summary"));
        appendSection(sb, "가격에 대한 반응",        report.get("price_summary"));
        appendSection(sb, "신뢰 분석",             report.get("trust_summary"));
        appendSection(sb, "잘 맞는 고객 확인",       report.get("target_validation_summary"));
        appendSection(sb, "고객군별 반응 분석",       report.get("segment_summary"));
        appendSection(sb, "상세페이지 종합 피드백",   report.get("detail_page_summary"));
        appendSection(sb, "고쳐야 할 부분",          report.get("improvement_summary"));
        appendSection(sb, "주의할 점",              report.get("risk_summary"));
        return sb.toString();
    }

    private void appendSection(StringBuilder sb, String title, Object value) {
        if (value == null) return;
        String text = value.toString().trim();
        if (text.isBlank()) return;
        sb.append("<h2>").append(escapeHtml(title)).append("</h2>\n");
        // 줄바꿈을 <br/>로 변환
        String escaped = escapeHtml(text).replace("\n", "<br/>\n");
        sb.append("<p>").append(escaped).append("</p>\n");
    }

    // ── XHTML 래핑 (Flying Saucer는 well-formed XHTML 필요) ──────────────────
    private String wrapXhtml(String title, String bodyHtml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN"
                    "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
                <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="ko">
                <head>
                    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
                    <title>%s</title>
                    <style type="text/css">
                        @page { size: A4; margin: 20mm 18mm; }
                        body { font-family: "Malgun Gothic", "Apple SD Gothic Neo", "Noto Sans KR", sans-serif;
                               font-size: 10pt; color: #1e293b; line-height: 1.75; }
                        h1 { font-size: 18pt; font-weight: bold; margin-bottom: 6pt; border-bottom: 2pt solid #2563eb; padding-bottom: 4pt; }
                        h2 { font-size: 13pt; font-weight: bold; margin-top: 16pt; margin-bottom: 6pt;
                             color: #2563eb; border-left: 4pt solid #2563eb; padding-left: 8pt; }
                        h3 { font-size: 11pt; font-weight: bold; margin-top: 12pt; margin-bottom: 4pt; color: #334155; }
                        p  { margin: 0 0 8pt; }
                        ul, ol { margin: 0 0 8pt; padding-left: 18pt; }
                        li { margin-bottom: 3pt; }
                        table { width: 100%%; border-collapse: collapse; margin-bottom: 10pt; font-size: 9pt; }
                        th { background: #f1f5f9; padding: 5pt 8pt; border: 0.5pt solid #cbd5e1; font-weight: bold; text-align: left; }
                        td { padding: 5pt 8pt; border: 0.5pt solid #e2e8f0; vertical-align: top; }
                        .disclaimer { font-size: 8pt; color: #92400e; background: #fef3c7;
                                      border: 0.5pt solid #fcd34d; padding: 8pt; margin-top: 16pt; }
                    </style>
                </head>
                <body>
                    <h1>%s</h1>
                    %s
                </body>
                </html>
                """.formatted(escapeHtml(title), escapeHtml(title), bodyHtml);
    }

    // ── Flying Saucer PDF 렌더링 ─────────────────────────────────────────────
    private byte[] renderPdf(String xhtml) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(xhtml);
            renderer.layout();
            renderer.createPDF(baos);
            return baos.toByteArray();
        }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
