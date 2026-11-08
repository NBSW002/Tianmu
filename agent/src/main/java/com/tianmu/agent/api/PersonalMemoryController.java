package com.tianmu.agent.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.RequestContext;
import com.tianmu.agent.memory.PersonalMemory;
import com.tianmu.agent.memory.PersonalMemory.*;
import com.tianmu.agent.memory.PersonalMemoryStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Manual management only; callers cannot nominate chat or business sources. Disabled by default. */
@RestController
@RequestMapping("/api/agent")
@ConditionalOnProperty(name="agent.memory.enabled",havingValue="true")
public final class PersonalMemoryController {
    public record Change(long expectedVersion,Content content) {
        @JsonAnySetter public void rejectUnknown(String key,JsonNode value){throw PersonalMemory.invalid();}
    }
    public record SettingChange(long expectedVersion,Mode mode) {
        @JsonAnySetter public void rejectUnknown(String key,JsonNode value){throw PersonalMemory.invalid();}
    }
    private final IdentityPort identity;
    private final PersonalMemoryStore memories;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.memory.MemoryLedger ledger;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.tianmu.agent.memory.MemoryBackground background;
    public record AutomaticChange(long expectedVersion,boolean enabled){@JsonAnySetter public void rejectUnknown(String key,JsonNode value){throw PersonalMemory.invalid();}}
    public record TopicChange(String name){@JsonAnySetter public void rejectUnknown(String key,JsonNode value){throw PersonalMemory.invalid();}}
    public PersonalMemoryController(IdentityPort identity,PersonalMemoryStore memories){this.identity=identity;this.memories=memories;}
    private String token(String auth) {
        if(auth==null || !auth.startsWith("Bearer ") || auth.substring(7).isBlank())throw new AgentException("UNAUTHORIZED","Login required");
        return auth.substring(7);
    }
    private AccessScope scope(String auth,Scope target) {
        String token=token(auth);
        if(target.type()==ScopeType.SESSION)throw PersonalMemory.invalid();
        return target.type()!=ScopeType.VIDEO?identity.authenticate(token):identity.forCreate(token,"",null,new RequestContext(target.key()),null);
    }
    private AccessScope existing(String auth,UUID id) {
        var owner=identity.authenticate(token(auth));return scope(auth,memories.scope(owner,id));
    }
    private <T>ResponseEntity<T> response(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
    @PutMapping("/memory-settings/automatic") public ResponseEntity<Settings> automatic(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody AutomaticChange input){
        var owner=identity.authenticate(token(auth));ledger.auto(owner,input.expectedVersion(),input.enabled());return response(memories.settings(owner));
    }
    @GetMapping("/memory-topics") public ResponseEntity<?> topics(@RequestHeader(value="Authorization",required=false)String auth){return response(ledger.topics(identity.authenticate(token(auth))));}
    @PostMapping("/memory-topics") public ResponseEntity<?> topic(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody TopicChange input){return response(java.util.Map.of("id",ledger.topic(identity.authenticate(token(auth)),input.name()).toString()));}
    @GetMapping("/runs/{runId}/memory") public ResponseEntity<?> used(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID runId){
        var owner=identity.forRun(token(auth),runId);var boundary=ledger.boundary(owner,runId);
        if(boundary==null)return response(java.util.Map.of("items",java.util.List.of()));
        var items=new java.util.ArrayList<java.util.Map<String,Object>>();
        boolean visible=ledger.historyAllowed(owner,runId);
        var seen=new java.util.HashSet<String>();
        for(var ref:boundary.refs()){
            if(!seen.add(ref.id()+":"+ref.version()))continue;
            var item=new java.util.LinkedHashMap<String,Object>();item.put("id",ref.id());item.put("version",ref.version());item.put("available",visible);
            item.put("initial",ref.initial());
            if(visible)try{item.put("content",ledger.read(owner,ref.id(),ref.version(),ref.sequence()==0?boundary.sequence():ref.sequence()).content());}catch(AgentException e){if(!e.code().equals("MEMORY_NOT_FOUND"))throw e;item.put("available",false);}
            items.add(item);
        }
        return response(java.util.Map.of("items",items,"snapshotSequence",boundary.sequence()));
    }
    @GetMapping("/runs/{runId}/memory-jobs") public ResponseEntity<?> jobs(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID runId){return response(background.jobs(identity.forRun(token(auth),runId),runId));}
    @PostMapping("/runs/{runId}/memory-jobs/{jobId}/retry") public ResponseEntity<?> retry(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID runId,@PathVariable UUID jobId,@RequestHeader("Idempotency-Key")String key){return response(background.retry(identity.forRun(token(auth),runId),runId,jobId,key));}
    @GetMapping("/memory-settings") public ResponseEntity<Settings> settings(@RequestHeader(value="Authorization",required=false)String auth) {
        return response(memories.settings(identity.authenticate(token(auth))));
    }
    @PatchMapping("/memory-settings") public ResponseEntity<Settings> settings(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody SettingChange input) {
        return response(memories.settings(identity.authenticate(token(auth)),input.expectedVersion(),input.mode()));
    }
    @GetMapping("/memories") public ResponseEntity<Page> list(@RequestHeader(value="Authorization",required=false)String auth,
            @RequestParam(defaultValue="USER")ScopeType scopeType,@RequestParam(defaultValue="self")String scopeKey,
            @RequestParam(defaultValue="9223372036854775807")long beforeSequence,@RequestParam(defaultValue="20")int limit,
            @RequestParam(defaultValue="")String query) {
        Scope target=new Scope(scopeType,scopeKey);return response(memories.list(scope(auth,target),target,beforeSequence,limit,query));
    }
    @GetMapping("/memories/{id}") public ResponseEntity<Entry> get(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable UUID id) {
        return response(memories.get(existing(auth,id),id));
    }
    @PostMapping("/memories") public ResponseEntity<Receipt> create(@RequestHeader(value="Authorization",required=false)String auth,
            @RequestHeader("Idempotency-Key")String key,@RequestBody Change input) {
        if(input.content()==null || input.expectedVersion()!=0)throw PersonalMemory.invalid();
        return response(memories.create(scope(auth,input.content().scope()),input.content(),key));
    }
    @PatchMapping("/memories/{id}") public ResponseEntity<Receipt> update(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody Change input) {
        if(input.content()==null)throw PersonalMemory.invalid();
        var old=existing(auth,id);var next=scope(auth,input.content().scope());
        if(!old.tenantId().equals(next.tenantId()) || !old.userId().equals(next.userId()))throw PersonalMemory.missing();
        // Preserve only currently reauthenticated grants for the old and new resource.
        var readable=new java.util.HashSet<>(old.readableVideoIds());readable.addAll(next.readableVideoIds());
        var grants=new AccessScope(old.tenantId(),old.userId(),java.util.Set.of(),"",readable);
        return response(memories.update(grants,id,input.expectedVersion(),input.content(),key));
    }
    @DeleteMapping("/memories/{id}") public ResponseEntity<Receipt> delete(@RequestHeader(value="Authorization",required=false)String auth,
            @PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestParam long expectedVersion) {
        return response(memories.delete(existing(auth,id),id,expectedVersion,key));
    }
    @PostMapping("/memories/reset") public ResponseEntity<Receipt> reset(@RequestHeader(value="Authorization",required=false)String auth,
            @RequestHeader("Idempotency-Key")String key) {
        return response(memories.reset(identity.authenticate(token(auth)),key));
    }
}
