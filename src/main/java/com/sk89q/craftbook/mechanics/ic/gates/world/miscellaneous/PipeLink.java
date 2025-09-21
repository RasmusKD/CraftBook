package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Sign;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/** Keys + helpers for storing linkage on sign PDC. */
public final class PipeLink {

    // replace your current keys with these:
    public static final org.bukkit.NamespacedKey SEND_BOUND =
            new org.bukkit.NamespacedKey("craftbook", "pipe_send_bound");
    public static final org.bukkit.NamespacedKey RECV_UUID =
            new org.bukkit.NamespacedKey("craftbook", "pipe_recv_uuid");


    private PipeLink() {}

    /** Store/read the receiver’s own UUID on the RECEIVER sign. */
    public static void writeReceiverUUID(Sign receiverSign, UUID id) {
        PersistentDataContainer pdc = receiverSign.getPersistentDataContainer();
        pdc.set(RECV_UUID, PersistentDataType.STRING, id.toString());
        receiverSign.update(true, false);
    }
    public static UUID readReceiverUUID(Sign receiverSign) {
        String raw = receiverSign.getPersistentDataContainer().get(RECV_UUID, PersistentDataType.STRING);
        if (raw == null) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }

    /** Store/read the bound receiver UUID on the SENDER sign. */
    public static void writeBoundReceiverUUID(Sign senderSign, UUID receiverId) {
        PersistentDataContainer pdc = senderSign.getPersistentDataContainer();
        pdc.set(SEND_BOUND, PersistentDataType.STRING, receiverId.toString());
        senderSign.update(true, false);
    }
    public static UUID readBoundReceiverUUID(Sign senderSign) {
        String raw = senderSign.getPersistentDataContainer().get(SEND_BOUND, PersistentDataType.STRING);
        if (raw == null) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException e) { return null; }
    }
    public static void clearSenderUUIDAt(UUID world, int x, int y, int z){
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(world);
        if (w == null) return;
        org.bukkit.block.Block b = w.getBlockAt(x,y,z);
        if (!(b.getState() instanceof org.bukkit.block.Sign s)) return;
        if (!PipeLinkBindListener.isSenderIC(s)) return;
        writeBoundReceiverUUID(s, null);
    }

}
