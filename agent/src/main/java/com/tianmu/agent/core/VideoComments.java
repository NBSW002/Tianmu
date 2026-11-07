package com.tianmu.agent.core;

import java.time.Instant;
import java.util.*;

/** Historical comment evidence format; current analysis streams FeedbackAnalysis.Unit records. */
public final class VideoComments {
    private VideoComments() {}
    public record Comment(String id, String parentId, String userId, String nickname, String text,
                          Instant createdAt, boolean inPeriod, boolean author, boolean parentUnavailable) {
        public Comment {
            if (!VideoComments.id(id) || parentId != null && !VideoComments.id(parentId) || userId != null && !VideoComments.id(userId)
                    || nickname == null || nickname.length() > 512 || text == null || text.length() > 16384 || createdAt == null)
                throw new IllegalArgumentException("Invalid comment");
        }
    }
    public record Dataset(String kind, DanmakuTypes.Query query, long totalComments, long audienceComments,
                          long uniqueAudience, List<Comment> comments, Instant dataAsOf, String source) {
        public Dataset {
            comments = List.copyOf(comments);
            if (!"video-comments-v1".equals(kind) || query == null || comments.size() > 5000 || dataAsOf == null
                    || source == null || source.length() > 128 || totalComments < 0 || audienceComments < 0 || uniqueAudience < 0)
                throw new IllegalArgumentException("Invalid comment dataset");
            Map<String, Comment> byId = new HashMap<>();
            for (var c : comments) if (byId.putIfAbsent(c.id(), c) != null) throw new IllegalArgumentException("Duplicate comment ID");
            if (comments.stream().filter(Comment::inPeriod).count() != totalComments
                    || comments.stream().filter(c -> c.inPeriod() && !c.author()).count() != audienceComments
                    || comments.stream().filter(c -> c.inPeriod() && !c.author() && c.userId() != null).map(Comment::userId).distinct().count() != uniqueAudience)
                throw new IllegalArgumentException("Comment totals differ from complete records");
            Instant from = query.start().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
            Instant to = query.end().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
            Set<String> checked = new HashSet<>();
            for (var c : comments) {
                if (c.inPeriod() != (!c.createdAt().isBefore(from) && c.createdAt().isBefore(to))
                        || c.parentUnavailable() != (c.parentId() != null && !byId.containsKey(c.parentId())))
                    throw new IllegalArgumentException("Comment context flags differ from source");
                Set<String> path = new HashSet<>(); String current = c.id();
                while (current != null && byId.containsKey(current) && !checked.contains(current)) {
                    if (!path.add(current)) throw new IllegalArgumentException("Cyclic comment thread");
                    current = byId.get(current).parentId();
                }
                checked.addAll(path);
            }
        }
    }
    private static boolean id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(value) > 0; } catch (NumberFormatException e) { return false; }
    }
}
