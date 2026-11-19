package com.eddyizm.tempus.util;

import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FavoriteRegistry {
    public enum Kind {SONG, ALBUM, ARTIST}

    // One request's decision and the one before it. A refusal strikes its own record and, skipping
    // records already struck, the unaccepted records beneath it that carry the same decision, since
    // those are earlier attempts at the same decision. Any other failed request is withdrawn alone
    // unless the retry queue takes over its decision, and a decision handed to that queue strikes the
    // pending records above the newest accepted one that say the opposite. Resolve reads past struck
    // records. A record the server accepted is never struck, and accepting one lifts a strike it
    // took while its request was still open.
    public static final class Record {
        private final String key;
        private final boolean starred;
        private final Record previous;
        private boolean accepted;
        private boolean struck;
        private final Decision decision;

        private Record(String key, boolean starred, Record previous, Decision decision) {
            this.key = key;
            this.starred = starred;
            this.previous = previous;
            this.decision = decision;
        }

        public String getEventId() { return decision.eventId; }
    }

    private static final class Decision {
        final Kind kind;
        final String id;
        final boolean starred;
        final String eventId;
        String lastEventId;
        boolean active = true;

        Decision(Kind kind, String id, boolean starred) {
            this(kind, id, starred, UUID.randomUUID().toString());
        }

        Decision(Kind kind, String id, boolean starred, String eventId) {
            this.kind = kind;
            this.id = id;
            this.starred = starred;
            this.eventId = eventId;
            this.lastEventId = eventId;
        }
    }

    private static final Map<String, Record> records = new HashMap<>();
    private static final Map<String, Boolean> snapshot = new HashMap<>();
    private static final Map<String, String> aliases = new HashMap<>();
    private static long generation;

    public static final class State {
        public final Kind kind;
        public final String id;
        public final boolean starred;
        public State(Kind kind, String id, boolean starred) {
            this.kind = kind;
            this.id = id;
            this.starred = starred;
        }
    }

    public static synchronized long generation() { return generation; }

    public static synchronized void snapshot(Iterable<State> states, long session) {
        if (session != generation) return;
        snapshot.clear();
        for (State state : states) snapshot.putIfAbsent(key(state.kind, state.id), state.starred);
        changed();
    }

    // Set by the session callback so the notification heart follows a song star made anywhere in
    // the app. It runs on the caller's thread while the lock is held, so it must only post work.
    public static volatile Runnable onChange;
    private static final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public static void addChangeListener(Runnable listener) { changeListeners.addIfAbsent(listener); }
    public static void removeChangeListener(Runnable listener) { changeListeners.remove(listener); }

    // HomeMusic: told of each song decision that changes what the song shows (not a retry of the same one), for
    // the Hub's listening data. Called with the lock held, so it must only post work.
    public interface SongListener {
        void onSongDecision(String id, boolean isStarred);
    }

    public static volatile SongListener onSongDecision;

    // The event id belongs to the decision, not to an HTTP attempt. Rollbacks name that id.
    public interface DecisionListener {
        void onDecision(Kind kind, String id, boolean starred, String eventId, String revertsEventId, boolean compensationChain);
    }
    public static volatile DecisionListener onDecision;

    private FavoriteRegistry() {
    }

    public static synchronized Record set(Kind kind, String id, boolean isStarred) {
        return setWithId(kind, id, isStarred, null, true);
    }

    public static synchronized Record setWithId(Kind kind, String id, boolean isStarred, String eventId, boolean emit) {
        if (id == null) return null;
        String key = key(kind, id);
        Record before = surviving(records.get(key));
        boolean newDecision = before == null || before.starred != isStarred;
        Decision decision = newDecision ? (eventId == null ? new Decision(kind, id, isStarred) :
                new Decision(kind, id, isStarred, eventId)) : before.decision;
        Record record = new Record(key, isStarred, records.get(key), decision);
        records.put(key, record);
        changed();
        SongListener songListener = onSongDecision;
        if (emit && newDecision && onDecision != null) {
            onDecision.onDecision(kind, id, isStarred, decision.eventId, null, false);
        }
        if (emit && kind == Kind.SONG && songListener != null && newDecision) {
            songListener.onSongDecision(id, isStarred);
        }
        return record;
    }

    public static synchronized boolean resolve(Kind kind, String id, boolean serverSaysStarred) {
        Record live = id != null ? surviving(records.get(key(kind, id))) : null;
        return live != null ? live.starred : snapshot.getOrDefault(key(kind, id), serverSaysStarred);
    }

    public static synchronized void rememberBaseline(Kind kind, String id, boolean starred) {
        if (id != null) snapshot.putIfAbsent(key(kind, id), starred);
    }

    // A catalog item became a library item: carry the decision, without emitting a second like.
    public static synchronized void alias(Kind kind, String from, String to) {
        if (from == null || to == null || records.containsKey(key(kind, to))) return;
        String source = key(kind, from);
        String destination = kind.name() + ":" + to;
        if (!key(kind, to).equals(source)) aliases.put(destination, source);
    }

    /** Restore a queued HTTP attempt after process death without inventing another user decision. */
    public static synchronized boolean restore(Kind kind, String id, boolean starred, String eventId) {
        if (id == null) return false;
        if (records.containsKey(key(kind, id))) {
            Record live = surviving(records.get(key(kind, id)));
            return live != null && live.decision.eventId.equals(eventId);
        }
        Decision decision = new Decision(kind, id, starred, eventId);
        String key = key(kind, id);
        records.put(key, new Record(key, starred, null, decision));
        return true;
    }

    public static synchronized boolean isCurrent(Record record) {
        return record != null && surviving(records.get(record.key)) == record;
    }

    public static synchronized void accept(Record record) {
        if (record == null) return;
        record.accepted = true;
        record.struck = false;
        compensateStruck(record.key);
        changed();
    }

    public static synchronized void strike(Record record) {
        if (record == null) return;
        for (Record r = record; r != null; r = r.previous) {
            if (r.struck) continue;
            if (r.accepted || r.starred != record.starred) break;
            r.struck = true;
        }
        compensateStruck(record.key);
        changed();
    }

    public static synchronized void withdraw(Record record) {
        if (record == null || record.accepted) return;
        record.struck = true;
        compensateStruck(record.key);
        changed();
    }

    public static synchronized void supersede(Kind kind, String id, boolean isStarred) {
        if (id == null) return;
        for (Record r = records.get(key(kind, id)); r != null && !r.accepted; r = r.previous) {
            if (r.starred != isStarred) r.struck = true;
        }
        compensateStruck(key(kind, id));
        changed();
    }

    private static void compensateStruck(String key) {
        Map<Decision, Boolean> surviving = new LinkedHashMap<>();
        for (Record r = records.get(key); r != null; r = r.previous) {
            surviving.merge(r.decision, !r.struck, (a, b) -> a || b);
        }
        for (Map.Entry<Decision, Boolean> entry : surviving.entrySet()) {
            Decision d = entry.getKey();
            boolean survives = entry.getValue();
            if (survives != d.active) {
                d.active = survives;
                String reverts = d.lastEventId;
                d.lastEventId = UUID.randomUUID().toString();
                if (onDecision != null) onDecision.onDecision(d.kind, d.id,
                        survives ? d.starred : !d.starred, d.lastEventId, reverts, !reverts.equals(d.eventId));
            }
        }
    }

    public static synchronized void clear() {
        records.clear();
        snapshot.clear();
        aliases.clear();
        generation++;
        changed();
    }

    private static void changed() {
        Runnable listener = onChange;
        if (listener != null) listener.run();
        for (Runnable observer : changeListeners) observer.run();
    }

    private static Record surviving(Record record) {
        while (record != null && record.struck) record = record.previous;
        return record;
    }

    private static String key(Kind kind, String id) {
        String key = kind.name() + ":" + id;
        while (aliases.containsKey(key)) key = aliases.get(key);
        return key;
    }
}
