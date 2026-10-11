package com.smartcollab.ai;

import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Word(docx) 문서의 본문 텍스트 [AI-02]. AI 는 docx 를 직접 읽지 못해 서버가 본문(word/document.xml)만 뽑아 보냅니다(서식은 빠짐).
 * 새 의존성(Apache POI) 없이 JDK 의 zip·StAX 로 읽고, 사용자가 올린 파일로 서버를 공격하는 두 경로를 막습니다.
 * <ul>
 *   <li>압축 폭탄: 본문 앞의 다른 항목까지 풀어낸 크기를 합쳐 정한 크기까지만 풀고, 넘으면 끝까지 풀지 않고 413.
 *       뽑은 본문도 글자 수 상한을 넘는 순간 멈춥니다 — 수십 KB 파일이 2,100만 자로 풀려 추출 요약이 힙을 다 썼습니다 [H-1]</li>
 *   <li>XML 외부 엔티티(XXE): DTD·외부 엔티티를 처리하지 않고, DTD 가 있으면 400 — 서버 파일을 읽어 AI 로 보내는 경로 차단</li>
 * </ul>
 */
final class DocxText {

    /** 풀어낸 크기(본문 앞 항목 포함)의 상한. 본문 글자 수는 호출하는 쪽이 따로 정합니다 */
    static final long MAX_XML_BYTES = 20L * 1024 * 1024;
    private static final String BODY = "word/document.xml";
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private DocxText() {
    }

    /** @param maxChars 뽑은 본문 글자 수 상한 */
    static String extract(byte[] docx, int maxChars) {
        return extract(docx, MAX_XML_BYTES, maxChars);
    }

    static String extract(byte[] docx, long maxInflatedBytes, int maxChars) {
        return parse(body(docx, maxInflatedBytes), maxChars);
    }

    /**
     * 본문 XML. ZipInputStream 은 앞 항목을 건너뛸 때도 끝까지 풀므로, 건너뛰는 항목도 직접 읽어 풀어낸 크기를 합산합니다 —
     * 앞에 1GB 로 풀리는 항목을 둔 파일이 요청마다 CPU 를 수 초 썼습니다 [H-1].
     */
    private static byte[] body(byte[] docx, long maxInflatedBytes) {
        long inflated = 0;
        byte[] skip = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (BODY.equals(entry.getName())) {
                    long room = maxInflatedBytes - inflated;
                    byte[] xml = zip.readNBytes((int) Math.min(room + 1, Integer.MAX_VALUE - 8));
                    if (xml.length > room) {
                        throw tooLarge();
                    }
                    return xml;
                }
                for (int n = zip.read(skip); n > 0; n = zip.read(skip)) {
                    inflated += n;
                    if (inflated > maxInflatedBytes) {
                        throw tooLarge();
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw unreadable();
        }
        throw unreadable();
    }

    /** 문단은 줄바꿈으로, 실행(run) 안의 탭·줄바꿈은 그대로. 문단 속성의 탭 정의(w:tabs/w:tab)는 글자가 아니므로 건너뜁니다 */
    private static String parse(byte[] xml, int maxChars) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        StringBuilder out = new StringBuilder();
        int paragraphs = 0;
        int runDepth = 0;
        boolean inText = false;
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD) {
                        throw unreadable();
                    }
                    boolean word = (event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.END_ELEMENT)
                            && W.equals(reader.getNamespaceURI());
                    if (event == XMLStreamConstants.START_ELEMENT && word) {
                        switch (reader.getLocalName()) {
                            case "p" -> {
                                if (paragraphs++ > 0) out.append('\n');
                            }
                            case "r" -> runDepth++;
                            case "t" -> inText = true;
                            case "tab" -> {
                                if (runDepth > 0) out.append('\t');
                            }
                            case "br", "cr" -> {
                                if (runDepth > 0) out.append('\n');
                            }
                            default -> {
                                // 그 밖의 요소(서식·속성)는 글자가 없음
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && word) {
                        if ("t".equals(reader.getLocalName())) inText = false;
                        else if ("r".equals(reader.getLocalName())) runDepth--;
                    } else if (inText && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)) {
                        out.append(reader.getText());
                    }
                    if (out.length() > maxChars) {
                        throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                                "Word 문서의 본문이 너무 깁니다. 요약·번역은 " + maxChars + "자 이하 문서만 가능합니다.");
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw unreadable();
        }
        return out.toString().strip();
    }

    private static ApiException tooLarge() {
        return new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Word 문서의 본문이 너무 큽니다.");
    }

    private static ApiException unreadable() {
        return ApiException.badRequest("Word 문서를 읽을 수 없습니다. 손상되었거나 docx 형식이 아닌지 확인하세요.");
    }
}
