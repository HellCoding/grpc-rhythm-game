package com.rhythmgame.score.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "score_records",
        indexes = @Index(name = "idx_song_difficulty_score",
                columnList = "songId, difficulty, score DESC"))
public class ScoreRecord {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String playerId;
    private String playerName;
    private String songId;
    private String difficulty;
    private int score;
    private int maxCombo;
    private String grade;
    private LocalDateTime playedAt;

    protected ScoreRecord() {}

    public ScoreRecord(String playerId, String songId, String difficulty,
                       int score, int maxCombo, String grade) {
        this.playerId = playerId;
        this.songId = songId;
        this.difficulty = difficulty;
        this.score = score;
        this.maxCombo = maxCombo;
        this.grade = grade;
        this.playedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getPlayerId() { return playerId; }
    public String getSongId() { return songId; }
    public String getDifficulty() { return difficulty; }
    public int getScore() { return score; }
    public int getMaxCombo() { return maxCombo; }
    public String getGrade() { return grade; }
    public LocalDateTime getPlayedAt() { return playedAt; }
}
