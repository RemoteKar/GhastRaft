package ghastraft;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 팀 배정 · 선박 소환 · 점령 · 자원 · 승패 판정 */
public final class Match {

    private static final String PREFIX = "ship";
    private static final String OBJ = "shipstat";

    private static final ChatColor[] COLORS = {
            ChatColor.RED, ChatColor.BLUE, ChatColor.GREEN, ChatColor.YELLOW,
            ChatColor.LIGHT_PURPLE, ChatColor.AQUA, ChatColor.GOLD, ChatColor.WHITE};
    private static final DyeColor[] DYES = {
            DyeColor.RED, DyeColor.BLUE, DyeColor.LIME, DyeColor.YELLOW,
            DyeColor.MAGENTA, DyeColor.LIGHT_BLUE, DyeColor.ORANGE, DyeColor.WHITE};
    private static final net.kyori.adventure.text.format.NamedTextColor[] TEXT_COLORS = {
            net.kyori.adventure.text.format.NamedTextColor.RED,
            net.kyori.adventure.text.format.NamedTextColor.BLUE,
            net.kyori.adventure.text.format.NamedTextColor.GREEN,
            net.kyori.adventure.text.format.NamedTextColor.YELLOW,
            net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE,
            net.kyori.adventure.text.format.NamedTextColor.AQUA,
            net.kyori.adventure.text.format.NamedTextColor.GOLD,
            net.kyori.adventure.text.format.NamedTextColor.WHITE};
    private static final String[] NAMES = {
            "레드", "블루", "그린", "옐로", "퍼플", "아쿠아", "오렌지", "화이트"};

    public static final int MAX_TEAMS = COLORS.length;

    private final List<Team> teams = new ArrayList<>();
    private final Map<Integer, Raft.Pilot> ships = new HashMap<>();
    private final Map<Integer, BossBar> captureBars = new HashMap<>();
    private final Map<UUID, Integer> teamOf = new HashMap<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Set<Integer> defeated = new HashSet<>();
    private final Map<Integer, int[]> resources = new HashMap<>();   // [철, 금]
    private final Map<Integer, Scoreboard> boards = new HashMap<>();
    private final Map<Integer, List<String>> lastLines = new HashMap<>();
    private boolean running;
    private Location arenaCenter;
    private int hungerTick;

    public boolean isRunning() {
        return this.running;
    }

    public int teamOf(UUID uuid) {
        Integer slot = this.teamOf.get(uuid);
        return slot == null ? -1 : slot;
    }

    public Raft.Pilot shipOf(int slot) {
        return this.ships.get(slot);
    }

    public static net.kyori.adventure.text.format.NamedTextColor textColor(int slot) {
        return TEXT_COLORS[Math.floorMod(slot, TEXT_COLORS.length)];
    }

    public static String teamName(int slot) {
        return NAMES[Math.floorMod(slot, NAMES.length)];
    }

    /** 도중 입장 - 인원이 가장 적은 팀에 넣는다. 팀이 없으면 -1 */
    public int joinSmallestTeam(Player player) {
        if (this.teams.isEmpty()) return -1;
        int best = -1;
        int bestSize = Integer.MAX_VALUE;
        for (int i = 0; i < this.teams.size(); i++) {
            if (this.defeated.contains(i)) continue;          // 이미 탈락한 팀에는 넣지 않는다
            Raft.Pilot ship = this.ships.get(i);
            if (this.running && (ship == null || ship.isRemoved() || ship.sinking)) continue;
            int size = this.teams.get(i).getEntries().size();
            if (size < bestSize) {
                bestSize = size;
                best = i;
            }
        }
        if (best < 0) return -1;                              // 들어갈 팀이 없으면 관전
        this.teams.get(best).addEntry(player.getName());
        this.teamOf.put(player.getUniqueId(), best);

        if (this.running && !this.defeated.contains(best)) {
            this.alive.add(player.getUniqueId());
            player.setGameMode(GameMode.ADVENTURE);
            player.getInventory().clear();
            player.getInventory().addItem(Kits.selector());
            player.getInventory().addItem(Guide.book());
            Raft.Pilot ship = this.ships.get(best);
            if (ship != null && !ship.isRemoved()) {
                Vec3 a = ship.anchor();
                player.teleport(new Location(player.getWorld(), a.x, a.y + Raft.DECK_TOP + 0.3, a.z));
            }
            Kits.openDialog(player);
            setupScoreboard();          // 새 인원을 모든 보드에 반영
        }
        return best;
    }

    public String teamLabel(int slot) {
        return COLORS[slot] + NAMES[slot] + ChatColor.RESET;
    }

    /* ------------------------------------------------------------------ 팀 */

    public int setupTeams(int count) {
        clearTeams();
        int n = Math.max(2, Math.min(MAX_TEAMS, count));
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();

        for (int i = 0; i < n; i++) {
            Team old = board.getTeam(PREFIX + i);
            if (old != null) old.unregister();

            Team team = board.registerNewTeam(PREFIX + i);
            team.setColor(COLORS[i]);
            team.setDisplayName(COLORS[i] + NAMES[i]);
            team.setPrefix(COLORS[i].toString());
            team.setAllowFriendlyFire(false);
            team.setCanSeeFriendlyInvisibles(true);
            this.teams.add(team);
            this.resources.put(i, new int[]{0, 0});
        }

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        Collections.shuffle(players);
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            int slot = i % n;
            this.teams.get(slot).addEntry(player.getName());
            this.teamOf.put(player.getUniqueId(), slot);
        }
        return n;
    }

    public void clearTeams() {
        for (Team team : this.teams) {
            try {
                team.unregister();
            } catch (IllegalStateException ignored) {
            }
        }
        this.teams.clear();
        this.teamOf.clear();
        this.resources.clear();
    }

    /* ---------------------------------------------------------------- 경기 */

    public String start(Location center, int grid) {
        if (this.teams.isEmpty()) return "먼저 /ship team <팀 수> 로 팀을 나누세요.";
        stopShips(true);
        this.alive.clear();
        this.defeated.clear();

        ServerLevel level = ((CraftWorld) center.getWorld()).getHandle();
        int n = this.teams.size();
        // 전장 반경 - 배끼리 한참 떨어져 시작하도록 넉넉히 잡는다(2팀 기준 지름 170블록쯤)
        double radius = Math.max(105.0, 36.0 * n + Raft.FOOTPRINT * 2.5);
        double y = center.getY() + 12.0;

        // 선박 소환 지점을 먼저 계산해서 산포물이 그 자리를 피하게 한다
        List<double[]> berths = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double angle = 2.0 * Math.PI * i / n;
            berths.add(new double[]{center.getX() + Math.cos(angle) * radius,
                                    center.getZ() + Math.sin(angle) * radius});
        }

        SpaceMap.clear();
        SpaceMap.generate(new Location(center.getWorld(), center.getX(), y, center.getZ()),
                18 + n * 6, 3 + n, radius * 1.55, berths, Raft.footprintOf(grid) + 12.0);

        for (int i = 0; i < n; i++) {
            double angle = 2.0 * Math.PI * i / n;
            double x = center.getX() + Math.cos(angle) * radius;
            double z = center.getZ() + Math.sin(angle) * radius;

            Raft.clearBerth(level, new Vec3(x, y, z), grid);   // 혹시 남은 블록도 치운다
            Raft.Pilot ship = Raft.spawn(level, new Vec3(x, y, z), grid);
            ship.setColor(DYES[i]);
            ship.teamSlot = i;
            ship.health = Raft.MAX_HEALTH;
            ship.maxHealth = Raft.MAX_HEALTH;
            this.ships.put(i, ship);
            this.resources.put(i, new int[]{0, 0});

            for (String entry : this.teams.get(i).getEntries()) {
                Player player = Bukkit.getPlayerExact(entry);
                if (player == null) continue;
                player.setGameMode(GameMode.ADVENTURE);   // 곡괭이 can_break 로 철/금 광석만 채굴 가능
                player.getInventory().clear();
                player.teleport(new Location(center.getWorld(), x, y + Raft.DECK_TOP + 0.2, z,
                        (float) Math.toDegrees(-angle) - 90.0F, 0.0F));
                player.getInventory().addItem(Kits.selector());
                player.getInventory().addItem(Guide.book());
                Kits.openDialog(player);
                this.alive.add(player.getUniqueId());
            }
        }

        this.arenaCenter = new Location(center.getWorld(), center.getX(), y + 20.0, center.getZ());
        this.running = true;
        setupScoreboard();
        Bukkit.broadcastMessage(ChatColor.GOLD + "[선박전] " + ChatColor.RESET
                + n + "개 팀, 생존자 " + this.alive.size() + "명. 직업을 선택하세요.");
        return null;
    }

    /** 참가자 상태를 경기 전으로 되돌린다 */
    private void resetPlayers() {
        for (UUID id : new ArrayList<>(this.teamOf.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
            org.bukkit.attribute.AttributeInstance scale =
                    player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
            if (scale != null) scale.setBaseValue(1.0);
            org.bukkit.attribute.AttributeInstance maxHealth =
                    player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (maxHealth != null) maxHealth.setBaseValue(20.0);
            Kits.applyGravity(player);          // 우주 중력은 유지
            player.setHealth(20.0);
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
            player.setFireTicks(0);
            player.setGameMode(GameMode.ADVENTURE);
            Location spawn = SpaceMap.stationSpawn();
            if (spawn != null) player.teleport(spawn);
        }
    }

    /** 경기 종료 - 연출 없이 즉시 제거 */
    public void stop() {
        stopShips(true);
        for (int slot : new ArrayList<>(this.captureBars.keySet())) hideCaptureBar(slot);
        this.captureBars.clear();
        boolean wasRunning = this.running;
        this.running = false;
        this.alive.clear();
        this.defeated.clear();
        SpaceMap.clear();
        Turrets.clearMissiles();
        if (wasRunning) resetPlayers();

        clearBoards();
    }

    private void stopShips(boolean instant) {
        for (Raft.Pilot ship : this.ships.values()) {
            if (ship.isRemoved()) continue;
            if (instant) ship.discardAll();
            else ship.startSinking();
        }
        if (instant) this.ships.clear();
    }

    /* ---------------------------------------------------------------- 틱 */

    public void tick() {
        if (!this.running) return;
        tickCapture();
        for (Raft.Pilot ship : this.ships.values()) {
            if (!ship.isRemoved()) Upgrades.tickRegenerator(ship, this);
        }
        tickAboardHunger();
        updateScoreboard();
    }

    /** 자기 함선 위에 있으면 배고픔이 천천히 찬다 (tick 은 5틱마다 호출되므로 12번 = 3초) */
    private void tickAboardHunger() {
        if (++this.hungerTick % 12 != 0) return;
        for (Raft.Pilot ship : this.ships.values()) {
            if (ship.isRemoved() || ship.sinking) continue;
            for (ServerPlayer sp : ship.aboard()) {
                Player player = sp.getBukkitEntity();
                if (ship.teamSlot >= 0 && teamOf(player.getUniqueId()) != ship.teamSlot) continue;
                if (player.getFoodLevel() < 20) {
                    player.setFoodLevel(Math.min(20, player.getFoodLevel() + 1));
                    player.setSaturation(Math.min(20.0F, player.getSaturation() + 1.0F));
                }
            }
        }
    }

    private void tickCapture() {
        for (Map.Entry<Integer, Raft.Pilot> entry : this.ships.entrySet()) {
            int slot = entry.getKey();
            Raft.Pilot ship = entry.getValue();
            if (ship.isRemoved() || ship.sinking || this.defeated.contains(slot)) {
                ship.capture = 0.0;
                hideCaptureBar(slot);      // 점령 완료/격침 직후에도 바가 남지 않게
                continue;
            }

            boolean enemy = false;
            boolean defender = false;
            for (ServerPlayer sp : ship.onCockpit()) {
                Player player = sp.getBukkitEntity();
                if (!this.alive.contains(player.getUniqueId())) continue;
                if (teamOf(player.getUniqueId()) == slot) defender = true;
                else enemy = true;
            }

            double before = ship.capture;
            if (enemy && !defender) ship.capture += 1.0 / Raft.CAPTURE_TICKS;
            else ship.capture -= 2.0 / Raft.CAPTURE_TICKS;
            ship.capture = Math.max(0.0, Math.min(1.0, ship.capture));

            if (before <= 0.0 && ship.capture > 0.0) {
                broadcastTeam(slot, ChatColor.RED + "적이 조종석을 점령하고 있습니다!");
            }
            showCaptureBar(slot, ship);

            if (ship.capture >= 1.0) defeat(slot, "조종석을 빼앗겼습니다");
        }
    }

    /** 보스바를 감추고 목록에서도 뺀다 */
    private void hideCaptureBar(int slot) {
        BossBar bar = this.captureBars.remove(slot);
        if (bar == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
    }

    private void showCaptureBar(int slot, Raft.Pilot ship) {
        if (ship.capture <= 0.0) {
            hideCaptureBar(slot);
            return;
        }
        BossBar bar = this.captureBars.get(slot);
        if (bar == null) {
            bar = BossBar.bossBar(Component.empty(), 0.0F, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
            this.captureBars.put(slot, bar);
        }
        bar.name(Component.text(NAMES[slot] + " 조종석 점령 " + (int) (ship.capture * 100) + "%",
                NamedTextColor.RED));
        bar.progress((float) ship.capture);
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
    }

    /* ---------------------------------------------------------------- 자원 */

    /** 자기 팀 배에 우클릭 - 손에 든 철/금을 전부 넘긴다. 넘긴 개수를 반환. */
    public int deposit(Player player, Raft.Pilot ship) {
        int slot = teamOf(player.getUniqueId());
        if (slot < 0 || slot != ship.teamSlot) return -1;

        ItemStack hand = player.getInventory().getItemInMainHand();
        int index = resourceIndex(hand.getType());
        if (index < 0) return 0;

        int amount = hand.getAmount();
        player.getInventory().setItemInMainHand(null);
        int[] pool = this.resources.computeIfAbsent(slot, k -> new int[]{0, 0});
        pool[index] += amount;

        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0F, 1.4F);
        broadcastTeam(slot, ChatColor.GREEN + player.getName() + " 이(가) "
                + (index == 0 ? "철 " : "금 ") + amount + "개를 보급했습니다. (누적 철 "
                + pool[0] + " · 금 " + pool[1] + ")");
        return amount;
    }

    private static int resourceIndex(Material type) {
        return switch (type) {
            case IRON_INGOT, RAW_IRON, IRON_BLOCK, IRON_NUGGET -> 0;
            case GOLD_INGOT, RAW_GOLD, GOLD_BLOCK, GOLD_NUGGET -> 1;
            default -> -1;
        };
    }

    /** 격침시킨 팀이 상대 함선에 실려 있던 자원을 가져간다 */
    public void plunder(int victim, int killer) {
        if (killer < 0 || killer == victim) return;
        int[] loot = this.resources.getOrDefault(victim, new int[]{0, 0});
        if (loot[0] <= 0 && loot[1] <= 0) return;

        int[] pool = this.resources.computeIfAbsent(killer, k -> new int[]{0, 0});
        pool[0] += loot[0];
        pool[1] += loot[1];
        this.resources.put(victim, new int[]{0, 0});

        Bukkit.broadcastMessage(ChatColor.GOLD + "[선박전] " + ChatColor.RESET
                + teamLabel(killer) + " 팀이 " + teamLabel(victim) + " 함선의 적재 자원을 노획했습니다 — 철 "
                + loot[0] + " · 금 " + loot[1]);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (teamOf(player.getUniqueId()) == killer) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8F, 1.2F);
            }
        }
    }

    /** 자원 차감. 부족하면 false */
    public boolean spend(int slot, int iron, int gold) {
        int[] pool = this.resources.computeIfAbsent(slot, k -> new int[]{0, 0});
        if (pool[0] < iron || pool[1] < gold) return false;
        pool[0] -= iron;
        pool[1] -= gold;
        return true;
    }

    public int[] resourcesOf(int slot) {
        return this.resources.getOrDefault(slot, new int[]{0, 0});
    }

    /* ---------------------------------------------------------------- 판정 */

    public void onDeath(Player player) {
        if (!this.running) return;
        if (!this.alive.remove(player.getUniqueId())) return;

        int slot = teamOf(player.getUniqueId());
        if (slot >= 0 && !hasAlive(slot)) defeat(slot, "전원 사망");
        else checkWinner();
    }

    private boolean hasAlive(int slot) {
        for (UUID id : this.alive) {
            if (teamOf(id) == slot) return true;
        }
        return false;
    }

    /** 팀 패배 - 배는 즉시 사라지지 않고 천천히 격침된다 */
    public void defeat(int slot, String reason) {
        if (!this.defeated.add(slot)) return;
        hideCaptureBar(slot);
        Bukkit.broadcastMessage(ChatColor.GOLD + "[선박전] " + ChatColor.RESET
                + teamLabel(slot) + " 팀 탈락 — " + reason);

        Raft.Pilot ship = this.ships.get(slot);
        if (ship != null && !ship.isRemoved()) ship.startSinking();

        for (UUID id : new ArrayList<>(this.alive)) {
            if (teamOf(id) == slot) {
                this.alive.remove(id);
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    player.setGameMode(GameMode.SPECTATOR);
                    player.sendMessage(ChatColor.GRAY + "팀이 탈락하여 관전 모드로 전환되었습니다.");
                }
            }
        }
        checkWinner();
    }

    private void checkWinner() {
        Set<Integer> remaining = new HashSet<>();
        for (UUID id : this.alive) {
            int slot = teamOf(id);
            if (slot >= 0) remaining.add(slot);
        }
        if (remaining.size() == 1) {
            int winner = remaining.iterator().next();
            Bukkit.broadcastMessage(ChatColor.GOLD + "[선박전] " + ChatColor.RESET
                    + teamLabel(winner) + " 팀 승리!");
            stop();
        } else if (remaining.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "[선박전] " + ChatColor.RESET + "무승부.");
            stop();
        }
    }

    /** 관전자를 놓을 전장 상공 지점 */
    public Location arenaCenter() {
        return this.arenaCenter == null ? null : this.arenaCenter.clone();
    }

    /** 자기 팀 함선 갑판 위에 있는가 */
    public boolean isAboardOwnShip(Player player) {
        Raft.Pilot ship = this.ships.get(teamOf(player.getUniqueId()));
        if (ship == null || ship.isRemoved() || ship.sinking) return false;
        for (ServerPlayer sp : ship.aboard()) {
            if (sp.getUUID().equals(player.getUniqueId())) return true;
        }
        return false;
    }

    public boolean isPlaying(Player player) {
        return this.running && this.alive.contains(player.getUniqueId());
    }

    private void broadcastTeam(int slot, String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (teamOf(player.getUniqueId()) == slot) player.sendMessage(message);
        }
    }

    /* ------------------------------------------------------------ 스코어보드 */

    /**
     * 팀마다 별도 스코어보드를 만든다.
     * 사이드바는 스코어보드 단위라, 팀별로 다른 내용을 보여주려면 보드 자체를 나눠야 한다.
     * 대신 탭 목록 색이 유지되도록 팀 등록은 모든 보드에 복제한다.
     */
    private void setupScoreboard() {
        clearBoards();
        for (int i = 0; i < this.teams.size(); i++) {
            Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();

            for (int j = 0; j < this.teams.size(); j++) {
                Team src = this.teams.get(j);
                Team copy = board.registerNewTeam(PREFIX + j);
                copy.setColor(COLORS[j]);
                copy.setPrefix(COLORS[j].toString());
                copy.setAllowFriendlyFire(false);
                for (String entry : src.getEntries()) copy.addEntry(entry);
            }

            Objective obj = board.registerNewObjective(OBJ, org.bukkit.scoreboard.Criteria.DUMMY,
                    net.kyori.adventure.text.Component.empty());   // 제목 없음
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            this.boards.put(i, board);
        }
        applyBoards();
    }

    /** 각 플레이어에게 자기 팀 보드를 붙인다 */
    private void applyBoards() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Scoreboard board = this.boards.get(teamOf(player.getUniqueId()));
            if (board != null) player.setScoreboard(board);
        }
    }

    private void clearBoards() {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player player : Bukkit.getOnlinePlayers()) player.setScoreboard(main);
        this.boards.clear();
        this.lastLines.clear();
    }

    private void updateScoreboard() {
        for (Map.Entry<Integer, Scoreboard> entry : this.boards.entrySet()) {
            int slot = entry.getKey();
            Scoreboard board = entry.getValue();
            Objective obj = board.getObjective(OBJ);
            if (obj == null) continue;

            Raft.Pilot ship = this.ships.get(slot);
            List<String> lines = new ArrayList<>();
            lines.add(ChatColor.WHITE + "함선  " + ChatColor.GREEN
                    + (ship == null || ship.isRemoved() ? 0 : (int) Math.ceil(ship.health))
                    + ChatColor.GRAY + " / " + (ship == null ? 0 : (int) ship.maxHealth));
            lines.add(ChatColor.DARK_GRAY + "----------");

            for (String name : this.teams.get(slot).getEntries()) {
                Player member = Bukkit.getPlayerExact(name);
                if (member == null) continue;
                boolean out = !this.alive.contains(member.getUniqueId());
                int hp = (int) Math.ceil(member.getHealth());
                int max = (int) member.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                lines.add(out
                        ? ChatColor.DARK_GRAY + name + " 탈락"
                        : ChatColor.GRAY + name + "  " + hpColor(hp, max) + hp
                          + ChatColor.DARK_GRAY + "/" + max);
            }

            List<String> previous = this.lastLines.get(slot);
            if (lines.equals(previous)) continue;                 // 바뀐 게 없으면 건드리지 않는다
            if (previous != null) for (String line : previous) board.resetScores(line);
            for (int i = 0; i < lines.size(); i++) obj.getScore(lines.get(i)).setScore(lines.size() - i);
            this.lastLines.put(slot, lines);
        }
    }

    private static ChatColor hpColor(int hp, int max) {
        double ratio = max <= 0 ? 0 : (double) hp / max;
        return ratio > 0.6 ? ChatColor.GREEN : ratio > 0.3 ? ChatColor.YELLOW : ChatColor.RED;
    }
}
