package com.rhythmgame.score.service;

import com.rhythmgame.proto.*;
import com.rhythmgame.score.repository.ScoreRecordRepository;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class ScoreGrpcService extends ScoreServiceGrpc.ScoreServiceImplBase {

    private final ScoreRecordRepository repository;

    public ScoreGrpcService(ScoreRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public void getRanking(GetRankingRequest request, StreamObserver<GetRankingResponse> responseObserver) {
        int topN = request.getTopN() > 0 ? request.getTopN() : 50;

        GetRankingResponse.Builder builder = GetRankingResponse.newBuilder();
        var records = repository.findTopScores(request.getSongId(), request.getDifficulty());

        int rank = 1;
        for (var record : records) {
            if (rank > topN) break;
            builder.addEntries(RankEntry.newBuilder()
                    .setRank(rank++)
                    .setPlayerId(record.getPlayerId())
                    .setScore(record.getScore())
                    .setMaxCombo(record.getMaxCombo())
                    .setGrade(record.getGrade())
                    .build());
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getMyBestScores(GetMyBestScoresRequest request, StreamObserver<GetMyBestScoresResponse> responseObserver) {
        responseObserver.onNext(GetMyBestScoresResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }
}
