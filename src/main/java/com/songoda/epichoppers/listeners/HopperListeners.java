package com.songoda.epichoppers.listeners;

import com.songoda.epichoppers.EpicHoppers;
import com.songoda.epichoppers.hopper.Hopper;
import com.songoda.epichoppers.hopper.levels.modules.Module;
import com.songoda.epichoppers.hopper.levels.modules.ModuleAutoCrafting;
import com.songoda.epichoppers.tasks.HopTask;
import com.songoda.epichoppers.utils.HopperDirection;
import com.songoda.epichoppers.utils.Methods;
import com.songoda.epichoppers.utils.ServerVersion;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Created by songoda on 4/18/2017.
 */
public class HopperListeners implements Listener {

    private final EpicHoppers instance;

    public HopperListeners(EpicHoppers instance) {
        this.instance = instance;
    }

    @EventHandler(ignoreCancelled = true)
    public void onHop(InventoryMoveItemEvent event) {
        Inventory source = event.getSource();
        Inventory destination = event.getDestination();

        // Hopper minecarts should be able to take care of themselves
        // Let EpicHoppers take over if the hopper is pointing down though
        if (destination.getHolder() instanceof HopperMinecart
                && source.getHolder() instanceof org.bukkit.block.Hopper
                && HopperDirection.getDirection(((org.bukkit.block.Hopper)source.getHolder()).getRawData()) != HopperDirection.DOWN)
            return;

        // Shulker boxes have a mind of their own and relentlessly steal items from hoppers
        if (this.instance.isServerVersionAtLeast(ServerVersion.V1_11) && destination.getHolder() instanceof org.bukkit.block.ShulkerBox && destination.getHolder() instanceof org.bukkit.block.Hopper) {
            event.setCancelled(true);
            return;
        }

        // Hopper going into minecarts
        if (destination.getHolder() instanceof Minecart && source.getHolder() instanceof org.bukkit.block.Hopper) {
            instance.getHopperManager().getHopper(((org.bukkit.block.Hopper) source.getHolder()).getLocation());
            event.setCancelled(true);
            return;
        }

        // Don't touch liquid tank hoppers
        if (instance.isLiquidtanks() && net.arcaniax.liquidtanks.object.LiquidTankAPI.isLiquidTank(event.getDestination().getLocation()))
            return;

        // Special cases when a hopper is picking up items
        if (destination.getHolder() instanceof org.bukkit.block.Hopper) {
            Hopper toHopper = instance.getHopperManager().getHopper(destination.getLocation());
            final ItemStack toMove = event.getItem();

            // Don't fill the last inventory slot on crafting hoppers (fixes crafters getting stuck)
            Module crafting = toHopper == null ? null : toHopper.getLevel().getModule("AutoCrafting");
            ItemStack toCraft = crafting instanceof ModuleAutoCrafting ? ((ModuleAutoCrafting) crafting).getAutoCrafting(toHopper) : null;
            // if we're not moving the item that we're trying to craft, we need to verify that we're not trying to fill the last slot
            // (filling every slot leaves no room for the crafter to function)
            if (toCraft != null && toCraft.getType() != Material.AIR
                    && !Methods.isSimilarMaterial(toMove, toCraft)
                    && !Methods.canMoveReserved(destination, toMove)) {
                event.setCancelled(true);
                return;
            }

            // pay attention to whitelist/blacklist if no linked chest defined
            if (toHopper != null
                    && toHopper.getFilter().getEndPoint() == null
                    && !(toHopper.getFilter().getWhiteList().isEmpty() && toHopper.getFilter().getBlackList().isEmpty())) {
                // this hopper has a filter with no rejection endpoint, so don't absorb disalowed items
                boolean allowItem;
                ItemStack moveInstead = null;
                // whitelist has priority
                if (!toHopper.getFilter().getWhiteList().isEmpty()) {
                    // is this item on the whitelist?
                    allowItem = toHopper.getFilter().getWhiteList().stream().anyMatch(item -> Methods.isSimilarMaterial(toMove, item));
                    if(!allowItem) {
                        // can we change the item to something else?
                        searchReplacement:
                        for(ItemStack sourceItem : source.getContents()) {
                            if(sourceItem != null && Methods.canMove(destination, sourceItem)) {
                                for(ItemStack item : toHopper.getFilter().getWhiteList()) {
                                    if(Methods.isSimilarMaterial(sourceItem, item)) {
                                        moveInstead = new ItemStack(sourceItem);
                                        moveInstead.setAmount(1);
                                        break searchReplacement;
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // check the blacklist
                    allowItem = !toHopper.getFilter().getBlackList().stream().anyMatch(item -> Methods.isSimilarMaterial(toMove, item));
                    if (!allowItem) {
                        // can we change the item to something else?
                        searchReplacement:
                        for (ItemStack sourceItem : source.getContents()) {
                            if (sourceItem != null && Methods.canMove(destination, sourceItem)) {
                                boolean blacklisted = toHopper.getFilter().getBlackList().stream().anyMatch(item -> Methods.isSimilarMaterial(sourceItem, item));
                                if (!blacklisted) {
                                    moveInstead = new ItemStack(sourceItem);
                                    moveInstead.setAmount(1);
                                    break;
                                }
                            }
                        }
                    }
                }
                if (!allowItem) {
                    event.setCancelled(true);
                    if (moveInstead != null) {
                        // hopper code is a bit derpy - changing the item doesn't change what's removed 
                        //event.setItem(moveInstead);
                        // we need to instead cancel and manually remove the item to move
                        source.removeItem(moveInstead);
                        Methods.updateAdjacentComparators(source.getLocation());
                        // now add it to the hopper
                        destination.addItem(moveInstead);
                        Methods.updateAdjacentComparators(destination.getLocation());
                    }
                    return;
                }
            }
        }

        if (!(source.getHolder() instanceof org.bukkit.block.Hopper))
            return;

        org.bukkit.block.Hopper sourceHopper = (org.bukkit.block.Hopper) source.getHolder();

        Location destinationLocation;
        if (destination.getHolder() instanceof org.bukkit.block.Hopper) {
            destinationLocation = ((org.bukkit.block.Hopper) destination.getHolder()).getLocation();
        } else if (destination.getHolder() instanceof Chest) {
            destinationLocation = ((Chest) destination.getHolder()).getLocation();
        } else if (destination.getHolder() instanceof DoubleChest) {
            destinationLocation = ((DoubleChest) destination.getHolder()).getLocation();
        } else {
            return;
        }

        if (!(destinationLocation.getBlock().getState() instanceof InventoryHolder))
            return;

        Hopper hopper = instance.getHopperManager().getHopper(sourceHopper.getLocation());

        hopper.clearLinkedBlocks();
        hopper.addLinkedBlock(destinationLocation);

        // Handle hopper push events elsewhere
        event.setCancelled(true);
    }
}
