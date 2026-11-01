package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryReleaseException;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryAllocationException;
import java.util.*;

/** Pool global, determinista y sin reservas parciales por escasez o validación fallida. */
public final class FrameAllocator {
    private final List<Frame> frames;
    private final Map<Integer, FrameHandle> active = new HashMap<>();

    public FrameAllocator(MemoryConfiguration geometry, PagingConfiguration paging) {
        Objects.requireNonNull(paging).validate(Objects.requireNonNull(geometry));
        var descriptors = new ArrayList<Frame>();
        for (int i = 0; i < geometry.userPositions() / paging.pageSize(); i++) {
            long base = (long) geometry.userStartAddress() + (long) i * paging.pageSize();
            descriptors.add(new Frame(i, Math.toIntExact(base), paging.pageSize()));
        }
        frames = List.copyOf(descriptors);
    }

    public List<FrameHandle> allocate(int count) {
        if (count <= 0) throw new MemoryAllocationException("Frame count must be positive");
        var selected = frames.stream().filter(frame -> !active.containsKey(frame.id())).limit(count).toList();
        if (selected.size() != count) throw new MemoryAllocationException("Insufficient free frames");
        var prepared = selected.stream().map(frame -> new FrameHandle(UUID.randomUUID(), frame)).toList();
        for (var handle : prepared) active.put(handle.frame().id(), handle);
        return prepared;
    }

    public boolean isActive(FrameHandle handle) {
        return handle != null && handle.equals(active.get(handle.frame().id()));
    }

    public void validateAll(List<FrameHandle> handles) {
        Objects.requireNonNull(handles);
        var ids = new HashSet<Integer>();
        for (var handle : handles) {
            if (!isActive(handle) || !ids.add(handle.frame().id())) {
                throw new InvalidMemoryReleaseException("Frame set contains stale, foreign, modified or duplicate handles");
            }
        }
    }

    public void releaseAll(List<FrameHandle> handles) {
        var copy = List.copyOf(handles);
        validateAll(copy);
        for (var handle : copy) active.remove(handle.frame().id());
    }
    public void reset() { active.clear(); }
    public int count() { return frames.size(); }
    public record Occupancy(Frame frame, Optional<FrameHandle> handle) { }
    public List<Occupancy> snapshot() {
        return frames.stream().map(frame -> new Occupancy(frame, Optional.ofNullable(active.get(frame.id())))).toList();
    }
}
