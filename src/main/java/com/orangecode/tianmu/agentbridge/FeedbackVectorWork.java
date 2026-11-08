package com.orangecode.tianmu.agentbridge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Durable, coalesced CDC work; completion is fenced by the lease and captured generation. */
public final class FeedbackVectorWork {
    public static final class LongAsNumber extends com.fasterxml.jackson.databind.JsonSerializer<Long> {
        @Override public void serialize(Long value,com.fasterxml.jackson.core.JsonGenerator out,com.fasterxml.jackson.databind.SerializerProvider provider)throws java.io.IOException {out.writeNumber(value);}
    }
    public record Lease(@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=LongAsNumber.class) long videoId,
                        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=LongAsNumber.class) long generation,
                        String token,String tenantId,String ownerId) {}
    private final JdbcTemplate db; private final TransactionTemplate tx; private final String tenant;
    public FeedbackVectorWork(JdbcTemplate db,String tenant){this.db=db;this.tenant=tenant;this.tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));}
    public void changed(long video){
        if(video<=0)throw new IllegalArgumentException("Invalid video");
        db.update("INSERT INTO feedback_vector_work(video_id,next_at) VALUES(?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 SECOND)) ON DUPLICATE KEY UPDATE generation=generation+1,attempts=0,next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 SECOND)",video);
    }
    public Lease claim(){return tx.execute(s->{
        var rows=db.queryForList("SELECT video_id,generation FROM feedback_vector_work WHERE generation>completed_generation AND attempts<10 AND next_at<=UTC_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6)) ORDER BY next_at,video_id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(rows.isEmpty())return null;var row=rows.get(0);long video=((Number)row.get("video_id")).longValue(),generation=((Number)row.get("generation")).longValue();
        String token=UUID.randomUUID().toString();
        db.update("UPDATE feedback_vector_work SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 20 MINUTE),attempts=attempts+1 WHERE video_id=?",token,video);
        var owners=db.queryForList("SELECT user_id FROM video WHERE video_id=? AND is_delete=0",video);
        return new Lease(video,generation,token,tenant,owners.isEmpty()?null:owners.get(0).get("user_id").toString());
    });}
    public void require(Lease lease){
        Integer count=db.queryForObject("SELECT COUNT(*) FROM feedback_vector_work WHERE video_id=? AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) AND generation>=?",Integer.class,lease.videoId(),lease.token(),lease.generation());
        if(!Objects.equals(count,1))throw new BridgeException("STALE_VECTOR_LEASE",409);
    }
    public void finish(Lease lease,boolean success){
        int changed=db.update("UPDATE feedback_vector_work SET completed_generation=IF(?,GREATEST(completed_generation,?),completed_generation),lease_token=NULL,lease_until=NULL,next_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 60 SECOND),last_error=IF(?,NULL,'VECTOR_WORK_FAILED'),attempts=IF(?,0,attempts) WHERE video_id=? AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)",success,lease.generation(),success,success,lease.videoId(),lease.token());
        if(changed!=1)throw new BridgeException("STALE_VECTOR_LEASE",409);
    }
}
