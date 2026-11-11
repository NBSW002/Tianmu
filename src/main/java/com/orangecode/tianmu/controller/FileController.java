package com.orangecode.tianmu.controller;

import com.orangecode.tianmu.application.*;
import com.orangecode.tianmu.common.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/file/uploads")
public class FileController {
    private final CurrentUser user;
    private final UploadSessions uploads;
    public FileController(CurrentUser user, UploadSessions uploads) { this.user=user; this.uploads=uploads; }
    @PostMapping public BaseResponse<UploadSessions.View> start(@RequestBody UploadPolicy.Request request) {
        return ResultUtils.success(uploads.start(user.requireUserId(),request));
    }
    @GetMapping("/{id}/progress") public BaseResponse<Set<Integer>> progress(@PathVariable String id) {
        return ResultUtils.success(uploads.progress(user.requireUserId(),id));
    }
    @GetMapping("/{id}/urls") public BaseResponse<List<UploadObjects.Part>> urls(@PathVariable String id,@RequestParam int offset,@RequestParam(defaultValue="32") int limit) {
        return ResultUtils.success(uploads.urls(user.requireUserId(),id,offset,limit));
    }
    @PostMapping("/{id}/merge") public BaseResponse<String> merge(@PathVariable String id) {
        return ResultUtils.success(uploads.merge(user.requireUserId(),id));
    }
}
