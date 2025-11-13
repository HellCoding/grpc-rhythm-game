package com.rhythmgame.live.domain;

import io.grpc.stub.StreamObserver;
import com.rhythmgame.proto.LobbyEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 최대 8인 멀티플레이 로비.
 * 플레이어 입장 → 곡 투표 → 카운트다운 → 라이브 시작 → 결과
 */
public class Lobby {

    public static final int MAX_PLAYERS = 8;
    public static final int VOTE_DURATION_SECONDS = 30;

    private final String lobbyId;
    private final Map<String, LobbyPlayer> players = new ConcurrentHashMap<>();
    private final Map<String, Integer> songVotes = new ConcurrentHashMap<>();
    private LobbyState state = LobbyState.WAITING;
    private String selectedSongId;

    public Lobby(String lobbyId) {
        this.lobbyId = lobbyId;
    }

    public synchronized boolean addPlayer(String playerId, String playerName, StreamObserver<LobbyEvent> observer) {
        if (players.size() >= MAX_PLAYERS || state != LobbyState.WAITING) {
            return false;
        }
        players.put(playerId, new LobbyPlayer(playerId, playerName, observer));
        return true;
    }

    public synchronized void removePlayer(String playerId) {
        players.remove(playerId);
    }

    public boolean isFull() {
        return players.size() >= MAX_PLAYERS;
    }

    public boolean shouldStartCountdown() {
        return players.size() >= 2 && state == LobbyState.WAITING;
    }

    public void vote(String playerId, String songId) {
        songVotes.merge(songId, 1, Integer::sum);
    }

    public String resolveMostVotedSong() {
        return songVotes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * 모든 로비 참가자에게 이벤트 브로드캐스트
     */
    public void broadcast(LobbyEvent event) {
        List<String> disconnected = new ArrayList<>();
        for (var entry : players.entrySet()) {
            try {
                entry.getValue().observer().onNext(event);
            } catch (Exception e) {
                disconnected.add(entry.getKey());
            }
        }
        disconnected.forEach(players::remove);
    }

    // Getters & State
    public String getLobbyId() { return lobbyId; }
    public int getPlayerCount() { return players.size(); }
    public Map<String, LobbyPlayer> getPlayers() { return Collections.unmodifiableMap(players); }
    public LobbyState getState() { return state; }
    public void setState(LobbyState state) { this.state = state; }
    public String getSelectedSongId() { return selectedSongId; }
    public void setSelectedSongId(String songId) { this.selectedSongId = songId; }

    public record LobbyPlayer(
            String playerId,
            String playerName,
            StreamObserver<LobbyEvent> observer
    ) {}

    public enum LobbyState {
        WAITING, VOTING, COUNTDOWN, PLAYING, FINISHED
    }
}
