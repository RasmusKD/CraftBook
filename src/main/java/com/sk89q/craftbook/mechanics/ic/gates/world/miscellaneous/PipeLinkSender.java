package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import com.sk89q.craftbook.ChangedSign;
import com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil;
import com.sk89q.craftbook.mechanics.ic.*;
import com.sk89q.craftbook.mechanics.pipe.PipeRequestEvent;
import com.sk89q.craftbook.util.SignUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class PipeLinkSender extends AbstractSelfTriggeredIC implements PipeInputIC {

    public PipeLinkSender(Server server, ChangedSign sign, AbstractICFactory factory) { super(server, sign, factory); }

    @Override public String getTitle() { return "PipeLink Sender"; }
    @Override public String getSignTitle() { return "PIPELINK_SENDER"; }
    @Override public void trigger(ChipState chip) { /* passive */ }

    @Override
    public void load() {
        Sign s = CraftBookBukkitUtil.toSign(getSign());
        UUID rid = PipeLink.readBoundReceiverUUID(s);

        Bukkit.getLogger().info("[Pipes/DBG] Sender SIGN @ "
                + s.getWorld().getName() + " " + s.getX() + " " + s.getY() + " " + s.getZ()
                + " bound=" + rid);

        if (rid != null) {
            PipeLinkService.get().bindSender(
                    s.getWorld().getUID(),
                    s.getX(), s.getY(), s.getZ(),
                    rid
            );
        }
    }

    @Override
    public void onPipeTransfer(com.sk89q.craftbook.mechanics.pipe.PipePutEvent event) {
        Sign senderSign = CraftBookBukkitUtil.toSign(getSign());
        UUID recvId = PipeLink.readBoundReceiverUUID(senderSign);

        Bukkit.getLogger().info("[Pipes/DBG] Sender onPipeTransfer: got " +
                event.getItems().size() + " items; bound=" + recvId);
        if (recvId == null) return;

        Block recvSignBlock = PipeLinkService.get().getReceiverSignBlock(recvId);
        if (recvSignBlock == null || !(recvSignBlock.getState() instanceof Sign)) {
            Bukkit.getLogger().warning("[Pipes/DBG] Receiver block not found or not a sign for " + recvId);
            return;
        }

        Block attached = SignUtil.getBackBlock(recvSignBlock);
        BlockFace backFace = SignUtil.getBack(recvSignBlock);
        List<Block> candidates = buildCandidates(attached, backFace);

        List<ItemStack> original = event.getItems();
        for (Block start : candidates) {
            if (start == null) continue;
            List<ItemStack> probe = cloneStacks(original);
            PipeRequestEvent req = new PipeRequestEvent(start, probe, attached, true, 1);
            Bukkit.getPluginManager().callEvent(req);

            int accepted = original.size() - req.getItems().size();
            Bukkit.getLogger().info("[Pipes/DBG] Sender probe start=" + pos(start) + " type=" + start.getType() + " accepted=" + accepted);
            if (accepted > 0) {
                event.setItems(req.getItems());
                Bukkit.getLogger().info("[Pipes/DBG] Sender routed via " + pos(start) + " accepted=" + accepted + " leftover=" + req.getItems().size());
                return;
            }
        }

        Bukkit.getLogger().info("[Pipes/DBG] Sender probe: all candidates accepted=0; leaving items untouched");
    }

    private static List<Block> buildCandidates(Block attached, BlockFace backFace) {
        Block onePast = attached.getRelative(backFace);
        Block twoPast = onePast.getRelative(backFace);
        Block left = attached.getRelative(rotateLeft(backFace));
        Block right = attached.getRelative(rotateRight(backFace));

        List<Block> ordered = new ArrayList<>();
        if (isPipeSegment(attached)) ordered.add(attached);
        ordered.addAll(Arrays.asList(onePast, twoPast, left, right));
        return ordered;
    }

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

    private static String pos(Block b){ return b.getWorld().getName()+" "+b.getX()+" "+b.getY()+" "+b.getZ(); }

    public static class Factory extends AbstractICFactory {
        public Factory(Server server) { super(server); }
        @Override public IC create(ChangedSign sign) { return new PipeLinkSender(getServer(), sign, this); }
        @Override public String getShortDescription() { return "Teleports incoming items to its bound receiver."; }
        @Override public String[] getLineHelp() { return new String[]{ "Bind with Blaze Rod: receiver first, then sender.", null }; }
    }
}
