package com.smartcollab.ai;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * 시험용 PDF 를 라이브러리 없이 만듭니다 — 쪽마다 표준 글꼴(Helvetica)로 줄을 쓰거나, 글자 없이 그림(사각형)만 그립니다(스캔 PDF 흉내).
 * 글자는 ASCII 만 씁니다. 압축 폭탄(작은 파일이 아주 크게 풀리는 내용 스트림)도 만듭니다.
 */
final class PdfFixtures {

    private PdfFixtures() {
    }

    /** 쪽마다 주어진 줄들을 씁니다. 빈 배열인 쪽은 글자 없이 사각형만 그립니다 */
    static byte[] pdf(String[]... pages) {
        List<byte[]> contents = new ArrayList<>();
        for (String[] page : pages) {
            String content = content(page);
            contents.add(("<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream").getBytes(StandardCharsets.US_ASCII));
        }
        return build(contents, false);
    }

    /**
     * 한 쪽짜리 PDF. 내용 스트림이 Flate 로 압축돼 있고 풀면 {@code inflatedBytes} 바이트의 공백(앞에 글자 한 줄)입니다.
     * {@code contentsArray} 면 쪽 내용을 배열 {@code [n 0 R]} 로 적습니다 — PDFBox 가 흘려 읽지 않고 통째로 푸는 모양입니다.
     */
    static byte[] bomb(long inflatedBytes, boolean contentsArray) {
        return bomb(inflatedBytes, contentsArray, false);
    }

    /** {@code hexWrapped} 면 압축한 내용을 다시 16진 글자로 적어 필터 두 개({@code [/ASCIIHexDecode /FlateDecode]})로 풀게 합니다 */
    static byte[] bomb(long inflatedBytes, boolean contentsArray, boolean hexWrapped) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        byte[] head = (content(new String[]{"Bomb page with enough words"}) + "\n").getBytes(StandardCharsets.US_ASCII);
        deflater.setInput(head);
        drain(deflater, buffer, compressed);
        byte[] spaces = new byte[1024 * 1024];
        java.util.Arrays.fill(spaces, (byte) ' ');
        for (long written = head.length; written < inflatedBytes; written += spaces.length) {
            deflater.setInput(spaces);
            drain(deflater, buffer, compressed);
        }
        deflater.finish();
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        byte[] data = compressed.toByteArray();
        String filter = "/FlateDecode";
        if (hexWrapped) {
            data = (java.util.HexFormat.of().formatHex(data) + ">").getBytes(StandardCharsets.US_ASCII);
            filter = "[/ASCIIHexDecode /FlateDecode]";
        }
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.writeBytes(("<< /Length " + data.length + " /Filter " + filter + " >>\nstream\n").getBytes(StandardCharsets.US_ASCII));
        stream.writeBytes(data);
        stream.writeBytes("\nendstream".getBytes(StandardCharsets.US_ASCII));
        return build(List.of(stream.toByteArray()), contentsArray);
    }

    private static void drain(Deflater deflater, byte[] buffer, ByteArrayOutputStream out) {
        while (!deflater.needsInput()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
    }

    private static byte[] build(List<byte[]> contents, boolean contentsArray) {
        List<byte[]> objects = new ArrayList<>();
        int pageCount = contents.size();
        // 1 카탈로그, 2 쪽 목록, 3 글꼴, 그 뒤로 쪽마다 (쪽, 내용) 두 개
        objects.add(ascii("<< /Type /Catalog /Pages 2 0 R >>"));
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            kids.append(4 + i * 2).append(" 0 R ");
        }
        objects.add(ascii("<< /Type /Pages /Kids [" + kids.toString().strip() + "] /Count " + pageCount + " >>"));
        objects.add(ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"));
        for (int i = 0; i < pageCount; i++) {
            objects.add(ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents "
                    + (contentsArray ? "[" + (5 + i * 2) + " 0 R]" : (5 + i * 2) + " 0 R") + " >>"));
            objects.add(contents.get(i));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n");
            out.writeBytes(objects.get(i));
            write(out, "\nendobj\n");
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

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }
}
