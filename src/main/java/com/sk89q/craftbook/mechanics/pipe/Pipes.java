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
    private static final long XZ_MASK = 0x3FFFFFFL;
    private static final int X_SHIFT = 38;
    private static final int Z_SHIFT = 12;
    private static final long Y_MASK = 0xFFFL;

    private static long posKey(int x, int y, int z) {
        return ((x & XZ_MASK) << X_SHIFT) | ((z & XZ_MASK) << Z_SHIFT) | (y & Y_MASK);
    }

    private static long posKey(Block block) {
        return posKey(block.getX(), block.getY(), block.getZ());
    }

    // Filter caching system
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

    // Material-only filter helper
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

    // Enhanced traversal with bypass support
    private void searchNearbyPipes(Block block, Set<Long> visitedPipes, List<ItemStack> items, boolean bypassMode) {
        Deque<Block> searchQueue = new ArrayDeque<>();
        searchQueue.addFirst(block);
        int hopCount = 0;

        while (!searchQueue.isEmpty() && !items.isEmpty() && hopCount < 512) {
            Block bl = searchQueue.poll();
            hopCount++;

            // Process current block if it's a functional pipe component
            processPipeBlock(bl, items, visitedPipes, bypassMode);

            if (!items.isEmpty()) {
                // Use efficient directional search
                if (!pipesDiagonal) {
                    for (BlockFace direction : PIPE_DIRECTIONS) {
                        Block adjacent = bl.getRelative(direction);
                        if (processPipeConnection(bl, adjacent, visitedPipes, searchQueue, items, bypassMode)) {
                            if (items.isEmpty()) {
                                return;
                            }
                        }
                    }
                } else {
                    searchDiagonalPipes(bl, visitedPipes, searchQueue, items, bypassMode);
                }
            }
        }
    }

    private boolean processPipeConnection(Block current, Block adjacent, Set<Long> visitedPipes, Deque<Block> searchQueue, List<ItemStack> items, boolean bypassMode) {
        if (!isValidPipeBlock(adjacent)) return false;
        if (visitedPipes.contains(posKey(adjacent))) return false;

        // In bypass mode, skip filter checks for traversal
        if (!bypassMode && hasFilterSign(adjacent)) {
            List<ItemStack> filteredItems = applyPipeFiltersCached(adjacent, new ArrayList<>(items));
            if (filteredItems.isEmpty()) {
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
            handleGlassPaneConnection(current, adjacent, visitedPipes, searchQueue);
        } else if (adjType == Material.PISTON) {
            searchQueue.addFirst(adjacent);
        }

        return true;
    }

    private boolean hasFilterSign(Block block) {
        for (BlockFace face : PIPE_DIRECTIONS) {
            Block signBlock = block.getRelative(face);
            if (SignUtil.isSign(signBlock)) {
                ChangedSign sign = CraftBookBukkitUtil.toChangedSign(signBlock);
                if (sign != null && sign.getLine(1).equalsIgnoreCase("[Pipe]")) {
                    // Skip bypass signs when checking for filter gates
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

    // Optimized cached version that handles both existence check + filtering
    private List<ItemStack> applyPipeFiltersCached(Block block, List<ItemStack> items) {
        Location blockLoc = block.getLocation();
        ParsedFilters cached = filterCache.get(blockLoc);

        if (cached == null || cached.isStale()) {
            cached = parseAndCacheFilters(block);
        }

        if (!cached.hasFilters) {
            return items;
        }

        List<ItemStack> result = new ArrayList<>();
        for (ItemStack item : items) {
            if (passesFilterFast(item, cached.allowedTypes, cached.excludedTypes)) {
                result.add(item);
            }
        }

        return result;
    }

    // Parse filters once and cache them
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
                        parseFiltersToMaterialSets(sign, allowed, excluded);
                    }
                }
            }
        }

        ParsedFilters parsed = new ParsedFilters(allowed, excluded);
        filterCache.put(block.getLocation(), parsed);
        return parsed;
    }

    // Safe filter parsing with crash protection
    private void parseFiltersToMaterialSets(ChangedSign sign, Set<Material> allowed, Set<Material> excluded) {
        // Fast path: skip parsing if no filters exist
        boolean hasFilters = !sign.getLine(2).trim().isEmpty() || !sign.getLine(3).trim().isEmpty();
        if (!hasFilters) return;

        // Parse line 2 (allowed items) with safety checks
        String line2 = sign.getLine(2).trim();
        if (!line2.isEmpty()) {
            for (String token : RegexUtil.COMMA_PATTERN.split(line2)) {
                String trimmed = token.trim();
                if (isValidItemToken(trimmed)) {
                    try {
                        // CRITICAL FIX: Use Material.matchMaterial first
                        Material mat = Material.matchMaterial(trimmed);
                        if (mat != null) {
                            allowed.add(mat);
                        } else if (trimmed.contains(":") || trimmed.contains("@")) {
                            // Only use ItemSyntax for complex cases
                            ItemStack filterItem = ItemSyntax.getItem(trimmed);
                            if (filterItem != null) {
                                allowed.add(filterItem.getType());
                            }
                        }
                    } catch (Exception e) {
                        CraftBookPlugin.inst().getLogger().warning("Invalid item in pipe filter: " + trimmed);
                    }
                }
            }
        }

        // Parse line 3 (excluded items) with safety checks
        String line3 = sign.getLine(3).trim();
        if (!line3.isEmpty()) {
            for (String token : RegexUtil.COMMA_PATTERN.split(line3)) {
                String trimmed = token.trim();
                if (isValidItemToken(trimmed)) {
                    try {
                        // CRITICAL FIX: Use Material.matchMaterial first
                        Material mat = Material.matchMaterial(trimmed);
                        if (mat != null) {
                            excluded.add(mat);
                        } else if (trimmed.contains(":") || trimmed.contains("@")) {
                            // Only use ItemSyntax for complex cases
                            ItemStack filterItem = ItemSyntax.getItem(trimmed);
                            if (filterItem != null) {
                                excluded.add(filterItem.getType());
                            }
                        }
                    } catch (Exception e) {
                        CraftBookPlugin.inst().getLogger().warning("Invalid item in pipe filter: " + trimmed);
                    }
                }
            }
        }
    }

    // Validate tokens before sending to ItemSyntax
    private boolean isValidItemToken(String token) {
        if (token == null || token.isEmpty()) return false;
        if (token.equals("b") || token.equals("bypass")) return false;
        if (token.length() > 50) return false;

        // Skip tokens that are likely to cause legacy resolution issues
        if (token.matches("\\d+")) return false; // Pure numbers (legacy IDs)
        if (token.contains(" ")) return false; // Spaces cause issues

        return true;
    }

    // Fast filter checking using Material sets
    private boolean passesFilterFast(ItemStack item, Set<Material> allowed, Set<Material> excluded) {
        if (!ItemUtil.isStackValid(item)) return false;

        Material type = item.getType();

        // Check exclusions first (faster to reject)
        if (!excluded.isEmpty() && excluded.contains(type)) {
            return false;
        }

        // Check inclusions (if any specified)
        if (!allowed.isEmpty()) {
            return allowed.contains(type);
        }

        return true; // No filters = allow all
    }

    private void handleGlassPaneConnection(Block current, Block pane, Set<Long> visitedPipes, Deque<Block> searchQueue) {
        BlockFace direction = getDirectionBetween(current, pane);
        if (direction != null) {
            Block beyond = pane.getRelative(direction);
            if (isValidPipeBlock(beyond) && !visitedPipes.contains(posKey(beyond))) {
                if (ItemUtil.isStainedGlassPane(pane.getType())) {
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
        if (!ItemUtil.isStainedGlass(current.getType()) && !ItemUtil.isStainedGlassPane(current.getType())) {
            return true;
        }
        if (!ItemUtil.isStainedGlass(beyond.getType()) && !ItemUtil.isStainedGlassPane(beyond.getType())) {
            return true;
        }

        return ItemUtil.getStainedColor(pane.getType()) == ItemUtil.getStainedColor(current.getType()) ||
                ItemUtil.getStainedColor(pane.getType()) == ItemUtil.getStainedColor(beyond.getType());
    }

    private void searchDiagonalPipes(Block bl, Set<Long> visitedPipes, Deque<Block> searchQueue, List<ItemStack> items, boolean bypassMode) {
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    if (items.isEmpty()) return;

                    if (shouldSkipDiagonalConnection(bl, x, y, z)) continue;

                    Block adjacent = bl.getRelative(x, y, z);
                    processPipeConnection(bl, adjacent, visitedPipes, searchQueue, items, bypassMode);
                }
            }
        }
    }

    private boolean shouldSkipDiagonalConnection(Block bl, int x, int y, int z) {
        boolean xIsY = Math.abs(x) == Math.abs(y);
        boolean xIsZ = Math.abs(x) == Math.abs(z);
        boolean yIsZ = Math.abs(y) == Math.abs(z);

        if (xIsY && xIsZ && yIsZ) {
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

    private void processPipeBlock(Block bl, List<ItemStack> items, Set<Long> visitedPipes, boolean bypassMode) {
        Material type = bl.getType();

        if (type == Material.PISTON) {
            processPistonBlock(bl, items, bypassMode);
        } else if (type == Material.DROPPER) {
            processDropperBlock(bl, items);
        }
    }

    // Enhanced piston processing with pass-through logic for bypass mode
    private void processPistonBlock(Block bl, List<ItemStack> items, boolean bypassMode) {
        Piston p = (Piston) bl.getBlockData();
        ChangedSign sign = getAttachedPipeSign(bl);

        HashSet<ItemStack> pFilters = new HashSet<>();
        HashSet<ItemStack> pExceptions = new HashSet<>();

        if(sign != null) {
            parseFiltersFromSign(sign, pFilters, pExceptions);
        }

        // In bypass mode or normal mode, pistons act as pass-through with selective extraction
        List<ItemStack> matchingItems = new ArrayList<>();
        List<ItemStack> passThroughItems = new ArrayList<>();

        for (ItemStack item : items) {
            List<ItemStack> singleItem = Collections.singletonList(item);
            List<ItemStack> filtered = filterByMaterialOnly(singleItem, pFilters, pExceptions);

            if (!filtered.isEmpty()) {
                matchingItems.add(item);
            } else {
                passThroughItems.add(item);
            }
        }

        // Process matching items normally (try to insert into facing inventory)
        if (!matchingItems.isEmpty()) {
            List<ItemStack> filteredItems = new ArrayList<>(VerifyUtil.withoutNulls(filterByMaterialOnly(matchingItems, pFilters, pExceptions)));

            PipeFilterEvent filterEvent = new PipeFilterEvent(bl, matchingItems, pFilters, pExceptions, filteredItems);
            Bukkit.getPluginManager().callEvent(filterEvent);

            filteredItems = filterEvent.getFilteredItems();
            if (!filteredItems.isEmpty()) {
                List<ItemStack> newItems = new ArrayList<>();
                Block fac = bl.getRelative(p.getFacing());

                PipePutEvent event = new PipePutEvent(bl, new ArrayList<>(filteredItems), fac);
                Bukkit.getPluginManager().callEvent(event);

                if (!event.isCancelled()) {
                    if (InventoryUtil.doesBlockHaveInventory(fac)) {
                        InventoryHolder holder = (InventoryHolder) fac.getState();
                        newItems.addAll(InventoryUtil.addItemsToInventory(holder, event.getItems().toArray(new ItemStack[0])));
                    } else if (fac.getType() == Material.JUKEBOX) {
                        processJukeboxInteraction(fac, event.getItems(), newItems);
                    } else {
                        newItems.addAll(event.getItems());
                    }

                    // Items that couldn't be inserted continue as pass-through
                    passThroughItems.addAll(newItems);
                } else {
                    // Event was cancelled, add back to pass-through
                    passThroughItems.addAll(filteredItems);
                }
            }
        }

        // Update items list - only pass-through items continue flowing
        items.clear();
        items.addAll(passThroughItems);
    }

    private void processDropperBlock(Block bl, List<ItemStack> items) {
        ChangedSign sign = getAttachedPipeSign(bl);

        HashSet<ItemStack> pFilters = new HashSet<>();
        HashSet<ItemStack> pExceptions = new HashSet<>();

        if(sign != null) {
            parseFiltersFromSign(sign, pFilters, pExceptions);
        }

        List<ItemStack> filteredItems = new ArrayList<>(VerifyUtil.withoutNulls(filterByMaterialOnly(items, pFilters, pExceptions)));
        if(filteredItems.isEmpty()) return;

        Dropper dropper = (Dropper) bl.getState();
        List<ItemStack> newItems = new ArrayList<>(dropper.getInventory().addItem(filteredItems.toArray(new ItemStack[0])).values());

        int totalItemsToDrop = 0;
        for(ItemStack stack : dropper.getInventory().getContents()) {
            if(ItemUtil.isStackValid(stack)) {
                totalItemsToDrop += stack.getAmount();
            }
        }

        int maxDropsPerTick = 64;
        int dropsThisTick = Math.min(totalItemsToDrop, maxDropsPerTick);

        for(int i = 0; i < dropsThisTick; i++) {
            dropper.drop();
        }

        items.removeAll(filteredItems);
        items.addAll(newItems);
    }

    // Safe filter parsing from signs with crash protection
    private void parseFiltersFromSign(ChangedSign sign, HashSet<ItemStack> pFilters, HashSet<ItemStack> pExceptions) {
        if (sign == null) return;

        // Fast path: skip parsing if no filters exist
        boolean hasFilters = !sign.getLine(2).trim().isEmpty() || !sign.getLine(3).trim().isEmpty();
        if (!hasFilters) return;

        parseFilterLine(sign.getLine(2), pFilters);
        parseFilterLine(sign.getLine(3), pExceptions);
    }

    // Safe helper to parse a single filter line
    private void parseFilterLine(String line, HashSet<ItemStack> collection) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return;

        for (String token : RegexUtil.COMMA_PATTERN.split(trimmed)) {
            String cleanToken = token.trim();
            if (isValidItemToken(cleanToken)) {
                try {
                    // CRITICAL FIX: Use Material.matchMaterial instead of ItemSyntax.getItem
                    Material mat = Material.matchMaterial(cleanToken);
                    if (mat != null) {
                        collection.add(new ItemStack(mat));
                    } else {
                        // Fallback: only use ItemSyntax for complex syntax (data values, etc)
                        // but with safety checks
                        if (cleanToken.contains(":") || cleanToken.contains("@")) {
                            ItemStack filterItem = ItemSyntax.getItem(cleanToken);
                            if (filterItem != null) {
                                collection.add(filterItem);
                            }
                        }
                    }
                } catch (Exception e) {
                    CraftBookPlugin.inst().getLogger().warning("Invalid item syntax in pipe filter: " + cleanToken + " - " + e.getMessage());
                }
            }
        }
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
        boolean bypassMode = (block.getType() == Material.STICKY_PISTON &&
                !request &&
                hasBypassEnabled(sign));

        HashSet<ItemStack> filters = new HashSet<>();
        HashSet<ItemStack> exceptions = new HashSet<>();

        if (sign != null) {
            parseFiltersFromSign(sign, filters, exceptions);
        }

        // To Materials
        Set<Material> allowMats = new HashSet<>();
        for (ItemStack s : filters) if (ItemUtil.isStackValid(s)) allowMats.add(s.getType());
        Set<Material> denyMats = new HashSet<>();
        for (ItemStack s : exceptions) if (ItemUtil.isStackValid(s)) denyMats.add(s.getType());

        Set<Long> visitedPipes = new HashSet<>();

        // HOP START (continue from a pipe segment)
        if (request && isValidPipeBlock(block) && block.getType() != Material.STICKY_PISTON) {
            PipeSuckEvent event = new PipeSuckEvent(block, new ArrayList<>(items), block);
            Bukkit.getPluginManager().callEvent(event);

            items.clear();
            items.addAll(event.getItems());

            if (!event.isCancelled() && !items.isEmpty()) {
                visitedPipes.add(posKey(block));
                searchNearbyPipes(block, visitedPipes, items, false); // No bypass for hops
            }

            List<ItemStack> leftovers = new ArrayList<>(items);
            PipeFinishEvent fEvent = new PipeFinishEvent(block, leftovers, block, true);
            Bukkit.getPluginManager().callEvent(fEvent);

            leftovers = fEvent.getItems();
            items.clear();

            if (!leftovers.isEmpty()) {
                if (hopReturn != null && InventoryUtil.doesBlockHaveInventory(hopReturn)) {
                    InventoryHolder holder = (InventoryHolder) hopReturn.getState();
                    List<ItemStack> still = InventoryUtil.addItemsToInventory(holder, leftovers.toArray(new ItemStack[0]));
                    leftovers = still;
                }

                Block dropAt = (hopReturn != null ? hopReturn : block);
                for (ItemStack item : leftovers) {
                    if (!ItemUtil.isStackValid(item)) continue;
                    dropAt.getWorld().dropItemNaturally(dropAt.getLocation().add(0.5, 0.5, 0.5), item);
                }
            }
            return;
        }

        // STICKY-PISTON start (with bypass support)
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
                    // KEY CHANGE: Pass bypass mode to search function
                    searchNearbyPipes(block, visitedPipes, items, bypassMode);
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
                    searchNearbyPipes(block, visitedPipes, items, bypassMode);
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
                        searchNearbyPipes(block, visitedPipes, items, bypassMode);
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
                    searchNearbyPipes(block, visitedPipes, items, bypassMode);
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

        Block hopReturn = event.isFromHop() ? event.getSuckedBlock() : null;
        startPipe(event.getBlock(), event.getItems(), true, hopReturn);
    }

    // Cache invalidation event handlers
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
        filterCache.entrySet().removeIf(entry ->
                entry.getKey().getWorld().equals(world));
    }

    // Helper method to clear filter cache in area
    private void clearFilterCacheNearby(Location center) {
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
        pipeInsulator = BlockSyntax.getBlock(config.getString(path + "insulator-block", BlockTypes.WHITE_WOOL.getId()), true);

        config.setComment(path + "stack-per-move", "This option stops the pipes taking the entire chest on power, and makes it just take a single stack.");
        pipeStackPerPull = config.getBoolean(path + "stack-per-move", true);

        config.setComment(path + "require-sign", "Requires pipes to have a [Pipe] sign connected to them. This is the only way to require permissions to make pipes.");
        pipeRequireSign = config.getBoolean(path + "require-sign", false);
    }
}