package com.orangecode.tianmu.controller;

import com.orangecode.tianmu.application.*;
import com.orangecode.tianmu.common.*;
import com.orangecode.tianmu.exception.BusinessException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/feedback/reactions")
public class FeedbackReactionController {
    private final FeedbackReactions reactions;private final CurrentUser user;
    public FeedbackReactionController(FeedbackReactions reactions,CurrentUser user){this.reactions=reactions;this.user=user;}
    public record SetReaction(String source,long itemId,Boolean liked) {}
    @PutMapping
    public BaseResponse<FeedbackReactions.State> set(@RequestBody SetReaction request){if(request==null || request.liked()==null || request.source()==null)throw new BusinessException(ErrorCode.PARAMS_ERROR);return ResultUtils.success(reactions.set(request.source(),request.itemId(),user.requireUserId(),request.liked()));}
    @GetMapping
    public BaseResponse<FeedbackReactions.State> get(@RequestParam String source,@RequestParam long itemId){return ResultUtils.success(reactions.get(source,itemId,user.requireUserId()));}
}
