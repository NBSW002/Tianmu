package com.tianmu.agent.runtime.store;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.RunState;
import java.util.*;

public interface ConversationStore {
    int MESSAGE_PAGE_SIZE = 50;
    default MemoryTypes.View memory(RunState run) { return MemoryTypes.View.empty(); }
    default void focus(RunState run, MemoryTypes.Focus value) {}
    /** Owner-authorized, scope-redacted, ascending messages strictly before the cursor; at most MESSAGE_PAGE_SIZE. */
    default List<MemoryTypes.Message> messages(UUID session, AccessScope owner, long before) {
        throw new AgentException("STORAGE_REQUIRED", "Message history requires persistent storage");
    }
}
