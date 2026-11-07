package com.tianmu.agent.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.feedback.FeedbackModels.*;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackResultCacheTest {
    JobFixture fixture=new JobFixture();
    static final class JobFixture {
        FeedbackJobStore.Job job(String user,String model,String policy){return new FeedbackJobStore.Job(UUID.randomUUID(),UUID.randomUUID(),new AccessScope("tenant",user,Set.of(1001L)),new Request(new DanmakuTypes.Query(1001,LocalDateTime.of(2026,11,1,0,0),LocalDateTime.of(2026,11,2,0,0)),Set.of(Source.COMMENT),Grounding.FEEDBACK_ONLY,null,null,"feedback-pipeline-v2"),Phase.ANALYZING,Instant.now(),Instant.now().plusSeconds(30),100000,20,0,0,model,policy,null,null,null,null,0,0);}
    }
    Input input(String text){return new Input(WorkKind.CLASSIFY,List.of(new Group("G1",Source.COMMENT,text,"",false,null,1,List.of("C:1"))),List.of(),null);}
    List<Proposal> proposals(){return List.of(new Proposal("T1","话题","原话支持的观察","",List.of("G1")));}
    @Test void keysSeparateOwnershipSourceContentModelAndPolicyButNotProducerIds(){
        var c=new FeedbackResultCache(new ObjectMapper(),FeedbackReusePolicy.defaults(),null);var a=fixture.job("u","m","p");String key=c.key(a,input("正向观点"));
        assertEquals(key,c.key(fixture.job("u","m","p"),input("正向观点")));
        assertNotEquals(key,c.key(fixture.job("v","m","p"),input("正向观点")));assertNotEquals(key,c.key(a,input("相反观点")));
        assertNotEquals(key,c.key(fixture.job("u","new-model","p"),input("正向观点")));assertNotEquals(key,c.key(fixture.job("u","m","new-policy"),input("正向观点")));
    }
    @Test void configuredWindowAndDisableAreEnforcedUsingCreationTime(){
        var now=new AtomicReference<>(Instant.now());Clock clock=new Clock(){public java.time.ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId z){return this;}public Instant instant(){return now.get();}};
        var c=new FeedbackResultCache(new ObjectMapper(),new FeedbackReusePolicy(true,Duration.ofSeconds(5),8),null,clock);var j=fixture.job("u","m","p");var i=input("观点");var key=c.key(j,i);c.put(key,j,i,proposals());assertNotNull(c.get(key,j));now.set(now.get().plusSeconds(5));assertNull(c.get(key,j));
        assertNull(new FeedbackResultCache(new ObjectMapper(),new FeedbackReusePolicy(true,Duration.ZERO,8),null).key(j,i));
    }
    @Test void mergeCacheRebindsCanonicalChildrenToTheCurrentJobsEvidence(){
        var c=new FeedbackResultCache(new ObjectMapper(),FeedbackReusePolicy.defaults(),null);var j=fixture.job("u","m","p");
        var old=new Topic("old-work:T1","声音","原声和配乐偏好","",List.of("G1"),1,0);var current=new Topic("new-work:T1",old.title(),old.observation(),old.recommendation(),old.members(),1,0);
        var before=new Input(WorkKind.MERGE,List.of(),List.of(old),null);var after=new Input(WorkKind.MERGE,List.of(),List.of(current),null);
        assertEquals(c.key(j,before),c.key(j,after));c.put(c.key(j,before),j,before,List.of(new Proposal("T1","声音","两种偏好","",List.of(old.id()))));
        assertEquals(List.of(current.id()),FeedbackResultCache.restore(after,c.get(c.key(j,after),j)).get(0).members());
    }
    @Test void sharedWaiterDoesNotCancelLeaderAndAcceptedValueCanBeReused()throws Exception {
        var c=new FeedbackResultCache(new ObjectMapper(),FeedbackReusePolicy.defaults(),null);var j=fixture.job("u","m","p");var i=input("观点");String key=c.key(j,i);
        var leaderControl=new CallControl(Instant.now().plusSeconds(5),new CancellationToken());var leader=c.awaitOrLead(key,j,leaderControl);assertNull(leader.entry());
        var pool=Executors.newSingleThreadExecutor();var token=new CancellationToken();
        try{var waiter=pool.submit(()->c.awaitOrLead(key,j,new CallControl(Instant.now().plusSeconds(5),token)));token.cancel(StopReason.USER_CANCELLED);assertThrows(ExecutionException.class,()->waiter.get(2,TimeUnit.SECONDS));
            c.put(key,j,i,proposals(),leader);try(var hit=c.awaitOrLead(key,j,leaderControl)){assertNotNull(hit.entry());assertEquals(proposals(),hit.entry().proposals());}
        }finally{leader.close();pool.shutdownNow();}
    }
    @Test void leaderFailureReleasesElectionWithoutCachingFailure(){
        var c=new FeedbackResultCache(new ObjectMapper(),FeedbackReusePolicy.defaults(),null);var j=fixture.job("u","m","p");var i=input("观点");String key=c.key(j,i);var control=new CallControl(Instant.now().plusSeconds(5),new CancellationToken());
        try(var first=c.awaitOrLead(key,j,control)){assertNull(first.entry());}
        try(var second=c.awaitOrLead(key,j,control)){assertNull(second.entry());c.put(key,j,i,proposals(),second);}assertNotNull(c.get(key,j));
    }
}
