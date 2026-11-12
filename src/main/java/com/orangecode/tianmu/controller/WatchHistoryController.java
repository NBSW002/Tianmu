package com.orangecode.tianmu.controller;
import com.orangecode.tianmu.application.*;
import com.orangecode.tianmu.common.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/history")
public class WatchHistoryController {
    private final WatchHistory history;
    private final CurrentUser user;
    public WatchHistoryController(WatchHistory history,CurrentUser user) { this.history=history; this.user=user; }
    public record Position(double seconds) {}
    @GetMapping public BaseResponse<?> list(@RequestParam(defaultValue="1") int page) { return ResultUtils.success(history.list(user.requireUserId(),page)); }
    @PutMapping("/{video}") public BaseResponse<?> record(@PathVariable long video,@RequestBody Position input) { history.record(user.requireUserId(),video,input.seconds()); return ResultUtils.success(true); }
    @DeleteMapping("/{video}") public BaseResponse<?> delete(@PathVariable long video) { history.delete(user.requireUserId(),video); return ResultUtils.success(true); }
    @DeleteMapping public BaseResponse<?> clear() { history.clear(user.requireUserId()); return ResultUtils.success(true); }
}
