package io.github.rajami1205.osimulator.model.memory;

import java.util.*;

/** Second-Chance metadata only. Selection is a read-only plan until memory commits it. */
public final class ClockReplacement {
    public record View(int hand, Map<FrameHandle, Boolean> references) {
        public View { references = Map.copyOf(references); }
    }
    public static final class Selection {
        private final View expected;
        private final View replacement;
        private final FrameHandle victim;
        private Selection(View expected, View replacement, FrameHandle victim) {
            this.expected = expected; this.replacement = replacement; this.victim = victim;
        }
        public FrameHandle victim() { return victim; }
    }
    private final int frameCount;
    private View state = new View(0, Map.of());
    public ClockReplacement(int frameCount) {
        if (frameCount <= 0) throw new IllegalArgumentException("Positive frame count required");
        this.frameCount = frameCount;
    }
    public View snapshot() { return state; }
    public void reference(FrameHandle handle) {
        Objects.requireNonNull(handle);
        if (handle.frame().id() >= frameCount) throw new IllegalArgumentException("Foreign frame");
        var bits = new HashMap<>(state.references()); bits.put(handle, true);
        state = new View(state.hand(), bits);
    }
    public void release(List<FrameHandle> handles) {
        var bits = new HashMap<>(state.references()); handles.forEach(bits::remove);
        state = new View(state.hand(), bits);
    }
    public void reset() { state = new View(0, Map.of()); }
    public Optional<Selection> prepare(List<FrameHandle> eligible) {
        var byId = new HashMap<Integer, FrameHandle>();
        for (var handle : eligible) {
            if (!state.references().containsKey(handle) || byId.put(handle.frame().id(), handle) != null)
                throw new IllegalStateException("Missing or duplicate CLOCK ownership");
        }
        var bits = new HashMap<>(state.references());
        int cursor = state.hand();
        for (long visited = 0; visited < 2L * frameCount; visited++) {
            var handle = byId.get(cursor);
            int successor = cursor == frameCount - 1 ? 0 : cursor + 1;
            if (handle != null) {
                if (!bits.get(handle)) return Optional.of(new Selection(state, new View(successor, bits), handle));
                bits.put(handle, false);
            }
            cursor = successor;
        }
        return Optional.empty();
    }
    View prepareCommit(Selection selection, FrameHandle incoming) {
        if (selection.expected != state) throw new IllegalStateException("Stale CLOCK plan");
        if (!selection.victim.frame().equals(incoming.frame()) || selection.victim.equals(incoming))
            throw new IllegalArgumentException("Replacement requires a new identity in the same frame");
        var bits = new HashMap<>(selection.replacement.references());
        bits.remove(selection.victim); bits.put(incoming, true);
        return new View(selection.replacement.hand(), bits);
    }
    // Called only after all fallible preparation/publication has succeeded.
    void commit(View prepared) { state = prepared; }
}
