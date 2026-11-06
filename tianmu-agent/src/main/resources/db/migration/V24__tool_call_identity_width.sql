-- Preserve the full native provider call ID (the protocol accepts up to 160 ASCII characters).
ALTER TABLE agent_tool_call MODIFY call_id VARCHAR(160) CHARACTER SET ascii NOT NULL;
