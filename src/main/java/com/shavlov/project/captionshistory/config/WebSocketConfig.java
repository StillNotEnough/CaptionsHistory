package com.shavlov.project.captionshistory.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceTranslationWebSocketHandler voiceTranslationWebSocketHandler;

    @Autowired
    public WebSocketConfig(VoiceTranslationWebSocketHandler voiceTranslationWebSocketHandler) {
        this.voiceTranslationWebSocketHandler = voiceTranslationWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceTranslationWebSocketHandler, "/voice-translation")
                .setAllowedOrigins("*");
    }
}
