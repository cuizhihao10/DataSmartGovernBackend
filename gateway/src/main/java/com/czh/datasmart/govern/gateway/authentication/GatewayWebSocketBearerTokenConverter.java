package com.czh.datasmart.govern.gateway.authentication;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Resolves a browser WebSocket Bearer token without putting it in a URL query.
 * The token subprotocol is consumed only by Spring Security and is stripped by
 * the WebSocket guard before the request is proxied to Python Runtime.
 */
public final class GatewayWebSocketBearerTokenConverter implements ServerAuthenticationConverter {

    public static final String WEBSOCKET_PATH = "/api/agent/events/ws";
    public static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
    public static final String EVENT_PROTOCOL = "datasmart-agent-events-v1";
    public static final String BEARER_PROTOCOL_PREFIX = "datasmart-bearer-v1.";
    private static final int MAX_ENCODED_TOKEN_LENGTH = 16_384;

    private final ServerBearerTokenAuthenticationConverter headerConverter =
            new ServerBearerTokenAuthenticationConverter();

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        return headerConverter.convert(exchange)
                .switchIfEmpty(Mono.defer(() -> websocketBearerToken(exchange)));
    }

    private Mono<Authentication> websocketBearerToken(ServerWebExchange exchange) {
        var request = exchange.getRequest();
        if (!WEBSOCKET_PATH.equals(request.getPath().value())
                || !isWebSocketUpgrade(request.getHeaders())) {
            return Mono.empty();
        }

        List<?> protocolHeaders = request.getHeaders().get(SEC_WEBSOCKET_PROTOCOL);
        for (Object rawHeader : protocolHeaders == null ? List.of() : protocolHeaders) {
            String header = String.valueOf(rawHeader);
            for (String protocol : header.split(",")) {
                String normalized = protocol.trim();
                if (!normalized.startsWith(BEARER_PROTOCOL_PREFIX)) {
                    continue;
                }
                String encodedToken = normalized.substring(BEARER_PROTOCOL_PREFIX.length());
                if (encodedToken.isBlank() || encodedToken.length() > MAX_ENCODED_TOKEN_LENGTH) {
                    return Mono.empty();
                }
                try {
                    String token = new String(
                            Base64.getUrlDecoder().decode(encodedToken),
                            StandardCharsets.UTF_8
                    ).trim();
                    return token.isBlank()
                            ? Mono.empty()
                            : Mono.just(new BearerTokenAuthenticationToken(token));
                } catch (IllegalArgumentException exception) {
                    return Mono.empty();
                }
            }
        }
        return Mono.empty();
    }

    private static boolean isWebSocketUpgrade(HttpHeaders headers) {
        String upgrade = headers.getFirst(HttpHeaders.UPGRADE);
        return "websocket".equalsIgnoreCase(upgrade)
                && headers.getConnection().stream()
                .filter(value -> value != null)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("upgrade"));
    }
}
