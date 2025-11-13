package com.rhythmgame.live.service;

import com.rhythmgame.game.domain.GameSession;
import com.rhythmgame.game.domain.NoteMap;
import com.rhythmgame.game.engine.NoteMapLoader;
import com.rhythmgame.live.domain.Lobby;
import com.rhythmgame.proto.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * LiveService — 최대 8인 실시간 멀티플레이.
 *
 * JoinLobby: Server-side streaming
 *   - 로비 상태 변경 이벤트를 실시간으로 클라이언트에 push
 *   - 플레이어 입장/퇴장, 투표 현황, 카운트다운, 시작 이벤트
 *
 * StartLive: Bidirectional streaming
 *   - 각 플레이어의 노트 판정을 서버에서 처리
 *   - 전원의 실시간 점수를 모든 참가자에게 브로드캐스트
 *
 * Redis Pub/Sub를 사용하면 다중 서버 인스턴스에서도 로비 동기화 가능 (확장 포인트)
 */
@GrpcService
public class LiveGrpcService extends LiveServiceGrpc.LiveServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(LiveGrpcService.class);

    private final NoteMapLoader noteMapLoader;
    private final Map<String, Lobby> lobbies = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // 라이브 세션: lobbyId -> (playerId -> GameSession)
    private final Map<String, Map<String, GameSession>> liveSessions = new ConcurrentHashMap<>();
    // 라이브 스트림 옵저버: lobbyId -> (playerId -> observer)
    private final Map<String, Map<String, StreamObserver<LivePlayResponse>>> liveObservers = new ConcurrentHashMap<>();

    public LiveGrpcService(NoteMapLoader noteMapLoader) {
        this.noteMapLoader = noteMapLoader;
    }

    /**
     * Server-side streaming — 로비 참가 후 실시간 이벤트 수신.
     */
    @Override
    public void joinLobby(JoinLobbyRequest request, StreamObserver<LobbyEvent> responseObserver) {
        Lobby lobby = findOrCreateLobby();

        boolean joined = lobby.addPlayer(request.getPlayerId(), request.getPlayerName(), responseObserver);
        if (!joined) {
            responseObserver.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription("Lobby is full").asException());
            return;
        }

        log.info("Player {} joined lobby {} ({}/{})",
                request.getPlayerName(), lobby.getLobbyId(),
                lobby.getPlayerCount(), Lobby.MAX_PLAYERS);

        // 전원에게 입장 이벤트 브로드캐스트
        lobby.broadcast(LobbyEvent.newBuilder()
                .setPlayerJoined(PlayerJoinedEvent.newBuilder()
                        .setLobbyId(lobby.getLobbyId())
                        .setPlayerId(request.getPlayerId())
                        .setPlayerName(request.getPlayerName())
                        .setCurrentPlayerCount(lobby.getPlayerCount())
                        .setMaxPlayers(Lobby.MAX_PLAYERS)
                        .build())
                .build());

        // 2명 이상이면 투표 카운트다운 시작
        if (lobby.shouldStartCountdown()) {
            startVotingCountdown(lobby);
        }
    }

    @Override
    public void voteSong(VoteSongRequest request, StreamObserver<VoteSongResponse> responseObserver) {
        Lobby lobby = lobbies.get(request.getLobbyId());
        if (lobby == null) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("Lobby not found").asException());
            return;
        }

        lobby.vote(request.getPlayerId(), request.getSongId());
        responseObserver.onNext(VoteSongResponse.newBuilder().setAccepted(true).build());
        responseObserver.onCompleted();
    }

    /**
     * Bidirectional streaming — 라이브 플레이.
     * 각 플레이어가 연결하여 노트 판정을 서버에서 처리하고,
     * 모든 참가자에게 실시간 점수를 브로드캐스트한다.
     */
    @Override
    public StreamObserver<LivePlayRequest> startLive(StreamObserver<LivePlayResponse> responseObserver) {
        return new StreamObserver<>() {
            private String lobbyId;
            private String playerId;

            @Override
            public void onNext(LivePlayRequest request) {
                switch (request.getRequestCase()) {
                    case READY -> handleReady(request.getReady(), responseObserver);
                    case NOTE_HIT -> handleNoteHit(request.getNoteHit());
                    default -> {}
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Live stream error for player {}: {}", playerId, t.getMessage());
                cleanup();
            }

            @Override
            public void onCompleted() {
                cleanup();
                responseObserver.onCompleted();
            }

            private void handleReady(LiveReadyRequest req, StreamObserver<LivePlayResponse> observer) {
                lobbyId = req.getLobbyId();
                playerId = req.getPlayerId();

                liveObservers.computeIfAbsent(lobbyId, k -> new ConcurrentHashMap<>())
                        .put(playerId, observer);

                Lobby lobby = lobbies.get(lobbyId);
                if (lobby == null) return;

                // 모든 플레이어가 ready 했는지 확인
                Map<String, StreamObserver<LivePlayResponse>> observers = liveObservers.get(lobbyId);
                if (observers.size() == lobby.getPlayerCount()) {
                    startLiveGame(lobby);
                }
            }

            private void handleNoteHit(LiveNoteHitRequest req) {
                Map<String, GameSession> sessions = liveSessions.get(lobbyId);
                if (sessions == null) return;

                GameSession session = sessions.get(playerId);
                if (session == null) return;

                GameSession.JudgeResult result = session.judgeNote(
                        req.getNoteIndex(), req.getTimingOffsetMs());

                if (result == null) return;

                // 전원에게 점수 업데이트 브로드캐스트
                broadcastScoreUpdate(lobbyId, sessions);

                // 모든 플레이어가 완료했는지 확인
                if (sessions.values().stream().allMatch(GameSession::isCompleted)) {
                    broadcastFinalResult(lobbyId, sessions);
                }
            }

            private void cleanup() {
                if (lobbyId != null && playerId != null) {
                    Map<String, StreamObserver<LivePlayResponse>> observers = liveObservers.get(lobbyId);
                    if (observers != null) observers.remove(playerId);
                }
            }
        };
    }

    @Override
    public void leaveLobby(LeaveLobbyRequest request, StreamObserver<LeaveLobbyResponse> responseObserver) {
        Lobby lobby = lobbies.get(request.getLobbyId());
        if (lobby != null) {
            lobby.removePlayer(request.getPlayerId());
            lobby.broadcast(LobbyEvent.newBuilder()
                    .setPlayerLeft(PlayerLeftEvent.newBuilder()
                            .setPlayerId(request.getPlayerId())
                            .setCurrentPlayerCount(lobby.getPlayerCount())
                            .build())
                    .build());
        }
        responseObserver.onNext(LeaveLobbyResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }

    // --- Private helpers ---

    private Lobby findOrCreateLobby() {
        return lobbies.values().stream()
                .filter(l -> l.getState() == Lobby.LobbyState.WAITING && !l.isFull())
                .findFirst()
                .orElseGet(() -> {
                    Lobby lobby = new Lobby(UUID.randomUUID().toString());
                    lobbies.put(lobby.getLobbyId(), lobby);
                    return lobby;
                });
    }

    private void startVotingCountdown(Lobby lobby) {
        lobby.setState(Lobby.LobbyState.VOTING);

        scheduler.schedule(() -> {
            String selectedSong = lobby.resolveMostVotedSong();
            if (selectedSong == null) selectedSong = "dynamite"; // 기본곡

            lobby.setSelectedSongId(selectedSong);
            lobby.setState(Lobby.LobbyState.COUNTDOWN);

            lobby.broadcast(LobbyEvent.newBuilder()
                    .setCountdown(CountdownEvent.newBuilder()
                            .setRemainingSeconds(0)
                            .setSelectedSongId(selectedSong)
                            .build())
                    .build());

            // 노트맵 로드 후 LiveStart 이벤트
            noteMapLoader.get(selectedSong, "NORMAL").ifPresent(noteMap -> {
                LiveStartEvent.Builder startEvent = LiveStartEvent.newBuilder()
                        .setSongId(noteMap.songId())
                        .setSongTitle(noteMap.title())
                        .setTotalNotes(noteMap.notes().size());

                lobby.getPlayers().forEach((id, player) ->
                        startEvent.addPlayers(LivePlayer.newBuilder()
                                .setPlayerId(id)
                                .setPlayerName(player.playerName())
                                .build()));

                lobby.broadcast(LobbyEvent.newBuilder()
                        .setLiveStart(startEvent.build())
                        .build());
            });

            lobby.setState(Lobby.LobbyState.PLAYING);
        }, Lobby.VOTE_DURATION_SECONDS, TimeUnit.SECONDS);
    }

    private void startLiveGame(Lobby lobby) {
        NoteMap noteMap = noteMapLoader.get(lobby.getSelectedSongId(), "NORMAL").orElse(null);
        if (noteMap == null) return;

        Map<String, GameSession> sessions = new ConcurrentHashMap<>();
        lobby.getPlayers().forEach((id, player) -> {
            sessions.put(id, new GameSession(
                    UUID.randomUUID().toString(), id, noteMap, Set.of()));
        });
        liveSessions.put(lobby.getLobbyId(), sessions);

        // 동시 시작 타임스탬프 (3초 후)
        long startTimestamp = System.currentTimeMillis() + 3000;
        broadcastToLive(lobby.getLobbyId(), LivePlayResponse.newBuilder()
                .setAllReady(LiveAllReadyResponse.newBuilder()
                        .setStartTimestampMs(startTimestamp)
                        .build())
                .build());
    }

    private void broadcastScoreUpdate(String lobbyId, Map<String, GameSession> sessions) {
        LiveScoreUpdate.Builder update = LiveScoreUpdate.newBuilder();
        sessions.forEach((id, session) ->
                update.addPlayerScores(PlayerScore.newBuilder()
                        .setPlayerId(id)
                        .setScore(session.getTotalScore())
                        .setCombo(session.getMaxCombo())
                        .build()));

        broadcastToLive(lobbyId, LivePlayResponse.newBuilder()
                .setScoreUpdate(update.build())
                .build());
    }

    private void broadcastFinalResult(String lobbyId, Map<String, GameSession> sessions) {
        LiveFinalResult.Builder result = LiveFinalResult.newBuilder();
        int combinedScore = 0;
        int rank = 1;

        List<Map.Entry<String, GameSession>> sorted = sessions.entrySet().stream()
                .sorted((a, b) -> b.getValue().getTotalScore() - a.getValue().getTotalScore())
                .toList();

        for (var entry : sorted) {
            result.addRankings(PlayerRankEntry.newBuilder()
                    .setRank(rank++)
                    .setPlayerId(entry.getKey())
                    .setScore(entry.getValue().getTotalScore())
                    .setMaxCombo(entry.getValue().getMaxCombo())
                    .build());
            combinedScore += entry.getValue().getTotalScore();
        }
        result.setCombinedScore(combinedScore);

        broadcastToLive(lobbyId, LivePlayResponse.newBuilder()
                .setFinalResult(result.build())
                .build());

        // 정리
        liveSessions.remove(lobbyId);
        liveObservers.remove(lobbyId);
        lobbies.remove(lobbyId);
    }

    private void broadcastToLive(String lobbyId, LivePlayResponse response) {
        Map<String, StreamObserver<LivePlayResponse>> observers = liveObservers.get(lobbyId);
        if (observers == null) return;
        observers.values().forEach(obs -> {
            try { obs.onNext(response); } catch (Exception ignored) {}
        });
    }
}
