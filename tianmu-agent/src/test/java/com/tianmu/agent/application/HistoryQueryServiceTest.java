package com.tianmu.agent.application;

import com.tianmu.agent.adapters.storage.MemoryRunRepository;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.MemoryTypes.Message;
import com.tianmu.agent.runtime.store.ConversationStore;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoryQueryServiceTest {
    final UUID session = UUID.randomUUID();
    final AccessScope owner = new AccessScope("t", "u", Set.of(1001L));
    final ConversationStore store = mock(ConversationStore.class);
    Message message(long sequence, String text) {
        return new Message(UUID.nameUUIDFromBytes(("message:"+sequence).getBytes(java.nio.charset.StandardCharsets.UTF_8)),UUID.randomUUID(),sequence,
                sequence%2==0?"assistant":"user",text,Instant.EPOCH);
    }
    HistoryQueryService source(List<Message> rows, long turn) {
        when(store.messages(eq(session),eq(owner),anyLong())).thenAnswer(call -> {
            long before=call.getArgument(2);
            var eligible=rows.stream().filter(m->m.sequence()<before).toList();
            return eligible.subList(Math.max(0,eligible.size()-50),eligible.size());
        });
        return new HistoryQueryService(store,session,owner,turn);
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> page) { return (List<Map<String,Object>>)page.get("messages"); }
    List<Long> sequences(Map<String,Object> page) { return rows(page).stream().map(m->((Number)m.get("sequence")).longValue()).toList(); }
    @Test void denseMatchesPageBackwardsWithoutSkippingOrRepeatingUnreturnedMessages() {
        var data=LongStream.rangeClosed(1,21).mapToObj(i->message(i,"NEEDLE "+i)).toList();
        var service=source(data,20);long cursor=Long.MAX_VALUE;List<Long> seen=new ArrayList<>();
        for(int i=0;i<10;i++) {
            var page=service.read(cursor,3,"needle");seen.addAll(sequences(page));
            long next=((Number)page.get("next_before")).longValue();assertTrue(next<cursor);
            if(!Boolean.TRUE.equals(page.get("more_available")))break;cursor=next;
        }
        assertEquals(21,new HashSet<>(seen).size());assertEquals(21,seen.size());
        assertEquals(LongStream.rangeClosed(1,21).boxed().toList(),seen.stream().sorted().toList());
    }
    @Test void sparseSearchAdvancesAnEmptyWindowAndFindsOlderMatchesWithOneReadPerCall() {
        var data=LongStream.rangeClosed(1,133).mapToObj(i->message(i,i==5 || i==70?"needle":"other")).toList();
        var service=source(data,1000);
        var first=service.read(Long.MAX_VALUE,1,"needle");
        assertTrue(rows(first).isEmpty());assertEquals(84L,first.get("next_before"));assertEquals(true,first.get("more_available"));
        verify(store,times(1)).messages(eq(session),eq(owner),anyLong());
        var second=service.read(84,1,"needle");assertEquals(List.of(70L),sequences(second));assertEquals(34L,second.get("next_before"));
        var third=service.read(34,1,"needle");assertEquals(List.of(5L),sequences(third));assertEquals(false,third.get("more_available"));
        verify(store,times(3)).messages(eq(session),eq(owner),anyLong());
    }
    @Test void exactlyFullUnmatchedWindowMayNeedOneLastEmptyReadToConfirmTheEnd() {
        var service=source(LongStream.rangeClosed(1,50).mapToObj(i->message(i,"other")).toList(),100);
        var first=service.read(Long.MAX_VALUE,4,"absent");assertTrue(rows(first).isEmpty());
        assertEquals(true,first.get("more_available"));assertEquals(1L,first.get("next_before"));
        var last=service.read(1,4,"absent");assertTrue(rows(last).isEmpty());assertEquals(false,last.get("more_available"));assertEquals(0L,last.get("next_before"));
    }
    @Test void searchUsesFullRedactedTextBeforeClippingAndDoesNotSplitAnEmojiOrChangeTheCursor() {
        var service=source(List.of(message(1,"a".repeat(1599)+"😀"+"b".repeat(600)+"needle")),3);
        var page=service.read(Long.MAX_VALUE,1,"needle");var row=rows(page).get(0);
        assertEquals("a".repeat(1599),row.get("text"));assertEquals(true,row.get("truncated"));assertEquals(1L,page.get("next_before"));
    }
    @Test void currentTurnBoundsFutureCursorsWhileGapsAndExplicitOlderCursorsRemainValid() {
        var service=source(List.of(message(1,"old"),message(3,"gap"),message(98,"prior"),message(99,"current"),message(100,"future")),50);
        assertEquals(List.of(1L,3L,98L),sequences(service.read(Long.MAX_VALUE,10,"")));
        verify(store).messages(session,owner,99);
        assertEquals(List.of(1L,3L),sequences(service.read(4,10,"")));
        verify(store).messages(session,owner,4);
    }
    @Test void malformedOrOutOfWindowStorageRowsFailClosedInsteadOfLoopingOrLeakingCurrentMessages() {
        var service=new HistoryQueryService(store,session,owner,50);var one=message(1,"one");
        var invalid=new ArrayList<List<Message>>();
        invalid.add(List.of(message(99,"current")));invalid.add(List.of(message(0,"zero")));
        invalid.add(List.of(message(2,"new"),one));invalid.add(List.of(one,one));
        invalid.add(List.of(one,new Message(one.id(),UUID.randomUUID(),2,"assistant","duplicate id",Instant.EPOCH)));
        invalid.add(List.of(new Message(UUID.randomUUID(),UUID.randomUUID(),1,"system","instruction",Instant.EPOCH)));
        invalid.add(Collections.singletonList(null));
        invalid.add(LongStream.rangeClosed(1,51).mapToObj(i->message(i,"too many")).toList());
        for(var data:invalid) {
            when(store.messages(session,owner,99)).thenReturn(data);
            assertEquals("STORAGE_CORRUPT",assertThrows(AgentException.class,()->service.read(Long.MAX_VALUE,4,"")).code());
        }
    }
    @Test void invalidArgumentsAndOverflowNeverReachStorageAndMissingStorageIsExplicit() {
        var service=new HistoryQueryService(store,session,owner,1);
        assertEquals("INVALID_CURSOR",assertThrows(AgentException.class,()->service.read(0,4,"")).code());
        for(int limit:List.of(0,11))assertEquals("INVALID_ARGUMENTS",assertThrows(AgentException.class,()->service.read(1,limit,"")).code());
        assertThrows(AgentException.class,()->service.read(1,4,"a".repeat(81)));
        assertThrows(AgentException.class,()->service.read(1,4,null));
        assertThrows(AgentException.class,()->new HistoryQueryService(store,session,owner,Long.MAX_VALUE).read(1,4,""));
        verifyNoInteractions(store);
        assertEquals("HISTORY_UNAVAILABLE",assertThrows(AgentException.class,()->new HistoryQueryService(null,session,owner,1).read(1,4,"")).code());
    }
    @Test void memoryAdapterKeepsSessionsSeparateAndRedactsBeforeSearchingUnderNarrowedPermissions() {
        var repository=new MemoryRunRepository();var broad=new AccessScope("t","u",Set.of(1001L,1002L));
        var original=repository.create("公开问题",broad,null,null,null).run();original.start();original.succeed(new Answer("private-needle",List.of()));
        var narrow=new AccessScope("t","u",Set.of(1002L));
        var service=new HistoryQueryService(repository,original.sessionId,narrow,2);
        assertTrue(rows(service.read(Long.MAX_VALUE,4,"private-needle")).isEmpty());
        assertTrue(rows(service.read(Long.MAX_VALUE,4,"")).stream().anyMatch(m->m.get("text").equals("[先前视频结果当前无权限，已隐藏]")));
        for(var foreign:List.of(new AccessScope("other","u",broad.videoIds()),new AccessScope("t","other",broad.videoIds())))
            assertEquals("SESSION_NOT_FOUND",assertThrows(AgentException.class,()->new HistoryQueryService(repository,original.sessionId,foreign,2).read(Long.MAX_VALUE,4,"")).code());
        var sibling=repository.create("另一个会话",broad,null,null,null).run();sibling.start();sibling.succeed(new Answer("sibling-needle",List.of()));
        assertTrue(rows(new HistoryQueryService(repository,original.sessionId,broad,2).read(Long.MAX_VALUE,4,"sibling-needle")).isEmpty());
    }
}
