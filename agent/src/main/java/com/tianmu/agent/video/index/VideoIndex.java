package com.tianmu.agent.video.index;

import java.time.Instant;
import java.util.*;

/** Text offsets refer to a sampled interval, never invented word-level timestamps. */
public record VideoIndex(UUID jobId, long videoId, String sourceVersion, long durationMs,
                         String status, int completed, int total, List<Chunk> chunks,
                         String errorCode, Instant updatedAt, Instant deadline) {
    public record Chunk(int ordinal,long startMs,long endMs,String text,String transcript,String coverage,List<Long> sampledAtMs) {
        public Chunk { sampledAtMs=List.copyOf(sampledAtMs); }
    }
    public VideoIndex { chunks=List.copyOf(chunks); }
    public boolean active(){return status.equals("QUEUED") || status.equals("RUNNING");}
    public VideoIndex state(String status,List<Chunk> chunks,String error){return new VideoIndex(jobId,videoId,sourceVersion,durationMs,status,chunks.size(),total,chunks,error,Instant.now(),deadline);}
}
