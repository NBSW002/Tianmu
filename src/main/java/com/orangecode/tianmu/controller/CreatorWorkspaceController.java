package com.orangecode.tianmu.controller;

import com.orangecode.tianmu.application.*;
import com.orangecode.tianmu.common.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/creator")
public class CreatorWorkspaceController {
    private final CreatorWorkspace workspace;
    private final CurrentUser user;
    public CreatorWorkspaceController(CreatorWorkspace workspace,CurrentUser user) { this.workspace=workspace; this.user=user; }
    public record StatusChange(int expectedStatus,int status) {}
    @GetMapping("/videos") public BaseResponse<?> videos(@RequestParam(defaultValue="1") int page) { return ResultUtils.success(workspace.videos(user.requireUserId(),page)); }
    @PatchMapping("/videos/{id}") public BaseResponse<?> edit(@PathVariable long id,@RequestBody CreatorWorkspace.Edit input) { workspace.edit(user.requireUserId(),id,input); return ResultUtils.success(true); }
    @PutMapping("/videos/{id}/status") public BaseResponse<?> status(@PathVariable long id,@RequestBody StatusChange input) { workspace.status(user.requireUserId(),id,input.expectedStatus(),input.status()); return ResultUtils.success(true); }
    @DeleteMapping("/videos/{id}") public BaseResponse<?> delete(@PathVariable long id) { workspace.deleteVideo(user.requireUserId(),id); return ResultUtils.success(true); }
    @GetMapping("/feedback") public BaseResponse<?> feedback(@RequestParam String source,@RequestParam(defaultValue="1") int page) { return ResultUtils.success(workspace.feedback(user.requireUserId(),source,page)); }
    @DeleteMapping("/feedback/{source}/{id}") public BaseResponse<?> deleteFeedback(@PathVariable String source,@PathVariable long id) { workspace.deleteFeedback(user.requireUserId(),source,id,null); return ResultUtils.success(true); }
}
