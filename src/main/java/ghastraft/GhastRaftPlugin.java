package ghastraft;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class GhastRaftPlugin extends JavaPlugin implements Listener {

    private static final int DEFAULT_GRID = 4;

    private final List<Raft.Pilot> ships = new ArrayList<>();
    private final Match match = new Match();
    private final Music music = new Music();
    private int tickCounter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Raft.TEAM_LOOKUP = match::teamOf;
        music.log = msg -> getLogger().info(msg);
        Raft.ON_DESTROYED = (victim, killer) -> {
            match.plunder(victim, killer);          // 적재 자원은 격침시킨 팀이 가져간다
            match.defeat(victim, "선박 격침");
        };
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 1L);
        org.bukkit.World space = SpaceMap.ensure();
        getLogger().info(space == null ? "우주 맵 준비 실패" : "우주 맵 준비 완료: " + space.getName());
        getLogger().info("GhastRaft " + Raft.VERSION + " enabled. /ship");
    }

    @Override
    public void onDisable() {
        match.stop();
        match.clearTeams();
        removeAll();
    }

    private void tick() {
        this.tickCounter++;
        if (this.tickCounter % 5 == 0) {
            match.tick();
            if (match.isRunning()) {
                for (Raft.Pilot ship : Raft.active()) Turrets.tick(ship, match);
            }
        }
        Turrets.tickMissiles();

        if (this.tickCounter % 10 == 0) {
            for (Player player : getServer().getOnlinePlayers()) {
                if (match.isPlaying(player)) {
                    Kits.resupply(player, this.tickCounter, match.isAboardOwnShip(player));
                }
            }
        }
        if (this.tickCounter % 20 == 0) {
            if (match.isRunning()) music.tick(getServer().getOnlinePlayers());
            else if (music.isPlaying()) music.stop(getServer().getOnlinePlayers());
        }
        if (this.tickCounter % 10 == 0) checkVoid();
        if (this.tickCounter % 20 == 0) {
            Iterator<Raft.Pilot> it = ships.iterator();
            while (it.hasNext()) {
                Raft.Pilot ship = it.next();
                if (ship.isRemoved()) {
                    ship.discardAll();
                    it.remove();
                }
            }
        }
    }

    private int removeAll() {
        int n = ships.size();
        for (Raft.Pilot ship : ships) ship.discardAll();
        ships.clear();
        return n;
    }

    /** 대상 엔티티가 선박의 일부라면 그 선박을 돌려준다 */
    private static Raft.Pilot shipPart(Entity entity) {
        if (!(entity instanceof CraftEntity craft)) return null;
        net.minecraft.world.entity.Entity nms = craft.getHandle();
        if (nms instanceof Raft.Pilot pilot) return pilot;
        if (nms instanceof Raft.Deck deck) return deck.owner;
        return null;
    }

    /* --------------------------------------------------------------- 이벤트 */

    /** 불·용암·낙하 등 환경 피해는 선체에 영향을 주지 않는다(무적을 못 걸어서 직접 막는다) */
    @EventHandler
    public void onAnyDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) return;   // 아래 핸들러가 처리
        if (shipPart(event.getEntity()) != null) event.setCancelled(true);
    }

    /** 채팅에 팀 색과 이름을 붙인다 */
    @EventHandler
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        int slot = match.teamOf(event.getPlayer().getUniqueId());
        if (slot < 0) return;
        NamedTextColor color = Match.textColor(slot);
        String name = Match.teamName(slot);
        event.renderer((source, display, message, viewer) ->
                Component.text("[" + name + "] ", color)
                        .append(display.color(color))
                        .append(Component.text(": ", NamedTextColor.GRAY))
                        .append(message.colorIfAbsent(NamedTextColor.WHITE)));
    }

    /** 경기 중이 아니면 사람끼리 때리지 못한다 */
    @EventHandler
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Entity source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            source = shooter;
        }
        if (!(source instanceof Player attacker)) return;
        if (attacker.equals(victim)) return;

        // 둘 다 진행 중인 경기의 생존자일 때만 허용
        if (!match.isPlaying(victim) || !match.isPlaying(attacker)) {
            event.setCancelled(true);
            attacker.sendActionBar(Component.text("경기 중에만 공격할 수 있습니다", NamedTextColor.GRAY));
        }
    }

    /**
     * 선체 피해는 Raft#hitShip 에서 직접 처리한다(닫힌 셜커가 화살을 무시해서
     * Bukkit 피해 이벤트가 아예 뜨지 않기 때문). 여기서는 셜커 자체가 죽지 않도록 막기만 한다.
     */
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (shipPart(event.getEntity()) != null) event.setCancelled(true);
    }

    /** 돌풍구 탄속을 올린다 */
    @EventHandler
    public void onProjectileLaunch(org.bukkit.event.entity.ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.AbstractWindCharge charge)) return;
        charge.setVelocity(charge.getVelocity().multiply(Kits.WIND_SPEED));
    }

    /**
     * 돌풍구 직격 피해. 바닐라는 밀치기만 있고 피해가 미미하다.
     * 장갑이 두꺼운 대상(함선·불곰)에는 더 크게 들어간다 - 대장갑 수단 역할.
     */
    @EventHandler
    public void onWindHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof org.bukkit.entity.AbstractWindCharge)) return;
        if (shipPart(event.getEntity()) != null) return;      // 선체는 Raft#hitShip 경로

        boolean armored = event.getEntity() instanceof Player target
                && "bear".equals(Kits.kitOf(target));
        event.setDamage(armored ? Kits.WIND_DAMAGE_ARMORED : Kits.WIND_DAMAGE);
    }

    /** 자기 팀 배에 우클릭 - 자원 보급. 적 배면 탑승 거부 안내. */
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Raft.Pilot ship = shipPart(event.getRightClicked());
        if (ship == null) return;

        Player player = event.getPlayer();
        int slot = match.teamOf(player.getUniqueId());

        if (ship.teamSlot >= 0 && slot != ship.teamSlot) {
            player.sendActionBar(Component.text("적 선박입니다. 조종석에 올라서면 점령이 시작됩니다.",
                    NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }

        int given = match.deposit(player, ship);
        if (given > 0) event.setCancelled(true);   // 보급했으면 탑승은 하지 않는다
    }

    /**
     * 인첸트 책을 들고 아이템을 클릭하면 그 자리에서 부여된다(모루 없이).
     * 커서에 책 · 클릭 대상이 적용 가능한 아이템이면 부여하고 책 한 권을 소모한다.
     */
    @EventHandler
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        org.bukkit.inventory.ItemStack cursor = event.getCursor();
        org.bukkit.inventory.ItemStack target = event.getCurrentItem();
        if (cursor == null || cursor.getType() != org.bukkit.Material.ENCHANTED_BOOK) return;
        if (target == null || target.getType() == org.bukkit.Material.AIR) return;
        if (!(cursor.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta meta)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        boolean applied = false;
        for (var entry : meta.getStoredEnchants().entrySet()) {
            org.bukkit.enchantments.Enchantment ench = entry.getKey();
            if (!ench.canEnchantItem(target)) continue;
            int current = target.getEnchantmentLevel(ench);
            int book = entry.getValue();

            int result;
            if (current == 0) result = book;
            else if (current == book) result = Math.min(ench.getMaxLevel(), current + 1);   // 같은 등급이면 승급
            else result = Math.max(current, book);
            if (result <= current) continue;

            target.addUnsafeEnchantment(ench, result);
            applied = true;
        }
        if (!applied) {
            player.sendActionBar(Component.text("이 아이템에는 적용할 수 없습니다", NamedTextColor.RED));
            return;
        }

        event.setCancelled(true);
        event.setCurrentItem(target);
        cursor.setAmount(cursor.getAmount() - 1);
        event.getView().setCursor(cursor.getAmount() <= 0 ? null : cursor);
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0F, 1.2F);
        player.sendActionBar(Component.text("인첸트 적용됨", NamedTextColor.LIGHT_PURPLE));
    }

    /** 리소스팩 수락 여부를 로그로 남긴다 - 팩이 없으면 커스텀 사운드는 소리 없이 실패한다 */
    @EventHandler
    public void onPackStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent event) {
        getLogger().info("리소스팩 [" + event.getPlayer().getName() + "] " + event.getStatus());
        if (event.getStatus() == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DECLINED
                || event.getStatus() == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            event.getPlayer().sendMessage(ChatColor.RED
                    + "리소스팩을 적용하지 않으면 배경음악이 들리지 않습니다. /ship pack 으로 다시 받으세요.");
        }
    }

    /** 리소스팩 적용. URL 이 비어 있으면 아무 것도 하지 않는다. */
    private void sendPack(Player player) {
        String url = getConfig().getString("resourcepack.url", "");
        String sha1 = getConfig().getString("resourcepack.sha1", "");
        if (url == null || url.isBlank()) return;
        byte[] hash = hexToBytes(sha1);
        boolean force = getConfig().getBoolean("resourcepack.force", true);
        if (hash == null) player.setResourcePack(url);
        else player.setResourcePack(url, hash, force);
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() != 40) return null;
        byte[] out = new byte[20];
        for (int i = 0; i < 20; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 접속 시 우주 정거장으로. 야생 월드에는 아무도 남지 않게 한다. */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sendPack(player);
        Guide.give(player);
        Kits.applyGravity(player);
        if (match.isRunning()) music.resume(player);

        // 팀이 편성돼 있는데 소속이 없으면 인원이 가장 적은 팀으로 중참
        if (match.teamOf(player.getUniqueId()) < 0) {
            int slot = match.joinSmallestTeam(player);
            if (slot >= 0) {
                getServer().broadcastMessage(ChatColor.GRAY + player.getName() + " 님이 "
                        + match.teamLabel(slot) + " 팀에 합류했습니다.");
            }
        }
        if (SpaceMap.world() == null) return;
        if (player.getWorld().equals(SpaceMap.world())) return;
        Location spawn = SpaceMap.stationSpawn();
        if (spawn != null) {
            player.teleport(spawn);
            player.setRespawnLocation(spawn, true);
        }
    }

    /** 돌·심층암은 파낼 수는 있지만 아이템으로 회수되지 않는다(소행성 굴착용) */
    @EventHandler
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
        org.bukkit.block.Block block = event.getBlock();
        Player player = event.getPlayer();

        switch (block.getType()) {
            case STONE, COBBLESTONE, DEEPSLATE, COBBLED_DEEPSLATE, TUFF -> {
                event.setDropItems(false);
                event.setExpToDrop(0);
            }
            // 광석은 바닥에 흘리지 않고 곧바로 인벤토리로
            case IRON_ORE, DEEPSLATE_IRON_ORE, GOLD_ORE, DEEPSLATE_GOLD_ORE,
                 NETHER_GOLD_ORE, RAW_IRON_BLOCK, RAW_GOLD_BLOCK -> {
                event.setDropItems(false);
                event.setExpToDrop(0);
                for (org.bukkit.inventory.ItemStack drop
                        : block.getDrops(player.getInventory().getItemInMainHand(), player)) {
                    for (org.bukkit.inventory.ItemStack leftover
                            : player.getInventory().addItem(drop).values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                    }
                }
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.6F, 1.6F);
            }
            default -> {
            }
        }
    }

    /** 직업 선택 아이템 · 용접기 우클릭 */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()) return;

        if (Kits.isSelector(event.getItem())) {
            event.setCancelled(true);
            Kits.openDialog(event.getPlayer());
            return;
        }
        if (Kits.isWelder(event.getItem())) {      // SCV 건설 메뉴
            event.setCancelled(true);
            Turrets.openDialog(event.getPlayer());
        }
    }

    /**
     * 아군이 쏜 화살·돌풍구는 아군 함선과 아군 플레이어를 관통한다.
     * ProjectileHitEvent 를 취소하면 발사체가 계속 날아가지만, 같은 히트박스 안에 그대로 있어
     * 다음 틱에 또 충돌한다. 속도를 유지한 채 살짝 앞으로 밀어 히트박스를 빠져나가게 한다.
     */
    @EventHandler
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        Projectile shot = event.getEntity();
        boolean passable = shot instanceof org.bukkit.entity.AbstractArrow
                || shot instanceof org.bukkit.entity.AbstractWindCharge;
        if (!passable) return;
        if (!(shot.getShooter() instanceof Player shooter)) return;
        Entity hit = event.getHitEntity();
        if (hit == null) return;

        int team = match.teamOf(shooter.getUniqueId());
        if (team < 0) return;

        boolean friendly;
        Raft.Pilot ship = shipPart(hit);
        if (ship != null) friendly = ship.teamSlot == team;
        else if (hit instanceof Player victim) friendly = match.teamOf(victim.getUniqueId()) == team;
        else return;
        if (!friendly) return;

        event.setCancelled(true);
        org.bukkit.util.Vector velocity = shot.getVelocity();
        if (velocity.lengthSquared() > 1.0E-6) {
            shot.teleport(shot.getLocation().add(velocity.clone().normalize().multiply(1.4)));
            shot.setVelocity(velocity);
        }
    }

    /**
     * 보급 상자는 열리지 않는다. 우클릭하면 그 자리에서 터지듯 사라지고 내용물이 바닥에 쏟아진다.
     * 상자를 창고로 써서 아군에게 장비를 넘기는 우회로도 함께 막힌다.
     */
    @EventHandler
    public void onChestOpen(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        org.bukkit.block.Block block = event.getClickedBlock();
        if (block == null || block.getType() != org.bukkit.Material.CHEST) return;

        event.setCancelled(true);
        org.bukkit.World world = block.getWorld();
        Location center = block.getLocation().add(0.5, 0.6, 0.5);

        List<org.bukkit.inventory.ItemStack> loot = new ArrayList<>();
        if (block.getState(false) instanceof org.bukkit.block.Chest chest) {
            for (org.bukkit.inventory.ItemStack item : chest.getInventory().getContents()) {
                if (item != null && item.getType() != org.bukkit.Material.AIR) loot.add(item.clone());
            }
            chest.getInventory().clear();
        }
        block.setType(org.bukkit.Material.AIR, false);

        world.playSound(center, org.bukkit.Sound.BLOCK_CHEST_OPEN, 1.0F, 1.2F);
        world.playSound(center, org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1.0F, 0.6F);
        world.spawnParticle(org.bukkit.Particle.END_ROD, center, 24, 0.25, 0.25, 0.25, 0.05);
        world.spawnParticle(org.bukkit.Particle.CLOUD, center, 12, 0.3, 0.2, 0.3, 0.02);

        for (org.bukkit.inventory.ItemStack item : loot) {
            org.bukkit.entity.Item dropped = world.dropItem(center, item);
            dropped.setVelocity(new org.bukkit.util.Vector(
                    (Math.random() - 0.5) * 0.12, 0.22, (Math.random() - 0.5) * 0.12));
        }
        if (loot.isEmpty()) {
            event.getPlayer().sendActionBar(Component.text("비어 있는 보급함", NamedTextColor.GRAY));
        }
    }

    /**
     * 지급 장비만 버릴 수 없다.
     * 보급 상자에서 나온 전리품(검·책·엔더 진주·사과 등)은 자유롭게 던져 나눌 수 있다.
     */
    @EventHandler
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (!match.isPlaying(event.getPlayer())) return;
        if (!Kits.isKitItem(event.getItemDrop().getItemStack())) return;
        event.setCancelled(true);
        event.getPlayer().sendActionBar(Component.text("지급 장비는 버릴 수 없습니다",
                NamedTextColor.GRAY));
    }

    /** 자동 포탑 눈덩이 - 아군에게는 맞지 않고, 적에게는 고정 피해 */
    @EventHandler
    public void onTurretShot(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof org.bukkit.entity.Snowball ball)) return;
        Integer team = ball.getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey(Kits.NS, "turret"),
                org.bukkit.persistence.PersistentDataType.INTEGER);
        if (team == null) return;

        if (event.getEntity() instanceof Player victim
                && match.teamOf(victim.getUniqueId()) == team) {
            event.setCancelled(true);              // 아군 오사
            return;
        }
        event.setDamage(Turrets.AUTO_DAMAGE);
    }

    /** 다이얼로그 버튼 */
    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        String id = event.getIdentifier().asString();
        if (!(event.getCommonConnection() instanceof io.papermc.paper.connection.PlayerGameConnection conn)) return;
        Player player = conn.getPlayer();
        if (player == null) return;

        if (Kits.KEY_MARINE.asString().equals(id)) {
            Kits.give(player, "marine", match.teamOf(player.getUniqueId()));
            return;
        }
        if (Kits.KEY_REAPER.asString().equals(id)) {
            Kits.give(player, "reaper", match.teamOf(player.getUniqueId()));
            return;
        }
        if (Kits.KEY_BEAR.asString().equals(id)) {
            Kits.give(player, "bear", match.teamOf(player.getUniqueId()));
            return;
        }
        if (Kits.KEY_SCV.asString().equals(id)) {
            Kits.give(player, "scv", match.teamOf(player.getUniqueId()));
            return;
        }

        for (Turrets.Type type : Turrets.Type.values()) {
            if (!type.key().asString().equals(id)) continue;
            String error = Turrets.build(player, type, match);
            if (error != null) {
                player.sendMessage(ChatColor.RED + error);
            } else {
                player.sendMessage(ChatColor.GREEN + type.label + " 건설 완료 — " + type.description);
            }
            return;
        }

        for (Upgrades.Kind kind : Upgrades.Kind.values()) {
            if (!kind.key().asString().equals(id)) continue;
            net.minecraft.world.entity.Entity vehicle = ((CraftPlayer) player).getHandle().getVehicle();
            if (!(vehicle instanceof Raft.Pilot ship)) {
                player.sendMessage(ChatColor.RED + "조종석에 탑승한 상태에서만 업그레이드할 수 있습니다.");
                return;
            }
            String error = Upgrades.buy(ship, kind, match);
            if (error != null) {
                player.sendMessage(ChatColor.RED + error);
            } else {
                player.sendMessage(ChatColor.GREEN + kind.label + " 완료 — " + kind.description);
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_USE, 0.8F, 1.4F);
                showShipUi(player, ship);
            }
            return;
        }
    }

    /** F(양손 교체) - 조종사에게 선박 UI */
    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        net.minecraft.world.entity.Entity vehicle = ((CraftPlayer) player).getHandle().getVehicle();
        if (!(vehicle instanceof Raft.Pilot ship)) return;
        event.setCancelled(true);
        showShipUi(player, ship);
    }

    private void showShipUi(Player player, Raft.Pilot ship) {
        int[] res = ship.teamSlot >= 0 ? match.resourcesOf(ship.teamSlot) : new int[]{0, 0};
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text("선체  " + (int) Math.ceil(ship.health)
                        + " / " + (int) ship.maxHealth, NamedTextColor.RED)),
                DialogBody.plainMessage(Component.text("크기  " + ship.gridSize + "x" + ship.gridSize
                        + "  ·  속도  x" + String.format("%.1f", ship.speedMul), NamedTextColor.WHITE)),
                DialogBody.plainMessage(Component.text("자원  철 " + res[0] + " · 금 " + res[1],
                        NamedTextColor.AQUA)),
                DialogBody.plainMessage(Component.empty()),
                DialogBody.plainMessage(Component.text("업그레이드", NamedTextColor.GOLD)));

        DialogBase base = DialogBase.builder(Component.text("선박")).body(body)
                .canCloseWithEscape(true).build();
        player.showDialog(Dialog.create(factory -> factory.empty().base(base)
                .type(DialogType.multiAction(Upgrades.buttons(ship, match), null, 1))));
    }

    /** 공허 낙하 - 자기 배로 구조된다(체력 절반 소모). [긴급 인양] 을 사면 소모가 없어진다. */
    private void checkVoid() {
        org.bukkit.World world = SpaceMap.world();
        if (world == null) return;

        for (Player player : world.getPlayers()) {
            if (player.getY() > SpaceMap.VOID_Y) continue;
            if (player.getGameMode() == GameMode.SPECTATOR) continue;

            if (match.isPlaying(player)) {
                // 구조는 기본 기능이다. 다만 대가로 현재 체력의 절반을 잃는다.
                // [긴급 인양] 업그레이드가 설치돼 있으면 그 대가가 없어진다.
                Raft.Pilot ship = match.shipOf(match.teamOf(player.getUniqueId()));
                if (ship != null && !ship.isRemoved() && !ship.sinking) {
                    net.minecraft.world.phys.Vec3 a = ship.anchor();
                    player.setFallDistance(0.0F);
                    player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                    player.teleport(new Location(world, a.x, a.y + Raft.DECK_TOP + 0.2, a.z));
                    player.setFallDistance(0.0F);

                    if (ship.salvage) {
                        player.sendActionBar(Component.text("긴급 인양", NamedTextColor.AQUA));
                    } else {
                        player.setHealth(Math.max(1.0, player.getHealth() / 2.0));
                        player.sendActionBar(Component.text("구조 — 체력 절반 소모", NamedTextColor.GOLD));
                    }
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.7F, 1.6F);
                } else {
                    player.setHealth(0.0);   // 돌아갈 배가 없으면 그대로 낙사
                }
            } else {
                Location spawn = SpaceMap.stationSpawn();
                if (spawn != null) {
                    player.setFallDistance(0.0F);
                    player.teleport(spawn);
                }
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        // 지급 장비는 떨구지 않는다
        event.getDrops().clear();
        event.setDroppedExp(0);

        Player player = event.getEntity();
        boolean inMatch = match.isPlaying(player);
        match.onDeath(player);

        // 사망 화면에서 대기시키지 않고 바로 관전으로 넘긴다
        if (inMatch) {
            getServer().getScheduler().runTask(this, () -> {
                if (player.isOnline() && player.isDead()) player.spigot().respawn();
            });
        }
    }

    /** 사망·탈락자는 관전 모드로 전환하고, 전장이 보이는 곳에 놓는다 */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!match.isRunning()) return;
        Player player = event.getPlayer();
        if (match.isPlaying(player)) return;      // 아직 생존자면 건드리지 않는다

        Location watch = match.arenaCenter();
        if (watch == null) watch = SpaceMap.stationSpawn();
        if (watch != null) event.setRespawnLocation(watch);

        getServer().getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            player.setGameMode(GameMode.SPECTATOR);
            player.sendMessage(ChatColor.GRAY + "관전 모드로 전환되었습니다.");
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        match.onDeath(event.getPlayer());
    }

    /* --------------------------------------------------------------- 명령어 */

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        switch (sub) {
            case "team": {
                int made = match.setupTeams(args.length > 1 ? parseInt(args[1], 2) : 2);
                StringBuilder sb = new StringBuilder(ChatColor.GOLD + "[선박전] " + ChatColor.RESET
                        + made + "개 팀 편성: ");
                for (int i = 0; i < made; i++) sb.append(match.teamLabel(i)).append(' ');
                getServer().broadcastMessage(sb.toString());
                return true;
            }
            case "start": {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("게임 안에서 실행하세요.");
                    return true;
                }
                String error = match.start(player.getLocation(),
                        args.length > 1 ? parseInt(args[1], DEFAULT_GRID) : DEFAULT_GRID);
                if (error != null) sender.sendMessage(ChatColor.RED + error);
                return true;
            }
            case "stop":
                music.stop(getServer().getOnlinePlayers());
                match.stop();
                sender.sendMessage("경기를 종료하고 선박을 즉시 제거했습니다.");
                return true;
            case "reset":
                match.stop();
                match.clearTeams();
                sender.sendMessage("팀과 선박을 초기화했습니다.");
                return true;
            case "map": {
                org.bukkit.World world = SpaceMap.ensure();
                if (world == null) {
                    sender.sendMessage(ChatColor.RED + "우주 맵 생성에 실패했습니다.");
                    return true;
                }
                if (sender instanceof Player player) {
                    player.teleport(new Location(world, 0.5, SpaceMap.STATION_Y + 1, 0.5));
                    player.sendMessage(ChatColor.GRAY + "우주 정거장으로 이동했습니다. 여기서 /ship start 하세요.");
                } else {
                    sender.sendMessage("우주 맵 준비 완료: " + world.getName());
                }
                return true;
            }
            case "book": {
                if (sender instanceof Player player) {
                    player.getInventory().addItem(Guide.book());
                    sender.sendMessage("안내서를 지급했습니다.");
                } else {
                    for (Player p : getServer().getOnlinePlayers()) Guide.give(p);
                    sender.sendMessage("전원에게 안내서를 지급했습니다.");
                }
                return true;
            }
            case "music": {
                music.force(getServer().getOnlinePlayers());
                sender.sendMessage("배경음악 강제 재생 (경기와 무관). 콘솔 로그를 확인하세요.");
                return true;
            }
            case "pack": {
                reloadConfig();
                if (sender instanceof Player player) {
                    sendPack(player);
                    sender.sendMessage("리소스팩 전송: " + getConfig().getString("resourcepack.url", "(설정 없음)"));
                } else {
                    for (Player p : getServer().getOnlinePlayers()) sendPack(p);
                    sender.sendMessage("전원에게 리소스팩 재전송");
                }
                return true;
            }
            case "kit": {
                if (sender instanceof Player player) Kits.openDialog(player);
                return true;
            }
            case "remove":
                sender.sendMessage("선박 " + removeAll() + "척 제거했습니다.");
                return true;
            case "debug": {
                boolean on = false;
                for (Raft.Pilot ship : ships) {
                    ship.debug = !ship.debug;
                    on = ship.debug;
                }
                sender.sendMessage(ships.isEmpty() ? "선박이 없습니다." : "디버그 " + (on ? "켬" : "끔"));
                return true;
            }
            case "mode":
                Raft.VELOCITY_MODE = !Raft.VELOCITY_MODE;
                sender.sendMessage("수평 캐리: " + (Raft.VELOCITY_MODE ? "폭발 패킷(속도)" : "위치 패킷"));
                return true;
            case "lead":
                if (args.length > 1) Raft.LEAD = parseDouble(args[1], Raft.LEAD);
                sender.sendMessage("보간 상쇄(LEAD) = " + Raft.LEAD);
                return true;
            case "spawn":
            case "": {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("게임 안에서만 사용할 수 있습니다.");
                    return true;
                }
                int grid = DEFAULT_GRID;
                boolean mount = true;
                for (String arg : args) {
                    if (arg.equalsIgnoreCase("here")) mount = false;
                    else if (!arg.equalsIgnoreCase("spawn")) grid = parseInt(arg, grid);
                }
                Location loc = player.getLocation();
                ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
                Raft.Pilot ship = Raft.spawn(level, new Vec3(loc.getX(), loc.getY() + 1.0, loc.getZ()), grid);
                ships.add(ship);
                player.sendMessage("선박 생성 (" + Math.max(3, Math.min(6, grid)) + "x" + Math.max(3, Math.min(6, grid)) + ")");
                if (mount) ((CraftPlayer) player).getHandle().startRiding(ship, true);
                return true;
            }
            default:
                sender.sendMessage(ChatColor.GOLD + "/ship" + ChatColor.RESET
                        + " map | pack | book | music | team <수> | start | stop | reset | kit | spawn | remove | debug | mode | lead");
                return true;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
