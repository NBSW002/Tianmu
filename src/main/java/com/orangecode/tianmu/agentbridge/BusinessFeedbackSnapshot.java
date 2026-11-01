package com.orangecode.tianmu.agentbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** One short consistent export to a private spool; HTTP transfer and model work occur after commit. */
public final class BusinessFeedbackSnapshot {
    public record Request(long videoId,LocalDateTime start,LocalDateTime end,Set<String> sources) {}
    public record Unit(String id,String source,String originalId,String text,String replyContext,boolean author,Long playbackMs,String userId,Instant createdAt) {}
    public record Counts(long comments,long danmaku,long authorComments,long authorDanmaku,long uniqueAudience) {}
    public record Receipt(Instant dataAsOf,Counts counts) {}
    public record Export(Path file,long bytes) implements AutoCloseable {public void close(){try{Files.deleteIfExists(file);}catch(IOException ignored){}}}
    private record Comment(long id,Long parent,Long user,String text) {}
    private final JdbcTemplate db;private final TransactionTemplate tx;private final BridgeProperties config;private final ObjectMapper json;
    public BusinessFeedbackSnapshot(JdbcTemplate db,BridgeProperties config,ObjectMapper json){
        this.db=db;this.config=config;this.json=json.copy().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);db.setQueryTimeout(config.queryTimeoutSeconds());
        tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(config.queryTimeoutSeconds());
    }
    public Export export(BusinessAuthentication.Principal owner,Request request){
        BusinessDanmakuAnalysis.validateRange(request.videoId(),request.start(),request.end());
        if(request.sources()==null || request.sources().isEmpty() || !Set.of("COMMENT","DANMAKU").containsAll(request.sources()))throw new BridgeException("INVALID_REQUEST",400);
        Path spool;
        try{spool=Files.createTempFile("tianmu-feedback-",".private");
            try{Files.setPosixFilePermissions(spool,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
        }catch(IOException e){throw new BridgeException("SNAPSHOT_STORAGE_UNAVAILABLE",503);}
        try(var out=new BufferedOutputStream(Files.newOutputStream(spool))){
            tx.execute(status->{write(owner,request,out);return null;});out.flush();return new Export(spool,Files.size(spool));
        }catch(RuntimeException|IOException e){try{Files.deleteIfExists(spool);}catch(IOException ignored){}if(e instanceof BridgeException b)throw b;throw new BridgeException("SNAPSHOT_EXPORT_FAILED",503);}
    }
    private void write(BusinessAuthentication.Principal principal,Request r,OutputStream out){
        long owner=Long.parseLong(principal.userId());
        if(!Objects.equals(1,db.queryForObject("SELECT COUNT(*) FROM video WHERE video_id=? AND user_id=? AND is_delete=0",Integer.class,r.videoId(),owner)))throw new BridgeException("FORBIDDEN",403);
        Double duration=db.queryForObject("SELECT duration FROM video WHERE video_id=?",Double.class,r.videoId());
        long comments=0,danmaku=0,authorComments=0,authorDanmaku=0,bytes=0;Set<Long> people=new HashSet<>();
        for(String source:List.of("DANMAKU","COMMENT")){
            if(!r.sources().contains(source))continue;String table=source.equals("COMMENT")?"comment":"bullet",key=source.equals("COMMENT")?"comment_id":"bullet_id";
            ZoneId storage=storage(table);String from=BusinessDanmakuAnalysis.wall(r.start(),storage),to=BusinessDanmakuAnalysis.wall(r.end(),storage);long cursor=0;
            while(true){
                var rows=db.queryForList("SELECT *,DATE_FORMAT(create_time,'%Y-%m-%d %H:%i:%s.%f') AS created_wall FROM "+table+" WHERE video_id=? AND create_time>=? AND create_time<? AND "+key+">? ORDER BY "+key+" LIMIT 500",r.videoId(),from,to,cursor);
                if(rows.isEmpty())break;
                Map<Long,Comment> parents=source.equals("COMMENT")?parents(r.videoId(),rows):Map.of();
                for(var row:rows){
                    long id=((Number)row.get(key)).longValue();cursor=id;Long user=positive(row.get("user_id"));boolean author=user!=null && user==owner;
                    String context=source.equals("COMMENT")?context(positive(row.get("parent_comment_id")),parents,owner):"";
                    Long at=null;if(source.equals("DANMAKU") && row.get("playback_time") instanceof Number time){double seconds=time.doubleValue();if(Double.isFinite(seconds) && seconds>=0 && duration!=null && seconds<duration)at=Math.round(seconds*1000);}
                    String text=BusinessDanmakuAnalysis.redact(Objects.toString(row.get("content"),""));if(text.length()>16384)throw new BridgeException("FEEDBACK_UNIT_TOO_LARGE",422);
                    Instant created=LocalDateTime.parse(row.get("created_wall").toString().replace(' ','T')).atZone(storage).toInstant();
                    var unit=new Unit((source.equals("COMMENT")?"C:":"D:")+id,source,Long.toString(id),text,context,author,at,user==null?null:user.toString(),created);
                    bytes+=line(out,Map.of("unit",unit));
                    if(source.equals("COMMENT")){comments++;if(author)authorComments++;}else{danmaku++;if(author)authorDanmaku++;}
                    if(comments+danmaku>20000 || bytes>67108864)throw new BridgeException("FULL_ANALYSIS_TOO_LARGE",422);
                    if(user!=null && !author)people.add(user);
                }
            }
        }
        Instant asOf=db.queryForObject("SELECT UTC_TIMESTAMP(6)",(rs,n)->rs.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant());
        line(out,Map.of("receipt",new Receipt(asOf,new Counts(comments,danmaku,authorComments,authorDanmaku,people.size()))));
    }
    private Map<Long,Comment> parents(long video,List<Map<String,Object>> page){
        Map<Long,Comment> found=new HashMap<>();Set<Long> needed=new TreeSet<>(),attempted=new HashSet<>();
        for(var row:page){long id=((Number)row.get("comment_id")).longValue();var parent=positive(row.get("parent_comment_id"));
            found.put(id,new Comment(id,parent,positive(row.get("user_id")),BusinessDanmakuAnalysis.redact(Objects.toString(row.get("content"),""))));if(parent!=null)needed.add(parent);}
        for(int depth=0;!needed.isEmpty();depth++){
            needed.removeAll(attempted);needed.removeAll(found.keySet());if(needed.isEmpty())break;if(depth>=64)throw new BridgeException("COMMENT_CONTEXT_TOO_DEEP",422);
            var ids=new ArrayList<>(needed);attempted.addAll(ids);needed.clear();
            for(int start=0;start<ids.size();start+=500){var part=ids.subList(start,Math.min(ids.size(),start+500));List<Object> args=new ArrayList<>();args.add(video);args.addAll(part);
                var rows=db.queryForList("SELECT comment_id,parent_comment_id,user_id,content FROM comment WHERE video_id=? AND comment_id IN ("+String.join(",",Collections.nCopies(part.size(),"?"))+")",args.toArray());
                for(var row:rows){long id=((Number)row.get("comment_id")).longValue();Long parent=positive(row.get("parent_comment_id"));found.put(id,new Comment(id,parent,positive(row.get("user_id")),BusinessDanmakuAnalysis.redact(Objects.toString(row.get("content"),""))));if(parent!=null)needed.add(parent);}
            }
        }
        return found;
    }
    private String context(Long parent,Map<Long,Comment> rows,long owner){
        var chain=new ArrayList<Map<String,Object>>();Set<Long> seen=new HashSet<>();
        while(parent!=null){
            if(!seen.add(parent))throw new BridgeException("INVALID_COMMENT_CONTEXT",422);if(seen.size()>64)throw new BridgeException("COMMENT_CONTEXT_TOO_DEEP",422);
            var p=rows.get(parent);if(p==null){chain.add(Map.of("id",parent.toString(),"parentUnavailable",true));break;}
            chain.add(Map.of("id",Long.toString(p.id()),"author",p.user()!=null && p.user()==owner,"text",p.text()));parent=p.parent();
        }
        try{String text=json.writeValueAsString(chain);if(text.length()>65536)throw new BridgeException("FEEDBACK_UNIT_TOO_LARGE",422);return chain.isEmpty()?"":text;}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new BridgeException("SNAPSHOT_EXPORT_FAILED",503);}
    }
    private ZoneId storage(String table){
        String type=db.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='create_time'",String.class,table);
        if(!Set.of("timestamp","datetime").contains(Objects.toString(type,"")))throw new BridgeException("UNSUPPORTED_TIME_COLUMN",503);
        return "timestamp".equals(type)?ZoneOffset.UTC:ZoneId.of(config.datetimeStorageZone());
    }
    private int line(OutputStream out,Object value){try{byte[] bytes=json.writeValueAsBytes(value);if(bytes.length>262144)throw new BridgeException("FEEDBACK_UNIT_TOO_LARGE",422);out.write(bytes);out.write('\n');return bytes.length+1;}catch(IOException e){throw new BridgeException("SNAPSHOT_EXPORT_FAILED",503);}}
    private static Long positive(Object value){return value instanceof Number n && n.longValue()>0?n.longValue():null;}
}
