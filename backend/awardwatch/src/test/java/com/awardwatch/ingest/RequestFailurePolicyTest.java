package com.awardwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

class RequestFailurePolicyTest {
    static Stream<Arguments> failures() {
        return Stream.of(
            Arguments.of(new UnknownHostException("dns"), true),
            Arguments.of(new ConnectException("refused"), true),
            Arguments.of(new NoRouteToHostException("unreachable"), true),
            Arguments.of(new HttpConnectTimeoutException("connect timed out"), true),
            Arguments.of(new IOException("wrapper", new ConnectException("refused")), true),
            Arguments.of(new SocketTimeoutException("read timed out"), false),
            Arguments.of(new HttpTimeoutException("request timed out"), false),
            Arguments.of(new SocketException("connection reset"), false),
            Arguments.of(new SSLException("TLS failure"), false),
            Arguments.of(new IOException("unknown I/O failure"), false)
        );
    }

    @ParameterizedTest
    @MethodSource("failures")
    void refundsOnlyConfirmedPreSendFailures(IOException cause, boolean refundable) {
        assertThat(RequestFailurePolicy.canRefund(new ResourceAccessException("failed", cause)))
            .isEqualTo(refundable);
    }

    @Test
    void messageTextAndNonTransportErrorsCannotAuthorizeRefunds() {
        assertThat(RequestFailurePolicy.canRefund(new ResourceAccessException("connection refused"))).isFalse();
        assertThat(RequestFailurePolicy.canRefund(new RestClientException("failed", new ConnectException()))).isFalse();
    }
}
