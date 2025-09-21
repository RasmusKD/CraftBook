package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.Set;
import java.util.UUID;

public class PipeLinkBreakListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!(e.getBlock().getState() instanceof Sign s)) return;

        // Sender: clear its bound UUID and reverse index
        if (PipeLinkBindListener.isSenderIC(s)) {
            UUID rid = PipeLink.readBoundReceiverUUID(s);
            if (rid != null) {
                PipeLink.writeBoundReceiverUUID(s, null);
                PipeLinkService.get().unbindSenderAt(s.getWorld().getUID(), s.getX(), s.getY(), s.getZ());
            }
            return;
        }

        // Receiver: unregister and clear all senders bound to it
        if (PipeLinkBindListener.isReceiverIC(s)) {
            UUID rid = PipeLink.readReceiverUUID(s);
            if (rid != null) {
                Set<PipeLinkService.SenderRef> senders = PipeLinkService.get().getSendersFor(rid);
                if (senders != null) {
                    for (PipeLinkService.SenderRef sr : senders) {
                        PipeLink.clearSenderUUIDAt(sr.world, sr.x, sr.y, sr.z);
                        PipeLinkService.get().unbindSenderAt(sr.world, sr.x, sr.y, sr.z);
                    }
                }
                PipeLinkService.get().unregisterReceiver(rid);
            }
        }
    }
}
