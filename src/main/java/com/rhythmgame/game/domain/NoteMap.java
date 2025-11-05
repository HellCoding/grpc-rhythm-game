package com.rhythmgame.game.domain;

import java.util.List;

/**
 * 곡의 노트맵 정의 — 서버가 보유한 정답 데이터
 * 클라이언트는 노트맵을 렌더링용으로만 받고,
 * 판정은 서버의 노트맵을 기준으로 수행한다.
 */
public record NoteMap(
        String songId,
        String title,
        String artist,
        int bpm,
        double durationSeconds,
        String difficulty,
        List<Note> notes
) {
    public record Note(
            int index,
            long timestampMs,      // 노트가 판정선에 도달해야 하는 정확한 시각 (ms)
            String inputType,      // TAP, SLIDE, FLICK
            String memberPart      // 어떤 멤버 파트인지 (카드 보너스 판정용)
    ) {}
}
