package com.smartcollab.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** X-Client-Id 헤더를 요청 동안 {@link RequestOrigin} 에 둡니다 [IMP-03]. */
public class RequestOriginFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (RequestOrigin.Scope ignored = RequestOrigin.bind(request.getHeader(RequestOrigin.HEADER))) {
            chain.doFilter(request, response);
        }
    }
}
