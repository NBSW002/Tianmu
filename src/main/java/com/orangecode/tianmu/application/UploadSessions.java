package com.orangecode.tianmu.application;

import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;

/** Durable ownership and fenced merge; object I/O never holds a database transaction. */
@Service
public class UploadSessions {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final UploadObjects objects;
    public UploadSessions(JdbcTemplate db, PlatformTransactionManager transactions, UploadObjects objects) {
        this.db=db; this.objects=objects; tx=new TransactionTemplate(transactions); tx.setTimeout(5);
    }
    public record View(String uploadId, String status, int chunkCount, String fileUrl) {}
    private View view(Map<String,Object> row) { return new View((String)row.get("upload_id"),(String)row.get("status"),request(row).chunks(),(String)row.get("file_url")); }
    private UploadPolicy.Request request(Map<String,Object> row) { return new UploadPolicy.Request((String)row.get("file_hash"),((Number)row.get("size_bytes")).longValue(),(String)row.get("file_type")); }
    private BusinessException missing() { return new BusinessException(ErrorCode.NOT_FOUND_ERROR,"上传不存在或已过期"); }
    private Map<String,Object> owned(long owner, String id, boolean lock) {
        if(id==null || !id.matches("[0-9a-f-]{36}")) throw missing();
        var rows=db.queryForList("SELECT * FROM upload_session WHERE upload_id=? AND owner_id=? AND (status='COMPLETE' OR expires_at>UTC_TIMESTAMP(6))"+(lock?" FOR UPDATE":""),id,owner);
        if(rows.isEmpty())throw missing(); return rows.get(0);
    }
    public View start(long owner, UploadPolicy.Request request) {
        Objects.requireNonNull(request);
        return tx.execute(status->{
            // Serializes admission per authenticated user across application instances.
            if(db.queryForList("SELECT user_id FROM `user` WHERE user_id=? FOR UPDATE",owner).isEmpty())throw missing();
            var existing=db.queryForList("SELECT * FROM upload_session WHERE owner_id=? AND file_hash=? AND size_bytes=? AND file_type=? AND expires_at>UTC_TIMESTAMP(6) ORDER BY created_at DESC LIMIT 1",owner,request.fileHash(),request.sizeBytes(),request.fileType());
            if(!existing.isEmpty())return view(existing.get(0));
            if(db.queryForObject("SELECT COUNT(*) FROM upload_session WHERE owner_id=? AND status<>'COMPLETE' AND expires_at>UTC_TIMESTAMP(6)",Long.class,owner)>=3)
                throw new BusinessException(ErrorCode.ACCESS_TOO_FREQUENTLY,"最多同时上传三个视频");
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO upload_session(upload_id,owner_id,file_hash,size_bytes,file_type,status,expires_at) VALUES(?,?,?,?,?,'UPLOADING',TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)))",id,owner,request.fileHash(),request.sizeBytes(),request.fileType());
            return view(owned(owner,id,false));
        });
    }
    public Set<Integer> progress(long owner,String id) {
        var row=owned(owner,id,false);
        return objects.progress(id,request(row));
    }
    public List<UploadObjects.Part> urls(long owner,String id,int offset,int limit) {
        if(offset<0 || limit<1 || limit>UploadPolicy.WINDOW)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        var row=owned(owner,id,false);var request=request(row);
        if(!"UPLOADING".equals(row.get("status")) || offset>=request.chunks())throw new BusinessException(ErrorCode.OPERATION_ERROR,"上传状态已变化");
        var out=new ArrayList<UploadObjects.Part>();
        for(int i=offset;i<Math.min(request.chunks(),offset+limit);i++)out.add(objects.sign(id,i,request.chunkSize(i)));
        return out;
    }
    public String merge(long owner,String id) {
        var row=tx.execute(status->{
            var saved=owned(owner,id,true);
            if("COMPLETE".equals(saved.get("status")))return saved;
            int changed=db.update("UPDATE upload_session SET status='MERGING',fence=fence+1,lease_until=TIMESTAMPADD(MINUTE,5,UTC_TIMESTAMP(6)) WHERE upload_id=? AND (status='UPLOADING' OR lease_until<UTC_TIMESTAMP(6))",id);
            if(changed!=1)throw new BusinessException(ErrorCode.ACCESS_TOO_FREQUENTLY,"正在合并，请稍后重试");
            return owned(owner,id,false);
        });
        if("COMPLETE".equals(row.get("status")))return (String)row.get("file_url");
        long fence=((Number)row.get("fence")).longValue();
        try {
            String url=objects.compose(id,fence,request(row));
            return tx.execute(status->{
                var current=owned(owner,id,true);
                if(!"MERGING".equals(current.get("status")) || ((Number)current.get("fence")).longValue()!=fence)
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,"合并状态已变化，请重试");
                long fileId=cn.hutool.core.util.IdUtil.getSnowflake(com.orangecode.tianmu.constants.SnowflakeConstant.WORKER_ID,com.orangecode.tianmu.constants.SnowflakeConstant.DATA_CENTER_ID).nextId();
                db.update("INSERT INTO `file`(file_id,file_hash,file_url) VALUES(?,?,?)",fileId,row.get("file_hash"),url);
                if(db.update("UPDATE upload_session SET status='COMPLETE',file_url=?,lease_until=NULL WHERE upload_id=? AND lease_until>UTC_TIMESTAMP(6)",url,id)!=1)
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,"合并租约过期，请重试");
                return url;
            });
        } catch(RuntimeException failure) {
            // If commit succeeded but its acknowledgement was lost, COMPLETE is never reverted.
            try { db.update("UPDATE upload_session SET status='UPLOADING',lease_until=NULL WHERE upload_id=? AND fence=? AND status='MERGING'",id,fence); }
            catch (RuntimeException unavailable) { failure.addSuppressed(unavailable); } // Lease expiry permits recovery after storage returns.
            throw failure;
        }
    }
    public void requireCompleted(long owner,String url) {
        if(url==null || db.queryForObject("SELECT COUNT(*) FROM upload_session WHERE owner_id=? AND file_url=? AND status='COMPLETE'",Long.class,owner,url)!=1)
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"只能发布自己已完成的上传");
    }
    @Scheduled(fixedDelay=60000)
    public void clean() {
        try {
            // Expiry is well after all signed PUT URLs and merge leases; repeat cleanup catches late writes.
            for(var row:db.queryForList("SELECT upload_id,file_url FROM upload_session WHERE expires_at<TIMESTAMPADD(MINUTE,-30,UTC_TIMESTAMP(6)) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6)) AND (cleaned_at IS NULL OR cleaned_at<TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6))) ORDER BY cleaned_at,created_at LIMIT 3")) {
                String id=(String)row.get("upload_id");objects.clean(id,(String)row.get("file_url"));
                db.update("UPDATE upload_session SET cleaned_at=UTC_TIMESTAMP(6) WHERE upload_id=?",id);
            }
        } catch(RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("upload_cleanup_failed type={}",failure.getClass().getSimpleName()); }
    }
}
