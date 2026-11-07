package com.tianmu.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Protected run storage, never a Trace-detail dependency. Only isolated completed reads are replayable. */
@Component
@ConditionalOnProperty(name="agent.storage.mode",havingValue="mysql")
public final class ToolBatchLedger {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public ToolBatchLedger(DataSource source,ObjectMapper json){db=new JdbcTemplate(source);db.setQueryTimeout(3);this.json=json.copy().findAndRegisterModules();}
    public static String id(RunState run){return run.id+":"+run.snapshot().steps();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new AgentException("STORAGE_CORRUPT","Invalid batch record");}}
    public void prepare(RunState run,List<ToolCall> calls,boolean parallel){
        run.checkpoint();run.verifyMemory();
        for(int index=0;index<calls.size();index++){
            var call=calls.get(index);String body=encode(call),hash=com.tianmu.agent.adapters.storage.FileObjectStore.hash(body.getBytes(StandardCharsets.UTF_8));
            db.update("INSERT INTO agent_tool_call(tenant_id,owner_user_id,run_id,batch_id,call_index,call_id,tool_name,tool_version,arguments_json,arguments_hash,execution_mode) VALUES(?,?,?,?,?,?,?,'native-v1',?,?,?) ON DUPLICATE KEY UPDATE call_index=call_index",run.owner.tenantId(),run.owner.userId(),run.id.toString(),id(run),index,call.providerCallId()==null?"native-"+index:call.providerCallId(),call.name(),body,hash,parallel?"PARALLEL":"SERIAL");
            String saved=db.queryForObject("SELECT arguments_hash FROM agent_tool_call WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND batch_id=? AND call_index=?",String.class,run.owner.tenantId(),run.owner.userId(),run.id.toString(),id(run),index);
            if(!hash.equals(saved))throw new AgentException("STORAGE_FENCED","Batch plan changed after admission");
        }
    }
    public ToolResult replay(RunState run,int index){
        run.checkpoint();run.verifyMemory();
        var values=db.queryForList("SELECT result_json FROM agent_tool_call WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND batch_id=? AND call_index=? AND state='SUCCEEDED' AND execution_mode='PARALLEL'",String.class,run.owner.tenantId(),run.owner.userId(),run.id.toString(),id(run),index);
        if(values.isEmpty())return null;
        try{return json.readValue(values.get(0),ToolResult.class);}catch(Exception e){throw new AgentException("STORAGE_CORRUPT","Invalid batch result");}
    }
    public void complete(RunState run,int index,ToolResult result,boolean success){
        run.checkpoint();run.verifyMemory();
        if(db.update("UPDATE agent_tool_call SET state=?,result_json=?,evidence_id=?,completed_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND batch_id=? AND call_index=?",success?"SUCCEEDED":"FAILED",encode(result),result.evidenceId(),run.owner.tenantId(),run.owner.userId(),run.id.toString(),id(run),index)!=1)throw new AgentException("STORAGE_FENCED","Missing batch plan");
    }
}
