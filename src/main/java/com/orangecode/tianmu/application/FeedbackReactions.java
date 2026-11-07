package com.orangecode.tianmu.application;

import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Feedback popularity is authoritative, actor-bound and independent of video likes. */
@Service
public class FeedbackReactions {
    private final JdbcTemplate db;
    public FeedbackReactions(JdbcTemplate db){this.db=db;}
    public record State(String source,long itemId,long likes,boolean liked) {}
    private static void validate(String source,long item){if(source==null || !Set.of("COMMENT","DANMAKU").contains(source) || item<=0)throw new BusinessException(ErrorCode.PARAMS_ERROR);}
    private void visible(String source,long item){String table=source.equals("COMMENT")?"comment":"bullet",key=source.equals("COMMENT")?"comment_id":"bullet_id";
        if(!Objects.equals(1,db.queryForObject("SELECT COUNT(*) FROM "+table+" f JOIN video v ON v.video_id=f.video_id WHERE f."+key+"=? AND v.status=2 AND v.is_delete=0",Integer.class,item)))throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);}
    @Transactional
    public State set(String source,long item,long actor,boolean liked){validate(source,item);if(actor<=0)throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);visible(source,item);
        if(liked)db.update("INSERT INTO feedback_reaction(source,item_id,actor_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE actor_id=actor_id",source,item,actor);
        else db.update("DELETE FROM feedback_reaction WHERE source=? AND item_id=? AND actor_id=?",source,item,actor);
        return get(source,item,actor);
    }
    @Transactional(readOnly=true)
    public State get(String source,long item,long actor){validate(source,item);visible(source,item);var row=db.queryForMap("SELECT COUNT(*) likes,COALESCE(MAX(actor_id=?),0) liked FROM feedback_reaction WHERE source=? AND item_id=?",actor,source,item);return new State(source,item,((Number)row.get("likes")).longValue(),((Number)row.get("liked")).intValue()!=0);}
}
