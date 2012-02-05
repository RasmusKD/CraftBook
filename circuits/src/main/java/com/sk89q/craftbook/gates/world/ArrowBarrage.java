// $Id$
/*
 * Copyright (C) 2010, 2011 sk89q <http://www.sk89q.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.craftbook.gates.world;


import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.util.Vector;

import com.sk89q.craftbook.ic.AbstractIC;
import com.sk89q.craftbook.ic.AbstractICFactory;
import com.sk89q.craftbook.ic.ChipState;
import com.sk89q.craftbook.ic.IC;
import com.sk89q.craftbook.ic.RestrictedIC;
import com.sk89q.craftbook.util.SignUtil;

public class ArrowBarrage extends AbstractIC {

    protected boolean risingEdge;

    public ArrowBarrage(Server server, Sign sign, boolean risingEdge) {
        super(server, sign);
        this.risingEdge = risingEdge;
    }

    @Override
    public String getTitle() {
        return "Arrow Barrage";
    }

    @Override
    public String getSignTitle() {
        return "ARROW BARRAGE";
    }

    @Override
    public void trigger(ChipState chip) {
        if (risingEdge && chip.getInput(0) || (!risingEdge && !chip.getInput(0))) {
        	float speed = 0.6F;
        	float spread = 12;
        	float vert = 0;
        	try {
        		String[] velocity = getSign().getLine(2).trim().split(":");
        		speed = Float.parseFloat(velocity[0]);
        		spread = Float.parseFloat(velocity[1]);
        		vert = Float.parseFloat(getSign().getLine(3).trim());
        	} catch (Exception e) {  }
        	
        	if(speed > 2.0) speed = 2F;
        	if(speed < 0.2) speed = 0.2F;
        	if(spread > 50) spread = 50;
        	if(spread < 0) spread = 0;
        	if(vert > 1) vert = 1;
        	if(vert < -1) vert = -1;
        	
            Block signBlock = getSign().getBlock();
            Block body = SignUtil.getBackBlock(signBlock);
            float x = signBlock.getX() - body.getX();
            float z = signBlock.getZ() - body.getZ();
        	Vector velocity = new Vector(x, vert, z);
        	Location shootLoc = new Location(getSign().getWorld(), signBlock.getX() + 0.5, signBlock.getY() + 0.5, signBlock.getZ() + 0.5);
        	for(int i = 0; i < 5; i++)
        		getSign().getWorld().spawnArrow(shootLoc, velocity, speed, spread);

        }
    }

    public static class Factory extends AbstractICFactory implements
            RestrictedIC {

        protected boolean risingEdge;

        public Factory(Server server, boolean risingEdge) {
            super(server);
            this.risingEdge = risingEdge;
        }

        @Override
        public IC create(Sign sign) {
            return new ArrowBarrage(getServer(), sign, risingEdge);
        }
    }
}
