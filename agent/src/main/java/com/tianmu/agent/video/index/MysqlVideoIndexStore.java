package com.tianmu.agent.video.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

@Component @ConditionalOnProperty(name="agent.storage.mode",havingValue="mysql")
public final class MysqlVideoIndexStore implements VideoIndexStore {
    private final JdbcTemplate db;private final ObjectMapper json;
    public MysqlVideoIndexStore(HikariDataSource source,Flyway migrations,ObjectMapper json){db=new JdbcTemplate(source);this.json=json;}
    public VideoIndex get(AccessScope scope,long video){
        var rows=db.queryForList("SELECT document FROM agent_video_index WHERE tenant_id=? AND owner_user_id=? AND video_id=?",String.class,scope.tenantId(),scope.userId(),video);
        if(rows.isEmpty())return null;
        try{return json.readValue(rows.get(0),VideoIndex.class);}catch(Exception e){throw new IllegalStateException("Invalid stored index",e);}
    }
    public VideoIndex start(AccessScope scope,VideoIndex proposed){
        for(int attempt=0;attempt<4;attempt++){
            var old=get(scope,proposed.videoId());if(old!=null && old.active())return old;
            if(old!=null){if(replace(scope,old,proposed))return proposed;continue;}
            try{db.update("INSERT INTO agent_video_index(tenant_id,owner_user_id,video_id,document,document_hash) VALUES(?,?,?,?,?)",scope.tenantId(),scope.userId(),proposed.videoId(),encode(proposed),hash(proposed));return proposed;}
            catch(DuplicateKeyException race){ /* Another instance admitted this video. */ }
        }
        throw new com.tianmu.agent.core.AgentException("INDEX_CONFLICT","Index changed; retry");
    }
    public boolean replace(AccessScope scope,VideoIndex expected,VideoIndex updated){
        return db.update("UPDATE agent_video_index SET document=?,document_hash=?,updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND owner_user_id=? AND video_id=? AND document_hash=?",encode(updated),hash(updated),scope.tenantId(),scope.userId(),expected.videoId(),hash(expected))==1;
    }
    private String encode(VideoIndex value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String hash(VideoIndex value){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
