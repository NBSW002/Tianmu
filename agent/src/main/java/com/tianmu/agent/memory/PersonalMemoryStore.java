package com.tianmu.agent.memory;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.memory.PersonalMemory.*;
import java.util.UUID;

/** Owner-scoped authoritative storage shared by management, model tools and derived-source jobs. */
public interface PersonalMemoryStore {
    Settings settings(AccessScope owner);
    Settings settings(AccessScope owner,long expectedVersion,Mode mode);
    Scope scope(AccessScope owner,UUID id);
    Entry get(AccessScope owner,UUID id);
    Page list(AccessScope owner,Scope scope,long beforeSequence,int limit,String query);
    Receipt create(AccessScope owner,Content content,String key);
    Receipt update(AccessScope owner,UUID id,long expectedVersion,Content content,String key);
    Receipt delete(AccessScope owner,UUID id,long expectedVersion,String key);
    Receipt reset(AccessScope owner,String key);
}
