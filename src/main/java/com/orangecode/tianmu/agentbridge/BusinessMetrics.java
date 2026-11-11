package com.orangecode.tianmu.agentbridge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Fixed read-only metric. Permission and count share one SQL statement/snapshot. */
public final class BusinessMetrics {
    public record VideoCandidate(String videoId,String title,long durationMs,Instant publishedAt){}
    public record VideoMatches(List<VideoCandidate> items,int nextOffset,boolean hasMore){}
    public VideoMatches search(BusinessAuthentication.Principal principal,String query,LocalDate start,LocalDate end,int offset,int limit){
        if(query==null || query.length()>120 || offset<0 || offset>1000 || limit<1 || limit>20 || (start==null)!=(end==null) || start!=null && !start.isBefore(end))throw new BridgeException("INVALID_REQUEST",400);
        String type=db.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='video' AND COLUMN_NAME='create_time'",String.class);
        if(!Set.of("timestamp","datetime").contains(type))throw new BridgeException("UNSUPPORTED_TIME_COLUMN",503);
        ZoneId storage="timestamp".equals(type)?ZoneOffset.UTC:ZoneId.of(config.datetimeStorageZone());
        var args=new ArrayList<Object>();args.add(Long.parseLong(principal.userId()));args.add(query.strip());
        String dates="";
        if(start!=null){dates=" AND create_time>=? AND create_time<?";args.add(BusinessDanmakuAnalysis.wall(start.atStartOfDay(),storage));args.add(BusinessDanmakuAnalysis.wall(end.atStartOfDay(),storage));}
        args.add(limit+1);args.add(offset);
        var found=db.query("SELECT video_id,title,duration,create_time FROM video WHERE user_id=? AND is_delete=0 AND LOCATE(?,COALESCE(title,''))>0"+dates+" ORDER BY create_time DESC,video_id DESC LIMIT ? OFFSET ?",
                (rs,n)->new VideoCandidate(Long.toString(rs.getLong("video_id")),Objects.toString(rs.getString("title"),""),Math.max(0,Math.round(rs.getDouble("duration")*1000)),rs.getTimestamp("create_time",Calendar.getInstance(TimeZone.getTimeZone(storage))).toInstant()),args.toArray());
        boolean more=found.size()>limit;var items=List.copyOf(found.subList(0,Math.min(limit,found.size())));return new VideoMatches(items,offset+items.size(),more);
    }
    public record Page(String videoId,String title,long durationMs,String coverDataUrl) {}
    private java.util.function.Function<String,String> coverImages=url->"";
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void reportCovers(com.orangecode.tianmu.application.ReportCoverImages images){coverImages=images::read;}

    public Page page(BusinessAuthentication.Principal principal,long videoId) {return page(principal,videoId,false);}
    public Page page(BusinessAuthentication.Principal principal,long videoId,boolean includeCover) {
        var rows = db.queryForList("SELECT title,duration,cover_url FROM video WHERE video_id=? AND (status=2 OR user_id=?) AND is_delete=0",videoId,Long.parseLong(principal.userId()));
        if (rows.size()!=1) throw new BridgeException("FORBIDDEN",403);
        var row = rows.get(0);
        long duration = row.get("duration") instanceof Number number && Double.isFinite(number.doubleValue()) ? Math.max(0,Math.round(number.doubleValue()*1000)) : 0;
        return new Page(Long.toString(videoId),Objects.toString(row.get("title"),""),duration,includeCover?coverImages.apply(Objects.toString(row.get("cover_url"),"")):"");
    }
    public record Metric(long videoId, LocalDate date, String metric, long value, String metricVersion,
            String source, Instant dataAsOf, String timezone, boolean complete, boolean demo) {}
    private final JdbcTemplate db;
    private final BridgeProperties config;
    public BusinessMetrics(JdbcTemplate db, BridgeProperties config) {
        this.db = new JdbcTemplate(Objects.requireNonNull(db.getDataSource()));
        this.db.setQueryTimeout(config.queryTimeoutSeconds()); this.config = config;
    }

    public Set<Long> readable(BusinessAuthentication.Principal principal, Set<Long> requested) {
        if (requested == null || requested.size() > 100 || requested.stream().anyMatch(v -> v == null || v <= 0)) throw new BridgeException("INVALID_RESOURCES", 400);
        if (requested.isEmpty()) return Set.of();
        String placeholders = String.join(",", Collections.nCopies(requested.size(), "?"));
        List<Object> args = new ArrayList<>(); args.add(Long.parseLong(principal.userId())); args.addAll(requested);
        return Set.copyOf(db.queryForList("SELECT video_id FROM video WHERE (status=2 OR user_id=?) AND is_delete=0 AND video_id IN (" + placeholders + ")", Long.class, args.toArray()));
    }
    public Set<Long> allowed(BusinessAuthentication.Principal principal, Set<Long> requested) {
        if (requested == null || requested.size() > 100 || requested.stream().anyMatch(v -> v == null || v <= 0))
            throw new BridgeException("INVALID_RESOURCES", 400);
        if (requested.isEmpty()) return Set.of();
        String parameters = String.join(",", Collections.nCopies(requested.size(), "?"));
        List<Object> args = new ArrayList<>(); args.add(Long.parseLong(principal.userId())); args.addAll(requested);
        return Set.copyOf(db.queryForList("SELECT video_id FROM video WHERE user_id=? AND is_delete=0 AND video_id IN (" + parameters + ")", Long.class, args.toArray()));
    }

    public Metric query(BusinessAuthentication.Principal principal, long videoId, LocalDate date, String metric) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (videoId <= 0 || date == null || !"bullet_count".equals(metric)) throw new BridgeException("INVALID_METRIC_QUERY", 400);
        if (date.isBefore(today.minusDays(30)) || !date.isBefore(today)) throw new BridgeException("DATE_OUT_OF_RANGE", 400);
        String type = db.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bullet' AND COLUMN_NAME='create_time'", String.class);
        if (!Set.of("timestamp", "datetime").contains(type)) throw new BridgeException("UNSUPPORTED_TIME_COLUMN", 503);
        ZoneId businessZone = ZoneId.of("Asia/Shanghai");
        Instant start = date.atStartOfDay(businessZone).toInstant(), end = date.plusDays(1).atStartOfDay(businessZone).toInstant();
        String sql = "SELECT v.video_id,(SELECT COUNT(*) FROM bullet b WHERE b.video_id=v.video_id AND b.create_time>=? AND b.create_time<?) AS metric_value,UTC_TIMESTAMP(6) AS as_of FROM video v WHERE v.video_id=? AND v.user_id=? AND v.is_delete=0";
        PreparedStatementCreator creator = connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(config.queryTimeoutSeconds());
            if ("timestamp".equals(type)) {
                Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                statement.setTimestamp(1, Timestamp.from(start), utc); statement.setTimestamp(2, Timestamp.from(end), utc);
            } else {
                ZoneId storage = ZoneId.of(config.datetimeStorageZone());
                statement.setString(1, LocalDateTime.ofInstant(start, storage).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                statement.setString(2, LocalDateTime.ofInstant(end, storage).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            statement.setLong(3, videoId); statement.setLong(4, Long.parseLong(principal.userId())); return statement;
        };
        var rows = db.query(creator, (result, row) -> new Metric(videoId, date, metric, result.getLong("metric_value"),
                "bullet-retained-created-v1", "mysql:bullet:retained-persisted-v1",
                result.getTimestamp("as_of", Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant(),
                "Asia/Shanghai", true, false));
        if (rows.isEmpty()) throw new BridgeException("FORBIDDEN", 403);
        return rows.get(0);
    }
}
