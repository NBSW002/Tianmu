package com.orangecode.tianmu.application;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.orangecode.tianmu.exception.BusinessException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class UploadSessionsTest {
    JdbcTemplate db; UploadObjects objects; UploadSessions service; DataSourceTransactionManager transactions;
    static final long ALICE=8200191L, BOB=8200192L;
    UploadPolicy.Request request(String hash) { return new UploadPolicy.Request(hash.repeat(32),5L*1024*1024+10,"mp4"); }
    public static java.time.LocalDateTime utc(int precision) { return java.time.LocalDateTime.now(java.time.ZoneOffset.UTC); }
    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(source);transactions=new DataSourceTransactionManager(source);objects=mock(UploadObjects.class);
        db.execute("CREATE ALIAS UTC_TIMESTAMP FOR 'com.orangecode.tianmu.application.UploadSessionsTest.utc'");
        db.execute("CREATE TABLE `user` (user_id BIGINT PRIMARY KEY)");
        db.update("INSERT INTO `user` VALUES(?),(?)",ALICE,BOB);
        db.execute("CREATE TABLE `file` (file_id BIGINT PRIMARY KEY,file_hash VARCHAR(32),file_url VARCHAR(2048))");
        String schema=Files.readString(Path.of("deploy/site/migrations/20261104_upload_sessions.sql"))
                .replace(" CHARACTER SET ascii COLLATE ascii_bin","");
        db.execute(schema);
        service=new UploadSessions(db,transactions,objects);
        when(objects.compose(anyString(),anyLong(),any())).thenAnswer(call->"https://objects.invalid/"+call.getArgument(0)+"/"+call.getArgument(1));
    }
    @AfterEach void close() { if(db!=null)db.execute("SHUTDOWN"); }
    @Test void sameContentIsIsolatedAndEveryReadAndMutationRequiresOwner() {
        var a=service.start(ALICE,request("a"));var b=service.start(BOB,request("a"));
        assertNotEquals(a.uploadId(),b.uploadId());assertEquals(a,service.start(ALICE,request("a")));
        assertThrows(BusinessException.class,()->service.urls(BOB,a.uploadId(),0,1));
        assertThrows(BusinessException.class,()->service.progress(BOB,a.uploadId()));
        assertThrows(BusinessException.class,()->service.merge(BOB,a.uploadId()));
        verifyNoInteractions(objects);
    }
    @Test void invalidSizeAndWindowsAreRejectedBeforeObjectWorkAndAdmissionIsBounded() {
        assertThrows(BusinessException.class,()->new UploadPolicy.Request("a".repeat(32),Long.MAX_VALUE,"mp4"));
        assertThrows(BusinessException.class,()->new UploadPolicy.Request("../path",12,"mp4"));
        var a=service.start(ALICE,request("a"));service.start(ALICE,request("b"));service.start(ALICE,request("c"));
        assertThrows(BusinessException.class,()->service.start(ALICE,request("d")));
        assertThrows(BusinessException.class,()->service.urls(ALICE,a.uploadId(),0,Integer.MAX_VALUE));
        verifyNoInteractions(objects);
    }
    @Test void completedMergeIsIdempotentAcrossServiceRestartAndOnlyOwnerMayPublish() {
        var a=service.start(ALICE,request("a"));String url=service.merge(ALICE,a.uploadId());
        service=new UploadSessions(db,transactions,objects);
        assertEquals(url,service.merge(ALICE,a.uploadId()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM `file`",Integer.class));
        verify(objects,times(1)).compose(eq(a.uploadId()),anyLong(),any());
        service.requireCompleted(ALICE,url);assertThrows(BusinessException.class,()->service.requireCompleted(BOB,url));
    }
    @Test void failureAfterCompositionRollsBackAndRetrySucceedsWithoutDeletingChunks() {
        var a=service.start(ALICE,request("a"));
        db.execute("ALTER TABLE `file` ADD CONSTRAINT fail_write CHECK (file_hash <> '"+"a".repeat(32)+"')");
        assertThrows(RuntimeException.class,()->service.merge(ALICE,a.uploadId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM `file`",Integer.class));
        assertEquals("UPLOADING",db.queryForObject("SELECT status FROM upload_session WHERE upload_id=?",String.class,a.uploadId()));
        verify(objects,never()).clean(anyString(),any());
        db.execute("ALTER TABLE `file` DROP CONSTRAINT fail_write");
        assertNotNull(service.merge(ALICE,a.uploadId()));
    }
    @Test void concurrentMergeIsRejectedAndOldLeaseCannotCommitOverNewAttempt() throws Exception {
        var a=service.start(ALICE,request("a"));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(objects.compose(eq(a.uploadId()),eq(1L),any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return "old-object";});
        var pool=Executors.newSingleThreadExecutor();
        try {
            var old=pool.submit(()->service.merge(ALICE,a.uploadId()));assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(BusinessException.class,()->service.merge(ALICE,a.uploadId()));
            db.update("UPDATE upload_session SET lease_until=TIMESTAMPADD(MINUTE,-1,UTC_TIMESTAMP(6)) WHERE upload_id=?",a.uploadId());
            String winner=service.merge(ALICE,a.uploadId());release.countDown();assertThrows(ExecutionException.class,()->old.get(5,TimeUnit.SECONDS));
            assertEquals(winner,service.merge(ALICE,a.uploadId()));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM `file`",Integer.class));
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void expiredUploadsCannotBeResumedAndCleanupPreservesCompletedObject() {
        var a=service.start(ALICE,request("a"));String url=service.merge(ALICE,a.uploadId());
        var b=service.start(BOB,request("b"));
        db.update("UPDATE upload_session SET expires_at=TIMESTAMPADD(DAY,-2,UTC_TIMESTAMP(6))");
        assertThrows(BusinessException.class,()->service.urls(BOB,b.uploadId(),0,1));
        service.clean();verify(objects).clean(a.uploadId(),url);verify(objects).clean(b.uploadId(),null);
        assertEquals(url,service.merge(ALICE,a.uploadId()));
    }
}
