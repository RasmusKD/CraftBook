package com.sk89q.craftbook.mechanics.pipe;

import java.util.List;

import org.bukkit.block.Block;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * A request to continue pipe traversal starting at a block.
 *
 * New fields:
 *  - fromHop: true when created by a PipeLink hop (Sender/Router).
 *  - hopDepth: how many hops deep this request is (defensive against loops).
 *
 * Backwards compatibility: the original 3-arg constructor remains and
 * sets fromHop=false, hopDepth=0 so existing code keeps working.
 */
// PipeRequestEvent.java  (replace file)
public class PipeRequestEvent extends PipeSuckEvent {

    private static final HandlerList handlers = new HandlerList();

    private final boolean fromHop;
    private final int hopDepth;

    // Back-compat
    public PipeRequestEvent(Block theBlock, List<ItemStack> items, Block sucked) {
        this(theBlock, items, sucked, false, 0);
    }

    // New
    public PipeRequestEvent(Block theBlock, List<ItemStack> items, Block sucked, boolean fromHop, int hopDepth) {
        super(theBlock, items, sucked);
        this.fromHop = fromHop;
        this.hopDepth = hopDepth;
    }

    public boolean isFromHop() { return fromHop; }
    public int getHopDepth()   { return hopDepth; }

    @Override public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}
