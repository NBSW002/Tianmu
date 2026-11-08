package com.tianmu.agent.runtime.store;

/** Private immutable objects; implementations enforce bounded reads and verify content integrity. */
public interface ObjectStore {
    /** Adapters should check that their configured destination is usable without exposing its location. */
    default void verifyReady() {}
    void put(String objectKey, byte[] body);
    /** Controlled writes may recover an uncertain acknowledgement, never an unbounded retry. */
    default void put(String objectKey,byte[] body,com.tianmu.agent.runtime.control.CallControl control){
        control.beforeDispatch();put(objectKey,body);control.checkpoint();
        if(!java.time.Instant.now().isBefore(control.deadline()))throw new com.tianmu.agent.core.AgentException("DEADLINE_EXCEEDED","Object write deadline exceeded");
    }
    byte[] get(String objectKey, long expectedSize, String expectedSha256);
    void delete(String objectKey);
}
