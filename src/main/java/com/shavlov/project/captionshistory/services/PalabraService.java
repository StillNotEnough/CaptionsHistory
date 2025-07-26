package com.shavlov.project.captionshistory.services;

import com.shavlov.project.captionshistory.models.Transcription;
import com.shavlov.project.captionshistory.repositories.TranscriptionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
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

    @Value("${palabra.api.client-id}")
    private String clientId;

    @Value("${palabra.api.client-secret}")
    private String clientSecret;

    private OkHttpClient client;
    private WebSocket webSocket;
    private String sessionId;
    private String publisherToken;
    public Consumer<Transcription> onTranscriptionReceived;

    @Autowired
    public PalabraService(TranscriptionRepository transcriptionRepository) {
        this.transcriptionRepository = transcriptionRepository;
    }

    /**
     * Creates a Palabra session and returns the WebSocket URL and publisher token.
     * @return SessionResponse containing ws_url and publisher token
     */
    private SessionResponse createSession() {
        try {
            OkHttpClient httpClient = new OkHttpClient();
            
            JSONObject requestBody = new JSONObject();
            JSONObject data = new JSONObject();
            data.put("subscriber_count", 0);
            data.put("publisher_can_subscribe", true);
            requestBody.put("data", data);

            RequestBody body = RequestBody.create(
                    requestBody.toString(),
                    MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url("https://api.palabra.ai/session-storage/session")
                    .addHeader("ClientId", clientId)
                    .addHeader("ClientSecret", clientSecret)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    
                    String wsUrl = jsonResponse.getString("ws_url");
                    String publisher = jsonResponse.getString("publisher");
                    String id = jsonResponse.getString("id");
                    
                    log.info("Successfully created Palabra session: {}", id);
                    return new SessionResponse(wsUrl, publisher, id);
                } else {
                    throw new RuntimeException("Failed to create session: " + response.code());
                }
            }
        } catch (Exception e) {
            log.error("Error creating Palabra session: {}", e.getMessage(), e);
            throw new RuntimeException("Error creating Palabra session", e);
        }
    }

    /**
     * Handles incoming WebSocket messages from Palabra API, parses transcription and translation,
     * saves them to the database, and notifies listeners.
     * @param message JSON message from Palabra API
     */
    public void handleWebSocketMessage(String message) {
        try {
            JSONObject jsonResponse = new JSONObject(message);
            String messageType = jsonResponse.optString("message_type", "");

            if ("partial_transcription".equals(messageType) || "final_transcription".equals(messageType)) {
                JSONObject data = jsonResponse.getJSONObject("data");
                JSONObject transcription = data.getJSONObject("transcription");
                String text = transcription.getString("text");
                String language = transcription.getString("language");

                if ("final_transcription".equals(messageType)) {
                    // For final transcriptions, we need to get the translation
                    // This would typically come in a separate message or we need to request it
                    log.info("Final transcription received: [{}] {}", language, text);
                    
                    // Create transcription record
                    Transcription transcriptionRecord = new Transcription();
                    transcriptionRecord.setOriginalText(text);
                    transcriptionRecord.setTranslatedText("[Translation pending]"); // Will be updated when translation arrives
                    
                    transcriptionRecord = transcriptionRepository.save(transcriptionRecord);
                    
                    if (onTranscriptionReceived != null) {
                        onTranscriptionReceived.accept(transcriptionRecord);
                    }
                } else {
                    log.debug("Partial transcription: [{}] {}", language, text);
                }
            } else if ("translation".equals(messageType)) {
                // Handle translation messages
                JSONObject data = jsonResponse.getJSONObject("data");
                String translatedText = data.getString("text");
                String targetLanguage = data.getString("target_language");
                
                log.info("Translation received: [{}] {}", targetLanguage, translatedText);
                
                // Update the latest transcription with translation
                // This is a simplified approach - in production you'd want to match with the original
                Transcription transcription = new Transcription();
                transcription.setOriginalText("[Original text]");
                transcription.setTranslatedText(translatedText);
                
                transcription = transcriptionRepository.save(transcription);
                
                if (onTranscriptionReceived != null) {
                    onTranscriptionReceived.accept(transcription);
                }
            } else {
                log.debug("Received message from Palabra API: {}", message);
            }
        } catch (Exception e) {
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
     * Connects to Palabra API WebSocket using the session-based approach.
     */
    public void connectToPalabraAPI() {
        try {
            client = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(30, TimeUnit.SECONDS)
                    .build();

            // Step 1: Create session
            SessionResponse session = createSession();
            String wsUrl = session.wsUrl;
            publisherToken = session.publisher;
            sessionId = session.id;

            // Step 2: Connect to WebSocket with publisher token
            String fullWsUrl = wsUrl + "?token=" + publisherToken;
            
            Request request = new Request.Builder()
                    .url(fullWsUrl)
                    .build();

            webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                    log.info("Connected to Palabra API WebSocket");
                    
                    // Step 3: Configure translation settings
                    configureTranslation(webSocket);
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
     * Configures translation settings for the WebSocket connection.
     */
    private void configureTranslation(WebSocket webSocket) {
        try {
            JSONObject settings = new JSONObject();
            settings.put("message_type", "set_task");
            
            JSONObject data = new JSONObject();
            
            // Input stream configuration
            JSONObject inputStream = new JSONObject();
            inputStream.put("content_type", "audio");
            JSONObject inputSource = new JSONObject();
            inputSource.put("type", "ws");
            inputSource.put("format", "pcm_s16le");
            inputSource.put("sample_rate", 24000);
            inputSource.put("channels", 1);
            inputStream.put("source", inputSource);
            data.put("input_stream", inputStream);
            
            // Output stream configuration
            JSONObject outputStream = new JSONObject();
            outputStream.put("content_type", "audio");
            JSONObject outputTarget = new JSONObject();
            outputTarget.put("type", "ws");
            outputTarget.put("format", "pcm_s16le");
            outputStream.put("target", outputTarget);
            data.put("output_stream", outputStream);
            
            // Pipeline configuration
            JSONObject pipeline = new JSONObject();
            pipeline.put("preprocessing", new JSONObject());
            
            JSONObject transcription = new JSONObject();
            transcription.put("source_language", "en");
            pipeline.put("transcription", transcription);
            
            JSONObject translation = new JSONObject();
            translation.put("target_language", "es");
            translation.put("speech_generation", new JSONObject());
            
            JSONArray translations = new JSONArray();
            translations.put(translation);
            pipeline.put("translations", translations);
            
            data.put("pipeline", pipeline);
            settings.put("data", data);
            
            webSocket.send(settings.toString());
            log.info("Translation settings configured: English → Spanish");
            
        } catch (Exception e) {
            log.error("Error configuring translation: {}", e.getMessage(), e);
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
     * Sends audio data to Palabra API WebSocket.
     * @param audioData Audio data as base64-encoded string
     */
    public void sendAudioData(String audioData) {
        if (webSocket != null) {
            try {
                JSONObject message = new JSONObject();
                message.put("message_type", "input_audio_data");
                
                JSONObject data = new JSONObject();
                data.put("data", audioData);
                message.put("data", data);
                
                if (!webSocket.send(message.toString())) {
                    log.error("Failed to send audio data to Palabra API");
                } else {
                    log.debug("Audio data sent to Palabra API");
                }
            } catch (Exception e) {
                log.error("Error sending audio data: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Sends a text translation request to Palabra API WebSocket.
     * @param text Text to translate
     * @param sourceLang Source language code
     * @param targetLang Target language code
     */
    public void sendTextForTranslation(String text, String sourceLang, String targetLang){
        // For text translation, you might need to use a different endpoint or message type
        // This is a placeholder for text-based translation
        log.info("Text translation requested: {} ({} → {})", text, sourceLang, targetLang);
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

    /**
     * Internal class to hold session response data.
     */
    private static class SessionResponse {
        final String wsUrl;
        final String publisher;
        final String id;

        SessionResponse(String wsUrl, String publisher, String id) {
            this.wsUrl = wsUrl;
            this.publisher = publisher;
            this.id = id;
        }
    }
}