package com.shavlov.project.captionshistory.controllers;

import com.shavlov.project.captionshistory.dto.TranscriptionDTO;
import com.shavlov.project.captionshistory.models.Transcription;
import com.shavlov.project.captionshistory.services.PalabraService;
import com.shavlov.project.captionshistory.services.TranscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transcriptions")
@CrossOrigin(origins = "*")
public class TranscriptionController {

    private final PalabraService palabraService;
    private final TranscriptionService transcriptionService;

    @Autowired
    /**
     * Constructor for dependency injection of PalabraService and TranscriptionService.
     */
    public TranscriptionController(PalabraService palabraService, TranscriptionService transcriptionService) {
        this.palabraService = palabraService;
        this.transcriptionService = transcriptionService;
    }

    @PostMapping("/audio")
    /**
     * Accepts audio data (as base64 or binary) from the frontend and forwards it to PalabraService for translation.
     * @param audioData The audio data as a string (base64 or other format expected by PalabraService)
     * @return HTTP 200 OK if sent successfully
     */
    public ResponseEntity<String> sendAudio(@RequestBody String audioData) {
        palabraService.sendAudioData(audioData);
        return ResponseEntity.ok("Audio data sent to Palabra API");
    }

    @GetMapping
    /**
     * Returns the list of all transcriptions (original and translated) in reverse chronological order.
     */
    public List<TranscriptionDTO> getTranscriptions() {
        List<Transcription> transcriptions = transcriptionService.findAllByOrderByCreatedAtDesc();
        return transcriptions.stream().map(transcriptionService::convertedToTranscriptionDTO).toList();
    }

    @DeleteMapping
    /**
     * Deletes all transcription history from the database.
     */
    public ResponseEntity<String> clearTranscription(){
        transcriptionService.deleteAll();
        return ResponseEntity.ok("History cleaned");
    }
}
