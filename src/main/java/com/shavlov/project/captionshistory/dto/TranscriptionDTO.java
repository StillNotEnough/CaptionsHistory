package com.shavlov.project.captionshistory.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TranscriptionDTO {
    private Long id;
    private String originalText;
    private String translatedText;
    private LocalDateTime createdAt;
}
