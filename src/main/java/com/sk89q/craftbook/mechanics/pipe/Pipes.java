package com.sk89q.craftbook.mechanics.pipe;

import com.sk89q.craftbook.AbstractCraftBookMechanic;
import com.sk89q.craftbook.ChangedSign;
import com.sk89q.craftbook.CraftBookPlayer;
import com.sk89q.craftbook.bukkit.CraftBookPlugin;
import com.sk89q.craftbook.bukkit.util.CraftBookBukkitUtil;
import com.sk89q.craftbook.util.BlockSyntax;
import com.sk89q.craftbook.util.BlockUtil;
import com.sk89q.craftbook.util.EventUtil;
import com.sk89q.craftbook.util.InventoryUtil;
import com.sk89q.craftbook.util.ItemSyntax;
import com.sk89q.craftbook.util.ItemUtil;
import com.sk89q.craftbook.util.LocationUtil;
import com.sk89q.craftbook.util.ProtectionUtil;
import com.sk89q.craftbook.util.RegexUtil;
import com.sk89q.craftbook.util.SignUtil;
import com.sk89q.craftbook.util.VerifyUtil;
import com.sk89q.craftbook.util.events.SourcedBlockRedstoneEvent;
import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Dropper;
import org.bukkit.block.Furnace;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Crafter;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Piston;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class Pipes extends AbstractCraftBookMechanic {

    // Efficient 6-directional search instead of 27-block cubic search
    private static final BlockFace[] PIPE_DIRECTIONS = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    // Efficient block coordinate packing
    private static final long XZ_MASK = 0x3FFFFFFL; // 26 bits (±67M blocks)
    private static final int X_SHIFT = 38;
    private static final int Z_SHIFT = 12;
    private static final long Y_MASK = 0xFFFL;       // 12 bits (0-4095)

    private static long posKey(int x, int y, int z) {
        return ((x & XZ_MASK) << X_SHIFT) | ((z & XZ_MASK) << Z_SHIFT) | (y & Y_MASK);
    }

    private static long posKey(Block block) {
        return posKey(block.getX(), block.getY(), block.getZ());
    }

    // NEW: Filter caching system
    private final Map<Location, ParsedFilters> filterCache = new ConcurrentHashMap<>();

    // Cache structure for parsed filters
    private static class ParsedFilters {
        private final Set<Material> allowedTypes;
        private final Set<Material> excludedTypes;
        private final boolean hasFilters;
        private final long parseTime;

        public ParsedFilters(Set<Material> allowed, Set<Material> excluded) {
            this.allowedTypes = new HashSet<>(allowed);
            this.excludedTypes = new HashSet<>(excluded);
            this.hasFilters = !allowed.isEmpty() || !excluded.isEmpty();
            this.parseTime = System.currentTimeMillis();
        }

        public boolean isStale() {
            // Cache expires after 5 minutes for safety
            return System.currentTimeMillis() - parseTime > 300000;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSignChange(SignChangeEvent event) {

        if(!EventUtil.passesFilter(event)) return;

        if(!event.getLine(1).equalsIgnoreCase("[pipe]")) return;

        CraftBookPlayer player = CraftBookPlugin.inst().wrapPlayer(event.getPlayer());

        if(!player.hasPermission("craftbook.circuits.pipes")) {
            if(CraftBookPlugin.inst().getConfiguration().showPermissionMessages)
                player.printError("mech.create-permission");
            SignUtil.cancelSign(event);
            return;
        }

        // Check for bypass flag on line 0
        String line0 = event.getLine(0).trim().toLowerCase();
        boolean isBypass = line0.equals("bypass") || line0.equals("b");

        if (isBypass && !player.hasPermission("craftbook.circuits.pipes.bypass")) {
            if(CraftBookPlugin.inst().getConfiguration().showPermissionMessages)
                player.printError("mech.create-permission");
            SignUtil.cancelSign(event);
            return;
        }

        if(ProtectionUtil.shouldUseProtection()) {
            Block pistonBlock = null;

            if (SignUtil.isWallSign(event.getBlock())) {
                pistonBlock = SignUtil.getBackBlock(event.getBlock());
            } else if (SignUtil.isStandingSign(event.getBlock())) {
                if (isPiston(event.getBlock().getRelative(BlockFace.DOWN))) {
                    pistonBlock = event.getBlock().getRelative(BlockFace.DOWN);
                } else if (isPiston(event.getBlock().getRelative(BlockFace.UP))) {
                    pistonBlock = event.getBlock().getRelative(BlockFace.UP);
                }
            }
            if(pistonBlock != null && isPiston(pistonBlock)) {
                Piston pis = (Piston) pistonBlock.getBlockData();
                Block off = pistonBlock.getRelative(pis.getFacing());
                if (InventoryUtil.doesBlockHaveInventory(off)) {
                    if (!ProtectionUtil.canAccessInventory(event.getPlayer(), off)) {
                        if (CraftBookPlugin.inst().getConfiguration().showPermissionMessages)
                            player.printError("area.use-permission");
                        SignUtil.cancelSign(event);
                        return;
                    }
                }
            } else {
                player.printError("circuits.pipes.pipe-not-found");
                SignUtil.cancelSign(event);
                return;
            }
        }

        event.setLine(1, "[Pipe]");

        if (isBypass) {
            player.print("circuits.pipes.create-bypass");
        } else {
            player.print("circuits.pipes.create");
        }

        // NEW: Clear filter cache for this sign location and nearby blocks
        clearFilterCacheNearby(event.getBlock().getLocation());
    }

    private static boolean isPiston(Block block) {
        return block.getType() == Material.PISTON || block.getType() == Material.STICKY_PISTON;
    }

    // Helper method to check if a sign has bypass enabled
    private static boolean hasBypassEnabled(ChangedSign sign) {
        if (sign == null) return false;
        String line0 = sign.getLine(0).trim().toLowerCase();
        return line0.equals("bypass") || line0.equals("b");
    }

    // NEW: Material-only filter helper (consistent everywhere)
    private List<ItemStack> filterByMaterialOnly(List<ItemStack> items, Set<ItemStack> allowStacks, Set<ItemStack> denyStacks) {
        Set<Material> allow = new HashSet<>();
        Set<Material> deny = new HashSet<>();

        for (ItemStack stack : allowStacks) {
            if (ItemUtil.isStackValid(stack)) {
                allow.add(stack.getType());
            }
        }
        for (ItemStack stack : denyStacks) {
            if (ItemUtil.isStackValid(stack)) {
                deny.add(stack.getType());
            }
        }

        List<ItemStack> result = new ArrayList<>();
        for (ItemStack item : items) {
            if (!ItemUtil.isStackValid(item)) continue;

            Material type = item.getType();
            if (!deny.isEmpty() && deny.contains(type)) continue;
            if (!allow.isEmpty() && !allow.contains(type)) continue;

            result.add(item);
        }
        return result;
    }

    private static boolean passesMaterialOnly(ItemStack item, Set<Material> allow, Set<Material> deny) {
        if (!ItemUtil.isStackValid(item)) return false;
        Material t = item.getType();
        if (!deny.isEmpty() && deny.contains(t)) return false;
        if (!allow.isEmpty() && !allow.contains(t)) return false;
        return true;
    }

    private static ChangedSign getAttachedPipeSign(Block block) {
        BlockData blockData = block.getBlockData();
        BlockFace facing = BlockFace.SELF;
        if(blockData instanceof Directional) {
            facing = ((Directional) blockData).getFacing();
        }

        for(BlockFace face : LocationUtil.getDirectFaces()) {
            if(face == facing || !SignUtil.isSign(block.getRelative(face)))
                continue;
            if(!SignUtil.isStandingSign(block.getRelative(face)) && (face == BlockFace.UP || face == BlockFace.DOWN))
                continue;
            else if (SignUtil.isStandingSign(block.getRelative(face)) && face != BlockFace.UP && face != BlockFace.DOWN)
                continue;
            if(!SignUtil.isStandingSign(block.getRelative(face)) && !SignUtil.getBackBlock(block.getRelative(face)).getLocation().equals(block.getLocation()))
                continue;
            ChangedSign sign = CraftBookBukkitUtil.toChangedSign(block.getRelative(face));
            if(sign != null && sign.getLine(1).equalsIgnoreCase("[Pipe]"))
                return sign;
        }

        return null;
    }

    private void searchNearbyPipes(Block block, Set<Long> visitedPipes, List<ItemStack> items, boolean bypassFilters) {
        Deque<Block> searchQueue = new ArrayDeque<>();
        searchQueue.addFirst(block);

        while (!searchQueue.isEmpty() && !items.isEmpty()) {
            Block bl = searchQueue.poll();

            // Process current block if it's a functional pipe component
            processPipeBlock(bl, items, visitedPipes);

            if (!items.isEmpty()) {
                // Use efficient directional search
                if (!pipesDiagonal) {
                    // 6-directional search (78% reduction in blocks checked)
                    for (BlockFace direction : PIPE_DIRECTIONS) {
                        Block adjacent = bl.getRelative(direction);
                        if (processPipeConnection(bl, adjacent, visitedPipes, searchQueue, items, bypassFilters)) {
                            // Early exit if no items left
                            if (items.isEmpty()) return;
                        }
                    }
                } else {
                    // Optimized diagonal search - still much better than original
                    searchDiagonalPipes(bl, visitedPipes, searchQueue, items, bypassFilters);
                }
            }
        }
    }

    private boolean processPipeConnection(Block current, Block adjacent, Set<Long> visitedPipes, Deque<Block> searchQueue, List<ItemStack> items, boolean bypassFilters) {
        if (!isValidPipeBlock(adjacent)) return false;
        if (visitedPipes.contains(posKey(adjacent))) return false;

        // Check for filter signs BEFORE adding to visited pipes (unless bypassing)
        if (!bypassFilters && hasFilterSign(adjacent)) {
            List<ItemStack> filteredItems = applyPipeFiltersCached(adjacent, new ArrayList<>(items));
            if (filteredItems.isEmpty()) {
                // No items can pass through this filter
                return false;
            }
        }

        visitedPipes.add(posKey(adjacent));

        // Check stained glass color compatibility
        if (ItemUtil.isStainedGlass(current.getType()) && ItemUtil.isStainedGlass(adjacent.getType())
                && current.getType() != adjacent.getType()) {
            return false;
        }

        Material adjType = adjacent.getType();
        if (adjType == Material.GLASS || ItemUtil.isStainedGlass(adjType)) {
            searchQueue.add(adjacent);
        } else if (adjType == Material.GLASS_PANE || ItemUtil.isStainedGlassPane(adjType)) {
            // Handle glass pane connections
            handleGlassPaneConnection(current, adjacent, visitedPipes, searchQueue, bypassFilters);
        } else if (adjType == Material.PISTON) {
            searchQueue.addFirst(adjacent); // Priority for pistons
        }

        return true;
    }

    private boolean hasFilterSign(Block block) {
        for (BlockFace face : PIPE_DIRECTIONS) {
            Block signBlock = block.getRelative(face);
            if (SignUtil.isSign(signBlock)) {
                ChangedSign sign = CraftBookBukkitUtil.toChangedSign(signBlock);
                if (sign != null && sign.getLine(1).equalsIgnoreCase("[Pipe]")) {
                    // Check if it has filters (skip bypass signs)
                    if (hasBypassEnabled(sign)) continue;

                    String line2 = sign.getLine(2).trim();
                    String line3 = sign.getLine(3).trim();
                    if (!line2.isEmpty() || !line3.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // NEW: Optimized cached version that handles both existence check + filtering
    private List<ItemStack> applyPipeFiltersCached(Block block, List<ItemStack> items) {
        Location blockLoc = block.getLocation();
        ParsedFilters cached = filterCache.get(blockLoc);

        // Check if we need to parse filters
        if (cached == null || cached.isStale()) {
            cached = parseAndCacheFilters(block);
        }

        // If no filters, return all items immediately (most common case)
        if (!cached.hasFilters) {
            return items;
        }

        // Apply cached filters - much faster than original
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack item : items) {
            if (passesFilterFast(item, cached)) {
                result.add(item);
            }
        }

        return result;
    }

    // NEW: Parse filters once and cache them
    private ParsedFilters parseAndCacheFilters(Block block) {
        Set<Material> allowed = new HashSet<>();
        Set<Material> excluded = new HashSet<>();

        for (BlockFace face : PIPE_DIRECTIONS) {
            Block signBlock = block.getRelative(face);
            if (SignUtil.isSign(signBlock)) {
                ChangedSign sign = CraftBookBukkitUtil.toChangedSign(signBlock);
                if (sign != null && sign.getLine(1).equalsIgnoreCase("[Pipe]")) {
                    // Skip bypass signs when parsing filters for traversal
                    if (!hasBypassEnabled(sign)) {
                        parseFiltersToSets(sign, allowed, excluded);
                    }
                }
            }
        }

        ParsedFilters parsed = new ParsedFilters(allowed, excluded);
        filterCache.put(block.getLocation(), parsed);
        return parsed;
    }

    // NEW: Parse filters directly to Material sets (faster)
    private void parseFiltersToSets(ChangedSign sign, Set<Material> allowed, Set<Material> excluded) {
        // Parse line 2 (allowed items)
        for (String filterText : RegexUtil.COMMA_PATTERN.split(sign.getLine(2))) {
            String trimmed = filterText.trim();
            if (!trimmed.isEmpty()) {
                ItemStack filterItem = ItemSyntax.getItem(trimmed);
                if (filterItem != null) {
                    allowed.add(filterItem.getType());
                }
            }
        }

        // Parse line 3 (excluded items)
        for (String filterText : RegexUtil.COMMA_PATTERN.split(sign.getLine(3))) {
            String trimmed = filterText.trim();
            if (!trimmed.isEmpty()) {
                ItemStack filterItem = ItemSyntax.getItem(trimmed);
                if (filterItem != null) {
                    excluded.add(filterItem.getType());
                }
            }
        }
    }

    // NEW: Fast filter checking using Material sets instead of ItemStack comparison
    private boolean passesFilterFast(ItemStack item, ParsedFilters filters) {
        Material type = item.getType();

        // Check exclusions first (faster to reject)
        if (!filters.excludedTypes.isEmpty() && filters.excludedTypes.contains(type)) {
            return false;
        }

        // Check inclusions
        if (!filters.allowedTypes.isEmpty()) {
            return filters.allowedTypes.contains(type);
        }

        return true; // No filters = allow all
    }

    private void handleGlassPaneConnection(Block current, Block pane, Set<Long> visitedPipes, Deque<Block> searchQueue, boolean bypassFilters) {
        // Glass panes connect through to the next block
        BlockFace direction = getDirectionBetween(current, pane);
        if (direction != null) {
            Block beyond = pane.getRelative(direction);
            if (isValidPipeBlock(beyond) && !visitedPipes.contains(posKey(beyond))) {
                if (ItemUtil.isStainedGlassPane(pane.getType())) {
                    // Check color compatibility for stained glass panes
                    if (isColorCompatible(current, pane, beyond)) {
                        visitedPipes.add(posKey(beyond));
                        searchQueue.add(beyond);
                    }
                } else {
                    visitedPipes.add(posKey(beyond));
                    searchQueue.add(beyond);
                }
            }
        }
    }

    private BlockFace getDirectionBetween(Block from, Block to) {
        Vector diff = to.getLocation().toVector().subtract(from.getLocation().toVector());
        for (BlockFace face : PIPE_DIRECTIONS) {
            if (face.getDirection().equals(diff)) {
                return face;
            }
        }
        return null;
    }

    private boolean isColorCompatible(Block current, Block pane, Block beyond) {
        // Simplified color compatibility check
        if (!ItemUtil.isStainedGlass(current.getType()) && !ItemUtil.isStainedGlassPane(current.getType())) {
            return true;
        }
        if (!ItemUtil.isStainedGlass(beyond.getType()) && !ItemUtil.isStainedGlassPane(beyond.getType())) {
            return true;
        }

        return ItemUtil.getStainedColor(pane.getType()) == ItemUtil.getStainedColor(current.getType()) ||
                ItemUtil.getStainedColor(pane.getType()) == ItemUtil.getStainedColor(beyond.getType());
    }

    private void searchDiagonalPipes(Block bl, Set<Long> visitedPipes, Deque<Block> searchQueue, List<ItemStack> items, boolean bypassFilters) {
        // Optimized diagonal search - only check necessary blocks
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue; // Skip center block
                    if (items.isEmpty()) return;

                    // Apply insulator logic for diagonal connections
                    if (shouldSkipDiagonalConnection(bl, x, y, z)) continue;

                    Block adjacent = bl.getRelative(x, y, z);
                    processPipeConnection(bl, adjacent, visitedPipes, searchQueue, items, bypassFilters);
                }
            }
        }
    }

    private boolean shouldSkipDiagonalConnection(Block bl, int x, int y, int z) {
        boolean xIsY = Math.abs(x) == Math.abs(y);
        boolean xIsZ = Math.abs(x) == Math.abs(z);
        boolean yIsZ = Math.abs(y) == Math.abs(z);

        if (xIsY && xIsZ && yIsZ) {
            // Triple diagonal - check all three insulator positions
            return pipeInsulator.equalsFuzzy(BukkitAdapter.adapt(bl.getRelative(x, 0, 0).getBlockData()))
                    && pipeInsulator.equalsFuzzy(BukkitAdapter.adapt(bl.getRelative(0, y, 0).getBlockData()))
                    && pipeInsulator.equalsFuzzy(BukkitAdapter.adapt(bl.getRelative(0, 0, z).getBlockData()));
        } else if (xIsY) {
            return pipeInsulator.equalsFuzzy(BukkitAdapter.adapt(bl.getRelative(x, 0, 0).getBlockData()))
                    && pipeInsulator.equalsFuzzy(BukkitAdapter.adapt(bl.getRelative(0, y, 0).getBlockData()));
        } else if (xIsZ) {
            return pipeInsulator.equalsFuzzy(BukkitAdapter.adapt(bl.getRelative(x, 0, 0).getBlockData()))
                    && pipeInsulator.equalsFuzzy(BukkitAdapter.adapt(bl.getRelative(0, 0, z).getBlockData()));
        } else if (yIsZ) {
            return pipeInsulator.equalsFuzzy(BukkitAdapter.adapt(bl.getRelative(0, y, 0).getBlockData()))
                    && pipeInsulator.equalsFuzzy(BukkitAdapter.adapt(bl.getRelative(0, 0, z).getBlockData()));
        }

        return false;
    }

    private void processPipeBlock(Block bl, List<ItemStack> items, Set<Long> visitedPipes) {
        Material type = bl.getType();

        if (type == Material.PISTON) {
            processPistonBlock(bl, items);
        } else if (type == Material.DROPPER) {
            processDropperBlock(bl, items);
        }
    }

    private void processPistonBlock(Block bl, List<ItemStack> items) {
        Piston p = (Piston) bl.getBlockData();
        ChangedSign sign = getAttachedPipeSign(bl);

        HashSet<ItemStack> pFilters = new HashSet<>();
        HashSet<ItemStack> pExceptions = new HashSet<>();

        if(sign != null) {
            parseFiltersFromSign(sign, pFilters, pExceptions);
        }

        // CHANGED: Use material-only filtering (consistent with cached traversal)
        List<ItemStack> filteredItems = new ArrayList<>(VerifyUtil.withoutNulls(filterByMaterialOnly(items, pFilters, pExceptions)));

        PipeFilterEvent filterEvent = new PipeFilterEvent(bl, items, pFilters, pExceptions, filteredItems);
        Bukkit.getPluginManager().callEvent(filterEvent);

        filteredItems = filterEvent.getFilteredItems();
        if(filteredItems.isEmpty()) return;

        List<ItemStack> newItems = new ArrayList<>();
        Block fac = bl.getRelative(p.getFacing());

        PipePutEvent event = new PipePutEvent(bl, new ArrayList<>(filteredItems), fac);
        Bukkit.getPluginManager().callEvent(event);

        if (!event.isCancelled()) {
            if (InventoryUtil.doesBlockHaveInventory(fac)) {
                // Only call getState() when we know we need it
                InventoryHolder holder = (InventoryHolder) fac.getState();
                newItems.addAll(InventoryUtil.addItemsToInventory(holder, event.getItems().toArray(new ItemStack[0])));
            } else if (fac.getType() == Material.JUKEBOX) {
                processJukeboxInteraction(fac, event.getItems(), newItems);
            } else {
                newItems.addAll(event.getItems());
            }

            items.removeAll(filteredItems);
            items.addAll(newItems);
        }
    }

    private void processDropperBlock(Block bl, List<ItemStack> items) {
        ChangedSign sign = getAttachedPipeSign(bl);

        HashSet<ItemStack> pFilters = new HashSet<>();
        HashSet<ItemStack> pExceptions = new HashSet<>();

        if(sign != null) {
            parseFiltersFromSign(sign, pFilters, pExceptions);
        }

        // CHANGED: Use material-only filtering (consistent with cached traversal)
        List<ItemStack> filteredItems = new ArrayList<>(VerifyUtil.withoutNulls(filterByMaterialOnly(items, pFilters, pExceptions)));
        if(filteredItems.isEmpty()) return;

        // Only call getState() when needed
        Dropper dropper = (Dropper) bl.getState();
        List<ItemStack> newItems = new ArrayList<>(dropper.getInventory().addItem(filteredItems.toArray(new ItemStack[0])).values());

        // CHANGED: Add dropper throttling to prevent TPS spikes
        int totalItemsToDrop = 0;
        for(ItemStack stack : dropper.getInventory().getContents()) {
            if(ItemUtil.isStackValid(stack)) {
                totalItemsToDrop += stack.getAmount();
            }
        }

        // Cap drops per tick to prevent lag
        int maxDropsPerTick = 64; // Could be made configurable
        int dropsThisTick = Math.min(totalItemsToDrop, maxDropsPerTick);

        for(int i = 0; i < dropsThisTick; i++) {
            dropper.drop();
        }

        items.removeAll(filteredItems);
        items.addAll(newItems);
    }

    private void parseFiltersFromSign(ChangedSign sign, HashSet<ItemStack> pFilters, HashSet<ItemStack> pExceptions) {
        // For bypass signs, line structure is different
        if (hasBypassEnabled(sign)) {
            // Line 0: bypass
            // Line 1: [Pipe]
            // Line 2: allowed items
            // Line 3: excluded items
            for(String line2 : RegexUtil.COMMA_PATTERN.split(sign.getLine(2))) {
                pFilters.add(ItemSyntax.getItem(line2.trim()));
            }
            for(String line3 : RegexUtil.COMMA_PATTERN.split(sign.getLine(3))) {
                pExceptions.add(ItemSyntax.getItem(line3.trim()));
            }
        } else {
            // Normal sign structure
            for(String line2 : RegexUtil.COMMA_PATTERN.split(sign.getLine(2))) {
                pFilters.add(ItemSyntax.getItem(line2.trim()));
            }
            for(String line3 : RegexUtil.COMMA_PATTERN.split(sign.getLine(3))) {
                pExceptions.add(ItemSyntax.getItem(line3.trim()));
            }
        }

        pFilters.removeAll(Collections.<ItemStack>singleton(null));
        pExceptions.removeAll(Collections.<ItemStack>singleton(null));
    }

    private void processJukeboxInteraction(Block jukebox, List<ItemStack> items, List<ItemStack> newItems) {
        Jukebox juke = (Jukebox) jukebox.getState();
        List<ItemStack> its = new ArrayList<>(items);
        if (juke.getPlaying() != Material.AIR) {
            Iterator<ItemStack> iter = its.iterator();
            while (iter.hasNext()) {
                ItemStack st = iter.next();
                if (!st.getType().isRecord()) continue;
                juke.setPlaying(st.getType());
                juke.update();
                iter.remove();
                break;
            }
        }
        newItems.addAll(its);
    }

    private static boolean isValidPipeBlock(Block block) {
        Material type = block.getType();
        switch (type) {
            case GLASS:
            case PISTON:
            case STICKY_PISTON:
            case DROPPER:
            case GLASS_PANE:
                return true;
            default:
                return ItemUtil.isStainedGlass(type) || ItemUtil.isStainedGlassPane(type) || SignUtil.isWallSign(block);
        }
    }

    private void startPipe(Block block, List<ItemStack> items, boolean request, Block hopReturn) {
        // Check if this sticky piston has a bypass-enabled sign
        ChangedSign sign = getAttachedPipeSign(block);
        boolean bypassFilters = (block.getType() == Material.STICKY_PISTON &&
                !request &&
                hasBypassEnabled(sign));

        HashSet<ItemStack> filters = new HashSet<>();
        HashSet<ItemStack> exceptions = new HashSet<>();

        if (sign != null) {
            parseFiltersFromSign(sign, filters, exceptions);
        }

        filters.removeAll(Collections.<ItemStack>singleton(null));
        exceptions.removeAll(Collections.<ItemStack>singleton(null));

        // To Materials
        Set<Material> allowMats = new HashSet<>();
        for (ItemStack s : filters) if (ItemUtil.isStackValid(s)) allowMats.add(s.getType());
        Set<Material> denyMats = new HashSet<>();
        for (ItemStack s : exceptions) if (ItemUtil.isStackValid(s)) denyMats.add(s.getType());

        Set<Long> visitedPipes = new HashSet<>();

        // -------- HOP START (continue from a pipe segment) --------
        if (request && isValidPipeBlock(block) && block.getType() != Material.STICKY_PISTON) {
            // Let listeners adjust like a normal suction-at-segment
            PipeSuckEvent event = new PipeSuckEvent(block, new ArrayList<>(items), block);
            Bukkit.getPluginManager().callEvent(event);

            items.clear();
            items.addAll(event.getItems());

            if (!event.isCancelled() && !items.isEmpty()) {
                visitedPipes.add(posKey(block));
                searchNearbyPipes(block, visitedPipes, items, false); // No bypass for hops
            }

            // Finish and collect leftovers
            List<ItemStack> leftovers = new ArrayList<>(items);
            PipeFinishEvent fEvent = new PipeFinishEvent(block, leftovers, block, true);
            Bukkit.getPluginManager().callEvent(fEvent);

            leftovers = fEvent.getItems();
            items.clear();

            if (!leftovers.isEmpty()) {
                // NEW: try to return leftovers to ORIGINAL SOURCE inventory (from hop)
                if (hopReturn != null && InventoryUtil.doesBlockHaveInventory(hopReturn)) {
                    InventoryHolder holder = (InventoryHolder) hopReturn.getState();
                    List<ItemStack> still = InventoryUtil.addItemsToInventory(holder, leftovers.toArray(new ItemStack[0]));
                    leftovers = still; // whatever didn't fit
                }

                // Last resort: drop at the **source** if we know it, otherwise at the segment
                Block dropAt = (hopReturn != null ? hopReturn : block);
                for (ItemStack item : leftovers) {
                    if (!ItemUtil.isStackValid(item)) continue;
                    dropAt.getWorld().dropItemNaturally(dropAt.getLocation().add(0.5, 0.5, 0.5), item);
                }
            }
            return;
        }
        // ----------------------------------------------------------

        // -------- ORIGINAL sticky-piston start (with bypass support) --------
        if (block.getType() == Material.STICKY_PISTON) {
            List<ItemStack> leftovers = new ArrayList<>();

            Piston p = (Piston) block.getBlockData();
            Block fac = block.getRelative(p.getFacing());

            if (fac.getType() == Material.CHEST
                    || fac.getType() == Material.TRAPPED_CHEST
                    || fac.getType() == Material.DROPPER
                    || fac.getType() == Material.DISPENSER
                    || fac.getType() == Material.HOPPER
                    || fac.getType() == Material.BARREL
                    || fac.getType() == Material.CHISELED_BOOKSHELF
                    || fac.getType() == Material.CRAFTER
                    || fac.getType() == Material.DECORATED_POT
                    || Tag.SHULKER_BOXES.isTagged(fac.getType())) {

                for (ItemStack stack : ((InventoryHolder) fac.getState()).getInventory().getContents()) {
                    if (!ItemUtil.isStackValid(stack)) continue;
                    if (!passesMaterialOnly(stack, allowMats, denyMats)) continue;
                    items.add(stack);
                    ((InventoryHolder) fac.getState()).getInventory().removeItem(stack);
                    if (pipeStackPerPull) break;
                }

                PipeSuckEvent event = new PipeSuckEvent(block, new ArrayList<>(items), fac);
                Bukkit.getPluginManager().callEvent(event);
                items.clear();
                items.addAll(event.getItems());
                if (!event.isCancelled()) {
                    visitedPipes.add(posKey(fac));
                    searchNearbyPipes(block, visitedPipes, items, bypassFilters);
                }

                if (!items.isEmpty()) {
                    if (fac.getType() == Material.CRAFTER) {
                        leftovers.addAll(InventoryUtil.addItemsToCrafter((Crafter) fac.getState(), items.toArray(new ItemStack[0])));
                    } else {
                        InventoryHolder holder = (InventoryHolder) fac.getState();
                        for (ItemStack item : items) {
                            if (item == null) continue;
                            leftovers.addAll(holder.getInventory().addItem(item).values());
                        }
                    }
                }

            } else if (fac.getType() == Material.FURNACE || fac.getType() == Material.BLAST_FURNACE || fac.getType() == Material.SMOKER) {

                Furnace f = (Furnace) fac.getState();

                if (!ItemUtil.isStackValid(f.getInventory().getResult())) return;
                if (!passesMaterialOnly(f.getInventory().getResult(), allowMats, denyMats)) return;

                items.add(f.getInventory().getResult());
                f.getInventory().setResult(null);

                PipeSuckEvent event = new PipeSuckEvent(block, new ArrayList<>(items), fac);
                Bukkit.getPluginManager().callEvent(event);
                items.clear();
                items.addAll(event.getItems());
                if (!event.isCancelled()) {
                    visitedPipes.add(posKey(fac));
                    searchNearbyPipes(block, visitedPipes, items, bypassFilters);
                }

                if (!items.isEmpty()) {
                    for (ItemStack item : items) {
                        if (item == null) continue;
                        if (f.getInventory().getResult() == null) {
                            f.getInventory().setResult(item);
                        } else {
                            leftovers.add(ItemUtil.addToStack(f.getInventory().getResult(), item));
                        }
                    }
                } else {
                    f.getInventory().setResult(null);
                }

            } else if (fac.getType() == Material.JUKEBOX) {

                Jukebox juke = (Jukebox) fac.getState();

                if (juke.getPlaying() != Material.AIR) {
                    items.add(new ItemStack(juke.getPlaying()));

                    PipeSuckEvent event = new PipeSuckEvent(block, new ArrayList<>(items), fac);
                    Bukkit.getPluginManager().callEvent(event);
                    items.clear();
                    items.addAll(event.getItems());

                    if (!event.isCancelled()) {
                        visitedPipes.add(posKey(fac));
                        searchNearbyPipes(block, visitedPipes, items, bypassFilters);
                    }

                    if (!items.isEmpty()) {
                        for (ItemStack item : items) {
                            if (!ItemUtil.isStackValid(item)) continue;
                            block.getWorld().dropItem(BlockUtil.getBlockCentre(block), item);
                        }
                    } else {
                        juke.setPlaying(Material.AIR);
                        juke.update();
                    }
                }

            } else {
                PipeSuckEvent event = new PipeSuckEvent(block, new ArrayList<>(items), fac);
                Bukkit.getPluginManager().callEvent(event);
                items.clear();
                items.addAll(event.getItems());
                if (!event.isCancelled() && !items.isEmpty()) {
                    visitedPipes.add(posKey(fac));
                    searchNearbyPipes(block, visitedPipes, items, bypassFilters);
                }
                leftovers.addAll(items);
            }

            PipeFinishEvent fEvent = new PipeFinishEvent(block, leftovers, fac, request);
            Bukkit.getPluginManager().callEvent(fEvent);

            leftovers = fEvent.getItems();
            items.clear();

            if (!leftovers.isEmpty()) {
                for (ItemStack item : leftovers) {
                    if (!ItemUtil.isStackValid(item)) continue;
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), item);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockRedstoneChange(SourcedBlockRedstoneEvent event){
        if (event.getBlock().getType() == Material.STICKY_PISTON) {
            ChangedSign sign = getAttachedPipeSign(event.getBlock());

            if (pipeRequireSign && sign == null) return;
            if(!EventUtil.passesFilter(event)) return;

            startPipe(event.getBlock(), new ArrayList<>(), false, null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPipeRequest(PipeRequestEvent event) {
        boolean stickyStart = event.getBlock().getType() == Material.STICKY_PISTON;
        boolean hopStart    = event.isFromHop() && isValidPipeBlock(event.getBlock());

        if (!(stickyStart || hopStart)) return;
        if (pipeRequireSign && getAttachedPipeSign(event.getBlock()) == null) return;
        if (!EventUtil.passesFilter(event)) return;

        // NEW: pass the hop's original source so we can return leftovers there
        Block hopReturn = event.isFromHop() ? event.getSuckedBlock() : null;
        startPipe(event.getBlock(), event.getItems(), true, hopReturn);
    }

    // NEW: Cache invalidation event handlers
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (isValidPipeBlock(block) || SignUtil.isSign(block)) {
            clearFilterCacheNearby(block.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (isValidPipeBlock(block) || SignUtil.isSign(block)) {
            clearFilterCacheNearby(block.getLocation());
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        World world = event.getWorld();
        // Remove all cached data for this world to prevent memory leaks
        filterCache.entrySet().removeIf(entry ->
                entry.getKey().getWorld().equals(world));
    }

    // NEW: Helper method to clear filter cache in area
    private void clearFilterCacheNearby(Location center) {
        // Clear cache for the location and all adjacent blocks
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    Location loc = center.clone().add(x, y, z);
                    filterCache.remove(loc);
                }
            }
        }
    }

    private boolean pipesDiagonal;
    private BlockStateHolder<?> pipeInsulator;
    private boolean pipeStackPerPull;
    private boolean pipeRequireSign;

    @Override
    public void loadConfiguration (YAMLProcessor config, String path) {
        config.setComment(path + "allow-diagonal", "Allow pipes to work diagonally. Required for insulators to work.");
        pipesDiagonal = config.getBoolean(path + "allow-diagonal", false);

        config.setComment(path + "insulator-block", "When pipes work diagonally, this block allows the pipe to be insulated to not work diagonally.");
        pipeInsulator = BlockSyntax.getBlock(config.getString(path + "insulator-block", BlockTypes.WHITE_WOOL.id()), true);

        config.setComment(path + "stack-per-move", "This option stops the pipes taking the entire chest on power, and makes it just take a single stack.");
        pipeStackPerPull = config.getBoolean(path + "stack-per-move", true);

        config.setComment(path + "require-sign", "Requires pipes to have a [Pipe] sign connected to them. This is the only way to require permissions to make pipes.");
        pipeRequireSign = config.getBoolean(path + "require-sign", false);
    }
}