package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import com.sk89q.craftbook.mechanics.pipe.PipeRequestEvent;
import com.sk89q.craftbook.mechanics.pipe.PipePutEvent;
import com.sk89q.craftbook.util.SignUtil;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Router that teleports items whenever a pipe targets a Sender (either the sign
 * itself or the block in front of it). Works for both Request and Put stages.
 */
public class PipeLinkRouterListener implements Listener {

    // ---------- REQUEST stage (pipe starting / mid traversal) ----------
    @EventHandler(ignoreCancelled = true)
    public void onRequest(PipeRequestEvent e) {
        Sign sender = findSenderForTarget(e.getBlock());
        if (sender == null) return;

        UUID rid = PipeLink.readBoundReceiverUUID(sender);
        if (rid == null) return;

        Block recvSign = PipeLinkService.get().getReceiverSignBlock(rid);
        if (recvSign == null || !(recvSign.getState() instanceof Sign)) {
            Bukkit.getLogger().warning("[Pipes/DBG] Router(Request): receiver not found for " + rid);
            return;
        }

        Block out = SignUtil.getBackBlock(recvSign);

        List<ItemStack> toSend = new ArrayList<>(e.getItems());
        PipeRequestEvent req = new PipeRequestEvent(out, toSend, recvSign);
        Bukkit.getPluginManager().callEvent(req);

        // Forward leftovers back to the original event, but stop default routing into the sender
        e.setItems(req.getItems());
        e.setCancelled(true);

        Bukkit.getLogger().info("[Pipes/DBG] Router(Request): " +
                "teleported start -> receiverBack accepted=" + (toSend.size() - req.getItems().size()) +
                " leftover=" + req.getItems().size());
    }

    // ---------- PUT stage (pipe finishing into a target) ----------
    @EventHandler(ignoreCancelled = true)
    public void onPut(PipePutEvent e) {
        Sign sender = findSenderForTarget(e.getPuttingBlock());
        if (sender == null) return;

        UUID rid = PipeLink.readBoundReceiverUUID(sender);
        if (rid == null) return;

        Block recvSign = PipeLinkService.get().getReceiverSignBlock(rid);
        if (recvSign == null || !(recvSign.getState() instanceof Sign)) {
            Bukkit.getLogger().warning("[Pipes/DBG] Router(Put): receiver not found for " + rid);
            return;
        }

        Block out = SignUtil.getBackBlock(recvSign);

        List<ItemStack> toSend = new ArrayList<>(e.getItems());
        PipeRequestEvent req = new PipeRequestEvent(out, toSend, recvSign);
        Bukkit.getPluginManager().callEvent(req);

        // Whatever wasn’t accepted downstream remains in the Put event
        e.setItems(req.getItems());

        Bukkit.getLogger().info("[Pipes/DBG] Router(Put): forwarded to receiverBack accepted=" +
                (toSend.size() - req.getItems().size()) + " leftover=" + req.getItems().size());
    }

    // ---------- helper: find a bound Sender sign for a target block ----------
    private static Sign findSenderForTarget(Block target) {
        // Case 1: the target is itself a sign with SEND_BOUND
        if (target.getState() instanceof Sign s) {
            if (PipeLink.readBoundReceiverUUID(s) != null) return s;
        }
        // Case 2: the target is the block directly in front of a bound Sender sign
        // Check the 4 horizontal neighbors (and up/down just in case)
        Block[] neighbors = new Block[] {
                target.getRelative(1, 0, 0),
                target.getRelative(-1, 0, 0),
                target.getRelative(0, 0, 1),
                target.getRelative(0, 0, -1),
                target.getRelative(0, 1, 0),
                target.getRelative(0, -1, 0)
        };
        for (Block nb : neighbors) {
            if (!(nb.getState() instanceof Sign s)) continue;
            if (PipeLink.readBoundReceiverUUID(s) == null) continue;
            // Is the sender sign facing this target block?
            Block frontOfSign = SignUtil.getFrontBlock(nb);
            if (frontOfSign != null && frontOfSign.equals(target)) return s;
        }
        return null;
    }
}
