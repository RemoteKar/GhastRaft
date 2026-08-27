package ghastraft;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 완전한 공허 우주 맵 - 스폰 정거장 · 광물 소행성 · 소형 정거장 */
public final class SpaceMap {

    public static final String WORLD_NAME = "space";
    /** 스폰 정거장 높이 */
    public static final int STATION_Y = 100;
    /** 이 아래로 떨어지면 공허로 간주 */
    public static final int VOID_Y = 20;

    /** 경기 때 깔았던 블록 - 종료 시 되돌린다 */
    private static final List<Location> PLACED = new ArrayList<>();
    private static final Random RNG = new Random();

    private SpaceMap() {
    }

    public static World world() {
        return Bukkit.getWorld(WORLD_NAME);
    }

    /**
     * 공허 월드를 준비한다.
     * server.properties 에서 level-name=space · level-type=flat · 빈 레이어로 잡아두면
     * 서버가 띄운 메인 월드가 곧 이 월드다. 그 경우엔 설정만 덧입힌다.
     */
    public static World ensure() {
        World world = Bukkit.getWorld(WORLD_NAME);
        if (world == null) {
            world = new WorldCreator(WORLD_NAME)
                    .type(WorldType.FLAT)
                    .generateStructures(false)
                    .generatorSettings("{\"layers\":[],\"biome\":\"minecraft:plains\"}")
                    .createWorld();
            if (world == null) return null;
        }
        applySettings(world);
        if (world.getBlockAt(0, STATION_Y, 0).getType() == Material.AIR) buildSpawnStation(world);
        world.setSpawnLocation(0, STATION_Y + 1, 0);
        return world;
    }

    /** 밤 고정 · 날씨 없음 · 몹 없음 */
    private static void applySettings(World world) {
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setTime(18000L);            // 자정
        world.setStorm(false);
        world.setThundering(false);
    }

    public static Location stationSpawn() {
        World world = world();
        return world == null ? null : new Location(world, 0.5, STATION_Y + 1, 0.5);
    }

    /** 낙사 방지용 스폰 정거장 */
    public static void buildSpawnStation(World world) {
        int r = 8;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > r * r) continue;
                boolean edge = x * x + z * z > (r - 1) * (r - 1);
                world.getBlockAt(x, STATION_Y, z).setType(edge ? Material.POLISHED_DEEPSLATE : Material.SMOOTH_STONE);
                if (edge) {
                    world.getBlockAt(x, STATION_Y + 1, z).setType(Material.DEEPSLATE_TILE_WALL);   // 낙사 방지 난간
                }
            }
        }
        // 조명 · 표식
        for (int[] spot : new int[][]{{5, 0}, {-5, 0}, {0, 5}, {0, -5}}) {
            world.getBlockAt(spot[0], STATION_Y, spot[1]).setType(Material.SEA_LANTERN);
        }
        world.getBlockAt(0, STATION_Y, 0).setType(Material.BEACON);
    }

    /* -------------------------------------------------------------- 산포물 */

    /** 경기 시작 시 소행성과 소형 정거장을 뿌린다 */
    public static void generate(Location center, int asteroids, int stations, double radius) {
        generate(center, asteroids, stations, radius, List.of(), 0.0);
    }

    /** avoid 좌표 주변 avoidRadius 안에는 아무것도 놓지 않는다(선박 소환 지점 확보) */
    public static void generate(Location center, int asteroids, int stations, double radius,
                                List<double[]> avoid, double avoidRadius) {
        World world = center.getWorld();
        for (int i = 0; i < asteroids; i++) {
            double angle = RNG.nextDouble() * Math.PI * 2.0;
            double dist = radius * (0.12 + RNG.nextDouble() * 0.88);   // 중앙 부근부터 외곽까지
            int x = (int) (center.getX() + Math.cos(angle) * dist);
            int z = (int) (center.getZ() + Math.sin(angle) * dist);
            int y = (int) center.getY() + RNG.nextInt(71) - 35;         // 상하 폭도 넓게
            if (tooClose(avoid, x, z, avoidRadius)) continue;
            asteroid(world, x, y, z, 2 + RNG.nextInt(4));
        }
        for (int i = 0; i < stations + 1; i++) {          // 통로는 정거장보다 하나 더
            double angle = RNG.nextDouble() * Math.PI * 2.0;
            double dist = radius * (0.2 + RNG.nextDouble() * 0.8);
            int x = (int) (center.getX() + Math.cos(angle) * dist);
            int z = (int) (center.getZ() + Math.sin(angle) * dist);
            int y = (int) center.getY() + RNG.nextInt(61) - 30;
            if (tooClose(avoid, x, z, avoidRadius)) continue;
            catwalk(world, x, y, z);
        }
        for (int i = 0; i < stations; i++) {
            double angle = RNG.nextDouble() * Math.PI * 2.0;
            double dist = radius * (0.25 + RNG.nextDouble() * 0.75);
            int x = (int) (center.getX() + Math.cos(angle) * dist);
            int z = (int) (center.getZ() + Math.sin(angle) * dist);
            int y = (int) center.getY() + RNG.nextInt(45) - 22;
            if (tooClose(avoid, x, z, avoidRadius)) continue;
            station(world, x, y, z);
        }
    }

    /** 구조물 사이를 잇는 직선 통로 - 심층암 타일 바닥에 철창 난간, 사슬 지지대와 영혼 랜턴 */
    private static void catwalk(World world, int cx, int cy, int cz) {
        boolean alongX = RNG.nextBoolean();
        int length = 24 + RNG.nextInt(24);
        boolean deep = RNG.nextBoolean();

        for (int i = 0; i < length; i++) {
            int x = alongX ? cx + i : cx;
            int z = alongX ? cz : cz + i;

            // 3칸 폭 바닥. 가운데는 철판, 양옆은 심층암 타일
            for (int w = -1; w <= 1; w++) {
                int fx = alongX ? x : x + w;
                int fz = alongX ? z + w : z;
                set(world, fx, cy, fz, w == 0
                        ? (i % 4 == 0 ? Material.IRON_BLOCK : Material.DEEPSLATE_TILES)
                        : Material.POLISHED_DEEPSLATE);
            }

            // 양옆 철창 난간 (한 칸 걸러 - 완전히 막지 않아 뛰어내릴 수 있다)
            if (i % 2 == 0) {
                for (int w = -1; w <= 1; w += 2) {
                    int fx = alongX ? x : x + w;
                    int fz = alongX ? z + w : z;
                    set(world, fx, cy + 1, fz, Material.IRON_BARS);
                }
            }

            // 6칸마다 위로 뻗은 사슬 지지대와 영혼 랜턴
            if (i % 6 == 0) {
                for (int y = 1; y <= 3; y++) set(world, x, cy + y + 1, z, Material.CHAIN);
                set(world, x, cy + 5, z, Material.SOUL_LANTERN);
            }

            // 통로에 들러붙은 광맥
            if (RNG.nextDouble() < 0.18) {
                int side = RNG.nextBoolean() ? 2 : -2;
                int ox = alongX ? x : x + side;
                int oz = alongX ? z + side : z;
                for (int k = 0; k < 3 + RNG.nextInt(3); k++) {
                    boolean iron = RNG.nextDouble() < 0.7;
                    set(world, ox + RNG.nextInt(3) - 1, cy + RNG.nextInt(3) - 1, oz + RNG.nextInt(3) - 1,
                            iron ? (deep ? Material.DEEPSLATE_IRON_ORE : Material.IRON_ORE)
                                 : (deep ? Material.DEEPSLATE_GOLD_ORE : Material.GOLD_ORE));
                }
            }
        }

        // 통로 끝 보급 상자
        if (RNG.nextDouble() < 0.7) {
            int ex = alongX ? cx + length - 1 : cx;
            int ez = alongX ? cz : cz + length - 1;
            Block block = world.getBlockAt(ex, cy + 1, ez);
            if (block.getType() == Material.AIR) placeChest(block);
        }
    }

    private static boolean tooClose(List<double[]> avoid, double x, double z, double radius) {
        for (double[] spot : avoid) {
            if (Math.hypot(spot[0] - x, spot[1] - z) < radius) return true;
        }
        return false;
    }

    /** 돌덩이에 철/금 광석이 박힌 소행성 */
    private static void asteroid(World world, int cx, int cy, int cz, int radius) {
        boolean deep = RNG.nextBoolean();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    double d = Math.sqrt(x * x + y * y + z * z);
                    if (d > radius + RNG.nextDouble() * 0.6 - 0.3) continue;

                    Material type;
                    double roll = RNG.nextDouble();
                    if (roll < 0.18) type = deep ? Material.DEEPSLATE_IRON_ORE : Material.IRON_ORE;
                    else if (roll < 0.26) type = deep ? Material.DEEPSLATE_GOLD_ORE : Material.GOLD_ORE;
                    else type = deep ? Material.DEEPSLATE : Material.STONE;

                    set(world, cx + x, cy + y, cz + z, type);
                }
            }
        }
    }

    /** 소형 정거장 - 널찍한 15x15 갑판에 모서리 탑과 조명 */
    private static void station(World world, int cx, int cy, int cz) {
        int r = 7;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > r * r) continue;                       // 원형 갑판
                boolean edge = x * x + z * z > (r - 1) * (r - 1);
                Material floor = edge ? Material.POLISHED_DEEPSLATE
                        : ((x + z) % 2 == 0 ? Material.SMOOTH_STONE : Material.POLISHED_ANDESITE);
                set(world, cx + x, cy, cz + z, floor);
                if (edge && (x + z) % 3 == 0) {
                    set(world, cx + x, cy + 1, cz + z, Material.DEEPSLATE_TILE_WALL);   // 띄엄띄엄 난간
                }
            }
        }

        // 모서리 탑
        for (int[] corner : new int[][]{{-5, -5}, {-5, 5}, {5, -5}, {5, 5}}) {
            for (int y = 1; y <= 4; y++) set(world, cx + corner[0], cy + y, cz + corner[1], Material.IRON_BARS);
            set(world, cx + corner[0], cy + 5, cz + corner[1], Material.SEA_LANTERN);
        }
        // 중앙 표식과 조명
        set(world, cx, cy + 1, cz, Material.LIGHTNING_ROD);
        for (int[] spot : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) {
            set(world, cx + spot[0], cy, cz + spot[1], Material.SEA_LANTERN);
        }

        // 보급 상자 - 정거장이 넓어진 만큼 최대 2개
        int chests = RNG.nextDouble() < 0.6 ? (RNG.nextDouble() < 0.35 ? 2 : 1) : 0;
        int[][] spots = {{2, 2}, {-2, -2}, {2, -2}, {-2, 2}};
        for (int i = 0; i < chests; i++) {
            int[] spot = spots[RNG.nextInt(spots.length)];
            Block block = world.getBlockAt(cx + spot[0], cy + 1, cz + spot[1]);
            if (block.getType() != Material.AIR) continue;
            placeChest(block);
        }
    }

    /**
     * 상자 배치. getState() 는 스냅샷 사본이라 거기 아이템을 넣고 update() 해도
     * 컨테이너 내용이 반영되지 않는다. getState(false) 로 실제 상태를 받아 직접 채운다.
     */
    private static void placeChest(Block block) {
        block.setType(Material.CHEST, false);
        PLACED.add(block.getLocation());
        if (block.getState(false) instanceof Chest chest) fillLoot(chest);
    }

    /** 보급 상자 내용물 */
    private static void fillLoot(Chest chest) {
        List<ItemStack> pool = new ArrayList<>();
        pool.add(new ItemStack(Material.BREAD, 4 + RNG.nextInt(5)));
        if (RNG.nextDouble() < 0.45) pool.add(new ItemStack(Material.DIAMOND_SWORD));
        if (RNG.nextDouble() < 0.12) pool.add(new ItemStack(Material.NETHERITE_SWORD));
        if (RNG.nextDouble() < 0.30) pool.add(new ItemStack(Material.GOLDEN_APPLE, 1 + RNG.nextInt(2)));
        // 적함 침투 수단. 위에서 아래로 던져야 갑판에 착지한다.
        if (RNG.nextDouble() < 0.50) pool.add(new ItemStack(Material.ENDER_PEARL, 1 + RNG.nextInt(2)));
        if (RNG.nextDouble() < 0.55) pool.add(book());
        if (RNG.nextDouble() < 0.25) pool.add(book());

        for (ItemStack item : pool) {
            int slot = RNG.nextInt(chest.getInventory().getSize());
            if (chest.getInventory().getItem(slot) == null) chest.getInventory().setItem(slot, item);
            else chest.getInventory().addItem(item);
        }
    }

    private static final Enchantment[] BOOK_POOL = {
            Enchantment.SHARPNESS, Enchantment.SHARPNESS, Enchantment.PROTECTION, Enchantment.PROTECTION,
            Enchantment.KNOCKBACK, Enchantment.FIRE_ASPECT, Enchantment.POWER, Enchantment.PUNCH,
            Enchantment.FEATHER_FALLING, Enchantment.QUICK_CHARGE, Enchantment.PIERCING, Enchantment.MULTISHOT};

    private static ItemStack book() {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
        Enchantment ench = BOOK_POOL[RNG.nextInt(BOOK_POOL.length)];
        int level = 1 + RNG.nextInt(Math.max(1, Math.min(3, ench.getMaxLevel())));
        meta.addStoredEnchant(ench, level, true);
        item.setItemMeta(meta);
        return item;
    }

    private static void set(World world, int x, int y, int z, Material type) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != Material.AIR) return;      // 이미 뭔가 있으면 건드리지 않는다
        block.setType(type, false);
        PLACED.add(block.getLocation());
    }

    /** 경기 종료 시 깔았던 것 정리 */
    public static void clear() {
        for (Location loc : PLACED) {
            Block block = loc.getBlock();
            if (block.getType() != Material.AIR) block.setType(Material.AIR, false);
        }
        PLACED.clear();
    }

    public static int placedCount() {
        return PLACED.size();
    }
}
