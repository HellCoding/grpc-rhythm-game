package com.rhythmgame.game.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhythmgame.game.domain.NoteMap;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 노트맵 로더 — classpath:notemaps/ 에서 JSON 파일을 읽어 메모리에 캐싱.
 * key = "{songId}_{difficulty}" (예: "dynamite_HARD")
 */
@Component
public class NoteMapLoader {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, NoteMap> noteMapCache = new ConcurrentHashMap<>();
    private final Map<String, NoteMapMeta> songMetaCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadAll() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:notemaps/*.json");

        for (Resource resource : resources) {
            NoteMap noteMap = objectMapper.readValue(resource.getInputStream(), NoteMap.class);
            String key = noteMap.songId() + "_" + noteMap.difficulty();
            noteMapCache.put(key, noteMap);

            songMetaCache.computeIfAbsent(noteMap.songId(), id -> new NoteMapMeta(
                    noteMap.songId(), noteMap.title(), noteMap.artist(),
                    noteMap.bpm(), noteMap.durationSeconds(), new HashMap<>()
            ));
            songMetaCache.get(noteMap.songId()).noteCounts().put(noteMap.difficulty(), noteMap.notes().size());
        }
    }

    public Optional<NoteMap> get(String songId, String difficulty) {
        return Optional.ofNullable(noteMapCache.get(songId + "_" + difficulty));
    }

    public List<NoteMapMeta> getAllSongs() {
        return new ArrayList<>(songMetaCache.values());
    }

    public Optional<NoteMapMeta> getSongMeta(String songId) {
        return Optional.ofNullable(songMetaCache.get(songId));
    }

    public record NoteMapMeta(
            String songId, String title, String artist,
            int bpm, double durationSeconds,
            Map<String, Integer> noteCounts
    ) {}
}
