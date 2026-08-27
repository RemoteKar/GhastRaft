package ghastraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** 규칙 안내서 */
public final class Guide {

    private static final NamespacedKey TAG = new NamespacedKey(Kits.NS, "guide");

    private Guide() {
    }

    private static Component head(String text) {
        return Component.text(text, NamedTextColor.DARK_BLUE).decorate(TextDecoration.BOLD);
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.BLACK);
    }

    private static Component dim(String text) {
        return Component.text(text, NamedTextColor.DARK_GRAY);
    }

    private static Component page(Component... parts) {
        Component page = Component.empty();
        for (Component part : parts) page = page.append(part).append(Component.newline());
        return page;
    }

    public static ItemStack book() {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        meta.setTitle("선박전 안내서");
        meta.setAuthor("관제탑");

        meta.addPages(
                page(head("선박전"),
                        Component.empty(),
                        line("팀마다 함선 한 척을 받아"),
                        line("공허 위에서 싸운다."),
                        Component.empty(),
                        head("승리 조건"),
                        line("· 적 함선 격침"),
                        line("· 적 조종석 8초 점령"),
                        line("· 적 팀 전원 사망"),
                        Component.empty(),
                        dim("마지막까지 남은 팀이 승리")),

                page(head("함선"),
                        Component.empty(),
                        line("중앙 조종석을 우클릭해"),
                        line("탑승한다. 아군만 가능."),
                        Component.empty(),
                        line("시선 방향 W 전진"),
                        line("Space 상승 · Shift 하차"),
                        Component.empty(),
                        line("갑판 위에서는 자유롭게"),
                        line("걷고 뛸 수 있다."),
                        Component.empty(),
                        dim("F - 함선 상태와 업그레이드")),

                page(head("자원"),
                        Component.empty(),
                        line("소행성의 철·금 광석을"),
                        line("곡괭이로 캔다."),
                        Component.empty(),
                        line("캔 원석을 들고 자기 팀"),
                        line("함선을 우클릭하면 팀"),
                        line("자원으로 들어간다."),
                        Component.empty(),
                        dim("돌은 캘 수 있지만 얻지 못한다"),
                        dim("배를 소행성에 대고 내릴 것")),

                page(head("업그레이드"),
                        Component.empty(),
                        line("조종석에서 F - 구매"),
                        Component.empty(),
                        line("크기 확장 I~III"),
                        line("속도 증가 I~III"),
                        line("내구도 증가 I~III"),
                        line("안정화"),
                        line("재생로"),
                        line("긴급 인양"),
                        Component.empty(),
                        dim("자원은 함선에 보급해야"),
                        dim("팀 자원으로 쌓인다")),

                page(head("직업"),
                        Component.empty(),
                        line("해병 - 철갑옷, 쇠뇌"),
                        dim("  체력 10칸 · 원거리"),
                        Component.empty(),
                        line("사신 - 겉날개, 폭죽"),
                        dim("  체력 8칸 · 유일한 비행"),
                        Component.empty(),
                        line("불곰 - 네더라이트, 돌풍구"),
                        dim("  체력 12칸 · 크기 1.5배"),
                        Component.empty(),
                        line("SCV - 용접기, 수리 담당"),
                        dim("  체력 15칸 · 갑옷 없음")),

                page(head("주의"),
                        Component.empty(),
                        line("공허로 떨어지면 자기 배로"),
                        line("구조되지만 체력 절반을"),
                        line("잃는다. 배가 없으면 사망."),
                        Component.empty(),
                        dim("긴급 인양을 사면 체력 소모 없음"),
                        Component.empty(),
                        head("보급 상자"),
                        line("정거장과 통로에 있다."),
                        line("무기 · 인첸트 책 · 빵"),
                        line("엔더 진주가 나온다."),
                        Component.empty(),
                        dim("인첸트 책은 아이템에"),
                        dim("직접 끌어다 놓으면 적용된다")));

        meta.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
        meta.lore(List.of(Component.text("우클릭하여 읽기", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return Kits.mark(item);
    }

    public static boolean has(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != Material.WRITTEN_BOOK || !item.hasItemMeta()) continue;
            if (item.getItemMeta().getPersistentDataContainer().has(TAG, PersistentDataType.BYTE)) return true;
        }
        return false;
    }

    /** 없을 때만 준다 */
    public static void give(Player player) {
        if (has(player)) return;
        player.getInventory().addItem(book());
    }
}
