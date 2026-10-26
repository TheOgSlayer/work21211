package dev.sellplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SellGui implements Listener {

    public static final int SELL_SLOT = 53; // bottom-right of a double chest

    private final SellPlugin plugin;

    public SellGui(SellPlugin plugin) {
        this.plugin = plugin;
    }

    public static class Holder implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    public void open(Player player) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text(Text.sc("place items here to sell")));
        holder.inv = inv;
        refresh(inv);
        player.openInventory(inv);
    }

    /** Recalculates the payout and rewrites the green pane's lore. */
    private void refresh(Inventory inv) {
        PriceManager prices = plugin.prices();
        double total = 0;
        int unsellable = 0;
        for (int i = 0; i < SELL_SLOT; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            PriceManager.Valuation v = prices.value(item);
            if (v.sellable()) total += v.total();
            else unsellable++;
        }
        inv.setItem(SELL_SLOT, sellPane(Text.round(total), unsellable));
    }

    private ItemStack sellPane(double total, int unsellable) {
        ItemStack pane = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Text.plain(Text.sc("sell"), NamedTextColor.GREEN));

        List<Component> lore = new ArrayList<>();
        lore.add(Text.plain(Text.sc("you will get: "), NamedTextColor.GRAY)
                .append(Component.text("+$", NamedTextColor.GREEN))
                .append(Component.text(Text.money(total), Text.BLUE)));
        if (unsellable > 0) {
            lore.add(Text.plain(Text.sc(unsellable + " unsellable item" + (unsellable == 1 ? "" : "s") + " will stay"),
                    NamedTextColor.RED));
        }
        lore.add(Component.empty());
        lore.add(Text.plain(Text.sc("click to sell all items"), NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        pane.setItemMeta(meta);
        return pane;
    }

    /** Inventory events fire before the change is applied, so refresh one tick later. */
    private void refreshNextTick(Holder holder) {
        Bukkit.getScheduler().runTask(plugin, () -> refresh(holder.getInventory()));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        if (!(e.getWhoClicked() instanceof Player player)) return;

        refreshNextTick(holder);

        // stop "double click to gather" from pulling the pane onto the cursor
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR
                && e.getCursor() != null && e.getCursor().getType() == Material.LIME_STAINED_GLASS_PANE) {
            e.setCancelled(true);
            return;
        }

        if (e.getRawSlot() == SELL_SLOT) {
            e.setCancelled(true);
            sell(player, holder);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        if (e.getRawSlots().contains(SELL_SLOT)) {
            e.setCancelled(true);
            return;
        }
        refreshNextTick(holder);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        if (!(e.getPlayer() instanceof Player player)) return;
        returnItems(player, e.getInventory());
    }

    /** Gives back whatever is still in the GUI (everything except the sell button). */
    public void returnItems(Player player, Inventory inv) {
        for (int i = 0; i < SELL_SLOT; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            inv.setItem(i, null);
            player.getInventory().addItem(item).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
    }

    private void sell(Player player, Holder holder) {
        Economy eco = plugin.economy();
        if (eco == null) {
            player.sendMessage(Text.error("no economy plugin found"));
            return;
        }

        Inventory inv = holder.getInventory();
        PriceManager prices = plugin.prices();

        double total = 0;
        Set<Component> problems = new LinkedHashSet<>();

        for (int i = 0; i < SELL_SLOT; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;

            PriceManager.Valuation v = prices.value(item);
            if (v.sellable()) {
                total += v.total();
                inv.setItem(i, null);
            } else if (v.blocked() && v.total() > 0) {
                problems.add(Text.blockedShulker(Text.prettyName(item.getType())));
            } else {
                problems.add(Text.unsellable(Text.prettyName(item.getType())));
            }
        }

        for (Component c : problems) Text.send(player, c);

        total = Text.round(total);
        if (total > 0) {
            eco.depositPlayer(player, total);
            Text.send(player, Text.sold(total));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        }
        refresh(inv);
    }
}
