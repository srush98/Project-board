package com.javaproject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.javaproject.security.HttpsBehindNlbFilter; 

class HttpsBehindNlbFilterTest {

    @Test
    void treatsPlainHttpFromTheNlbAsHttpsOnPort443() throws Exception {
        // What the pod really receives from the NLB: plain HTTP on 8080
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/secured");
        request.setScheme("http");
        request.setServerName("srushti.site");
        request.setServerPort(8080);
        MockFilterChain chain = new MockFilterChain();

        new HttpsBehindNlbFilter().doFilter(request, new MockHttpServletResponse(), chain);

        // What Spring Security sees after the filter
        HttpServletRequest seen = (HttpServletRequest) chain.getRequest();
        assertEquals("https", seen.getScheme());
        assertTrue(seen.isSecure());
        assertEquals(443, seen.getServerPort());
        assertEquals("https://srushti.site/secured", seen.getRequestURL().toString());
    }
}