package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import com.sk89q.craftbook.ChangedSign;
import com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil;
import com.sk89q.craftbook.mechanics.ic.AbstractICFactory;
import com.sk89q.craftbook.mechanics.ic.AbstractSelfTriggeredIC;
import com.sk89q.craftbook.mechanics.ic.ChipState;
import com.sk89q.craftbook.mechanics.ic.IC;
import org.bukkit.Server;
import org.bukkit.block.Sign;

import java.util.UUID;

/**
 * PipeLink Receiver – passiv destination for en bundet sender.
 */
public class PipeLinkReceiver extends AbstractSelfTriggeredIC {

    public PipeLinkReceiver(Server server, ChangedSign sign, AbstractICFactory factory) {
        super(server, sign, factory);
    }

    @Override public String getTitle() { return "PipeLink Receiver"; }
    @Override public String getSignTitle() { return "PIPELINK_RECEIVER"; }

    @Override
    public void trigger(ChipState chip) {
        // Passiv – ingen tick-logik.
    }

    @Override
    public void load() {
        org.bukkit.Bukkit.getLogger().info("[Pipes/DBG] Receiver load @ "
                + getBackBlock().getWorld().getName() + " "
                + getBackBlock().getX() + " " + getBackBlock().getY() + " " + getBackBlock().getZ());

        // Sørg for at receiver-skiltet har en UUID og er registreret
        Sign sign = CraftBookBukkitUtil.toSign(getSign());
        UUID id = PipeLink.readReceiverUUID(sign);
        if (id == null) {
            id = java.util.UUID.randomUUID();
            PipeLink.writeReceiverUUID(sign, id);
            // visuel opdatering af skilt
            sign.update(false);
        }

        PipeLinkService.get().registerReceiver(
                id,
                sign.getWorld().getUID(),
                sign.getX(),
                sign.getY(),
                sign.getZ()
        );
    }

    @Override
    public void unload() {
        // Valgfrit: afregistrér receiver på unload (f.eks. hvis mekanikken disables)
        try {
            Sign sign = CraftBookBukkitUtil.toSign(getSign());
            UUID id = PipeLink.readReceiverUUID(sign);
            if (id != null) {
                PipeLinkService.get().unregisterReceiver(id);
            }
        } catch (Throwable ignored) {
            // hvis sign ikke længere findes, ignorer
        }
        getSign().update(false);
    }

    // -------- Factory --------
    public static class Factory extends AbstractICFactory {
        public Factory(Server server) { super(server); }

        @Override public IC create(ChangedSign sign) { return new PipeLinkReceiver(getServer(), sign, this); }

        @Override public String getShortDescription() {
            return "Destination for linked PipeLink Sender ICs.";
        }

        @Override public String[] getLineHelp() {
            return new String[]{ "Place this where items should arrive from a bound sender.", null };
        }
    }
}
