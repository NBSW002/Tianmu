package com.tianmu.agent.feedback;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import com.tianmu.agent.runtime.control.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Application workflow. The main agent never transports page history or chooses retry/lease policy. */
public final class FeedbackAnalysisService {
    private static final com.fasterxml.jackson.databind.ObjectMapper PRESENTATION=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final FeedbackJobStore store;private final FeedbackObjects objects;private final FeedbackSnapshotPort source;
    private final FeedbackModels models;private final FeedbackPolicy policy;private final FeedbackPlanner planner;
    private final Map<UUID,CancellationToken> running=new ConcurrentHashMap<>();
    private record CacheKey(String tenant,String user,Request request,String snapshot,String model,String policy,VideoContent.Facts video){}
    private final com.github.benmanes.caffeine.cache.Cache<CacheKey,List<ResultNode>> completed=com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(16).expireAfterWrite(Duration.ofHours(1)).build();
    private CacheKey cacheKey(FeedbackJobStore.Job j){return new CacheKey(j.owner().tenantId(),j.owner().userId(),j.request(),j.snapshot().contentHash(),j.model(),j.policyHash(),j.video());}
    public FeedbackAnalysisService(FeedbackJobStore store,FeedbackObjects objects,FeedbackSnapshotPort source,FeedbackModels models,FeedbackPolicy policy){
        this.store=store;this.objects=objects;this.source=source;this.models=models;this.policy=policy;planner=new FeedbackPlanner(objects,policy);
    }
    public FeedbackJobStore.Job submit(UUID run,AccessScope owner,Request request,VideoContent.Facts video) {
        return submit(run,owner,request,video,Instant.now().plus(policy.timeout()));
    }
    public FeedbackJobStore.Job submit(UUID run,AccessScope owner,Request request,VideoContent.Facts video,Instant executionDeadline) {
        if(!owner.videoIds().contains(request.query().videoId()))throw new AgentException("FORBIDDEN","Video outside current authorization");
        request.query().checkTime(Instant.now());source.authorize(owner,request.query().videoId());
        if(request.grounding()==Grounding.VIDEO_GROUNDED && (video==null || video.videoId()!=request.query().videoId()
                || request.videoStartMs()!=null && (video.startMs()!=request.videoStartMs() || video.endMs()!=request.videoEndMs())))
            throw new AgentException("INVALID_EVIDENCE","Read the required video evidence before starting analysis");
        if(request.grounding()==Grounding.FEEDBACK_ONLY && video!=null)throw new AgentException("TASK_CONSTRAINT_VIOLATION","Video was not selected");
        var now=Instant.now();var proposed=new FeedbackJobStore.Job(UUID.randomUUID(),run,owner,request,Phase.QUEUED,now,min(now.plus(policy.timeout()),executionDeadline),policy.maxTokens(),policy.maxModelCalls(),0,0,
                models.modelName(),models.policyHash(),null,null,video,null,0,0);
        var job=store.create(proposed);
        if(!job.owner().tenantId().equals(owner.tenantId()) || !job.owner().userId().equals(owner.userId()) || !job.request().equals(request) || !Objects.equals(job.video(),video))throw new AgentException("TASK_CONSTRAINT_VIOLATION","An existing analysis has different frozen conditions");
        return job;
    }
    public FeedbackJobStore.Job owned(UUID id,AccessScope caller) {
        var j=store.get(id);if(j==null || !j.owner().tenantId().equals(caller.tenantId()) || !j.owner().userId().equals(caller.userId()))throw new AgentException("RESULT_NOT_FOUND","Analysis not found");
        if(!caller.videoIds().contains(j.request().query().videoId()))throw new AgentException("FORBIDDEN","Analysis video outside current scope");
        source.authorize(caller,j.request().query().videoId());return j;
    }
    public FeedbackJobStore.Job forRun(UUID id){return store.forRun(id);}
    public List<FeedbackJobStore.Job> active(){return store.active(32);}
    public void cancel(UUID id){var token=running.get(id);if(token!=null)token.cancel(StopReason.USER_CANCELLED);store.cancel(id);}
    public void resume(UUID id,AccessScope caller){owned(id,caller);if(!store.reauthorize(id,caller))throw new AgentException("ANALYSIS_NOT_PAUSED","Analysis is not waiting for authorization");}
    public void advance(UUID id) {advance(id,null);}
    public void advance(UUID id,CancellationToken parent) {
        var token=new CancellationToken();if(running.putIfAbsent(id,token)!=null)return;
        // The callback only propagates the fast signal; durable cancellation stays on this worker.
        var registration=parent==null?null:parent.onCancel(()->token.cancel(parent.reason()));
        FeedbackJobStore.Work work=null;Input input=null;
        try {
            token.checkpoint();
            var j=store.get(id);if(j==null || !MemoryFeedbackJobStore.active(j.phase()))return;
            if(!Instant.now().isBefore(j.deadline())){store.fail(id,"DEADLINE_EXCEEDED");return;}
            if(!j.model().equals(models.modelName()) || !j.policyHash().equals(models.policyHash())){store.fail(id,"RESOURCE_VERSION_MISMATCH");return;}
            source.authorize(j.owner(),j.request().query().videoId());
            if(j.snapshot()==null) {
                var collector=planner.collector(id,j.request());
                var receipt=source.export(j.request(),j.owner(),new CallControl(min(j.deadline(),Instant.now().plusSeconds(90)),token),rows->{token.checkpoint();collector.accept(rows);});
                var planned=collector.finish(receipt);token.checkpoint();
                if(store.initialize(id,j.version(),planned.snapshot(),j.video(),planned.inputs())){
                    var cached=completed.getIfPresent(cacheKey(store.get(id)));
                    if(cached!=null && cached.size()==planned.inputs().size())for(var result:cached){
                        token.checkpoint();var claim=store.claim(id,Instant.now(),Duration.ofSeconds(60));if(claim==null)break;
                        var batch=objects.get(claim.input(),Input.class);
                        var proposals=result.topics().stream().map(t->new Proposal(topicKey(t),t.title(),t.observation(),t.recommendation(),t.members())).toList();
                        var verified=planner.validate(claim.id().toString(),batch,proposals);if(!store.complete(claim,objects.put(id,verified)))break;
                    }
                }
                return;
            }
            work=store.claim(id,Instant.now(),Duration.ofSeconds(policy.modelTimeout().toSeconds()+30));
            if(work!=null) {
                Input stored=objects.get(work.input(),Input.class);
                var prior=work.kind()==WorkKind.CLASSIFY?store.completedBefore(id,work.ordinal()):List.<FeedbackJobStore.Work>of();
                if(work.kind()==WorkKind.CLASSIFY && prior.size()!=work.ordinal())throw new AgentException("STORAGE_CORRUPT","Classification prefix is incomplete");
                input=new Input(stored.kind(),stored.groups(),stored.kind()==WorkKind.CLASSIFY?accumulate(prior):stored.children(),stored.kind()==WorkKind.CLASSIFY?j.video():null,stored.correction());
                var claim=work;CallControl control=new CallControl(min(j.deadline(),Instant.now().plus(policy.modelTimeout())),token,new FeedbackBudget(store,work),()->{});
                List<Proposal> proposals;
                if(input.kind()==WorkKind.MERGE){
                    var leaves=new ArrayList<Topic>();
                    for(var batch:store.completedLevel(id,0))leaves.addAll(objects.get(batch.result(),ResultNode.class).topics());
                    if(!leaves.stream().map(Topic::id).toList().equals(input.children().stream().map(Topic::id).toList()))
                        throw new AgentException("STORAGE_CORRUPT","Aggregation references differ from accepted batch notes");
                    proposals=combine(leaves).stream().map(t->new Proposal(t.id(),t.title(),t.observation(),t.recommendation(),t.members())).toList();
                }
                else proposals=models.analyze(input,control);
                token.checkpoint();var result=planner.validate(work.id().toString(),input,proposals);
                store.complete(work,objects.put(id,result));return;
            }
            finishLevel(store.get(id));
        } catch(FeedbackCorrectionException e) {
            if(token.cancelled())store.cancel(id);
            else if(work!=null && input!=null)store.retry(work,e.code(),Instant.now().plusSeconds(Math.min(30,work.attempts()*2L)),3,
                    objects.put(id,new Input(input.kind(),input.groups(),input.children(),null,e.correction())));
            else store.fail(id,e.code());
        } catch(AgentException e) {
            var j=store.get(id);
            if(token.cancelled())store.cancel(id);
            else if(j!=null && Set.of("UNAUTHORIZED","AUTH_CONTEXT_EXPIRED","AUTHORIZATION_EXPIRED").contains(e.code()))store.phase(id,j.version(),Phase.WAITING_AUTH);
            else if(work!=null && Set.of("MODEL_UNAVAILABLE","MODEL_ERROR","MODEL_CALL_TIMEOUT","INVALID_ANALYSIS","BUSINESS_TIMEOUT","BUSINESS_UNAVAILABLE").contains(e.code()))
                store.retry(work,e.code(),Instant.now().plusSeconds(Math.min(30,work.attempts()*2L)),3);
            else store.fail(id,e.code());
        } catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("feedback_work_failed job={} type={} location={}",id,e.getClass().getSimpleName(),Arrays.stream(e.getStackTrace()).filter(f->f.getClassName().startsWith("com.tianmu.")).limit(5).toList());store.fail(id,"ANALYSIS_FAILED");}
        finally {if(registration!=null)registration.close();running.remove(id,token);}
    }
    private void finishLevel(FeedbackJobStore.Job job) {
        if(job==null || !MemoryFeedbackJobStore.active(job.phase()))return;
        if(job.snapshot().counts().total()==0){saveAsset(job,List.of());return;}
        var rows=store.completedLevel(job.id(),0);
        if(rows.isEmpty())return;
        if(store.hasLevel(job.id(),1)){
            rows=store.completedLevel(job.id(),1);if(rows.isEmpty())return;
            if(rows.size()!=1)throw new AgentException("STORAGE_CORRUPT","Expected one code aggregation result");
        }
        if(rows.size()==1){
            var root=objects.get(rows.get(0).result(),ResultNode.class);
            if(root.covered()!=job.snapshot().counts().total() || root.authorCovered()!=job.snapshot().counts().authorComments()+job.snapshot().counts().authorDanmaku())
                throw new AgentException("INVALID_ANALYSIS","Root coverage differs from snapshot");
            saveAsset(job,List.of(rows.get(0).result()));return;
        }
        // One deterministic fold by stable topic key. Historical leaf detail stays in its own result object.
        var children=new ArrayList<Topic>();
        for(var row:rows)children.addAll(objects.get(row.result(),ResultNode.class).topics());
        // Store references and weights, not another potentially huge copy of all accepted prose.
        var compact=children.stream().map(t->new Topic(t.id(),t.title(),"","",t.members(),t.messages(),t.authorMessages())).toList();
        store.planLevel(job.id(),job.version(),1,List.of(objects.put(job.id(),new Input(WorkKind.MERGE,List.of(),compact,null))));
    }
    private static String topicKey(Topic t){return t.id().substring(t.id().lastIndexOf(':')+1);}
    private List<Topic> accumulate(List<FeedbackJobStore.Work> rows){
        var topics=new ArrayList<Topic>();for(var row:rows)topics.addAll(objects.get(row.result(),ResultNode.class).topics());return combine(topics);
    }
    private static List<Topic> combine(List<Topic> topics){
        var grouped=new LinkedHashMap<String,List<Topic>>();
        for(var t:topics)grouped.computeIfAbsent(topicKey(t),ignored->new ArrayList<>()).add(t);
        return grouped.entrySet().stream().map(entry->{var rows=entry.getValue();
            return new Topic(entry.getKey(),rows.get(0).title(),
                    FeedbackTopicNotes.present(rows.stream().map(Topic::observation).toList(),2400,"观察"),
                    FeedbackTopicNotes.present(rows.stream().map(Topic::recommendation).toList(),1200,"建议"),
                    rows.stream().map(Topic::id).toList(),rows.stream().mapToLong(Topic::messages).reduce(0,Math::addExact),
                    rows.stream().mapToLong(Topic::authorMessages).reduce(0,Math::addExact));
        }).toList();
    }
    private void saveAsset(FeedbackJobStore.Job j,List<Blob> roots) {
        var asset=new Asset(j.id(),j.runId(),j.owner().tenantId(),j.owner().userId(),j.request(),j.snapshot(),roots,j.model(),j.policyHash(),j.video());
        if(store.ready(j.id(),j.version(),objects.put(j.id(),asset)))completed.put(cacheKey(j),store.completedLevel(j.id(),0).stream().map(w->objects.get(w.result(),ResultNode.class)).toList());
    }
    public Asset asset(UUID id,AccessScope caller,Request expected) {
        var j=owned(id,caller);if(j.phase()!=Phase.READY || j.asset()==null)throw new AgentException("ANALYSIS_NOT_READY","Analysis is not complete");
        Asset asset=objects.get(j.asset(),Asset.class);asset.requireCompatible(caller,expected);
        if(!asset.model().equals(models.modelName()) || !asset.policyHash().equals(models.policyHash()))throw new AgentException("RESOURCE_VERSION_MISMATCH","Analysis rules or model changed");
        return asset;
    }
    public List<Topic> topics(Asset asset){return asset.roots().isEmpty()?List.of():objects.get(asset.roots().get(0),ResultNode.class).topics();}
    public Asset historicalAsset(UUID id,AccessScope caller){
        var job=owned(id,caller);if(job.phase()!=Phase.READY || job.asset()==null)throw new AgentException("RESULT_NOT_FOUND","Completed analysis not found");
        var asset=objects.get(job.asset(),Asset.class);asset.requireCompatible(caller,asset.request());return asset;
    }
    public Map<String,Object> search(Asset asset,String query,int offset,int limit){
        if(query==null || query.isBlank() || query.length()>120 || offset<0 || offset>5000 || limit<1 || limit>10)throw new AgentException("INVALID_ARGUMENTS","Invalid feedback search");
        var literal=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(query.strip()),java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.UNICODE_CASE);
        int matches=0;var selected=new ArrayList<Unit>();
        for(var chunk:asset.snapshot().chunks())for(var unit:objects.get(chunk,Unit[].class)){
            if(literal.matcher(unit.text()).find() || literal.matcher(unit.replyContext()).find()){
                if(matches>=offset && selected.size()<limit)selected.add(unit);matches++;
            }
        }
        var wanted=selected.stream().map(Unit::id).collect(java.util.stream.Collectors.toSet());var leafByOriginal=new HashMap<String,String>();
        for(var work:store.completedLevel(asset.jobId(),0)){
            var input=objects.get(work.input(),Input.class);var result=objects.get(work.result(),ResultNode.class);
            for(var topic:result.topics())for(var group:input.groups())if(topic.members().contains(group.id()))for(String original:group.originalIds())if(wanted.contains(original))leafByOriginal.put(original,topic.id());
            if(leafByOriginal.size()==wanted.size())break;
        }
        if(leafByOriginal.size()!=wanted.size())throw new AgentException("STORAGE_CORRUPT","Search hit lacks accepted classification provenance");
        var records=selected.stream().map(u->{var row=new LinkedHashMap<String,Object>(originalView(u));row.put("topic_id",leafByOriginal.get(u.id()));
            String text=literal.matcher(u.text()).find()?u.text():u.replyContext();var hit=literal.matcher(text);hit.find();
            int from=Math.max(0,hit.start()-60),to=Math.min(text.length(),hit.end()+60);
            if(from>0 && Character.isLowSurrogate(text.charAt(from)))from--;if(to<text.length() && Character.isLowSurrogate(text.charAt(to)))to++;
            row.put("match_excerpt",text.substring(from,to));return row;}).toList();
        int next=Math.min(matches,offset+records.size());
        return com.tianmu.agent.core.LookupFacts.normalize(Map.of("query",query,"matches",records,"matched_records",matches,"snapshot_counts",asset.snapshot().counts(),"request",asset.request(),"data_as_of",asset.snapshot().dataAsOf(),
                "coverage","MATCHED_RECORDS_ONLY","match_method","LITERAL_TEXT_OR_REPLY_CONTEXT","next_offset",next,"complete",next==matches));
    }
    public Map<String,Object> notes(Asset asset,int offset,int limit){
        if(offset<0 || limit<1 || limit>10)throw new AgentException("INVALID_ARGUMENTS","Invalid observation page");
        var all=new ArrayList<Map<String,Object>>();for(var work:store.completedLevel(asset.jobId(),0))for(var topic:objects.get(work.result(),ResultNode.class).topics())all.add(topicView(topic));
        return page("observations",all,offset,limit);
    }
    public byte[] report(Asset asset){
        var details=new ArrayList<FeedbackReportRenderer.Detail>();
        var batches=store.completedLevel(asset.jobId(),0);
        if(asset.snapshot().counts().total()>0 && batches.isEmpty())throw new AgentException("STORAGE_CORRUPT","Accepted batch notes are missing");
        // Read each immutable batch once, rather than loading a work/input/result repeatedly for every topic.
        for(var work:batches){
            var input=objects.get(work.input(),Input.class);var result=objects.get(work.result(),ResultNode.class);
            for(var topic:result.topics())details.add(new FeedbackReportRenderer.Detail(topic,batches.size()==1?0:1,
                    input.groups().stream().filter(g->topic.members().contains(g.id())).limit(2).map(g->(g.author()?"作者：":"观众：")+preview(g.text(),400)+(g.text().codePointCount(0,g.text().length())>400?"（预览，完整原文可回查）":"")).toList()));
        }
        var daily=new TreeMap<String,Long>();
        for(var chunk:asset.snapshot().chunks())for(Unit unit:objects.get(chunk,Unit[].class))if(unit.createdAt()!=null)daily.merge(unit.createdAt().atZone(DanmakuTypes.ZONE).toLocalDate().toString(),1L,Long::sum);
        // All-retained ranges may span years; collapse older days into monthly buckets before rendering.
        if(daily.size()>90){var monthly=new TreeMap<String,Long>();daily.forEach((day,n)->monthly.merge(day.substring(0,7),n,Long::sum));daily=monthly;}
        return FeedbackReportRenderer.render(asset,topics(asset),details,daily);
    }
    public Map<String,Object> read(Asset asset,String section,int offset,int limit) {
        return read(asset,section,null,offset,limit);
    }
    private FeedbackJobStore.Work topicWork(Asset asset,String topic){
        try{
            int at=topic.lastIndexOf(":T");var work=store.work(UUID.fromString(topic.substring(0,at)));
            if(work==null || !work.jobId().equals(asset.jobId()) || !work.state().equals("COMPLETE"))throw new IllegalArgumentException();return work;
        }catch(RuntimeException invalid){throw new AgentException("RESULT_NOT_FOUND","Topic is not part of this analysis");}
    }
    public Topic topic(Asset asset,String id){return objects.get(topicWork(asset,id).result(),ResultNode.class).topics().stream().filter(t->t.id().equals(id)).findFirst().orElseThrow(()->new AgentException("RESULT_NOT_FOUND","Topic not found"));}
    private static String preview(String s,int max){return s.substring(0,s.offsetByCodePoints(0,Math.min(max,s.codePointCount(0,s.length()))));}
    private static Map<String,Object> topicView(Topic t){return Map.of("id",t.id(),"title",t.title(),"observation",t.observation(),"recommendation",t.recommendation(),"messages",t.messages(),"author_messages",t.authorMessages());}
    private static Map<String,Object> groupView(Group g){return Map.of("id",g.id(),"source",g.source().name(),"text_preview",preview(g.text(),240),"context_preview",preview(g.replyContext(),240),"messages",g.messages(),"author",g.author());}
    private static Map<String,Object> originalView(Unit u){var m=new LinkedHashMap<String,Object>();m.put("id",u.id());m.put("source",u.source().name());m.put("text_preview",preview(u.text(),240));m.put("context_preview",preview(u.replyContext(),240));m.put("author",u.author());m.put("playback_ms",u.playbackMs());return m;}
    private static Map<String,Object> page(String key,List<Map<String,Object>> all,int offset,int limit){
        int from=Math.min(offset,all.size()),end=from,bytes=0;var out=new ArrayList<Map<String,Object>>();
        while(end<all.size() && out.size()<limit){var item=all.get(end);int n=PRESENTATION.valueToTree(item).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(!out.isEmpty() && bytes+n>16000)break;out.add(item);bytes+=n;end++;}
        return Map.of(key,out,"next_offset",end,"complete",end==all.size());
    }
    public Map<String,Object> originalText(Asset asset,String topicId,String originalId,int offset,int limit){
        var selected=topic(asset,topicId);var work=topicWork(asset,topicId);
        if(work.kind()!=WorkKind.CLASSIFY || objects.get(work.input(),Input.class).groups().stream().filter(g->selected.members().contains(g.id())).noneMatch(g->g.originalIds().contains(originalId)))throw new AgentException("RESULT_NOT_FOUND","Original is outside this leaf topic");
        for(Blob chunk:asset.snapshot().chunks())for(Unit unit:objects.get(chunk,Unit[].class))if(unit.id().equals(originalId)){
            try{var visible=new LinkedHashMap<String,Object>();visible.put("id",unit.id());visible.put("source",unit.source());visible.put("text",unit.text());visible.put("reply_context",unit.replyContext());visible.put("author",unit.author());visible.put("playback_ms",unit.playbackMs());visible.put("created_at",unit.createdAt());
                return Map.of("original_id",originalId,"format","json","page",com.tianmu.agent.resource.TextPage.read(PRESENTATION.writeValueAsString(visible),offset,limit));}
            catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new AgentException("STORAGE_CORRUPT","Invalid original");}
        }
        throw new AgentException("STORAGE_CORRUPT","Original not found");
    }
    public Map<String,Object> read(Asset asset,String section,String topicId,int offset,int limit) {
        return PRESENTATION.convertValue(readRaw(asset,section,topicId,offset,limit),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    }
    private Map<String,Object> readRaw(Asset asset,String section,String topicId,int offset,int limit) {
        if(offset<0 || limit<1 || limit>10)throw new AgentException("INVALID_ARGUMENTS","Invalid analysis result page");
        if(section.equals("summary"))return Map.of("job_id",asset.jobId(),"status","READY","counts",asset.snapshot().counts(),"request",asset.request(),"data_as_of",asset.snapshot().dataAsOf(),"model",asset.model(),"grounding",asset.request().grounding(),"limitations","Coverage is checked by code; topic meaning is model-generated. Sampled video frames do not verify sound or continuous actions.");
        if(section.equals("children")){
            if(topicId==null)throw new AgentException("INVALID_ARGUMENTS","Select a topic before reading its children");
            var selected=topic(asset,topicId);var work=topicWork(asset,topicId);
            if(work.kind()==WorkKind.MERGE){var all=selected.members();int from=Math.min(offset,all.size()),end=Math.min(all.size(),from+limit);
                return page("topics",all.stream().map(id->topicView(topic(asset,id))).toList(),offset,limit);}
            var input=objects.get(work.input(),Input.class);var groups=input.groups().stream().filter(g->selected.members().contains(g.id())).toList();
            int from=Math.min(offset,groups.size()),end=Math.min(groups.size(),from+limit);
            return page("groups",groups.stream().map(FeedbackAnalysisService::groupView).toList(),offset,limit);
        }
        if(section.equals("originals")){
            if(topicId==null)throw new AgentException("INVALID_ARGUMENTS","Select a leaf topic before reading originals");
            var selected=topic(asset,topicId);var work=topicWork(asset,topicId);
            if(work.kind()!=WorkKind.CLASSIFY)throw new AgentException("INVALID_ARGUMENTS","Descend through children to a leaf topic first");
            var ids=objects.get(work.input(),Input.class).groups().stream().filter(g->selected.members().contains(g.id())).flatMap(g->g.originalIds().stream()).toList();
            int from=Math.min(offset,ids.size()),end=Math.min(ids.size(),from+limit);var wanted=new HashSet<>(ids.subList(from,end));Map<String,Unit> found=new HashMap<>();
            for(Blob chunk:asset.snapshot().chunks()){
                for(Unit unit:objects.get(chunk,Unit[].class))if(wanted.contains(unit.id()))found.put(unit.id(),unit);
                if(found.size()==wanted.size())break;
            }
            if(found.size()!=wanted.size())throw new AgentException("STORAGE_CORRUPT","Original evidence coverage mismatch");
            return Map.of("originals",ids.subList(from,end).stream().map(found::get).map(FeedbackAnalysisService::originalView).toList(),"next_offset",end,"complete",end==ids.size());
        }
        if(!section.equals("topics"))throw new AgentException("INVALID_ARGUMENTS","Read summary, topics, children or originals");
        var all=topics(asset);int from=Math.min(offset,all.size()),end=Math.min(all.size(),from+limit);
        return page("topics",all.stream().map(FeedbackAnalysisService::topicView).toList(),offset,limit);
    }
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
}
