package com.rhythmgame.game.domain;

import com.rhythmgame.proto.Judgment;
import java.util.*;

/**
 * 플레이어 1명의 게임 세션 상태.
 * 서버 메모리에서 관리되며, 곡이 끝나면 DB에 결과를 저장한다.
 */
public class GameSession {

    private final String sessionId;
    private final String playerId;
    private final NoteMap noteMap;
    private final Set<String> equippedMemberParts; // 장착 카드의 멤버 파트

    private int totalScore;
    private int combo;
    private int maxCombo;
    private double hpPercent = 100.0;

    // 판정별 카운트
    private final int[] judgmentCounts = new int[5]; // MARVELOUS~MISS 순서

    // 이미 판정된 노트 인덱스 (중복 전송 방지 = 안티치트)
    private final BitSet judgedNotes;

    // 노트 순서 검증 (이전 노트보다 큰 인덱스만 허용)
    private int lastJudgedIndex = -1;

    public GameSession(String sessionId, String playerId, NoteMap noteMap, Set<String> equippedMemberParts) {
        this.sessionId = sessionId;
        this.playerId = playerId;
        this.noteMap = noteMap;
        this.equippedMemberParts = equippedMemberParts;
        this.judgedNotes = new BitSet(noteMap.notes().size());
    }

    /**
     * 노트 판정 처리.
     * @return 판정 결과 (null이면 부정 요청으로 무시)
     */
    public JudgeResult judgeNote(int noteIndex, long timingOffsetMs) {
        // 안티치트: 범위 검증
        if (noteIndex < 0 || noteIndex >= noteMap.notes().size()) {
            return null;
        }

        // 안티치트: 중복 판정 방지
        if (judgedNotes.get(noteIndex)) {
            return null;
        }

        // 안티치트: 순서 검증 (대략적 — 네트워크 지연 고려하여 ±3 허용)
        if (noteIndex < lastJudgedIndex - 3) {
            return null;
        }

        judgedNotes.set(noteIndex);
        lastJudgedIndex = Math.max(lastJudgedIndex, noteIndex);

        NoteMap.Note note = noteMap.notes().get(noteIndex);
        long absOffset = Math.abs(timingOffsetMs);

        // 판정 윈도우
        Judgment judgment;
        if (absOffset <= 20)       judgment = Judgment.MARVELOUS;
        else if (absOffset <= 50)  judgment = Judgment.EXCELLENT;
        else if (absOffset <= 100) judgment = Judgment.GOOD;
        else if (absOffset <= 150) judgment = Judgment.FAIR;
        else                       judgment = Judgment.MISS;

        // 콤보 처리
        if (judgment == Judgment.MARVELOUS || judgment == Judgment.EXCELLENT) {
            combo++;
        } else if (judgment == Judgment.FAIR || judgment == Judgment.MISS) {
            combo = 0;
        }
        // GOOD은 콤보 유지 (증가하지 않음, 리셋도 안 함)
        maxCombo = Math.max(maxCombo, combo);

        // 점수 계산
        int baseScore = switch (judgment) {
            case MARVELOUS -> 1000;
            case EXCELLENT -> 800;
            case GOOD -> 500;
            case FAIR -> 200;
            case MISS -> 0;
            default -> 0;
        };

        // 콤보 배율 (10콤보마다 5% 증가, 최대 50%)
        double comboMultiplier = 1.0 + Math.min(combo / 10, 10) * 0.05;

        // 카드 보너스 (장착 카드의 멤버 파트와 일치하면 20% 추가)
        boolean cardBonusActive = equippedMemberParts.contains(note.memberPart());
        double cardMultiplier = cardBonusActive ? 1.2 : 1.0;

        int noteScore = (int) (baseScore * comboMultiplier * cardMultiplier);
        totalScore += noteScore;

        // HP 처리
        if (judgment == Judgment.FAIR) hpPercent = Math.max(0, hpPercent - 5);
        if (judgment == Judgment.MISS) hpPercent = Math.max(0, hpPercent - 10);

        judgmentCounts[judgment.getNumber()]++;

        return new JudgeResult(noteIndex, judgment, noteScore, totalScore, combo, maxCombo, hpPercent, cardBonusActive);
    }

    public boolean isHpZero() {
        return hpPercent <= 0;
    }

    public boolean isCompleted() {
        return judgedNotes.cardinality() >= noteMap.notes().size();
    }

    public String calculateGrade() {
        double percent = (double) totalScore / (noteMap.notes().size() * 1000) * 100;
        if (percent >= 98) return "SSS";
        if (percent >= 95) return "SS";
        if (percent >= 90) return "S";
        if (percent >= 80) return "A";
        if (percent >= 70) return "B";
        if (percent >= 60) return "C";
        return "F";
    }

    // Getters
    public String getSessionId() { return sessionId; }
    public String getPlayerId() { return playerId; }
    public NoteMap getNoteMap() { return noteMap; }
    public int getTotalScore() { return totalScore; }
    public int getMaxCombo() { return maxCombo; }
    public int[] getJudgmentCounts() { return judgmentCounts; }

    public record JudgeResult(
            int noteIndex,
            Judgment judgment,
            int noteScore,
            int totalScore,
            int combo,
            int maxCombo,
            double hpPercent,
            boolean cardBonusActive
    ) {}
}
