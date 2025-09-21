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
 * Bridge: if a pipe tries to put items into a Sender sign (has SEND_BOUND on PDC),
 * forward those items to the back of the bound Receiver.
 */
public class PipeLinkTransferListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onPipePut(PipePutEvent event) {
        Block target = event.getPuttingBlock(); // where the pipe is putting into
        if (!(target.getState() instanceof Sign)) return;

        Sign senderSign = (Sign) target.getState();
        UUID rid = PipeLink.readBoundReceiverUUID(senderSign);
        if (rid == null) return; // not a Sender

        // DEBUG
        Bukkit.getLogger().info("[Pipes/DBG] Bridge: PipePut into Sender. Items=" + event.getItems().size()
                + " bound=" + rid);

        Block recvSignBlock = PipeLinkService.get().getReceiverSignBlock(rid);
        if (recvSignBlock == null || !(recvSignBlock.getState() instanceof Sign)) {
            Bukkit.getLogger().warning("[Pipes/DBG] Bridge: Receiver not found or not a sign for " + rid);
            return;
        }

        Block out = SignUtil.getBackBlock(recvSignBlock);

        List<ItemStack> toSend = new ArrayList<>(event.getItems());
        PipeRequestEvent req = new PipeRequestEvent(out, toSend, recvSignBlock, true, 1);
        Bukkit.getPluginManager().callEvent(req);

        // DEBUG
        Bukkit.getLogger().info("[Pipes/DBG] Bridge: forwarded. accepted=" +
                (toSend.size() - req.getItems().size()) + " leftover=" + req.getItems().size());

        // Leftovers remain in the original event so upstream can handle/drop them.
        event.setItems(req.getItems());
    }
}
