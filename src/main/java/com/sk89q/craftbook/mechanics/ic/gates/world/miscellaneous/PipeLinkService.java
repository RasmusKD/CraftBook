package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PipeLinkService {

    private static final PipeLinkService INSTANCE = new PipeLinkService();
    public static PipeLinkService get() { return INSTANCE; }

    public static class ReceiverRef {
        public final UUID world;
        public final int x,y,z;
        public ReceiverRef(UUID w,int x,int y,int z){ this.world=w; this.x=x; this.y=y; this.z=z; }
    }

    public static class SenderRef {
        public final UUID world;
        public final int x,y,z;
        public SenderRef(UUID w,int x,int y,int z){ this.world=w; this.x=x; this.y=y; this.z=z; }
    }

    // rid -> receiverpos
    private final Map<UUID, ReceiverRef> receivers = new ConcurrentHashMap<>();
    // rid -> set af sendere (reverse-index)
    private final Map<UUID, Set<SenderRef>> recvToSenders = new ConcurrentHashMap<>();
    // senderpos -> rid
    private final Map<String, UUID> senderAtToRid = new ConcurrentHashMap<>();

    private static String key(UUID w, int x, int y, int z) {
        return w+":"+x+":"+y+":"+z;
    }

    // --- Receiver ---
    public void registerReceiver(UUID rid, UUID w, int x, int y, int z){
        receivers.put(rid, new ReceiverRef(w,x,y,z));
    }

    public void unregisterReceiver(UUID rid){
        var senders = recvToSenders.remove(rid);
        if (senders != null) {
            for (var s : senders) {
                PipeLink.clearSenderUUIDAt(s.world, s.x, s.y, s.z);
                senderAtToRid.remove(key(s.world, s.x, s.y, s.z));
            }
        }
        receivers.remove(rid);
    }

    public ReceiverRef getReceiverRef(UUID rid){
        return receivers.get(rid);
    }

    /** Find receiver sign block, or null if world not loaded / not known. */
    public Block getReceiverSignBlock(UUID rid){
        ReceiverRef ref = receivers.get(rid);
        if (ref == null) return null;
        World w = Bukkit.getWorld(ref.world);
        if (w == null) return null;
        return w.getBlockAt(ref.x, ref.y, ref.z);
    }

    // --- Sender ---
    public void bindSender(UUID w, int x, int y, int z, UUID rid){
        String k = key(w,x,y,z);
        UUID prev = senderAtToRid.put(k, rid);

        // remove from previous reverse set if changed
        if (prev != null && !prev.equals(rid)) {
            var set = recvToSenders.get(prev);
            if (set != null) set.removeIf(s -> s.world.equals(w) && s.x==x && s.y==y && s.z==z);
        }

        recvToSenders
                .computeIfAbsent(rid, r -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(new SenderRef(w,x,y,z));
    }

    public void unbindSenderAt(UUID w, int x, int y, int z){
        String k = key(w,x,y,z);
        UUID rid = senderAtToRid.remove(k);
        if (rid != null) {
            var set = recvToSenders.get(rid);
            if (set != null) set.removeIf(s -> s.world.equals(w) && s.x==x && s.y==y && s.z==z);
        }
    }

    /** Robust get: if reverse map is empty, derive from the forward map. */
    public Set<SenderRef> getSendersFor(UUID rid){
        Set<SenderRef> direct = recvToSenders.get(rid);
        if (direct != null && !direct.isEmpty()) return direct;

        // fallback derive
        Set<SenderRef> derived = new HashSet<>();
        for (Map.Entry<String, UUID> e : senderAtToRid.entrySet()) {
            if (!rid.equals(e.getValue())) continue;
            String[] parts = e.getKey().split(":");
            try {
                UUID w = UUID.fromString(parts[0]);
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                derived.add(new SenderRef(w,x,y,z));
            } catch (Exception ignore) {}
        }
        if (!derived.isEmpty()) {
            recvToSenders.put(rid, derived);
        }
        return derived;
    }

    /** Clear all in-memory state. Call on plugin disable. */
    public void cleanup() {
        receivers.clear();
        recvToSenders.clear();
        senderAtToRid.clear();
    }
}
