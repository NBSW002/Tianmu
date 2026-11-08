package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.spi.CompletionEvaluator;
import java.util.*;

/** A queued operation is not a persisted-memory receipt. */
public final class MemoryReceiptRequirement  {
    private static final java.util.regex.Pattern CLAIM = java.util.regex.Pattern.compile("(?m)(^|[。！!\\n])\\s*(?:好的[，, ]*)?(?:(?:我|已经|已)[，, ]*)?(?:已经|已)?(?:记住了|记下了|记住|保存到(?:长期|个人)?记忆|保存了)");
    /** Keep the answer useful without representing a queued background task as a committed write. */
    public static String withoutUnconfirmedClaims(String text) {
        return CLAIM.matcher(text).replaceAll("$1好的");
    }
}
