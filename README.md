# BarterShop

BarterShop is a GUI-first, **item-for-item** chest shop plugin for Paper. There is
no currency, no Vault hook, no virtual money and no economy integration. Every
trade exchanges physical Minecraft items for other physical Minecraft items.

* **Target:** Paper `1.21.11-R0.1-SNAPSHOT`, Java 21
* **Build:** Maven
* **Storage:** SQLite (WAL, `synchronous=FULL`)
* **JAR:** `target/BarterShop-1.0.0.jar`

## Build

```bash
mvn test
mvn package
```

The shaded JAR is written to `target/BarterShop-1.0.0.jar`. It bundles the
SQLite JDBC driver and otherwise depends only on the Paper API.

## Install

1. Drop the JAR into the server's `plugins/` directory.
2. Start the server once. `plugins/BarterShop/config.yml` and `messages.yml`
   are generated.
3. Restart after editing settings that are read at startup.

No Vault, economy or database plugin is required. The plugin creates
`plugins/BarterShop/shops.db` automatically.

## Player workflow

### Create a shop

1. Place a sign with `[shop]` on its first line. The player who edits the sign
   must have `bartershop.create`.
2. The setup GUI opens for that player. If the sign is attached to an empty
   chest, that chest is selected automatically.
3. Click the chest icon to select a different chest by right-clicking it, or
   confirm the linked chest with the lime-dye button.
4. The chest must be completely empty, unclaimed, and within 8 blocks of both
   the sign and the player. Single and double chests are supported; the linked
   chest size determines shop stock capacity (27 or 54 slots).
5. Confirming opens the shop management hub. Everything after this point is
   GUI-driven: offers, quantities, matching rules, stock, proceeds, history,
   settings, relinking, transfer and deletion.

The sign is only an entry point. No special item is consumed, renamed or
required to create or configure a shop.

### What the shop sign shows

The front of the sign is rewritten automatically whenever offers, stock,
availability, expiry or maintenance state change. It is display only; shop
identity and permissions are always resolved server-side from the registered
block and the shop ID, never from the sign text.

* **Line 1** is the configured marker (`[shop]` by default). It identifies the
  sign and must stay intact for click detection.
* **One active offer** shows what the customer hands over (`16x Oak Log`), what
  they receive (`-> 4x Iron`) and the offer mode with the click hint
  (`SELL · Click` or `BUY · Click`). Offers with several payment item types
  show the number of types instead of a single item.
* **Several active offers** show the number of active offers, the two primary
  items and `Click to browse`.
* **Status instead of offers:** `OUT OF STOCK` when stock cannot satisfy the
  trade, `Offers expired` when every offer is past its expiry, `No offers` for
  an empty shop, `Disabled`, `Unavailable` for orphaned anchors and
  `Maintenance` during global maintenance. A scheduled refresh rewrites the
  sign the moment the last active offer expires.
* Every content line is clipped to Minecraft's 15-character limit with a
  visible `…`, so long item or shop names never overflow the sign.
* Sign text uses the server's default language (`language.default` in
  `config.yml`) so every player reads the same words regardless of their own
  language setting.
* Signs in unloaded chunks are rewritten as soon as their chunk loads, and at
  the latest by the five-minute anchor scan.

### Add offers

`Offers → Add offer` opens the editor.

* `Mode` toggles **Selling** (customer pays, receives shop stock) and
  **Buying** (customer supplies an item, receives an item the owner stocked).
  A shop supports both directions at the same time by holding independent
  sell and buy offers.
* `Pay item` and `Receive item` are chosen from the owner's inventory or the
  material catalogue.
* Amount buttons support exact values from 1 to the configured maximum, with
  presets and +/- controls. Amounts are independent of stack size and can span
  multiple stacks.
* `Matching` toggles between exact metadata matching and material-only
  matching for the **payment** side. Exact matching covers material, damage,
  enchantments, custom name, lore, potion data, custom model data, persistent
  data and all other serialised item components. A display-name match is never
  treated as an item match.

Selling example: pay `16x Emerald`, receive `1x Diamond`. The customer pays
16 emeralds and receives one diamond from shop stock. The owner receives the
16 emeralds in pending proceeds.

Buying example: pay `32x Cobblestone`, receive `1x Emerald`. The customer
supplies 32 cobblestones, receives one emerald from the owner's stock, and the
cobblestone enters shop stock.

### Stock

`Stock` shows the virtual stock. `Deposit` closes the GUI temporarily; click a
stack in your own inventory to move it into shop stock. Click a stock icon to
withdraw the whole stack, or right-click to withdraw one item. Partial
withdrawals are supported when the owner's inventory has room.

Shop stock lives in the database, not in the physical chest. The chest is an
anchor and is never used as the item container.

### Customer trading

Right-click the shop sign or the protected chest. Customers see the owner,
offers, exact quantities, stock availability and a confirmation screen.

* The trade is revalidated server-side at click time; the GUI snapshot is never
  trusted.
* Payment is removed from the customer's main inventory/hotbar only if the
  full trade can be committed.
* Delivery is added to the customer's inventory only if it fits completely.
* Selling payments go to the owner's pending proceeds. Buying payments go to
  shop stock.
* The owner never receives proceeds directly in their inventory during a
  trade. They must collect from `Shop management → Pending proceeds`.
* Owners cannot trade with their own shop by default
  (`shop.allow-owner-trade`).

### Collect proceeds and recover items

`Pending proceeds` lists queued stacks, with collect and history buttons.
Collection is best-effort and partial: items that fit are delivered, and the
remainder stays pending. A full inventory never causes items to be discarded.

When a shop is deleted, its stock and pending proceeds move to the owner's
**Recovered Items** mailbox (`/shop → Recovered Items`). Administrators can
delete an orphaned shop from the admin GUI; the items still go to the owner's
mailbox, never to the administrator.

### Join notifications

On join, owners with queued items receive a summary that:

* identifies the shop(s) or `Recovered items`,
* lists item types and quantities,
* explicitly says the items are stored and not yet delivered,
* points at `/shop` and the collection GUI.

The summary is capped at five shops with an "and N more" line, and duplicate
summaries are throttled. A player with an orphaned shop also receives a join
warning telling them to relink it.

## Commands and permissions

Commands are convenience/admin entry points only; ordinary shop actions are in
GUIs.

| Command | Purpose |
| --- | --- |
| `/shop` | Open your shops and recovered-items GUI |
| `/shop help` | Open the GUI-first creation help |
| `/shopadmin` | Open the admin GUI (requires `bartershop.admin`) |
| `/shopadmin scan` | Scan all anchors and mark orphaned shops |
| `/shopadmin reload` | Reload `config.yml` and `messages.yml` |

| Permission | Default | Purpose |
| --- | --- | --- |
| `bartershop.use` | true | Use customer GUIs and trade |
| `bartershop.create` | true | Create a shop from a `[shop]` sign and empty chest |
| `bartershop.shops.unlimited` | false | Bypass the per-player shop limit |
| `bartershop.admin` | op | Admin GUI, scan, relink, delete, transfer, audit |

## Protection model

Protection is by **block location**, including both halves of a double chest.
It is not limited to cancelling one inventory-open event.

Covered paths include:

* ordinary chest opening and direct inventory access,
* `InventoryOpenEvent` from other plugins/APIs,
* hoppers, hopper chains and hopper minecarts (`InventoryMoveItemEvent` from
  either source or destination),
* dispensers/droppers adjacent to a shop chest,
* pistons (extend and retract) and block movement,
* block break, block place, double-chest merging, sign replacement/editing,
* explosions, fire, ignition, spread, liquid flow, block forming/fading,
  entity block changes and block drops,
* sign support blocks and physics-driven sign removal.

Registered signs reject all `SignChangeEvent` edits. Unregistered `[shop]`
signs have a persistent claim; another player cannot claim or break a claimed
setup. Registered chests and signs cannot be broken by their owner either:
the supported removal path is the shop GUI delete action, which moves stock and
proceeds to the recovery mailbox instead of dropping items.

A periodic anchor scan (and `/shopadmin scan`) marks shops whose chest or sign
was removed by something outside Bukkit events (WorldEdit, console, another
plugin), pauses trading, and warns an online owner. `Settings → Relink chest`
and `Settings → Relink sign` are the repair paths. If both anchors are gone,
an administrator can delete the shop so its contents reach the owner's
mailbox.

## Design

```
src/main/java/dev/bartershop/
  core/        pure domain logic (items, stock, proceeds, trade engine, inventory model)
  storage/     SQLite schema, repository, journal codec, migration
  service/     shop lifecycle, trading, collection, signs, notifications
  gui/         inventory menus, icons, click protection, admin/recovery screens
  listener/    sign, player and physical protection listeners
  command/     GUI/admin entry points only
  util/        Bukkit <-> domain adapters (items, locations, inventories)
```

* **Pure core.** `InventoryModel`, `StockStorage`, `ProceedsStorage`,
  `TradeEngine` and `ProceedsCollector` contain no Bukkit types. Full-inventory,
  partial-collection, metadata matching and concurrency behaviour are unit
  tested without a server.
* **Server-side authority.** Every GUI action calls a service that re-checks
  ownership, permission, shop state, offer validity, stock, payment and
  capacity. Hiding a button is never the authorization boundary.
* **Per-shop locking.** Trades, collections, deposits, withdrawals, deletion and
  transfers use a `ReentrantLock` per shop with a configurable timeout. Two
  customers cannot oversell the same stock, and a trade cannot interleave with
  a collection.
* **Inventory diff plans.** Trades and withdrawals compute a complete before/after
  inventory model, verify every changed slot still matches, resolve all output
  stacks, then apply. If the live inventory changed, nothing is applied.
* **Durability.** SQLite uses WAL and `synchronous=FULL`. A recovery journal is
  written before shop-state mutation for trades and collections. On startup,
  interrupted trade journals roll shop stock/proceeds forward and interrupted
  collection journals return their journaled items to pending proceeds. GUI
  reads use a dedicated database thread; critical writes are awaited so the
  transaction can roll back cleanly.
* **Schema versioning.** `PRAGMA user_version` is checked; a newer database is
  rejected rather than silently corrupted. Version 1 is the initial schema.
* **No Bukkit serialization in the database.** Items are stored as normalised
  serialized item bytes plus a SHA-256 signature. Ownership is by UUID; player
  names are display data only.

## Verification

### Automated tests

`mvn test` runs 100 tests:

| Test class | Tests | Focus |
| --- | ---: | --- |
| `LocalizationTest` | 6 | en/de key parity, placeholders, MiniMessage validity, sign strings |
| `InventoryModelTest` | 7 | slot accounting, exact/loose matching, removal and capacity |
| `StockStorageTest` | 9 | merging, splitting, capacity, all-or-nothing removal |
| `TradeEngineTest` | 15 | sell/buy, multi-stack, insufficient payment/stock, full inventory/storage, metadata, concurrency, repeated clicks |
| `MultiPaymentTradeTest` | 5 | several payment item types in one trade |
| `OfferValidationTest` | 3 | amount bounds, mode changes |
| `ProceedsCollectorTest` | 6 | full/partial collection, mixed proceeds, metadata preservation |
| `JournalCodecTest` | 4 | trade/item payload round-trip and malformed data |
| `ShopRepositoryTest` | 20 | persistence, schema, journal recovery, collection rollback/commit, mailbox, deletion, history |
| `MigrationTest` | 2 | schema version handling and rejected newer databases |
| `RateLimiterTest` | 3 | per-action budgets and reconnect behaviour |
| `SignLayoutTest` | 12 | single/multi-offer signs, stock, expiry, statuses, 15-character clipping |
| `TradeConservationStressTest` | 1 | randomized trades conserve items and stock |
| `ProceedsStressTest` | 1 | randomized collections neither duplicate nor lose items |
| `ShopRepositoryStressTest` | 6 | concurrent persistence under load |

Last run: **100 tests, 0 failures, 0 errors, 0 skipped**, followed by a successful
`mvn package`.

### Live Paper test

Tested on a real Paper `1.21.11` server with two scripted offline-mode clients
(Mineflayer 1.21.11) using real inventory clicks and sign packets:

* `[shop]` sign edit opened the setup GUI; confirmation created the shop.
* GUI offer creation (selling `1x Emerald` for `1x Diamond`), stock deposit,
  customer confirmation, delivery and customer inventory update.
* Owner pending proceeds appeared; collection delivered the emerald and left
  zero pending.
* Rejoin after a full server restart loaded the shop and pending proceeds.
* Full owner inventory rejected collection without removing anything; freeing
  one slot allowed the collection to succeed.
* Buying offer (`1x Diamond` → `3x Emeralds`) paid from stock and delivered to
  the customer; the shop received the diamond in stock.
* Non-owner chest click opened the customer GUI rather than the physical chest.
* A hopper under the shop chest and a hopper minecart under the chest both
  pulled nothing: the injected test diamond stayed in the chest and both
  transfer attempts left the receiving container empty.
* Owner deletion moved stock to Recovered Items; the mailbox opened and
  collection delivered the stock. A second collect reported "no recovered
  items", proving the pending state was cleared.
* Non-owner sign editing was rejected; the marker line remained `[shop]`.
* Shop signs showed live offers (`2x Emerald` / `-> 4x Diamond` / `SELL · Click`),
  switched to `OUT OF STOCK`, `Disabled` and `Maintenance` when state changed,
  and showed `2 offers` / `Click to browse` for a multi-offer shop.
* With `language: de` the sign rendered `VERKAUF · Klick` and
  `Wartung` / `Klick für Info`.
* An offer expired 30 seconds after startup and the sign switched to
  `Offers expired` without a player interaction.

The live test also exposed and fixed a real GUI navigation bug: saving or
deleting an offer rebuilt the offer list with itself as its parent, so its Back
button looped. The rebuilt list now returns to the shop hub.

## Known limitations

These are deliberate, documented limits rather than silent omissions.

1. **Cross-resource crash atomicity.** Minecraft player inventories and SQLite
   cannot be committed in one atomic transaction. BarterShop journals shop
   state and recovers it on startup, and rechecks the live inventory before
   applying a diff. A crash in the very short window between a player-inventory
   mutation and the corresponding database commit can still leave an
   interrupted trade/collection inconsistent. Collection recovery is
   conservative for shop state (journaled items return to pending proceeds);
   if the inventory delivery had already been persisted, that recovery can
   make the items collectible twice. This is a fundamental boundary of Bukkit
   inventory APIs, not something the plugin hides. For high-value servers,
   keep database backups and inspect the `[AUDIT]`/journal logs after an
   unclean shutdown.
2. **Physical chest contents.** Shop stock is virtual. If an administrator or
   another plugin inserts items into a shop chest with console/API access, the
   plugin blocks normal access but does not move those items into the virtual
   stock. Remove them administratively after backing them up.
3. **Third-party block/item movers.** The listener covers the Bukkit/Paper
   events listed above. A plugin that writes blocks or inventories without
   firing those events (direct NMS or world-edit style writes) can bypass
   event-based protection. The periodic anchor scan catches missing anchors;
   test protection-plugin interactions on your server.
4. **Threading trade-off.** GUI reads are asynchronous, but critical writes are
   awaited on the server thread so a failed write can be rolled back before
   items move. These local SQLite writes were not benchmarked here; a stalled
   disk can delay the server thread up to the database call timeout.
5. **Single database writer.** The design targets one Paper server and a local
   SQLite file. It is not a cross-server/network shop database.
6. **Paper version.** Compiled against Paper `1.21.11-R0.1-SNAPSHOT` and Java 21.
   Rebuild against your target API before deploying to a different Minecraft
   version; item serialization and sign APIs are version-sensitive.
7. **Admin limits.** Administrators can relink, enable/disable, transfer and
   delete shops, and can inspect the audit log. They cannot take items from an
   owner's pending proceeds or mailbox; deletion routes items to the owner's
   mailbox by design.
