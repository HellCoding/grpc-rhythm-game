package com.rhythmgame.score.repository;

import com.rhythmgame.score.domain.ScoreRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ScoreRecordRepository extends JpaRepository<ScoreRecord, Long> {

    @Query("SELECT s FROM ScoreRecord s WHERE s.songId = :songId AND s.difficulty = :difficulty ORDER BY s.score DESC")
    List<ScoreRecord> findTopScores(String songId, String difficulty);

    Optional<ScoreRecord> findTopByPlayerIdAndSongIdAndDifficultyOrderByScoreDesc(
            String playerId, String songId, String difficulty);

    @Query("SELECT COUNT(s) + 1 FROM ScoreRecord s WHERE s.songId = :songId AND s.difficulty = :difficulty AND s.score > :score")
    int findRank(String songId, String difficulty, int score);
}
