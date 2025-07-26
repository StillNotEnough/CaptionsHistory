package com.shavlov.project.captionshistory.services;

import com.shavlov.project.captionshistory.models.Transcription;
import com.shavlov.project.captionshistory.repositories.TranscriptionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Service for handling Palabra API authentication, WebSocket connection, and real-time speech translation.
 */
@Service
@Slf4j
public class PalabraService {

    private final TranscriptionRepository transcriptionRepository;

    @Value("${palabra.api.url}")
    private String apiUrl;

    @Value("${palabra.api.client-id}")
    private String clientId;

    @Value("${palabra.api.client-secret}")
    private String clientSecret;

    private OkHttpClient client;
    private WebSocket webSocket;
    public Consumer<Transcription> onTranscriptionReceived;


    @Autowired
    public PalabraService(TranscriptionRepository transcriptionRepository) {
        this.transcriptionRepository = transcriptionRepository;
    }

    /**
     * Handles incoming WebSocket messages from Palabra API, parses transcription and translation,
     * saves them to the database, and notifies listeners.
     * @param message JSON message from Palabra API
     */
    public void handleWebSocketMessage(String message) {
        try {
            JSONObject jsonResponse = new JSONObject(message);

            String originalText;
            String translatedText;

            if (jsonResponse.has("original") && jsonResponse.has("translated")) {
                originalText = jsonResponse.getString("original");
                translatedText = jsonResponse.getString("translated");
            } else if (jsonResponse.has("text") && jsonResponse.has("translation")) {
                originalText = jsonResponse.getString("text");
                translatedText = jsonResponse.getString("translation");
            } else {
                log.warn("Unexpected message format from Palabra API: {}", message);
                return;
            }

            // Сохраняем транскрипцию в базу данных
            Transcription transcription = new Transcription();
            transcription.setOriginalText(originalText);
            transcription.setTranslatedText(translatedText);

            transcription = transcriptionRepository.save(transcription);
            log.info("Saved transcription: Original='{}', Translation='{}'", originalText, translatedText);

            // Уведомляем WebSocket handler о новой транскрипции
            if (onTranscriptionReceived != null) {
                onTranscriptionReceived.accept(transcription);
            }
        }catch (Exception e) {
            log.error("Error handling WebSocket message: {}", e.getMessage(), e);
        }
    }

    /**
     * Initializes the WebSocket connection to Palabra API on service startup.
     */
    @PostConstruct
    public void init(){
        connectToPalabraAPI();
    }

    /**
     * Connects to Palabra API WebSocket and sets up listeners for real-time translation.
     * Sends client credentials as headers.
     */
    public void connectToPalabraAPI() {
        try {
            client = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(30, TimeUnit.SECONDS)
                    .build();

            // WebSocket request with credentials as headers
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("ClientId", clientId)
                    .addHeader("ClientSecret", clientSecret)
                    .build();

            webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                    log.info("Connected to Palabra API WebSocket");

                    // Send config for translation (if required by API)
                    JSONObject config = new JSONObject();
                    config.put("source_language", "en");
                    config.put("target_language", "es");
                    config.put("mode", "real-time");
                    webSocket.send(config.toString());
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    log.debug("Received message from Palabra API: {}", text);
                    handleWebSocketMessage(text);
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    log.warn("WebSocket connection closing: {} - {}", code, reason);
                    webSocket.close(1000, null);
                }

                @Override
                public void onClosed(@NotNull WebSocket webSocket, int code, String reason) {
                    log.info("WebSocket connection closed: {} - {}", code, reason);
                    scheduleReconnect();
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    log.error("WebSocket connection failed: {}", t.getMessage(), t);
                    scheduleReconnect();
                }
            });
        } catch (Exception e) {
            log.error("Failed to connect to Palabra API: {}", e.getMessage(), e);
            scheduleReconnect();
        }
    }

    /**
     * Schedules a reconnection attempt to Palabra API WebSocket after a delay.
     */
    private void scheduleReconnect(){
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                log.info("Attempting to reconnect to Palabra API...");
                connectToPalabraAPI();
            }catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Reconnection interrupted");
            }
        }).start();
    }

    /**
     * Sends audio data (or text message) to Palabra API WebSocket.
     * @param audioData Audio data as a string (base64 or binary expected by Palabra)
     */
    public void sendAudioData(String audioData) {
        if (webSocket != null && !webSocket.send(audioData)) {
            log.error("Failed to send audio data to Palabra API");
        } else {
            log.debug("Audio data sent to Palabra API");
        }
    }

    /**
     * Sends a text translation request to Palabra API WebSocket.
     * @param text Text to translate
     * @param sourceLang Source language code
     * @param targetLang Target language code
     */
    public void sendTextForTranslation(String text, String sourceLang, String targetLang){
        JSONObject message = new JSONObject();
        message.put("text", text);
        message.put("source_language", sourceLang);
        message.put("target_language", targetLang);
        message.put("type", "translation_request");

        sendAudioData(message.toString());
    }

    /**
     * Checks if the WebSocket connection to Palabra API is active.
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return webSocket != null;
    }

    /**
     * Cleans up WebSocket and HTTP client resources on service shutdown.
     */
    @PreDestroy
    public void cleanup(){
        if (webSocket != null){
            webSocket.close(1000, "Application shutdown");
        }
        if (client != null) {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }
}