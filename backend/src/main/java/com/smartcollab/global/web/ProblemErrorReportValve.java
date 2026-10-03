package com.smartcollab.global.web;

import com.smartcollab.global.error.ProblemWriter;
import com.smartcollab.global.security.ClientIp;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;
import org.slf4j.MDC;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tomcat 이 스프링에 넘기기 전에 거절한 요청의 오류 본문을 다른 오류와 같은 problem+json 으로 씁니다 [QA-08].
 * <p>경로에 {@code %00}·인코딩된 {@code /}({@code %2F}) 가 들면 Tomcat 이 주소를 해석하지 못해 라우팅 전에 400 으로 끝내고,
 * 호스트의 오류 보고 밸브가 HTML 페이지를 썼습니다(출시 기준 QA 퍼징의 오류 형식 불일치 86건). 필터를 거치지 않으므로 추적 ID 도
 * 여기서 정하고, API 경로라면 접근 기록과 같은 한 줄을 남깁니다. 본문이 이미 쓰인 응답(스프링이 처리한 오류)은 건드리지 않습니다.</p>
 */
@Slf4j
public class ProblemErrorReportValve extends ErrorReportValve {

    private final ObjectMapper mapper;

    public ProblemErrorReportValve(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        // 기본 밸브와 같은 조건: 오류 상태가 아니거나, 이미 본문이 있거나, 이미 보고했으면 쓰지 않습니다.
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        AtomicBoolean ioAllowed = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, ioAllowed);
        if (!ioAllowed.get()) {
            return;
        }
        String requestId = RequestTraceFilter.requestIdFor(request);
        MDC.put(RequestTraceFilter.MDC_KEY, requestId);
        try {
            String path = request.getRequestURI();
            if (path != null && RequestTraceFilter.isTraced(path)) {
                log.info("{} {} {} rejected before routing ip={}", request.getMethod(), RequestTraceFilter.maskSecrets(path),
                        status, ClientIp.of(request));
            }
            response.setHeader(RequestTraceFilter.HEADER, requestId);
            ProblemWriter.writeStatus(response, mapper, status, requestId);
            response.finishResponse();
        } catch (IOException | IllegalStateException e) {
            // 연결이 끊겼거나 이미 응답이 나간 경우 — 기본 밸브처럼 조용히 넘어갑니다.
        } finally {
            MDC.remove(RequestTraceFilter.MDC_KEY);
        }
    }

    /**
     * Boot 가 호스트에 붙이는 기본 오류 보고 밸브(HTML)를 이 밸브로 바꿉니다. Boot 의 설정(order 0)보다 뒤에 실행되어야 해서
     * 가장 낮은 우선순위로 둡니다. 호스트가 시작할 때 기본 밸브를 다시 붙이지 않도록 밸브 클래스도 알려 줍니다.
     */
    static class Installer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

        private final ObjectMapper mapper;

        Installer(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public void customize(TomcatServletWebServerFactory factory) {
            factory.addContextCustomizers(context -> {
                if (!(context.getParent() instanceof StandardHost host)) {
                    return;
                }
                for (Valve valve : host.getPipeline().getValves()) {
                    if (valve instanceof ErrorReportValve) {
                        host.getPipeline().removeValve(valve);
                    }
                }
                host.getPipeline().addValve(new ProblemErrorReportValve(mapper));
                host.setErrorReportValveClass(ProblemErrorReportValve.class.getName());
            });
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
