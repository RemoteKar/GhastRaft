package ghastraft;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.enchantments.Enchantment;
import io.papermc.paper.block.BlockPredicate;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAdventurePredicate;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.set.RegistrySet;
import org.bukkit.block.BlockType;

import java.util.List;

/** 직업 선택 다이얼로그와 지급 장비 */
public final class Kits {

    public static final String NS = "ghastraft";
    public static final Key KEY_MARINE = Key.key(NS, "kit_marine");
    public static final Key KEY_REAPER = Key.key(NS, "kit_reaper");
    public static final Key KEY_BEAR = Key.key(NS, "kit_bear");
    public static final Key KEY_SCV = Key.key(NS, "kit_scv");
    private static final NamespacedKey WELDER_TAG = new NamespacedKey(NS, "welder");
    /** 지급 장비 표식. 이게 붙은 것만 버릴 수 없다(전리품은 자유롭게 던질 수 있다). */
    private static final NamespacedKey KIT_ITEM = new NamespacedKey(NS, "kit_item");
    private static final NamespacedKey KIT_TAG = new NamespacedKey(NS, "kit");

    /** 사신 폭죽 최대 보유량 / 재장전 간격(틱) */
    public static final int FIREWORK_MAX = 5;
    public static final int FIREWORK_RELOAD = 200;
    /** 해병 화살 유지량 */
    public static final int ARROW_KEEP = 16;
    /** 불곰 돌풍구 최대 보유량 / 재장전 간격(틱) */
    public static final int WIND_MAX = 2;
    public static final int WIND_RELOAD = 40;
    /** 돌풍구 직격 피해 · 탄속 배율. 장갑 목표(함선·불곰)에는 더 크게 들어간다. */
    public static final double WIND_DAMAGE = 6.0;
    public static final double WIND_DAMAGE_ARMORED = 10.0;
    public static final double WIND_SPEED = 2.0;
    /** 불곰 기본 크기 */
    public static final double BEAR_SCALE = 1.5;
    /** 전 플레이어 공통 중력 (바닐라 0.08). 우주답게 낮춘다. */
    public static final double GRAVITY = 0.05;

    public static void applyGravity(Player player) {
        org.bukkit.attribute.AttributeInstance gravity =
                player.getAttribute(org.bukkit.attribute.Attribute.GRAVITY);
        if (gravity != null) gravity.setBaseValue(GRAVITY);
    }
    /**
     * 불곰 네더라이트 방어구 수치 (투구/상의/하의/신발).
     * 기본값은 3/8/6/3 = 20 에 인성 12, 밀치기 저항 0.4 로 지나치게 단단하다.
     * 겉모습은 네더라이트로 두고 수치는 철갑옷과 동일하게 맞춘다(인성·밀치기 저항 없음).
     *
     * 체감 내구력 = 체력 / (1 - 경감률)
     *   해병 : 20 / (1 - 0.60) = 50
     *   불곰 : 24 / (1 - 0.60) = 60      -> 해병보다 20% 정도만 단단하다
     */
    private static final double[] BEAR_ARMOR = {2.0, 6.0, 5.0, 2.0};   // 합 15 (철갑옷과 동일)
    /** SCV 용접기 1회 수리량과 재사용 대기(틱) - 연타로 속도가 오르지 않게 묶어둔다 */
    public static final double REPAIR_PER_HIT = 5.0;
    public static final long REPAIR_COOLDOWN = 20L;
    /** 직업별 기본 체력 (하트 = 값/2) */
    public static final double SCV_HEALTH = 30.0;      // 갑옷 없음, 15칸
    public static final double BEAR_HEALTH = 24.0;     // 12칸
    public static final double MARINE_HEALTH = 20.0;   // 10칸
    public static final double REAPER_HEALTH = 16.0;   // 8칸

    private static final TrimMaterial[] TRIMS = {
            TrimMaterial.REDSTONE,    // 레드
            TrimMaterial.LAPIS,       // 블루
            TrimMaterial.EMERALD,     // 그린
            TrimMaterial.GOLD,        // 옐로
            TrimMaterial.AMETHYST,    // 퍼플
            TrimMaterial.DIAMOND,     // 아쿠아
            TrimMaterial.COPPER,      // 오렌지
            TrimMaterial.QUARTZ};     // 화이트

    private Kits() {
    }

    /* ------------------------------------------------------------ 선택 도구 */

    /** 지급 장비로 표시 */
    public static ItemStack mark(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.getPersistentDataContainer().set(KIT_ITEM,
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isKitItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(
                KIT_ITEM, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    public static ItemStack selector() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("직업 선택", NamedTextColor.GOLD));
        meta.lore(List.of(Component.text("우클릭하여 직업을 고릅니다", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(
                new NamespacedKey(NS, "selector"), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return mark(item);
    }

    public static boolean isSelector(ItemStack item) {
        if (item == null || item.getType() != Material.NETHER_STAR || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(
                new NamespacedKey(NS, "selector"), org.bukkit.persistence.PersistentDataType.BYTE);
    }

    public static void openDialog(Player player) {
        ActionButton marine = ActionButton.create(
                Component.text("해병", NamedTextColor.AQUA),
                Component.text("철갑옷 풀세트 · 쇠뇌 · 철검 · 체력 10칸"),
                140, DialogAction.customClick(KEY_MARINE, null));

        ActionButton reaper = ActionButton.create(
                Component.text("사신", NamedTextColor.LIGHT_PURPLE),
                Component.text("겉날개 · 검은 가죽 · 폭죽 · 철검 · 체력 8칸"),
                140, DialogAction.customClick(KEY_REAPER, null));

        DialogBase base = DialogBase.builder(Component.text("직업 선택"))
                .body(List.of(
                        DialogBody.plainMessage(Component.text("전장에 나설 직업을 고르세요.", NamedTextColor.GRAY)),
                        DialogBody.plainMessage(Component.empty()),
                        DialogBody.plainMessage(Component.text("해병 — 단단하고 원거리에 강합니다.", NamedTextColor.AQUA)),
                        DialogBody.plainMessage(Component.text("사신 — 기동성과 폭발 화력.", NamedTextColor.LIGHT_PURPLE)),
                        DialogBody.plainMessage(Component.text("불곰 — 거대하고 단단하며 돌풍으로 밀어냅니다.", NamedTextColor.GOLD)),
                        DialogBody.plainMessage(Component.text("SCV — 함선 수리와 고속 채굴 담당.", NamedTextColor.YELLOW))))
                .canCloseWithEscape(true)
                .build();

        ActionButton bear = ActionButton.create(
                Component.text("불곰", NamedTextColor.GOLD),
                Component.text("네더라이트 갑옷(방어 15) · 나무검 · 돌풍구 · 체력 12칸"),
                140, DialogAction.customClick(KEY_BEAR, null));

        ActionButton scv = ActionButton.create(
                Component.text("SCV", NamedTextColor.YELLOW),
                Component.text("플라즈마 용접기 · 네더라이트 곡괭이 · 갑옷 없음 · 체력 15칸"),
                140, DialogAction.customClick(KEY_SCV, null));

        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.multiAction(List.of(marine, reaper, bear, scv), null, 2))));
    }

    /* ---------------------------------------------------------------- 지급 */

    public static void give(Player player, String kit, int teamSlot) {
        player.getInventory().clear();
        player.getPersistentDataContainer().set(KIT_TAG,
                org.bukkit.persistence.PersistentDataType.STRING, kit);

        // 크기·체력은 직업마다 다르다
        org.bukkit.attribute.AttributeInstance scale =
                player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        if (scale != null) scale.setBaseValue("bear".equals(kit) ? BEAR_SCALE : 1.0);

        org.bukkit.attribute.AttributeInstance maxHealth =
                player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(switch (kit) {
                case "scv" -> SCV_HEALTH;
                case "bear" -> BEAR_HEALTH;
                case "reaper" -> REAPER_HEALTH;
                default -> MARINE_HEALTH;
            });
        }

        Material sword = switch (kit) {
            case "bear" -> Material.WOODEN_SWORD;
            case "scv" -> Material.STONE_SWORD;
            default -> Material.IRON_SWORD;
        };
        player.getInventory().setItem(0, unbreakable(new ItemStack(sword)));
        player.getInventory().setItem(8, pickaxe("scv".equals(kit)));
        player.getInventory().setItem(7, new ItemStack(Material.BREAD, 5));

        if ("scv".equals(kit)) {
            // 갑옷 없음 - 대신 기본 체력이 30
            player.getInventory().setItem(1, welder());
            player.sendMessage(Component.text(
                    "SCV 선택 — 용접기로 아군 함선을 때리면 수리됩니다. 갑옷이 없는 대신 체력 15칸.",
                    NamedTextColor.YELLOW));
        } else if ("bear".equals(kit)) {
            player.getInventory().setHelmet(nerfed(Material.NETHERITE_HELMET, teamSlot,
                    BEAR_ARMOR[0], org.bukkit.inventory.EquipmentSlotGroup.HEAD));
            player.getInventory().setChestplate(nerfed(Material.NETHERITE_CHESTPLATE, teamSlot,
                    BEAR_ARMOR[1], org.bukkit.inventory.EquipmentSlotGroup.CHEST));
            player.getInventory().setLeggings(nerfed(Material.NETHERITE_LEGGINGS, teamSlot,
                    BEAR_ARMOR[2], org.bukkit.inventory.EquipmentSlotGroup.LEGS));
            player.getInventory().setBoots(nerfed(Material.NETHERITE_BOOTS, teamSlot,
                    BEAR_ARMOR[3], org.bukkit.inventory.EquipmentSlotGroup.FEET));
            player.getInventory().setItem(1, new ItemStack(Material.WIND_CHARGE, WIND_MAX));
            player.sendMessage(Component.text("불곰 선택 — 돌풍구는 2초마다 1개씩 최대 2개까지 재장전됩니다.",
                    NamedTextColor.GOLD));
        } else if ("reaper".equals(kit)) {
            player.getInventory().setHelmet(trimmed(new ItemStack(Material.LEATHER_HELMET), teamSlot));
            player.getInventory().setChestplate(unbreakable(new ItemStack(Material.ELYTRA)));
            player.getInventory().setLeggings(trimmed(new ItemStack(Material.LEATHER_LEGGINGS), teamSlot));
            player.getInventory().setBoots(trimmed(new ItemStack(Material.LEATHER_BOOTS), teamSlot));
            player.getInventory().setItem(1, new ItemStack(Material.FIREWORK_ROCKET, FIREWORK_MAX));
            player.sendMessage(Component.text("사신 선택 — 폭죽은 10초마다 1개씩 최대 5개까지 재장전됩니다.",
                    NamedTextColor.LIGHT_PURPLE));
        } else {
            player.getInventory().setHelmet(trimmed(new ItemStack(Material.IRON_HELMET), teamSlot));
            player.getInventory().setChestplate(trimmed(new ItemStack(Material.IRON_CHESTPLATE), teamSlot));
            player.getInventory().setLeggings(trimmed(new ItemStack(Material.IRON_LEGGINGS), teamSlot));
            player.getInventory().setBoots(trimmed(new ItemStack(Material.IRON_BOOTS), teamSlot));

            ItemStack crossbow = unbreakable(new ItemStack(Material.CROSSBOW));
            ItemMeta meta = crossbow.getItemMeta();
            meta.addEnchant(Enchantment.INFINITY, 1, true);
            meta.addEnchant(Enchantment.QUICK_CHARGE, 2, true);
            crossbow.setItemMeta(meta);
            player.getInventory().setItem(1, crossbow);
            player.getInventory().setItem(2, new ItemStack(Material.ARROW, ARROW_KEEP));
            player.sendMessage(Component.text("해병 선택 — 화살은 자동으로 보충됩니다.", NamedTextColor.AQUA));
        }
        Guide.give(player);   // 인벤토리를 비우면서 같이 지워지므로 다시 넣어준다

        // 지급된 것 전부에 표식을 남긴다(전리품과 구분하기 위해)
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) inv.setItem(i, mark(item));
        }

        applyGravity(player);
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        player.updateInventory();
    }

    /**
     * 철 곡괭이 - 철/금 광석만 캘 수 있다.
     * can_break 데이터 컴포넌트를 쓰면 어드벤처 모드에서도 지정한 블록만 정상적으로 캐진다.
     */
    /** 플라즈마 용접기 - 아군 함선을 때리면 수리된다 */
    public static ItemStack welder() {
        ItemStack item = unbreakable(new ItemStack(Material.BREEZE_ROD));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("플라즈마 용접기", NamedTextColor.AQUA));
        meta.lore(List.of(
                Component.text("아군 함선을 때리면 선체를 수리합니다", NamedTextColor.GRAY),
                Component.text("1회당 " + (int) REPAIR_PER_HIT + " 수리 · "
                        + (REPAIR_COOLDOWN / 20.0) + "초 간격", NamedTextColor.DARK_GRAY)));
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.getPersistentDataContainer().set(WELDER_TAG,
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** 이 플레이어가 고른 직업 (없으면 null) */
    public static String kitOf(Player player) {
        return player.getPersistentDataContainer().get(KIT_TAG,
                org.bukkit.persistence.PersistentDataType.STRING);
    }

    public static boolean isWelder(ItemStack item) {
        if (item == null || item.getType() != Material.BREEZE_ROD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(
                WELDER_TAG, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    public static ItemStack pickaxe(boolean netherite) {
        ItemStack item = unbreakable(new ItemStack(
                netherite ? Material.NETHERITE_PICKAXE : Material.IRON_PICKAXE));
        BlockPredicate ores = BlockPredicate.predicate()
                .blocks(RegistrySet.keySetFromValues(RegistryKey.BLOCK, List.of(
                        BlockType.IRON_ORE, BlockType.DEEPSLATE_IRON_ORE,
                        BlockType.GOLD_ORE, BlockType.DEEPSLATE_GOLD_ORE,
                        BlockType.NETHER_GOLD_ORE, BlockType.RAW_IRON_BLOCK, BlockType.RAW_GOLD_BLOCK,
                        // 돌·심층암도 캘 수는 있다. 다만 아이템은 안 나온다(GhastRaftPlugin#onBlockBreak).
                        BlockType.STONE, BlockType.COBBLESTONE,
                        BlockType.DEEPSLATE, BlockType.COBBLED_DEEPSLATE, BlockType.TUFF)))
                .build();
        item.setData(DataComponentTypes.CAN_BREAK, ItemAdventurePredicate.itemAdventurePredicate(List.of(ores)));
        ItemMeta meta = item.getItemMeta();
        meta.lore(List.of(
                Component.text("소행성을 파낼 수 있습니다", NamedTextColor.GRAY),
                Component.text("철·금 광석만 자원으로 회수됩니다", NamedTextColor.DARK_GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    /** 직업별 소모품 보충. 플러그인이 10틱마다 호출한다. */
    public static void resupply(Player player, long tick, boolean aboardOwnShip) {
        String kit = player.getPersistentDataContainer().get(KIT_TAG,
                org.bukkit.persistence.PersistentDataType.STRING);
        if (kit == null) return;

        switch (kit) {
            case "bear" -> {
                if (tick % WIND_RELOAD != 0) return;
                if (count(player, Material.WIND_CHARGE) < WIND_MAX) {
                    player.getInventory().addItem(new ItemStack(Material.WIND_CHARGE, 1));
                }
            }
            case "reaper" -> {
                if (tick % FIREWORK_RELOAD != 0) return;
                // 자기 함선 갑판 위에서만 재장전된다. 나가서 쓰고 돌아와야 한다.
                if (!aboardOwnShip) {
                    player.sendActionBar(Component.text("함선에서만 폭죽을 보급받습니다", NamedTextColor.GRAY));
                    return;
                }
                if (count(player, Material.FIREWORK_ROCKET) < FIREWORK_MAX) {
                    player.getInventory().addItem(new ItemStack(Material.FIREWORK_ROCKET, 1));
                }
            }
            default -> {
                if (tick % FIREWORK_RELOAD != 0) return;
                int have = count(player, Material.ARROW);
                if (have < ARROW_KEEP) player.getInventory().addItem(new ItemStack(Material.ARROW, ARROW_KEEP - have));
            }
        }
    }

    private static int count(Player player, Material type) {
        int n = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == type) n += item.getAmount();
        }
        return n;
    }

    private static ItemStack unbreakable(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.setUnbreakable(true);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 방어 수치를 직접 지정한 방어구.
     * 속성 수정자를 하나라도 명시하면 아이템의 기본 수정자가 통째로 대체된다.
     * 즉 인성과 밀치기 저항도 함께 0 이 된다.
     */
    private static ItemStack nerfed(Material type, int teamSlot, double armor,
                                    org.bukkit.inventory.EquipmentSlotGroup slot) {
        ItemStack item = trimmed(new ItemStack(type), teamSlot);
        ItemMeta meta = item.getItemMeta();
        // 수정자 키는 부위마다 달라야 한다. 네 부위가 같은 키를 쓰면 속성 시스템이
        // 같은 수정자로 보고 하나만 적용한다(방어력이 2~4 로 보이던 원인).
        meta.addAttributeModifier(org.bukkit.attribute.Attribute.ARMOR,
                new org.bukkit.attribute.AttributeModifier(
                        new NamespacedKey(NS, "bear_armor_" + slot.toString().toLowerCase()), armor,
                        org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, slot));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 팀 색 트림을 입힌 방어구.
     * 가죽 방어구는 검게 염색까지 해서 바탕을 눌러준다 - 그 위에 얹힌 팀 트림이 훨씬 잘 보인다.
     */
    private static ItemStack trimmed(ItemStack item, int teamSlot) {
        ItemMeta meta = item.getItemMeta();
        meta.setUnbreakable(true);
        if (meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta leather) {
            leather.setColor(org.bukkit.Color.fromRGB(0x141414));   // 검정
        }
        if (meta instanceof ArmorMeta armor && teamSlot >= 0) {
            armor.setTrim(new ArmorTrim(TRIMS[teamSlot % TRIMS.length], TrimPattern.SENTRY));
        }
        item.setItemMeta(meta);
        return item;
    }
}
