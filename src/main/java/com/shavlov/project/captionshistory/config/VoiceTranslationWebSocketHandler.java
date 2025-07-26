package com.shavlov.project.captionshistory.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shavlov.project.captionshistory.models.Transcription;
import com.shavlov.project.captionshistory.services.PalabraService;
import com.shavlov.project.captionshistory.services.TranscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;

@Component
@Slf4j
public class VoiceTranslationWebSocketHandler extends TextWebSocketHandler {

    private final PalabraService palabraService;
    private final TranscriptionService transcriptionService;

    @Autowired
    public VoiceTranslationWebSocketHandler(PalabraService palabraService, TranscriptionService transcriptionService) {
        this.palabraService = palabraService;
        this.transcriptionService = transcriptionService;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<WebSocketSession> sessions = Collections.synchronizedSet(new HashSet<>());
    private final Map<WebSocketSession, String> sessionToTimestamp = new HashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        log.info("WebSocket connection established: {}", session.getId());

        // Отправляем подтверждение на фронт
        // TODO
        sendMessage(session, Map.of(
                "type", "CONNECTION_ESTABLISHED",
                "message", "Connected successfully"
        ));

    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode messageNode = objectMapper.readTree(message.getPayload());
            String type = messageNode.get("type").asText();

            if ("TRANSLATE".equals(type)) {
                handleTranslationRequest(session, messageNode); // Обрабатываем запрос на перевод
            }
        } catch (Exception e) {
            sendErrorMessage(session, "Error processing request: " + e.getMessage());
        }
    }

    private void handleTranslationRequest(WebSocketSession session, JsonNode messageNode) {
        try {
            String originalText = messageNode.get("originalText").asText();
            String sourceLang = messageNode.get("sourceLang").asText();        // "en"
            String targetLang = messageNode.get("targetLang").asText();        // "es"
            String timestamp = messageNode.get("timestamp").asText();

            log.info("Translation request - Text: '{}', From: {} To: {}", originalText, sourceLang, targetLang);

            // Store timestamp for this session
            sessionToTimestamp.put(session, timestamp);

            // Set up callback for when PalabraService receives a translation
            palabraService.onTranscriptionReceived = (Transcription transcription) -> {
                try {
                    // Only send to the session that requested
                    if (session.isOpen()) {
                        Map<String, Object> response = Map.of(
                                "type", "TRANSLATION_RESULT",
                                "originalText", transcription.getOriginalText(),
                                "translatedText", transcription.getTranslatedText(),
                                "timestamp", sessionToTimestamp.get(session)
                        );
                        sendMessage(session, response);
                    }
                    // Save transcription
                    transcriptionService.save(transcription);
                } catch (Exception e) {
                    sendErrorMessage(session, "Error sending translation result");
                }
            };

            // Send to Palabra API
            JSONObject palabraRequest = new JSONObject();
            palabraRequest.put("text", originalText);
            palabraRequest.put("source_language", sourceLang);
            palabraRequest.put("target_language", targetLang);
            palabraService.sendAudioData(palabraRequest.toString());

        } catch (Exception e) {
            log.error("Error processing translation request: {}", e.getMessage(), e);
            sendErrorMessage(session, "Error processing translation request");
        }
    }

    private void sendMessage(WebSocketSession session, Object message) {
        try {
            if (session.isOpen()){
                String json = objectMapper.writeValueAsString(message); // Object → JSON
                session.sendMessage(new TextMessage(json)); // Отправляем
            }
        }catch (Exception e){
            log.error("Error sending message to session {}: {}", session.getId(), e.getMessage());
        }
    }

        private void sendErrorMessage(WebSocketSession session, String errorMessage) {
        sendMessage(session, Map.of(
                "type", "ERROR",
                "message", errorMessage
        ));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}: {}", session.getId(), exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        log.info("WebSocket connection closed: {}", session.getId());
    }
}