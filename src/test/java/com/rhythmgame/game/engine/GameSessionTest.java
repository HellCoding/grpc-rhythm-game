package com.rhythmgame.game.engine;

import com.rhythmgame.game.domain.GameSession;
import com.rhythmgame.game.domain.NoteMap;
import com.rhythmgame.proto.Judgment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * GameSession 판정 엔진 테스트.
 * 서버 판정의 정확성과 안티치트 로직을 검증한다.
 */
class GameSessionTest {

    private NoteMap noteMap;
    private GameSession session;

    @BeforeEach
    void setUp() {
        noteMap = new NoteMap("test_song", "Test Song", "Artist", 120, 10.0, "NORMAL",
                List.of(
                        new NoteMap.Note(0, 1000, "TAP", "MEMBER_A"),
                        new NoteMap.Note(1, 2000, "TAP", "MEMBER_B"),
                        new NoteMap.Note(2, 3000, "SLIDE", "MEMBER_A"),
                        new NoteMap.Note(3, 4000, "FLICK", "MEMBER_C"),
                        new NoteMap.Note(4, 5000, "TAP", "MEMBER_B")
                ));
        session = new GameSession("session-1", "player-1", noteMap, Set.of("MEMBER_A"));
    }

    @Test
    @DisplayName("MARVELOUS 판정: ±20ms 이내")
    void marvelousJudgment() {
        var result = session.judgeNote(0, 15);
        assertThat(result.judgment()).isEqualTo(Judgment.MARVELOUS);
        assertThat(result.noteScore()).isGreaterThanOrEqualTo(1000); // 카드 보너스 포함 가능
    }

    @Test
    @DisplayName("EXCELLENT 판정: ±21~50ms")
    void excellentJudgment() {
        var result = session.judgeNote(0, 35);
        assertThat(result.judgment()).isEqualTo(Judgment.EXCELLENT);
    }

    @Test
    @DisplayName("MISS 판정: >150ms")
    void missJudgment() {
        var result = session.judgeNote(0, 200);
        assertThat(result.judgment()).isEqualTo(Judgment.MISS);
        assertThat(result.noteScore()).isEqualTo(0);
    }

    @Test
    @DisplayName("콤보 시스템: MARVELOUS/EXCELLENT → 콤보 증가, FAIR/MISS → 리셋")
    void comboSystem() {
        session.judgeNote(0, 10);  // MARVELOUS → combo 1
        session.judgeNote(1, 10);  // MARVELOUS → combo 2
        var r3 = session.judgeNote(2, 10);  // MARVELOUS → combo 3
        assertThat(r3.combo()).isEqualTo(3);

        var r4 = session.judgeNote(3, 200); // MISS → combo 0
        assertThat(r4.combo()).isEqualTo(0);
        assertThat(r4.maxCombo()).isEqualTo(3); // 최대 콤보는 유지
    }

    @Test
    @DisplayName("카드 보너스: 장착 카드 멤버 파트와 일치하면 20% 추가 점수")
    void cardBonus() {
        // MEMBER_A 장착 상태 (index 0 = MEMBER_A)
        var withBonus = session.judgeNote(0, 10);
        assertThat(withBonus.cardBonusActive()).isTrue();
        assertThat(withBonus.noteScore()).isEqualTo(1200); // 1000 * 1.2

        // MEMBER_B는 비장착 (index 1 = MEMBER_B)
        var withoutBonus = session.judgeNote(1, 10);
        assertThat(withoutBonus.cardBonusActive()).isFalse();
    }

    @Test
    @DisplayName("HP 시스템: FAIR -5%, MISS -10%")
    void hpSystem() {
        session.judgeNote(0, 130); // FAIR → HP 95%
        var result = session.judgeNote(1, 130); // FAIR → HP 90%
        assertThat(result.hpPercent()).isEqualTo(90.0);

        var missResult = session.judgeNote(2, 200); // MISS → HP 80%
        assertThat(missResult.hpPercent()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("안티치트: 중복 노트 판정 차단")
    void antiCheatDuplicateNote() {
        session.judgeNote(0, 10); // 정상
        var duplicate = session.judgeNote(0, 10); // 중복 → null
        assertThat(duplicate).isNull();
    }

    @Test
    @DisplayName("안티치트: 범위 초과 노트 인덱스 차단")
    void antiCheatOutOfBounds() {
        var result = session.judgeNote(999, 10);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("안티치트: 극단적 역순 노트 차단 (±3 허용)")
    void antiCheatReverseOrder() {
        session.judgeNote(4, 10); // index 4
        var result = session.judgeNote(0, 10); // index 0 → lastJudgedIndex(4) - 3 = 1 이하 → null
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("등급 계산: 점수 비율에 따른 SSS~F")
    void gradeCalculation() {
        // 모든 노트 MARVELOUS (카드 보너스 없는 노트들도 있음)
        for (int i = 0; i < noteMap.notes().size(); i++) {
            session.judgeNote(i, 0);
        }
        // 카드 보너스 포함 점수가 높으므로 SSS 이상
        String grade = session.calculateGrade();
        assertThat(grade).isIn("SSS", "SS", "S");
    }
}
