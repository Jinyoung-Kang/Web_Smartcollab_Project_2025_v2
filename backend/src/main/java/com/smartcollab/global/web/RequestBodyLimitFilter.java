package com.smartcollab.global.web;

import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.error.ProblemWriter;
import com.smartcollab.global.error.RequestBodyTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 요청 본문 크기 제한 [SEC-10].
 * <p>JSON 본문에는 기본 한도가 없어, 로그인하지 않은 사용자도 공개 API 에 수십 MB 를 보내 서버가 끝까지 읽어
 * 메모리에 올리게 할 수 있었습니다(60MB 로 재현). 길이를 알리면 읽기 전에, 길이를 알리지 않는 전송(chunked)은
 * 한도를 넘는 순간 413 으로 끊습니다. 파일 업로드(multipart)는 업로드 한도({@code spring.servlet.multipart.*})가 따로 막습니다.</p>
 * <p>multipart 는 업로드 경로에서만 받습니다 [S-18]. 다른 경로로 온 multipart 도 서버(Tomcat·Spring)가 업로드 한도까지
 * 본문을 끝까지 해석한 뒤에야 415 를 돌려줘, 로그인하지 않은 사용자도 공개 API 로 큰 본문을 읽힐 수 있었습니다.</p>
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    /** multipart 를 받는 유일한 경로 (FileController 의 업로드) */
    static final String UPLOAD_PATH = "/api/files/upload";

    private final long maxBytes;
    private final ObjectMapper mapper;

    public RequestBodyLimitFilter(long maxBytes, ObjectMapper mapper) {
        this.maxBytes = maxBytes;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isMultipart(request)) {
            if (UPLOAD_PATH.equals(request.getRequestURI().substring(request.getContextPath().length()))) {
                chain.doFilter(request, response);   // 업로드 한도가 막습니다
            } else {
                ProblemWriter.write(response, mapper, ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        "파일 업로드 주소가 아니면 multipart 요청을 받지 않습니다.");
            }
            return;
        }
        long length = request.getContentLengthLong();
        if (length > maxBytes) {
            reject(response);
            return;
        }
        try {
            chain.doFilter(length < 0 ? new LimitedBodyRequest(request, maxBytes) : request, response);
        } catch (RequestBodyTooLargeException e) {
            // 컨트롤러가 본문을 읽다 한도를 넘긴 경우 (Spring MVC 가 처리하지 않고 올려 보낸 때만 여기로 옵니다)
            if (!response.isCommitted()) {
                response.resetBuffer();
                reject(response);
            }
        }
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    private void reject(HttpServletResponse response) throws IOException {
        ProblemWriter.write(response, mapper, ErrorCode.PAYLOAD_TOO_LARGE, RequestBodyTooLargeException.MESSAGE);
    }

    /** 길이를 모르는 본문을 읽으면서 센 바이트가 한도를 넘으면 예외를 던집니다. */
    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;

        LimitedBodyRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        LimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) count(n);
            return n;
        }

        private void count(int n) throws RequestBodyTooLargeException {
            read += n;
            if (read > maxBytes) {
                throw new RequestBodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
