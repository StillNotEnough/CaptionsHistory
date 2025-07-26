package com.shavlov.project.captionshistory.services;

import com.shavlov.project.captionshistory.dto.TranscriptionDTO;
import com.shavlov.project.captionshistory.models.Transcription;
import com.shavlov.project.captionshistory.repositories.TranscriptionRepository;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class TranscriptionService {

    private final TranscriptionRepository transcriptionRepository;
    private final ModelMapper modelMapper;

    @Autowired
    public TranscriptionService(TranscriptionRepository transcriptionRepository, ModelMapper modelMapper) {
        this.transcriptionRepository = transcriptionRepository;
        this.modelMapper = modelMapper;
    }

    public List<Transcription> findAllByOrderByCreatedAtDesc(){
        return transcriptionRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public void save(Transcription transcription){
        transcriptionRepository.save(transcription);
    }

    @Transactional
    public void deleteAll(){
        transcriptionRepository.deleteAll();
    }

    public Transcription convertedToTranscription(TranscriptionDTO transcriptionDTO){
        return modelMapper.map(transcriptionDTO, Transcription.class);
    }

    public TranscriptionDTO convertedToTranscriptionDTO(Transcription transcription){
        return modelMapper.map(transcription, TranscriptionDTO.class);
    }
}