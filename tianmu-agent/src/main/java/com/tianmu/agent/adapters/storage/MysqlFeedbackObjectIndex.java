package com.tianmu.agent.adapters.storage;

import com.tianmu.agent.feedback.FeedbackObjectIndex;
import com.tianmu.agent.core.FeedbackAnalysis.Blob;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;

public final class MysqlFeedbackObjectIndex implements FeedbackObjectIndex {
    private final JdbcTemplate db;private final Runnable authority;
    public MysqlFeedbackObjectIndex(DataSource source,Runnable authority){db=new JdbcTemplate(source);db.setQueryTimeout(5);this.authority=authority;}
    public void register(UUID job,Blob blob){authority.run();db.update("INSERT INTO agent_feedback_object(object_key,job_id,expires_at) VALUES(?,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 DAY))",blob.key(),job.toString());}
    public List<Expired> expired(int limit){
        authority.run();
        db.update("DELETE j FROM agent_feedback_job j LEFT JOIN agent_run r ON r.run_id=j.run_id WHERE NOT EXISTS (SELECT 1 FROM agent_feedback_object o WHERE o.job_id=j.job_id) AND (r.run_id IS NULL OR r.data_expires_at<UTC_TIMESTAMP(6))");
        return db.query("SELECT o.object_key,o.job_id FROM agent_feedback_object o LEFT JOIN agent_feedback_job j ON j.job_id=o.job_id LEFT JOIN agent_run r ON r.run_id=j.run_id LEFT JOIN agent_session s ON s.session_id=r.session_id AND s.tenant_id=r.tenant_id WHERE o.expires_at<UTC_TIMESTAMP(6) OR j.job_id IS NULL OR r.run_id IS NULL OR s.status='DELETING' OR r.data_expires_at<UTC_TIMESTAMP(6) ORDER BY o.expires_at LIMIT ?",(rs,n)->new Expired(rs.getString(1),UUID.fromString(rs.getString(2))),limit);
    }
    public void removed(String key){authority.run();db.update("DELETE FROM agent_feedback_object WHERE object_key=?",key);
        db.update("DELETE j FROM agent_feedback_job j LEFT JOIN agent_run r ON r.run_id=j.run_id WHERE NOT EXISTS (SELECT 1 FROM agent_feedback_object o WHERE o.job_id=j.job_id) AND (r.run_id IS NULL OR r.data_expires_at<UTC_TIMESTAMP(6))");
    }
}
