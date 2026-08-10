package com.czh.datasmart.govern.gateway.authentication;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayWebSocketBearerTokenConverterTest {

    private final GatewayWebSocketBearerTokenConverter converter =
            new GatewayWebSocketBearerTokenConverter();

    @Test
    void shouldResolveBearerTokenFromWebSocketSubprotocol() {
        String token = "header.payload.signature";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(token.getBytes(StandardCharsets.UTF_8));
        var request = MockServerHttpRequest.get(GatewayWebSocketBearerTokenConverter.WEBSOCKET_PATH)
                .header("Connection", "Upgrade")
                .header("Upgrade", "websocket")
                .header("Sec-WebSocket-Protocol",
                        GatewayWebSocketBearerTokenConverter.EVENT_PROTOCOL + ", "
                                + GatewayWebSocketBearerTokenConverter.BEARER_PROTOCOL_PREFIX + encoded)
                .build();

        var authentication = converter.convert(MockServerWebExchange.from(request)).block();

        assertThat(authentication).isInstanceOf(BearerTokenAuthenticationToken.class);
        assertThat(((BearerTokenAuthenticationToken) authentication).getToken()).isEqualTo(token);
    }

    @Test
    void shouldIgnoreSubprotocolTokenOnAnotherPath() {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("token".getBytes(StandardCharsets.UTF_8));
        var request = MockServerHttpRequest.get("/api/agent/events/replay")
                .header("Connection", "Upgrade")
                .header("Upgrade", "websocket")
                .header("Sec-WebSocket-Protocol",
                        GatewayWebSocketBearerTokenConverter.BEARER_PROTOCOL_PREFIX + encoded)
                .build();

        assertThat(converter.convert(MockServerWebExchange.from(request)).block()).isNull();
    }

    @Test
    void shouldFailClosedForMalformedEncodedToken() {
        var request = MockServerHttpRequest.get(GatewayWebSocketBearerTokenConverter.WEBSOCKET_PATH)
                .header("Connection", "Upgrade")
                .header("Upgrade", "websocket")
                .header("Sec-WebSocket-Protocol",
                        GatewayWebSocketBearerTokenConverter.BEARER_PROTOCOL_PREFIX + "not-base64!!!")
                .build();

        assertThat(converter.convert(MockServerWebExchange.from(request)).block()).isNull();
    }
}
