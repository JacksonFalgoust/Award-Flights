package com.awardwatch.ingest;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/** Shared by quota accounting and logging so uncertain charges stay reserved. */
final class RequestFailurePolicy {
    private RequestFailurePolicy() {}

    static boolean canRefund(RestClientException failure) {
        if (!(failure instanceof ResourceAccessException)) return false;
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        // Read timeouts, resets, TLS errors and unspecified I/O failures do not
        // establish whether the provider received and charged the request.
        return cause instanceof UnknownHostException
            || cause instanceof ConnectException
            || cause instanceof NoRouteToHostException
            || cause instanceof HttpConnectTimeoutException;
    }
}
