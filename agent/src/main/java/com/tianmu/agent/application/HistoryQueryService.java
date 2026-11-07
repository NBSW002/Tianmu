package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.MemoryTypes.Message;
import com.tianmu.agent.runtime.store.ConversationStore;
import java.util.*;

/** Read-only history view pinned to the current session, caller scope and turn. */
public final class HistoryQueryService {
    private static final int TEXT_LIMIT = 1600;
    private final ConversationStore conversations;
    private final UUID session;
    private final AccessScope owner;
    private final long turn;
    private final java.util.function.UnaryOperator<List<Message>> consumption;

    public HistoryQueryService(ConversationStore conversations, UUID session, AccessScope owner, long turn) {
        this(conversations,session,owner,turn,java.util.function.UnaryOperator.identity());
    }
    public HistoryQueryService(ConversationStore conversations,UUID session,AccessScope owner,long turn,java.util.function.UnaryOperator<List<Message>> consumption){
        this.conversations = conversations;
        this.session = Objects.requireNonNull(session);
        this.owner = Objects.requireNonNull(owner);
        this.turn = turn;
        this.consumption=Objects.requireNonNull(consumption);
    }

    public Map<String, Object> read(long before, int limit, String query) {
        if (conversations == null) throw new AgentException("HISTORY_UNAVAILABLE", "History unavailable");
        if (before < 1) throw new AgentException("INVALID_CURSOR", "Invalid history cursor");
        if (limit < 1 || limit > 10 || query == null || query.length() > 80 || !query.isEmpty() && query.isBlank())
            throw new AgentException("INVALID_ARGUMENTS", "Invalid history limit or query");
        long boundary;
        try { boundary = Math.subtractExact(Math.multiplyExact(turn, 2), 1); }
        catch (ArithmeticException overflow) { throw new AgentException("INVALID_CURSOR", "Invalid current turn"); }
        if (boundary < 1) throw new AgentException("INVALID_CURSOR", "Invalid current turn");
        long cursor = Math.min(before, boundary);
        var loaded = conversations.messages(session, owner, cursor);
        validate(loaded, cursor);
        String needle = query.toLowerCase(Locale.ROOT);
        var matched = loaded.stream().filter(m -> needle.isEmpty() || m.text().toLowerCase(Locale.ROOT).contains(needle)).toList();
        int start = Math.max(0, matched.size() - limit);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Message message : consumption.apply(matched.subList(start, matched.size()))) {
            String text = message.text();
            boolean truncated = text.length() > TEXT_LIMIT;
            if (truncated) text = text.substring(0, Character.isHighSurrogate(text.charAt(TEXT_LIMIT - 1)) ? TEXT_LIMIT - 1 : TEXT_LIMIT);
            records.add(Map.of("role", message.role().toLowerCase(Locale.ROOT), "text", text, "run_id", message.runId().toString(),
                    "sequence", message.sequence(), "truncated", truncated));
        }
        boolean remainingMatches = start > 0;
        // before is exclusive: retain unreturned matches, or advance past the fully searched window.
        long next = remainingMatches ? matched.get(start).sequence() : loaded.isEmpty() ? 0 : loaded.get(0).sequence();
        return Map.of("messages", List.copyOf(records), "next_before", next,
                "more_available", remainingMatches || loaded.size() == ConversationStore.MESSAGE_PAGE_SIZE);
    }

    private void validate(List<Message> messages, long before) {
        if (messages == null || messages.size() > ConversationStore.MESSAGE_PAGE_SIZE) throw corrupt();
        long previous = 0;
        Set<UUID> ids = new HashSet<>();
        for (Message message : messages) {
            if (message == null || message.id() == null || message.runId() == null || message.text() == null
                    || message.role() == null || !Set.of("user", "assistant").contains(message.role().toLowerCase(Locale.ROOT))
                    || message.sequence() <= previous || message.sequence() >= before || !ids.add(message.id())) throw corrupt();
            previous = message.sequence();
        }
    }
    private AgentException corrupt() { return new AgentException("STORAGE_CORRUPT", "History page violates its bounded cursor contract"); }
}
