package com.javaproject.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The NLB terminates TLS and forwards plain HTTP, without X-Forwarded-Proto.
 * When enabled (only in Kubernetes), treat every request as HTTPS on port 443
 * so redirects, HSTS and secure checks behave correctly. KAN-7.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.behind-tls-proxy", havingValue = "true")
public class HttpsBehindNlbFilter extends OncePerRequestFilter {

   @Override
   protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
         FilterChain chain) throws ServletException, IOException {
      chain.doFilter(new HttpServletRequestWrapper(request) {
         @Override
         public String getScheme() {
            return "https";
         }

         @Override
         public boolean isSecure() {
            return true;
         }

         @Override
         public int getServerPort() {
            return 443;
         }

         @Override
         public StringBuffer getRequestURL() {
            return new StringBuffer("https://").append(getServerName()).append(getRequestURI());
         }
      }, response);
   }
}