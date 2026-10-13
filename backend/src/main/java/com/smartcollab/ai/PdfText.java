package com.smartcollab.ai;

import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 의 글자 [AI-13]. 번역은 PDF 를 통째로(쪽 이미지 + 글자) 보내지 않고 서버가 뽑은 글자를 텍스트로 보냅니다 — 통째로 보내면
 * 영어 번역이 한국어 원문을 그대로 옮기거나 원문과 번역을 나란히 적는 일이 실측 3번 중 2번이었고, 서버는 원문을 몰라 가려낼 수 없었습니다.
 * 글자가 거의 없는 PDF(스캔한 그림 등)·글자를 제대로 읽을 수 없는 PDF·열 수 없는 PDF 는 {@code null} 을 돌려 원본을 그대로 보내게 합니다.
 * <ul>
 *   <li>압축 폭탄: 그림이 아닌 스트림을 풀어낸 크기를 먼저 합산해 상한을 넘으면 끝까지 풀지 않고 원본을 보냅니다. PDFBox 는 쪽 내용이 Flate
 *       스트림 하나일 때만 흘려 읽고(500MB 로 풀리는 파일에 CPU 3.7초) 그 밖에는 통째로 메모리에 풀었습니다(쪽 내용이 배열이면
 *       300MB 에 329MB 할당, 600MB 는 512MB 힙에서 OOM)
 *       — Word 의 [H-1] 과 같은 대비</li>
 *   <li>PDF 를 읽는 동안 쓰는 메모리에도 상한을 둡니다. 넘으면 열 수 없는 PDF 로 봅니다</li>
 *   <li>뽑은 글자가 글자 수 상한을 넘으면 그 자리에서 멈추고 413 — Word 와 같은 상한입니다 [H-1]</li>
 * </ul>
 */
final class PdfText {

    private static final Logger log = LoggerFactory.getLogger(PdfText.class);

    /** 그림이 아닌 스트림(쪽 내용·글꼴 등)을 풀어낸 크기 합계의 상한 */
    static final long MAX_INFLATED_BYTES = 64L * 1024 * 1024;
    /** 압축을 푼 내용 등 PDF 를 읽는 동안 쓰는 메모리의 상한 */
    static final long MAX_MEMORY_BYTES = 64L * 1024 * 1024;
    /** 글자가 있는 쪽으로 볼 최소 글자 수(공백 제외) — 쪽 번호·머리글만 있는 그림 쪽을 거릅니다 */
    private static final int MIN_PAGE_CHARS = 10;
    /** 읽을 수 없는 글자(대체 문자·사용자 정의 영역·제어 문자)가 이 비율을 넘으면 글꼴 정보가 깨진 PDF 로 봅니다 */
    private static final double MAX_UNREADABLE_RATIO = 0.1;

    private PdfText() {
    }

    /**
     * @param maxChars 뽑은 글자 수 상한
     * @return 쪽 사이를 빈 줄로 나눈 글자. 절반 넘는 쪽에 글자가 없거나 읽을 수 없으면 {@code null}
     */
    static String extract(byte[] pdf, int maxChars) {
        try (PDDocument document = Loader.loadPDF(pdf, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(MAX_MEMORY_BYTES).streamCache)) {
            if (inflatesBeyond(document.getDocument(), MAX_INFLATED_BYTES)) {
                log.warn("PDF streams inflate beyond {} bytes; sending the original PDF", MAX_INFLATED_BYTES);
                return null;
            }
            int pages = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            StringBuilder out = new StringBuilder();
            int textPages = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).strip();
                if (visibleChars(text) >= MIN_PAGE_CHARS) {
                    textPages++;
                }
                if (!text.isEmpty()) {
                    out.append(out.isEmpty() ? "" : "\n\n").append(text);
                }
                if (out.length() > maxChars) {
                    throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "PDF 문서의 글자가 너무 많습니다. 번역은 " + maxChars + "자 이하 문서만 가능합니다.");
                }
            }
            if (pages == 0 || textPages * 2 < pages || unreadable(out)) {
                return null;
            }
            return out.toString();
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            // 암호·손상·메모리 상한 초과 등 — 원본을 그대로 보내고, 읽을 수 없으면 Claude 가 400 으로 알립니다
            log.info("Could not extract PDF text ({}); sending the original PDF", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 그림이 아닌 스트림을 풀어 본 크기의 합이 상한을 넘는지 — 넘는 순간 멈춥니다. PDFBox 는 쪽 내용이 Flate 스트림 하나일 때만 흘려 읽고,
     * 내용이 여러 스트림이거나 다른 압축이면 통째로 메모리에 풉니다. 그래서 여기서는 필터를 하나씩, 상한이 있는 출력으로 풀어 봅니다.
     */
    private static boolean inflatesBeyond(COSDocument document, long limit) throws IOException {
        Budget budget = new Budget(limit);
        for (COSObjectKey key : document.getXrefTable().keySet()) {
            if (!(document.getObjectFromPool(key).getObject() instanceof COSStream stream)
                    || COSName.IMAGE.equals(stream.getCOSName(COSName.SUBTYPE))) {
                continue;
            }
            List<COSName> filters = filters(stream.getFilters());
            if (filters.isEmpty()) {
                continue;   // 압축하지 않은 스트림은 파일 크기 안
            }
            try (InputStream raw = stream.createRawInputStream()) {
                InputStream in = raw;
                for (int i = 0; i < filters.size(); i++) {
                    Filter filter = FilterFactory.INSTANCE.getFilter(filters.get(i));
                    if (i == filters.size() - 1) {
                        filter.decode(in, budget.sink(), stream, i);
                    } else {
                        // 여러 필터가 이어지면 중간 결과를 담되, 담는 양도 상한에 셉니다
                        Budget.Buffer middle = budget.buffer();
                        filter.decode(in, middle, stream, i);
                        in = middle.toInputStream();
                    }
                }
            } catch (Budget.Exceeded e) {
                return true;
            } catch (IOException e) {
                // 풀 수 없는 스트림은 글자를 뽑을 때도 건너뜁니다
            }
        }
        return false;
    }

    private static List<COSName> filters(COSBase filters) {
        if (filters instanceof COSName name) {
            return List.of(name);
        }
        List<COSName> names = new ArrayList<>();
        if (filters instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.getObject(i) instanceof COSName name) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** 풀어낸 바이트의 합계. 상한을 넘으면 {@link Exceeded} — 필터가 IOException 을 삼킬 수 있어 런타임 예외로 빠져나옵니다 */
    private static final class Budget {
        private final long limit;
        private long used;

        Budget(long limit) {
            this.limit = limit;
        }

        void add(int n) {
            used += n;
            if (used > limit) {
                throw new Exceeded();
            }
        }

        OutputStream sink() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                    add(1);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    add(len);
                }
            };
        }

        Buffer buffer() {
            return new Buffer();
        }

        final class Buffer extends ByteArrayOutputStream {
            @Override
            public synchronized void write(int b) {
                add(1);
                super.write(b);
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) {
                add(len);
                super.write(b, off, len);
            }

            InputStream toInputStream() {
                return new ByteArrayInputStream(buf, 0, count);
            }
        }

        static final class Exceeded extends RuntimeException {
            Exceeded() {
                super(null, null, false, false);
            }
        }
    }

    private static int visibleChars(String text) {
        return (int) text.codePoints().filter(c -> !Character.isWhitespace(c)).count();
    }

    private static boolean unreadable(CharSequence text) {
        long visible = 0;
        long bad = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            visible++;
            int type = Character.getType(c);
            if (c == '�' || type == Character.PRIVATE_USE || type == Character.CONTROL || type == Character.UNASSIGNED) {
                bad++;
            }
        }
        return visible == 0 || bad > visible * MAX_UNREADABLE_RATIO;
    }
}
