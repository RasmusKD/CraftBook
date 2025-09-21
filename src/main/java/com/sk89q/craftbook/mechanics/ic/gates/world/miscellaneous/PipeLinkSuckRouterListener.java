package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import com.sk89q.craftbook.mechanics.pipe.PipeRequestEvent;
import com.sk89q.craftbook.mechanics.pipe.PipeSuckEvent;
import com.sk89q.craftbook.util.SignUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class PipeLinkSuckRouterListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSuck(PipeSuckEvent e) {
        Block target = e.getBlock(); // "into"
        log("[seen] into=" + pos(target) + " type=" + target.getType() + " items=" + e.getItems().size());

        FoundSender fs = findBoundSenderAdjacent(target);
        if (fs == null) {
            log("[miss] no bound sender adjacent to " + pos(target));
            return;
        }

        UUID rid = PipeLink.readBoundReceiverUUID(fs.senderSign);
        if (rid == null) {
            log("[miss] sender had no bound UUID @" + pos(fs.senderSign.getBlock()));
            return;
        }

        Block recvSign = PipeLinkService.get().getReceiverSignBlock(rid);
        if (recvSign == null || !(recvSign.getState() instanceof Sign)) {
            log("[miss] receiver not found for " + rid);
            return;
        }

        // Determine attached + direction
        Block attached = SignUtil.getBackBlock(recvSign);
        BlockFace backFace = SignUtil.getBack(recvSign);

        // Build candidate start blocks (order matters)
        List<Block> candidates = buildCandidates(attached, backFace);

        // Try each candidate: fire a temporary PipeRequestEvent and see what downstream accepts
        List<ItemStack> original = e.getItems();
        for (Block start : candidates) {
            if (start == null) continue;

            List<ItemStack> probe = cloneStacks(original);
            PipeRequestEvent req = new PipeRequestEvent(start, probe, e.getSuckedBlock(), true, 1);
            Bukkit.getPluginManager().callEvent(req);

            int accepted = original.size() - req.getItems().size();
            log("[probe] start=" + pos(start) + " type=" + start.getType() + " accepted=" + accepted + " leftover=" + req.getItems().size());

            if (accepted > 0) {
                // Use this candidate: forward leftovers (if any) to upstream and cancel original suck
                e.setItems(req.getItems());
                e.setCancelled(true);
                log("[HOP] sender@" + pos(fs.senderSign.getBlock()) + " side=" + fs.matchSide +
                        " -> recvStart " + pos(start) + " accepted=" + accepted + " leftover=" + req.getItems().size());
                return;
            }
        }

        // Nothing accepted -> do not interfere; let original suck continue
        log("[noop] all candidates accepted=0, leaving original suck untouched");
    }

    /** Candidate ordering: attached (if glass/piston), one-past, two-past, left, right. */
    private static List<Block> buildCandidates(Block attached, BlockFace backFace) {
        Block onePast = attached.getRelative(backFace);
        Block twoPast = onePast.getRelative(backFace);
        Block left = attached.getRelative(rotateLeft(backFace));
        Block right = attached.getRelative(rotateRight(backFace));

        List<Block> ordered = new ArrayList<>();
        // Prefer attached if it's a pipe segment on your server
        if (isPipeSegment(attached)) ordered.add(attached);
        ordered.addAll(Arrays.asList(onePast, twoPast, left, right));
        return ordered;
    }

    // Treat these as pipe segments (expand if you use more)
    private static boolean isPipeSegment(Block b) {
        Material m = b.getType();
        return m == Material.GLASS
                || m == Material.TINTED_GLASS
                || m == Material.PISTON
                || m == Material.STICKY_PISTON
                || m.name().endsWith("_GLASS");
    }

    private static BlockFace rotateLeft(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.WEST;
            case SOUTH -> BlockFace.EAST;
            case WEST  -> BlockFace.SOUTH;
            case EAST  -> BlockFace.NORTH;
            default    -> f;
        };
    }

    private static BlockFace rotateRight(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.EAST;
            case SOUTH -> BlockFace.WEST;
            case WEST  -> BlockFace.NORTH;
            case EAST  -> BlockFace.SOUTH;
            default    -> f;
        };
    }

    private static List<ItemStack> cloneStacks(List<ItemStack> src) {
        List<ItemStack> out = new ArrayList<>(src.size());
        for (ItemStack s : src) if (s != null) out.add(s.clone());
        return out;
    }

    private static class FoundSender {
        final Sign senderSign; final String matchSide;
        FoundSender(Sign s, String side){ senderSign=s; matchSide=side; }
    }

    /** Find a bound Sender sign whose FRONT or BACK equals target (6-neighborhood). */
    private static FoundSender findBoundSenderAdjacent(Block target) {
        if (target.getState() instanceof Sign s) {
            UUID bound = PipeLink.readBoundReceiverUUID(s);
            log("[scan] SELF SIGN " + pos(target) + " L0='" + s.getLine(0) + "' bound=" + bound);
            if (bound != null) return new FoundSender(s, "SELF");
        } else {
            log("[scan] SELF " + pos(target) + " type=" + target.getType());
        }

        Block[] nb = {
                target.getRelative( 1, 0, 0),
                target.getRelative(-1, 0, 0),
                target.getRelative( 0, 0, 1),
                target.getRelative( 0, 0,-1),
                target.getRelative( 0, 1, 0),
                target.getRelative( 0,-1, 0),
        };

        for (Block b : nb) {
            if (b.getState() instanceof Sign s) {
                UUID bound = PipeLink.readBoundReceiverUUID(s);
                Block front = SignUtil.getFrontBlock(b);
                Block back  = SignUtil.getBackBlock(b);
                log("[scan] NEIGH SIGN " + pos(b) + " L0='" + s.getLine(0) + "' bound=" + bound +
                        " front=" + pos(front) + " back=" + pos(back));
                if (bound != null) {
                    if (front != null && front.equals(target)) return new FoundSender(s,"FRONT");
                    if (back  != null && back.equals(target))  return new FoundSender(s,"BACK");
                }
            } else {
                log("[scan] NEIGH " + pos(b) + " type=" + b.getType());
            }
        }
        return null;
    }

    private static void log(String m){ Bukkit.getLogger().info("[Pipes/DBG][SuckRouter] " + m); }
    private static String pos(Block b){ return b==null?"null":(b.getWorld().getName()+" "+b.getX()+" "+b.getY()+" "+b.getZ()); }
}
