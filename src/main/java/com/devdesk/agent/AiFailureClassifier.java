package com.devdesk.agent;

import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

final class AiFailureClassifier {

    private static final Pattern STATUS_PREFIX = Pattern.compile("^(?:HTTP\\s+)?(\\d{3})\\s*-",
            Pattern.CASE_INSENSITIVE);

    private AiFailureClassifier() {}

    static AiIntegrationException.Kind classify(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException response) {
                return classifyStatus(response.getStatusCode());
            }
            if (cause instanceof NonTransientAiException nonTransient) {
                Matcher matcher = STATUS_PREFIX.matcher(nonTransient.getMessage() == null
                        ? "" : nonTransient.getMessage());
                if (matcher.find()) {
                    return classifyStatus(HttpStatusCode.valueOf(Integer.parseInt(matcher.group(1))));
                }
                return AiIntegrationException.Kind.PROVIDER;
            }
            if (cause instanceof TransientAiException
                    || cause instanceof ResourceAccessException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return AiIntegrationException.Kind.TEMPORARY;
            }
        }
        return AiIntegrationException.Kind.PROVIDER;
    }

    private static AiIntegrationException.Kind classifyStatus(HttpStatusCode status) {
        if (status.value() == 401 || status.value() == 403) {
            return AiIntegrationException.Kind.AUTHENTICATION;
        }
        if (status.value() == 429 || status.is5xxServerError()) {
            return AiIntegrationException.Kind.TEMPORARY;
        }
        return AiIntegrationException.Kind.PROVIDER;
    }

    static AiIntegrationException safeException(Throwable failure) {
        return new AiIntegrationException(classify(failure), failure);
    }
}
