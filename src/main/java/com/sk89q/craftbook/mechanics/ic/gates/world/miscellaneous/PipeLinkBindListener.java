package com.sk89q.craftbook.mechanics.ic.gates.world.miscellaneous;

import com.sk89q.craftbook.util.SignUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PipeLinkBindListener implements Listener {

    private final Map<UUID, UUID> awaitingBind = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastReceiver = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getClickedBlock() == null || !(e.getClickedBlock().getState() instanceof Sign)) {
            // Allow CLEAR shortcut: sneak + left-click air with Blaze Rod
            if (e.getAction() == Action.LEFT_CLICK_AIR &&
                    e.getPlayer().isSneaking() &&
                    e.getPlayer().getInventory().getItemInMainHand().getType() == Material.BLAZE_ROD) {
                lastReceiver.remove(e.getPlayer().getUniqueId());
                e.getPlayer().sendMessage(ChatColor.GRAY + "[Pipes] Huskede receiver er ryddet.");
            }
            return;
        }

        final Player p = e.getPlayer();
        if (!p.isSneaking()) return;

        final Sign sign = (Sign) e.getClickedBlock().getState();
        final Material inHand = p.getInventory().getItemInMainHand().getType();

        if (inHand == Material.BLAZE_ROD) {
            handleBlaze(p, sign);
            e.setCancelled(true);
            return;
        }

        if (inHand == Material.BREEZE_ROD) {
            handleInspect(p, sign);
            e.setCancelled(true);
        }
    }

    // ---------- Blaze Rod logic ----------
    private void handleBlaze(Player p, Sign clicked) {
        UUID pid = p.getUniqueId();

        // If sender clicked and we have a remembered receiver, bind immediately.
        if (isSenderIC(clicked) && lastReceiver.containsKey(pid)) {
            UUID rid = lastReceiver.get(pid);
            doBind(p, clicked, rid, true);
            return;
        }

        // Normal two-click flow
        if (!awaitingBind.containsKey(pid)) {
            // First click must be a receiver
            if (!isReceiverIC(clicked)) {
                p.sendMessage(ChatColor.RED + "[Pipes] Klik først et Receiver-skilt ([MC1281]/PIPELINK_RECEIVER) med Blaze Rod.");
                return;
            }

            UUID rid = PipeLink.readReceiverUUID(clicked);
            if (rid == null) {
                rid = UUID.randomUUID();
                PipeLink.writeReceiverUUID(clicked, rid);
                clicked.update(false);
            }
            PipeLinkService.get().registerReceiver(
                    rid, clicked.getWorld().getUID(), clicked.getX(), clicked.getY(), clicked.getZ()
            );

            // Remember for mass-binding too
            lastReceiver.put(pid, rid);
            awaitingBind.put(pid, rid);

            p.sendMessage(ChatColor.GREEN + "[Pipes] Receiver valgt. Klik nu en Sender ([MC1282]/PIPELINK_SENDER).");
            return;
        }

        // Second click must be a sender
        if (!isSenderIC(clicked)) {
            p.sendMessage(ChatColor.RED + "[Pipes] Nu skal du klikke en Sender ([MC1282]/PIPELINK_SENDER).");
            return;
        }

        UUID rid = awaitingBind.remove(pid);
        if (rid == null) {
            p.sendMessage(ChatColor.RED + "[Pipes] Intern fejl: mangler receiver-valg. Prøv igen.");
            return;
        }
        // Keep lastReceiver memory after a successful bind (mass-binding QoL)
        doBind(p, clicked, rid, true);
    }

    private void doBind(Player p, Sign senderSign, UUID rid, boolean withParticles) {
        PipeLink.writeBoundReceiverUUID(senderSign, rid);
        senderSign.update(false);

        PipeLinkService.get().bindSender(
                senderSign.getWorld().getUID(),
                senderSign.getX(), senderSign.getY(), senderSign.getZ(),
                rid
        );

        Block recv = PipeLinkService.get().getReceiverSignBlock(rid);
        if (recv != null) {
            p.sendMessage(ChatColor.AQUA + "[Pipes] Sender bundet til receiver @ "
                    + recv.getWorld().getName() + " "
                    + recv.getX() + " " + recv.getY() + " " + recv.getZ());
            if (withParticles) drawLinkParticles(p, center(senderSign.getBlock()), center(recv));
        } else {
            p.sendMessage(ChatColor.YELLOW + "[Pipes] Sender bundet, men receiver kan ikke findes i verden (forældet?).");
        }
    }

    // ---------- Breeze Rod inspect ----------
    private void handleInspect(Player p, Sign clicked) {
        // SENDER inspect
        if (isSenderIC(clicked)) {
            UUID rid = PipeLink.readBoundReceiverUUID(clicked);
            if (rid == null) {
                p.sendMessage(ChatColor.GOLD + "[Pipes] Denne sender er ikke bundet.");
                return;
            }

            Block recv = PipeLinkService.get().getReceiverSignBlock(rid);
            if (recv == null || !(recv.getState() instanceof Sign)) {
                p.sendMessage(ChatColor.YELLOW + "[Pipes] Receiver findes ikke (forældet link).");
                return;
            }

            p.sendMessage(ChatColor.GREEN + "[Pipes] Sender er bundet til receiver @ "
                    + recv.getWorld().getName() + " "
                    + recv.getX() + " " + recv.getY() + " " + recv.getZ());
            drawLinkParticles(p, center(clicked.getBlock()), center(recv));
            return;
        }

        // RECEIVER inspect
        if (isReceiverIC(clicked)) {
            UUID rid = PipeLink.readReceiverUUID(clicked);
            if (rid == null) {
                p.sendMessage(ChatColor.YELLOW + "[Pipes] Denne receiver har ingen ID endnu. Brug Blaze Rod (shift-højreklik) for at initialisere og binde en sender.");
                return;
            }

            // Ensure registered in service
            if (PipeLinkService.get().getReceiverRef(rid) == null) {
                PipeLinkService.get().registerReceiver(
                        rid, clicked.getWorld().getUID(), clicked.getX(), clicked.getY(), clicked.getZ()
                );
            }

            Set<PipeLinkService.SenderRef> senders = PipeLinkService.get().getSendersFor(rid);

            // Sort for stable order
            List<PipeLinkService.SenderRef> sorted = new ArrayList<>(senders);
            sorted.sort(Comparator
                    .comparing((PipeLinkService.SenderRef s) -> {
                        World w = Bukkit.getWorld(s.world);
                        return w == null ? "" : w.getName();
                    })
                    .thenComparingInt(s -> s.x)
                    .thenComparingInt(s -> s.y)
                    .thenComparingInt(s -> s.z));

            p.sendMessage(ChatColor.AQUA + "[Pipes] " + sorted.size() + " sender" + (sorted.size()==1?"":"e") + " bundet til denne receiver:");
            for (PipeLinkService.SenderRef sr : sorted) {
                World w = Bukkit.getWorld(sr.world);
                if (w != null) {
                    p.sendMessage(ChatColor.GRAY + "- " + w.getName() + " " + sr.x + " " + sr.y + " " + sr.z);
                    Block sb = w.getBlockAt(sr.x, sr.y, sr.z);
                    drawLinkParticles(p, center(sb), center(clicked.getBlock()));
                }
            }
            return;
        }

        p.sendMessage(ChatColor.RED + "[Pipes] Dette er ikke et PipeLink-skilt.");
    }

    // ---------- Helpers ----------
    public static boolean isReceiverIC(Sign s) {
        String l0 = norm(s.getLine(0));
        String l1 = norm(s.getLine(1));
        return containsAny(l0, "MC1281", "[MC1281]", "PIPELINK_RECEIVER", "PIPELINKRECEIVER")
                || containsAny(l1, "PIPELINK_RECEIVER", "PIPELINKRECEIVER", "RECEIVER");
    }

    public static boolean isSenderIC(Sign s) {
        String l0 = norm(s.getLine(0));
        String l1 = norm(s.getLine(1));
        return containsAny(l0, "MC1282", "[MC1282]", "PIPELINK_SENDER", "PIPELINKSENDER")
                || containsAny(l1, "PIPELINK_SENDER", "PIPELINKSENDER", "SENDER");
    }

    private static String norm(String s) {
        return (s == null ? "" : ChatColor.stripColor(s).trim().toUpperCase(Locale.ROOT));
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    private static org.bukkit.Location center(Block b) {
        return new org.bukkit.Location(b.getWorld(), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
    }

    /** Draws a soft dotted line from A → B for the viewer only. */
    private static void drawLinkParticles(Player p, org.bukkit.Location a, org.bukkit.Location b) {
        int points = 28;
        double dx = (b.getX() - a.getX()) / points;
        double dy = (b.getY() - a.getY()) / points;
        double dz = (b.getZ() - a.getZ()) / points;

        new BukkitRunnable() {
            int t = 0;
            final Particle.DustOptions dust = new Particle.DustOptions(Color.AQUA, 1.2F);

            @Override public void run() {
                if (t >= points) { cancel(); return; }
                double x = a.getX() + dx * t;
                double y = a.getY() + dy * t;
                double z = a.getZ() + dz * t;

                p.spawnParticle(Particle.DUST, x, y, z, 1, dust);
                t++;
            }
        }.runTaskTimer(com.sk89q.craftbook.bukkit.CraftBookPlugin.inst(), 0L, 1L);
    }
}
