package ghastraft;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

/** 선박 업그레이드 정의와 구매 처리 */
public final class Upgrades {

    public enum Kind {
        SIZE("크기 확장", 3, "선박이 한 칸 커지고 최대 체력도 오릅니다"),
        SPEED("속도 증가", 3, "함선이 더 빨라집니다"),
        HULL("내구도 증가", 3, "함선 최대 체력이 늘어납니다 (현재 체력은 그대로)"),
        STABILIZER("안정화", 1, "함선 조작이 더 안정적이게 됩니다"),
        REGENERATOR("재생로", 1, "승선한 아군에게 재생 효과를 줍니다"),
        SALVAGE("긴급 인양", 1, "공허 구조 시 체력을 잃지 않습니다");

        public final String label;
        public final int maxLevel;
        public final String description;

        Kind(String label, int maxLevel, String description) {
            this.label = label;
            this.maxLevel = maxLevel;
            this.description = description;
        }

        public Key key() {
            return Key.key(Kits.NS, "upgrade_" + name().toLowerCase());
        }
    }

    /** [철, 금] 비용. 단계가 오를수록 두 배씩. */
    private static int[] cost(Kind kind, int nextLevel) {
        return switch (kind) {
            case SIZE -> new int[]{20 * nextLevel, 8 * nextLevel};                // 20/8 · 40/16 · 60/24
            case SPEED -> new int[]{12 * nextLevel, 6 * nextLevel};              // 12/6 · 24/12 · 36/18
            case HULL -> new int[]{18 * nextLevel, 3 * nextLevel};               // 18/3 · 36/6 · 54/9
            case STABILIZER -> new int[]{18, 18};
            case REGENERATOR -> new int[]{12, 24};
            case SALVAGE -> new int[]{24, 24};
        };
    }

    public static int level(Raft.Pilot ship, Kind kind) {
        return switch (kind) {
            case SIZE -> ship.sizeLevel;
            case SPEED -> ship.speedLevel;
            case HULL -> ship.hpLevel;
            case STABILIZER -> ship.stabilized ? 1 : 0;
            case REGENERATOR -> ship.regenerator ? 1 : 0;
            case SALVAGE -> ship.salvage ? 1 : 0;
        };
    }

    private static final String[] ROMAN = {"", "", " II", " III"};

    /** 다이얼로그에 올릴 버튼 목록 */
    public static List<ActionButton> buttons(Raft.Pilot ship, Match match) {
        List<ActionButton> list = new ArrayList<>();
        int[] pool = ship.teamSlot >= 0 ? match.resourcesOf(ship.teamSlot) : new int[]{0, 0};

        for (Kind kind : Kind.values()) {
            int have = level(ship, kind);
            boolean maxed = have >= kind.maxLevel;
            int next = have + 1;
            int[] price = cost(kind, next);
            boolean affordable = !maxed && pool[0] >= price[0] && pool[1] >= price[1];

            String label = kind.label + (kind.maxLevel > 1 && next < ROMAN.length ? ROMAN[next] : "");
            Component name = maxed
                    ? Component.text(kind.label + " (최대)", NamedTextColor.DARK_GRAY)
                    : Component.text(label, affordable ? NamedTextColor.GREEN : NamedTextColor.GRAY);
            Component tip = maxed
                    ? Component.text(kind.description)
                    : Component.text(kind.description + " · 철 " + price[0] + " 금 " + price[1]);

            list.add(ActionButton.create(name, tip, 180,
                    maxed ? null : DialogAction.customClick(kind.key(), null)));
        }
        return list;
    }

    /** 구매 시도. 결과 메시지를 돌려준다. */
    public static String buy(Raft.Pilot ship, Kind kind, Match match) {
        if (ship.teamSlot < 0) return "팀 선박이 아닙니다.";
        int have = level(ship, kind);
        if (have >= kind.maxLevel) return "이미 최대 단계입니다.";

        int next = have + 1;
        int[] price = cost(kind, next);
        if (!match.spend(ship.teamSlot, price[0], price[1])) {
            int[] pool = match.resourcesOf(ship.teamSlot);
            return "자원이 부족합니다. (필요 철 " + price[0] + " 금 " + price[1]
                    + " · 보유 철 " + pool[0] + " 금 " + pool[1] + ")";
        }

        switch (kind) {
            case SIZE -> {
                ship.sizeLevel = next;
                ship.resize(ship.gridSize + 1);
                // 선체가 커진 만큼 최대 체력도 오른다. 현재 체력은 같은 비율을 유지한다
                // (수리도 아니고 손해도 아니다 - 늘어난 부분이 기존과 같은 상태로 붙는 셈).
                double ratio = ship.maxHealth <= 0.0 ? 1.0 : ship.health / ship.maxHealth;
                ship.maxHealth = Raft.maxHealthFor(ship.hpLevel, ship.sizeLevel);
                ship.health = ship.maxHealth * ratio;
            }
            case SPEED -> {
                ship.speedLevel = next;
                ship.speedMul = 1.0 + 0.3 * next;
            }
            case HULL -> {
                // 최대치만 올린다. 즉석 수리가 되면 안 되므로 현재 체력은 건드리지 않는다.
                ship.hpLevel = next;
                ship.maxHealth = Raft.maxHealthFor(ship.hpLevel, ship.sizeLevel);
            }
            case STABILIZER -> ship.stabilized = true;
            case REGENERATOR -> ship.regenerator = true;
            case SALVAGE -> ship.salvage = true;
        }
        return null;
    }

    /** 재생로 - 승선한 아군에게 짧은 재생. 파티클은 끈다. */
    public static void tickRegenerator(Raft.Pilot ship, Match match) {
        if (!ship.regenerator || ship.sinking) return;
        for (net.minecraft.server.level.ServerPlayer sp : ship.aboard()) {
            Player player = sp.getBukkitEntity();
            if (ship.teamSlot >= 0 && match.teamOf(player.getUniqueId()) != ship.teamSlot) continue;
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 45, 0, true, false, false));
        }
    }

    private Upgrades() {
    }
}
