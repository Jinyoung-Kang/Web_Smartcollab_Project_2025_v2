package com.smartcollab.ai;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 시험용 PDF 를 라이브러리 없이 만듭니다 — 쪽마다 표준 글꼴(Helvetica)로 줄을 쓰거나, 글자 없이 그림(사각형)만 그립니다(스캔 PDF 흉내).
 * 글자는 ASCII 만 씁니다.
 */
final class PdfFixtures {

    private PdfFixtures() {
    }

    /** 쪽마다 주어진 줄들을 씁니다. 빈 배열인 쪽은 글자 없이 사각형만 그립니다 */
    static byte[] pdf(String[]... pages) {
        List<String> objects = new ArrayList<>();
        int pageCount = pages.length;
        // 1 카탈로그, 2 쪽 목록, 3 글꼴, 그 뒤로 쪽마다 (쪽, 내용) 두 개
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            kids.append(4 + i * 2).append(" 0 R ");
        }
        objects.add("<< /Type /Pages /Kids [" + kids.toString().strip() + "] /Count " + pageCount + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        for (int i = 0; i < pageCount; i++) {
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents "
                    + (5 + i * 2) + " 0 R >>");
            String content = content(pages[i]);
            objects.add("<< /Length " + content.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + content + "\nendstream");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xref = out.size();
        StringBuilder table = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            table.append(String.format("%010d 00000 n \n", offset));
        }
        write(out, table + "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static String content(String[] lines) {
        if (lines.length == 0) {
            return "0.5 g 72 300 468 400 re f";
        }
        StringBuilder s = new StringBuilder("BT /F1 12 Tf 72 720 Td 16 TL");
        for (String line : lines) {
            s.append(" (").append(line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")).append(") Tj T*");
        }
        return s.append(" ET").toString();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }
}
