package dev.bartershop.listener;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.LocationKey;
import dev.bartershop.core.model.Shop;
import dev.bartershop.gui.CustomerShopMenu;
import dev.bartershop.gui.ShopHubMenu;
import dev.bartershop.util.LocationUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.UUID;

/**
 * Physical protection for shop chests and signs. Protection is by block
 * location (both halves of a double chest), not by inventory contents, so it
 * also covers hopper chains, minecart hoppers and third-party transfer paths.
 */
public final class ProtectionListener implements Listener {

    private final BarterShopPlugin plugin;

    public ProtectionListener(BarterShopPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ block break

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        Shop chestShop = shopForChest(block);
        if (chestShop != null) {
            event.setCancelled(true);
            plugin.messages().send(player, "protect.chest");
            return;
        }
        Shop signShop = shopForSign(block);
        if (signShop != null) {
            event.setCancelled(true);
            plugin.messages().send(player, "protect.sign");
            return;
        }
        LocationKey key = LocationUtil.key(block);
        if (plugin.shopManager().isProtectedSupport(key)) {
            event.setCancelled(true);
            plugin.messages().send(player, "protect.sign-support");
            return;
        }
        if (LocationUtil.isSign(block)) {
            String line = LocationUtil.signLine(block, 0);
            if (plugin.signService().isMarker(line)) {
                var claim = plugin.shopManager().claim(key);
                if (claim.isPresent() && !claim.get().playerUuid().equals(player.getUniqueId())
                        && !player.hasPermission("bartershop.admin")) {
                    event.setCancelled(true);
                    plugin.messages().send(player, "sign.claimed");
                    return;
                }
                if (claim.isPresent()) {
                    plugin.signService().onSignBroken(player, block);
                }
            }
        }
        UUID tagged = taggedShopId(block);
        if (tagged != null && plugin.shopManager().byId(tagged).isEmpty()
                && !player.hasPermission("bartershop.admin")) {
            event.setCancelled(true);
            plugin.messages().send(player, "protect.unknown-shop");
        }
    }

    // ------------------------------------------------------------ block place

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (LocationUtil.isChest(block)) {
            for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                if (shopForChest(block.getRelative(face)) != null) {
                    event.setCancelled(true);
                    plugin.messages().send(event.getPlayer(), "protect.double-chest");
                    return;
                }
            }
        }
        if (shopForChest(block) != null || shopForSign(block) != null
                || plugin.shopManager().isProtectedSupport(LocationUtil.key(block))) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------------- interaction

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR) {
            if (player.isSneaking() && plugin.signService().cancelWithSneak(player)) {
                event.setCancelled(true);
            }
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        boolean protectedBlock = shopForChest(block) != null || shopForSign(block) != null
                || plugin.shopManager().isProtectedSupport(LocationUtil.key(block));
        if (event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            if (protectedBlock) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            if (protectedBlock) {
                event.setCancelled(true);
            }
            return;
        }

        if (LocationUtil.isSign(block)) {
            if (plugin.signService().handleSignClick(player, block)) {
                deny(event);
                return;
            }
            if (protectedBlock) {
                deny(event);
                return;
            }
            return;
        }

        if (LocationUtil.isChest(block)) {
            if (plugin.signService().handleChestClick(player, block)) {
                deny(event);
                return;
            }
            Shop shop = shopForChest(block);
            if (shop != null) {
                deny(event);
                if (plugin.shopManager().canManage(player, shop)) {
                    boolean admin = player.hasPermission("bartershop.admin");
                    Bukkit.getScheduler().runTask(plugin,
                            () -> new ShopHubMenu(plugin, player, shop, admin, null).open());
                } else if (shop.orphaned()) {
                    plugin.messages().send(player, "shop.unavailable");
                } else if (!shop.enabled()) {
                    plugin.messages().send(player, "shop.disabled");
                } else if (!player.hasPermission("bartershop.use")) {
                    plugin.messages().send(player, "general.no-permission");
                } else {
                    Bukkit.getScheduler().runTask(plugin,
                            () -> new CustomerShopMenu(plugin, player, shop, null).open());
                }
                return;
            }
        }

    }

    /** Cancels opening the physical chest through any plugin or API path. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (isShopInventory(event.getInventory())) {
            event.setCancelled(true);
            if (event.getPlayer() instanceof Player player) {
                plugin.messages().send(player, "protect.chest");
            }
        }
    }

    // ------------------------------------------------------- item transfer paths

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (isShopInventory(event.getSource()) || isShopInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        Block block = event.getBlock();
        if (isShopChestBlock(block)) {
            event.setCancelled(true);
            return;
        }
        BlockData data = block.getBlockData();
        if (data instanceof Directional directional) {
            if (isShopChestBlock(block.getRelative(directional.getFacing()))) {
                event.setCancelled(true);
            }
        }
    }

    // -------------------------------------------------------------- pistons etc.

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonThreat(event.getBlock(), event.getDirection(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonThreat(event.getBlock(), event.getDirection(), event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    private boolean pistonThreat(Block piston, BlockFace direction, List<Block> moved) {
        Block head = piston.getRelative(direction);
        if (isProtectedOrSupport(head)) {
            return true;
        }
        for (Block block : moved) {
            if (isProtectedOrSupport(block) || isProtectedOrSupport(block.getRelative(direction))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- environment

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedOrSupport);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedOrSupport);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isProtectedOrSupport(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (isProtectedOrSupport(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (isProtectedOrSupport(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (isProtectedOrSupport(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (isProtectedOrSupport(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (isProtectedOrSupport(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        if (isProtectedOrSupport(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        LocationKey key = LocationUtil.key(event.getBlock());
        if (plugin.shopManager().isProtectedSign(key) || plugin.shopManager().isProtectedSupport(key)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrops(org.bukkit.event.block.BlockDropItemEvent event) {
        if (isProtectedOrSupport(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void deny(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
    }

    private boolean isProtectedOrSupport(Block block) {
        if (block == null) {
            return false;
        }
        return isShopChestBlock(block)
                || shopForSign(block) != null
                || plugin.shopManager().isProtectedSupport(LocationUtil.key(block));
    }

    private boolean isShopChestBlock(Block block) {
        return LocationUtil.isChest(block) && (shopForChest(block) != null || taggedShopId(block) != null);
    }

    private Shop shopForChest(Block block) {
        if (block == null || !LocationUtil.isChest(block)) {
            return null;
        }
        Shop shop = plugin.shopManager().shopAtChest(LocationUtil.key(block));
        if (shop != null) {
            return shop;
        }
        UUID tagged = taggedShopId(block);
        return tagged == null ? null : plugin.shopManager().byId(tagged).orElse(null);
    }

    private Shop shopForSign(Block block) {
        if (block == null || !LocationUtil.isSign(block)) {
            return null;
        }
        Shop shop = plugin.shopManager().shopAtSign(LocationUtil.key(block));
        if (shop != null) {
            return shop;
        }
        UUID tagged = taggedShopId(block);
        return tagged == null ? null : plugin.shopManager().byId(tagged).orElse(null);
    }

    private UUID taggedShopId(Block block) {
        if (block == null || (!LocationUtil.isSign(block) && !LocationUtil.isChest(block))) {
            return null;
        }
        return LocationUtil.shopIdAt(plugin, block);
    }

    private boolean isShopInventory(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        if (inventory instanceof DoubleChestInventory doubleChest) {
            return sideProtected(doubleChest.getLeftSide()) || sideProtected(doubleChest.getRightSide());
        }
        return sideProtected(inventory);
    }

    private boolean sideProtected(Inventory side) {
        if (side == null) {
            return false;
        }
        if (side.getHolder() instanceof BlockInventoryHolder holder) {
            Shop shop = shopForChest(holder.getBlock());
            if (shop != null) {
                return true;
            }
        }
        if (side.getLocation() != null) {
            return plugin.shopManager().isProtectedChest(LocationUtil.key(side.getLocation()));
        }
        return false;
    }
}
