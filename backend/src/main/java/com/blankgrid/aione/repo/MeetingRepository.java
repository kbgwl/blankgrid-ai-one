package com.blankgrid.aione.repo;

import com.blankgrid.aione.entity.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    List<Meeting> findAllByOrderByCreatedAtDesc();

    Optional<Meeting> findByIngestToken(String ingestToken);

    List<Meeting> findByStatusOrderByStartedAtDesc(String status);
}
