package com.tcc.common.failure;

import com.tcc.common.headers.TccHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class FailureInjectionFilterTest {

    private final FailureInjectionFilter filter = new FailureInjectionFilter();

    @Test
    void postWithFailAtTry_returns500() throws Exception {
        var req = new MockHttpServletRequest("POST", "/tcc/inventory/reservations");
        req.addHeader(TccHeaders.FAIL_AT, "TRY");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(500);
        assertThat(res.getContentAsString()).contains("injected failure at TRY");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void putWithFailAtConfirm_returns500() throws Exception {
        var req = new MockHttpServletRequest("PUT", "/tcc/inventory/reservations/abc/confirm");
        req.addHeader(TccHeaders.FAIL_AT, "CONFIRM");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(500);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void deleteWithFailAtCancel_returns500() throws Exception {
        var req = new MockHttpServletRequest("DELETE", "/tcc/inventory/reservations/abc");
        req.addHeader(TccHeaders.FAIL_AT, "CANCEL");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(500);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void postWithFailAtConfirm_doesNotFail() throws Exception {
        var req = new MockHttpServletRequest("POST", "/tcc/inventory/reservations");
        req.addHeader(TccHeaders.FAIL_AT, "CONFIRM");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void noHeader_passesThrough() throws Exception {
        var req = new MockHttpServletRequest("POST", "/tcc/inventory/reservations");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void sleepHeader_delaysButContinues() throws Exception {
        var req = new MockHttpServletRequest("POST", "/tcc/inventory/reservations");
        req.addHeader(TccHeaders.SLEEP_MILLIS, "150");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        long start = System.currentTimeMillis();
        filter.doFilter(req, res, chain);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(140);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void nonTccPath_isSkipped() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/orders");
        req.addHeader(TccHeaders.FAIL_AT, "TRY");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }
}
