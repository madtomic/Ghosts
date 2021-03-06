package com.me.tft_02.ghosts.managers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.me.tft_02.ghosts.Ghosts;
import com.me.tft_02.ghosts.config.Config;
import com.me.tft_02.ghosts.database.TombstoneDatabase;
import com.me.tft_02.ghosts.datatypes.TombBlock;
import com.me.tft_02.ghosts.events.tomb.PreTombCreateEvent;
import com.me.tft_02.ghosts.locale.LocaleLoader;
import com.me.tft_02.ghosts.managers.player.PlayerManager;
import com.me.tft_02.ghosts.util.BlockUtils;
import com.me.tft_02.ghosts.util.Misc;
import com.me.tft_02.ghosts.util.Permissions;

public class TombstoneManager {

    public static boolean createTombstone(Player player, List<ItemStack> drops) {
        Location location = player.getLocation();
        Block block = player.getWorld().getBlockAt(location);

        PreTombCreateEvent preTombCreateEvent = new PreTombCreateEvent(player, block);
        Ghosts.p.getServer().getPluginManager().callEvent(preTombCreateEvent);

        if (preTombCreateEvent.isCancelled()) {
            return false;
        }

        if (BlockUtils.cannotBeReplaced(block.getState())) {
            block = block.getRelative(BlockFace.UP);
        }

        if (Config.getInstance().getVoidCheck() && ((block.getY() > player.getWorld().getMaxHeight() - 1) || (block.getY() > player.getWorld().getMaxHeight()) || player.getLocation().getY() < 1)) {
            Ghosts.p.debug("Chest would be in the Void. Inventory dropped.");
            return false;
        }

        // Check if the player has a chest.
        int playerChestCount = 0;
        int playerSignCount = 0;
        for (ItemStack item : drops) {
            if (item == null) {
                continue;
            }
            if (item.getType() == Material.CHEST) {
                playerChestCount += item.getAmount();
            }
            if (item.getType() == Material.SIGN) {
                playerSignCount += item.getAmount();
            }
        }

        if (playerChestCount == 0 && !Permissions.freechest(player)) {
            Ghosts.p.debug("No chest! Inventory dropped.");
            return false;
        }

        // Check if we can replace the block.
        block = BlockUtils.findPlace(block, false);
        if (block == null) {
            Ghosts.p.debug("No room to place chest. Inventory dropped.");
            return false;
        }

        // Check if there is a nearby chest
        if (Config.getInstance().getNoInterfere() && BlockUtils.checkChest(block, Material.CHEST)) {
            Ghosts.p.debug("Existing chest interfering with chest placement. Inventory dropped.");
            return false;
        }

        int removeChestCount = 1;
        int removeSignCount = 0;

        // Do the check for a large chest block here so we can check for interference
        Block largeBlock = BlockUtils.findLarge(block);

        // Set the current block to a chest, init some variables for later use.
        block.setType(Material.CHEST);
        BlockState state = block.getState();
        if (!(state instanceof Chest)) {
            Ghosts.p.debug("Could not access chest. Inventory dropped.");
            return false;
        }

        Chest smallChest = (Chest) state;
        Chest largeChest = null;
        int maxSlot = smallChest.getInventory().getSize();

        // Check if they need a large chest.
        if (drops.size() > maxSlot) {
            // If they are allowed, spawn a large chest to catch their entire inventory.
            if (largeBlock != null && Permissions.largechest(player)) {
                removeChestCount = 2;
                // Check if the player has enough chests
                if (playerChestCount >= removeChestCount || Permissions.freechest(player)) {
                    largeBlock.setType(Material.CHEST);
                    largeChest = (Chest) largeBlock.getState();
                    maxSlot = maxSlot * 2;
                }
                else {
                    removeChestCount = 1;
                }
            }
        }

        // Don't remove any chests if they get a free one.
        if (Permissions.freechest(player)) {
            removeChestCount = 0;
        }

        // Check if we have signs enabled, if the player can use signs, and if the player has a sign or gets a free sign
        Block signBlock = null;
        if (Config.getInstance().getUseTombstoneSign() && Permissions.sign(player) && (playerSignCount > 0 || Permissions.freesign(player))) {
            // Find a place to put the sign, then place the sign.
            signBlock = smallChest.getWorld().getBlockAt(smallChest.getX(), smallChest.getY() + 1, smallChest.getZ());
            if (BlockUtils.canBeReplaced(signBlock.getState())) {
                BlockUtils.createSign(signBlock, player);
                removeSignCount += 1;
            }
            else if (largeChest != null) {
                signBlock = largeChest.getWorld().getBlockAt(largeChest.getX(), largeChest.getY() + 1, largeChest.getZ());
                if (BlockUtils.canBeReplaced(signBlock.getState())) {
                    BlockUtils.createSign(signBlock, player);
                    removeSignCount += 1;
                }
            }
        }

        // Don't remove a sign if they get a free one
        if (Permissions.freesign(player)) {
            removeSignCount -= 1;
        }

        // Create a TombBlock for this tombstone
        TombBlock tombBlock = new TombBlock(smallChest.getBlock(), (largeChest != null) ? largeChest.getBlock() : null, signBlock, player.getUniqueId() ,player.getName(), player.getLevel() + 1, (System.currentTimeMillis() / 1000));

        // Add tombstone to list
        TombstoneDatabase.tombList.offer(tombBlock);

        // Add tombstone blocks to tombBlockList
        TombstoneDatabase.tombBlockList.put(tombBlock.getBlock().getLocation(), tombBlock);
        if (tombBlock.getLargeBlock() != null) {
            TombstoneDatabase.tombBlockList.put(tombBlock.getLargeBlock().getLocation(), tombBlock);
        }
        if (tombBlock.getSign() != null) {
            TombstoneDatabase.tombBlockList.put(tombBlock.getSign().getLocation(), tombBlock);
        }

        // Add tombstone to player lookup list
        ArrayList<TombBlock> playerTombList = TombstoneDatabase.playerTombList.get(player.getUniqueId());
        if (playerTombList == null) {
            playerTombList = new ArrayList<TombBlock>();
            TombstoneDatabase.playerTombList.put(player.getUniqueId(), playerTombList);
        }
        playerTombList.add(tombBlock);

        TombstoneDatabase.saveTombList(player.getWorld());
        drops = handleItemLoss(drops, Config.getInstance().getLossesItems());
        storeInventoryInTomb(drops, removeChestCount, removeSignCount, smallChest, largeChest, maxSlot);

        sendNotificationMessages(player, drops);
        return true;
    }

    private static List<ItemStack> handleItemLoss(List<ItemStack> drops, double percentage) {
        int itemCount = 0;
        int itemsLost = 0;
        int size = drops.size();
        List<Integer> dontRemove = new ArrayList<Integer>();

        for (ItemStack itemStack : drops) {
            if (itemStack.getType() == Material.CHEST || itemStack.getType() == Material.SIGN) {
                size -= 1;
                dontRemove.add(drops.indexOf(itemStack));
                continue;
            }

            itemCount += itemStack.getAmount();
        }

        int lose = (int) Math.floor(itemCount * percentage * 0.01D);
        List<Integer> removeIndexes = new ArrayList<Integer>();

        for (int i = 0; i < lose; i++) {
            int randomIndex = Misc.getRandom().nextInt(size);
            boolean check = false;

            while (!check) {
                if (dontRemove.contains(randomIndex)) {
                    randomIndex = Misc.getRandom().nextInt(size);
                } else {
                    check = true;
                }
            }

            removeIndexes.add(randomIndex);
        }

        for (int index : removeIndexes) {
            ItemStack itemStack = drops.get(index);

            itemsLost++;

            if (itemStack.getAmount() > 0) {
                itemStack.setAmount(itemStack.getAmount() - 1);
                continue;
            }

            drops.remove(itemStack);
        }
        Ghosts.p.debug("Lost " + itemsLost + " items.");

        return drops;
    }

    private static void storeInventoryInTomb(List<ItemStack> drops, int removeChestCount, int removeSignCount, Chest smallChest, Chest largeChest, int maxSlot) {
        int slot = 0;

        // Next get the players inventory using drops.
        for (Iterator<ItemStack> iter = drops.listIterator(); iter.hasNext();) {
            ItemStack item = iter.next();
            if (item == null) {
                continue;
            }

            // Take the chest(s)
            if (removeChestCount > 0 && item.getType() == Material.CHEST) {
                if (item.getAmount() >= removeChestCount) {
                    item.setAmount(item.getAmount() - removeChestCount);
                    removeChestCount = 0;
                }
                else {
                    removeChestCount -= item.getAmount();
                    item.setAmount(0);
                }
                if (item.getAmount() == 0) {
                    iter.remove();
                    continue;
                }
            }

            // Take a sign
            if (removeSignCount > 0 && item.getType() == Material.SIGN) {
                item.setAmount(item.getAmount() - 1);
                removeSignCount -= 1;
                if (item.getAmount() == 0) {
                    iter.remove();
                    continue;
                }
            }

            // Add items to chest if not full.
            if (slot < maxSlot) {
                if (slot >= smallChest.getInventory().getSize()) {
                    if (largeChest == null) {
                        continue;
                    }
                    largeChest.getInventory().setItem(slot % smallChest.getInventory().getSize(), item);
                }
                else {
                    smallChest.getInventory().setItem(slot, item);
                }
                iter.remove();
                slot++;
            }
            else if (removeChestCount == 0) {
                break;
            }
        }
    }

    private static void sendNotificationMessages(Player player, List<ItemStack> drops) {
        player.sendMessage(LocaleLoader.getString("Tombstone.Inventory_Stored"));

        if (drops.size() > 0) {
            player.sendMessage(LocaleLoader.getString("Tombstone.Inventory_Overflow", drops.size()));
        }

        int breakTime = ((Config.getInstance().getLevelBasedTime() > 0) ? Math.min((player.getLevel() + 1) * Config.getInstance().getLevelBasedTime(), Config.getInstance().getTombRemoveTime()) : Config.getInstance().getTombRemoveTime());
        if (!Config.getInstance().getKeepUntilEmpty() || Config.getInstance().getTombRemoveTime() > 0) {
            player.sendMessage(LocaleLoader.getString("Tombstone.Time", Misc.getPrettyTime(breakTime)));
        }
    }

    public static boolean destroyAllTombstones(OfflinePlayer offlinePlayer, boolean dropContents, boolean notify) {
        ArrayList<TombBlock> tombstoneList = TombstoneDatabase.getTombstoneList().get(offlinePlayer.getUniqueId());
        if (tombstoneList.isEmpty()) {
            return false;
        }

        ArrayList<TombBlock> toDestroy = new ArrayList<TombBlock>();

        for (TombBlock tombBlock : tombstoneList) {
            toDestroy.add(tombBlock);
        }

        for (TombBlock tombBlock : toDestroy) {
            TombstoneManager.destroyTombstone(tombBlock, dropContents);
        }

        if (notify && offlinePlayer.isOnline()) {
            ((Player) offlinePlayer).sendMessage(LocaleLoader.getString("Tombstone.Broken.All"));
        }

        return true;
    }

    public void destroyTombstone(Location location, boolean dropContents) {
        destroyTombstone(TombstoneDatabase.tombBlockList.get(location), dropContents);
    }

    public static void destroyTombstone(TombBlock tombBlock, boolean dropContents) {
        destroyTombstone(tombBlock, dropContents, false);
    }

    /**
     * Destroy a tombstone
     */
    public static void destroyTombstone(TombBlock tombBlock, boolean dropContents, boolean notify) {
        Block block = tombBlock.getBlock();
        Block largeBlock = tombBlock.getLargeBlock();

        if (!block.getChunk().load()) {
            Ghosts.p.getLogger().severe("Error loading world chunk trying to remove tombstone at " + block.getX() + "," + block.getY() + "," + block.getZ() + " owned by " + tombBlock.getOwnerName() + ".");
            return;
        }

        // Empty chest
        if (!dropContents) {
            if (block.getState() instanceof Chest) {
                ((Chest) block.getState()).getInventory().clear();
            }
            if (largeBlock != null && largeBlock.getState() instanceof Chest) {
                ((Chest) largeBlock.getState()).getInventory().clear();
            }
        }

        if (tombBlock.getSign() != null) {
            tombBlock.getSign().setType(Material.AIR);
        }

        block.setType(Material.AIR);

        if (largeBlock != null) {
            largeBlock.setType(Material.AIR);
        }

        removeTomb(tombBlock, true);

        if (!notify) {
            return;
        }

        OfflinePlayer offlinePlayer = Ghosts.p.getServer().getOfflinePlayer(tombBlock.getOwnerUniqueId());
        if (offlinePlayer.isOnline()) {
            offlinePlayer.getPlayer().sendMessage(LocaleLoader.getString("Tombstone.Broken"));
        }
    }

    /**
     * Destroy a tomb from the tombblock data
     * Call this when breaking a tomb block
     */
    public static void removeTomb(TombBlock tombBlock, boolean removeList) {
        if (tombBlock == null) {
            return;
        }

        TombstoneDatabase.tombBlockList.remove(tombBlock.getBlock().getLocation());
        if (tombBlock.getLargeBlock() != null) {
            TombstoneDatabase.tombBlockList.remove(tombBlock.getLargeBlock().getLocation());
        }

        UUID ownerUniqueId = tombBlock.getOwnerUniqueId();
        ArrayList<TombBlock> tombList = TombstoneDatabase.playerTombList.get(ownerUniqueId);

        // Remove just this tomb from tombList
        if (tombList != null) {
            tombList.remove(tombBlock);
            if (tombList.size() == 0) {
                // Player has no other tombs anymore
                TombstoneDatabase.playerTombList.remove(ownerUniqueId);

                PlayerManager.resurrect(Ghosts.p.getServer().getOfflinePlayer(ownerUniqueId));
            }
        }

        if (removeList) {
            TombstoneDatabase.tombList.remove(tombBlock);
        }

        if (tombBlock.getBlock() != null) {
            TombstoneDatabase.saveTombList(tombBlock.getBlock().getWorld());
        }
    }
}
