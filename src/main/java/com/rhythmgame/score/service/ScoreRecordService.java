package com.rhythmgame.score.service;

import com.rhythmgame.score.domain.ScoreRecord;
import com.rhythmgame.score.repository.ScoreRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScoreRecordService {

    private final ScoreRecordRepository repository;

    public ScoreRecordService(ScoreRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int saveAndGetRank(String playerId, String songId, String difficulty,
                              int score, int maxCombo, String grade) {
        // 개인 최고 기록보다 높을 때만 갱신
        repository.findTopByPlayerIdAndSongIdAndDifficultyOrderByScoreDesc(playerId, songId, difficulty)
                .ifPresentOrElse(
                        existing -> {
                            if (score > existing.getScore()) {
                                repository.save(new ScoreRecord(playerId, songId, difficulty, score, maxCombo, grade));
                            }
                        },
                        () -> repository.save(new ScoreRecord(playerId, songId, difficulty, score, maxCombo, grade))
                );

        return repository.findRank(songId, difficulty, score);
    }
}
