package com.rhythmgame.game.service;

import com.rhythmgame.game.domain.GameSession;
import com.rhythmgame.game.domain.NoteMap;
import com.rhythmgame.game.engine.NoteMapLoader;
import com.rhythmgame.proto.*;
import com.rhythmgame.score.service.ScoreRecordService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GameService gRPC 구현체.
 *
 * 핵심: PlayStream 양방향 스트리밍
 * - Client → Server: StartSong, NoteHit(timing_offset_ms), EndSong
 * - Server → Client: SongReady, NoteJudge(판정+점수+콤보+HP), FinalResult
 *
 * 서버 판정(Server-Authoritative) 방식으로 안티치트를 구현한다.
 * 클라이언트는 timing_offset_ms만 전송하고, 서버가 노트맵과 대조하여 판정한다.
 */
@GrpcService
public class GameGrpcService extends GameServiceGrpc.GameServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(GameGrpcService.class);

    private final NoteMapLoader noteMapLoader;
    private final ScoreRecordService scoreRecordService;

    // 활성 세션 관리 (playerId -> session)
    private final Map<String, GameSession> activeSessions = new ConcurrentHashMap<>();

    public GameGrpcService(NoteMapLoader noteMapLoader, ScoreRecordService scoreRecordService) {
        this.noteMapLoader = noteMapLoader;
        this.scoreRecordService = scoreRecordService;
    }

    /**
     * 양방향 스트리밍 — 곡 플레이의 전체 라이프사이클을 하나의 스트림에서 처리.
     *
     * gRPC 양방향 스트리밍을 사용하는 이유:
     * 1. 곡 하나에 500+ 노트 → 매 노트마다 HTTP 요청은 비효율
     * 2. HTTP/2 단일 연결에서 멀티플렉싱 → 연결 오버헤드 제거
     * 3. Protobuf 바이너리 직렬화 → JSON 대비 70% 작은 페이로드
     * 4. 서버 판정 결과를 즉시 push → 폴링 불필요
     */
    @Override
    public StreamObserver<PlayRequest> playStream(StreamObserver<PlayResponse> responseObserver) {
        return new StreamObserver<>() {
            private GameSession session;

            @Override
            public void onNext(PlayRequest request) {
                switch (request.getRequestCase()) {
                    case START_SONG -> handleStartSong(request.getStartSong(), responseObserver);
                    case NOTE_HIT -> handleNoteHit(request.getNoteHit(), responseObserver);
                    case END_SONG -> handleEndSong(request.getEndSong(), responseObserver);
                    default -> responseObserver.onError(
                            Status.INVALID_ARGUMENT.withDescription("Unknown request type").asException()
                    );
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("PlayStream error: {}", t.getMessage());
                cleanup();
            }

            @Override
            public void onCompleted() {
                cleanup();
                responseObserver.onCompleted();
            }

            private void handleStartSong(StartSongRequest req, StreamObserver<PlayResponse> resp) {
                String songId = req.getSongId();
                String difficulty = req.getDifficulty().name();

                NoteMap noteMap = noteMapLoader.get(songId, difficulty)
                        .orElse(null);

                if (noteMap == null) {
                    resp.onError(Status.NOT_FOUND
                            .withDescription("Song not found: " + songId + " / " + difficulty)
                            .asException());
                    return;
                }

                // 장착 카드의 멤버 파트 추출 (간소화 — 카드 ID를 멤버 파트로 매핑)
                Set<String> memberParts = new HashSet<>(req.getEquippedCardIdsList());

                String sessionId = UUID.randomUUID().toString();
                session = new GameSession(sessionId, req.getPlayerId(), noteMap, memberParts);
                activeSessions.put(req.getPlayerId(), session);

                log.info("Game started: player={}, song={}, difficulty={}, notes={}",
                        req.getPlayerId(), songId, difficulty, noteMap.notes().size());

                resp.onNext(PlayResponse.newBuilder()
                        .setSongReady(SongReadyResponse.newBuilder()
                                .setSessionId(sessionId)
                                .setTotalNotes(noteMap.notes().size())
                                .setBpm(noteMap.bpm())
                                .setDurationSeconds(noteMap.durationSeconds())
                                .build())
                        .build());
            }

            private void handleNoteHit(NoteHitRequest req, StreamObserver<PlayResponse> resp) {
                if (session == null) {
                    resp.onError(Status.FAILED_PRECONDITION
                            .withDescription("No active session. Send StartSong first.")
                            .asException());
                    return;
                }

                GameSession.JudgeResult result = session.judgeNote(
                        req.getNoteIndex(), req.getTimingOffsetMs());

                if (result == null) {
                    // 부정 요청 (중복, 범위 초과 등) — 무시
                    return;
                }

                resp.onNext(PlayResponse.newBuilder()
                        .setNoteJudge(NoteJudgeResponse.newBuilder()
                                .setNoteIndex(result.noteIndex())
                                .setJudgment(result.judgment())
                                .setNoteScore(result.noteScore())
                                .setTotalScore(result.totalScore())
                                .setCombo(result.combo())
                                .setMaxCombo(result.maxCombo())
                                .setHpPercent(result.hpPercent())
                                .setCardBonusActive(result.cardBonusActive())
                                .build())
                        .build());

                // HP 0이면 강제 종료
                if (session.isHpZero()) {
                    sendFinalResult(resp, "HP_ZERO");
                }
            }

            private void handleEndSong(EndSongRequest req, StreamObserver<PlayResponse> resp) {
                sendFinalResult(resp, req.getReason());
            }

            private void sendFinalResult(StreamObserver<PlayResponse> resp, String reason) {
                if (session == null) return;

                String grade = session.calculateGrade();
                int[] counts = session.getJudgmentCounts();

                // 점수 저장 + 랭킹 조회
                int ranking = scoreRecordService.saveAndGetRank(
                        session.getPlayerId(),
                        session.getNoteMap().songId(),
                        session.getNoteMap().difficulty(),
                        session.getTotalScore(),
                        session.getMaxCombo(),
                        grade
                );

                log.info("Game ended: player={}, score={}, grade={}, rank={}, reason={}",
                        session.getPlayerId(), session.getTotalScore(), grade, ranking, reason);

                resp.onNext(PlayResponse.newBuilder()
                        .setFinalResult(FinalResultResponse.newBuilder()
                                .setTotalScore(session.getTotalScore())
                                .setMaxCombo(session.getMaxCombo())
                                .setMarvelousCount(counts[Judgment.MARVELOUS.getNumber()])
                                .setExcellentCount(counts[Judgment.EXCELLENT.getNumber()])
                                .setGoodCount(counts[Judgment.GOOD.getNumber()])
                                .setFairCount(counts[Judgment.FAIR.getNumber()])
                                .setMissCount(counts[Judgment.MISS.getNumber()])
                                .setGrade(grade)
                                .setRanking(ranking)
                                .setNewRecord(ranking == 1)
                                .build())
                        .build());

                cleanup();
            }

            private void cleanup() {
                if (session != null) {
                    activeSessions.remove(session.getPlayerId());
                    session = null;
                }
            }
        };
    }

    @Override
    public void getSongList(GetSongListRequest request, StreamObserver<GetSongListResponse> responseObserver) {
        GetSongListResponse.Builder builder = GetSongListResponse.newBuilder();

        noteMapLoader.getAllSongs().stream()
                .filter(meta -> request.getArtistFilter().isEmpty()
                        || meta.artist().equalsIgnoreCase(request.getArtistFilter()))
                .forEach(meta -> builder.addSongs(SongSummary.newBuilder()
                        .setSongId(meta.songId())
                        .setTitle(meta.title())
                        .setArtist(meta.artist())
                        .build()));

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getSongDetail(GetSongDetailRequest request, StreamObserver<SongDetail> responseObserver) {
        noteMapLoader.getSongMeta(request.getSongId()).ifPresentOrElse(
                meta -> {
                    responseObserver.onNext(SongDetail.newBuilder()
                            .setSongId(meta.songId())
                            .setTitle(meta.title())
                            .setArtist(meta.artist())
                            .setBpm(meta.bpm())
                            .setDurationSeconds(meta.durationSeconds())
                            .putAllNoteCounts(meta.noteCounts())
                            .build());
                    responseObserver.onCompleted();
                },
                () -> responseObserver.onError(
                        Status.NOT_FOUND.withDescription("Song not found").asException())
        );
    }
}
