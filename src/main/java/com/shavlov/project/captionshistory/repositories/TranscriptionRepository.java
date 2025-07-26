package com.shavlov.project.captionshistory.repositories;

import com.shavlov.project.captionshistory.models.Transcription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TranscriptionRepository extends JpaRepository<Transcription,Long> {
    List<Transcription> findAllByOrderByCreatedAtDesc();
}
