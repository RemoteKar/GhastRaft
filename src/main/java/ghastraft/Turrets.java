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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** SCV 가 갑판에 짓는 시설물 - 자동 포탑과 미사일 포탑 */
public final class Turrets {

    public enum Type {
        AUTO("자동 포탑", 24, 8, 20, 22.0, "주변 적을 자동으로 사격합니다 (2 피해)"),
        MISSILE("미사일 포탑", 40, 24, 70, 140.0, "조종자 시야 안의 적함을 노립니다 (8 피해)");

        public final String label;
        public final int iron;
        public final int gold;
        public final int cooldown;      // 틱
        public final double range;
        public final String description;

        Type(String label, int iron, int gold, int cooldown, double range, String description) {
            this.label = label;
            this.iron = iron;
            this.gold = gold;
            this.cooldown = cooldown;
            this.range = range;
            this.description = description;
        }

        public Key key() {
            return Key.key(Kits.NS, "turret_" + name().toLowerCase());
        }
    }

    /** 갑판에 고정된 포탑 하나 */
    public static final class Turret {
        Type type;
        Raft.Chassis mount;
        BlockDisplay display;
        double offX, offY, offZ;
        int cooldown;

        void discard() {
            if (this.display != null) this.display.remove();
            if (this.mount != null) this.mount.discard();
        }
    }

    /** 날아가는 중인 미사일 */
    private static final class Missile {
        BlockDisplay body;
        Vec3 pos;
        Vec3 velocity;
        Raft.Pilot target;
        int team;
        int life;
    }

    private static final List<Missile> MISSILES = new ArrayList<>();
    public static final double AUTO_DAMAGE = 2.0;
    public static final double MISSILE_DAMAGE = 8.0;
    /** 미사일 포탑이 노리는 시야각(도) */
    private static final double VIEW_ANGLE = 50.0;

    private Turrets() {
    }

    /* ---------------------------------------------------------------- 건설 */

    public static void openDialog(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Type type : Type.values()) {
            buttons.add(ActionButton.create(
                    Component.text(type.label, NamedTextColor.YELLOW),
                    Component.text(type.description + " · 철 " + type.iron + " 금 " + type.gold),
                    180, DialogAction.customClick(type.key(), null)));
        }
        DialogBase base = DialogBase.builder(Component.text("시설물 건설"))
                .body(List.of(
                        DialogBody.plainMessage(Component.text("서 있는 자리에 짓습니다.", NamedTextColor.GRAY)),
                        DialogBody.plainMessage(Component.text("자기 팀 함선 갑판 위에서만 가능합니다.",
                                NamedTextColor.DARK_GRAY))))
                .canCloseWithEscape(true).build();
        player.showDialog(Dialog.create(factory -> factory.empty().base(base)
                .type(DialogType.multiAction(buttons, null, 1))));
    }

    /** 서 있는 자리에 건설. 실패 사유를 돌려준다(성공이면 null) */
    public static String build(Player player, Type type, Match match) {
        int slot = match.teamOf(player.getUniqueId());
        Raft.Pilot ship = match.shipOf(slot);
        if (ship == null || ship.isRemoved() || ship.sinking) return "함선이 없습니다.";
        if (!match.isAboardOwnShip(player)) return "자기 팀 함선 갑판 위에서만 지을 수 있습니다.";
        if (!match.spend(slot, type.iron, type.gold)) {
            int[] pool = match.resourcesOf(slot);
            return "자원이 부족합니다. (필요 철 " + type.iron + " 금 " + type.gold
                    + " · 보유 철 " + pool[0] + " 금 " + pool[1] + ")";
        }

        Vec3 anchor = ship.anchor();
        // 셜커 한 칸을 1x1 로 보고 블록 격자에 맞춘다
        double px = Math.floor(player.getX()) + 0.5;
        double pz = Math.floor(player.getZ()) + 0.5;
        double py = anchor.y + Raft.DECK_TOP;

        for (Turret existing : ship.turrets) {
            if (Math.abs(anchor.x + existing.offX - px) < 0.9
                    && Math.abs(anchor.z + existing.offZ - pz) < 0.9) {
                return "이미 시설물이 있는 자리입니다.";
            }
        }

        ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
        World world = player.getWorld();

        Turret turret = new Turret();
        turret.type = type;
        turret.offX = px - anchor.x;
        turret.offY = 0.0;
        turret.offZ = pz - anchor.z;

        Raft.Chassis mount = new Raft.Chassis(level);
        mount.setPos(px, py, pz);
        Raft.dressMountPublic(mount);
        level.addFreshEntity(mount);
        turret.mount = mount;

        // 상부에 철 다락문 - 포탑 본체는 그 아래 갑판에 놓인 셈이 된다
        BlockDisplay display = world.spawn(new Location(world, px, py, pz), BlockDisplay.class, d -> {
            d.setBlock(Material.IRON_TRAPDOOR.createBlockData());
            d.setTransformation(new Transformation(
                    new Vector3f(-0.5F, 0.0F, -0.5F),
                    new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F),
                    new Vector3f(1.0F, 1.0F, 1.0F),
                    new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F)));
            d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
        });
        ((org.bukkit.craftbukkit.entity.CraftEntity) display).getHandle().startRiding(mount, true);
        turret.display = display;

        ship.turrets.add(turret);
        world.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0F, 0.8F);
        world.spawnParticle(Particle.ELECTRIC_SPARK, px, py + 0.4, pz, 20, 0.3, 0.3, 0.3, 0.05);
        return null;
    }

    /* ------------------------------------------------------------------ 틱 */

    /** 포탑 사격 - 5틱마다 호출 */
    public static void tick(Raft.Pilot ship, Match match) {
        if (ship.sinking || ship.isRemoved()) return;
        Vec3 anchor = ship.anchor();
        World world = ship.level().getWorld();

        for (Turret turret : ship.turrets) {
            if (turret.cooldown > 0) {
                turret.cooldown -= 5;
                continue;
            }
            Location muzzle = new Location(world,
                    anchor.x + turret.offX, anchor.y + Raft.DECK_TOP + 0.6, anchor.z + turret.offZ);

            if (turret.type == Type.AUTO) {
                if (fireAuto(ship, match, muzzle, turret)) turret.cooldown = turret.type.cooldown;
            } else {
                if (fireMissile(ship, match, muzzle, turret)) turret.cooldown = turret.type.cooldown;
            }
        }
    }

    /** 주변 적 플레이어에게 눈덩이 */
    private static boolean fireAuto(Raft.Pilot ship, Match match, Location muzzle, Turret turret) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player candidate : muzzle.getWorld().getNearbyPlayers(muzzle, turret.type.range)) {
            if (!match.isPlaying(candidate)) continue;
            if (ship.teamSlot >= 0 && match.teamOf(candidate.getUniqueId()) == ship.teamSlot) continue;
            double dist = candidate.getLocation().distanceSquared(muzzle);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        if (best == null) return false;

        Vector dir = best.getEyeLocation().toVector().subtract(muzzle.toVector()).normalize();
        Snowball ball = muzzle.getWorld().spawn(muzzle.clone().add(dir.clone().multiply(0.8)), Snowball.class);
        ball.setVelocity(dir.multiply(2.2));
        ball.setGravity(false);
        ball.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(Kits.NS, "turret"),
                org.bukkit.persistence.PersistentDataType.INTEGER, ship.teamSlot);
        muzzle.getWorld().playSound(muzzle, Sound.ENTITY_SNOWBALL_THROW, 0.8F, 1.6F);
        return true;
    }

    /** 조종자 시야각 안의 적함에 미사일 */
    private static boolean fireMissile(Raft.Pilot ship, Match match, Location muzzle, Turret turret) {
        if (!(ship.getFirstPassenger() instanceof net.minecraft.server.level.ServerPlayer rider)) return false;
        Vector look = rider.getBukkitEntity().getEyeLocation().getDirection();

        Raft.Pilot best = null;
        double bestDist = Double.MAX_VALUE;
        for (Raft.Pilot other : Raft.active()) {
            if (other == ship || other.isRemoved() || other.sinking) continue;
            if (other.teamSlot >= 0 && other.teamSlot == ship.teamSlot) continue;

            Vec3 to = other.anchor().subtract(ship.anchor());
            double dist = to.length();
            if (dist > turret.type.range || dist < 1.0) continue;

            Vector dir = new Vector(to.x, to.y, to.z).normalize();
            double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dir.dot(look)))));
            if (angle > VIEW_ANGLE) continue;                 // 조종자가 보고 있지 않으면 쏘지 않는다
            if (dist < bestDist) {
                bestDist = dist;
                best = other;
            }
        }
        if (best == null) return false;

        World world = muzzle.getWorld();
        Vec3 target = best.anchor().add(0.0, Raft.DECK_TOP / 2.0, 0.0);
        Vec3 start = new Vec3(muzzle.getX(), muzzle.getY(), muzzle.getZ());
        Vec3 dir = target.subtract(start).normalize();

        Missile missile = new Missile();
        missile.pos = start;
        missile.velocity = dir.scale(1.4);
        missile.target = best;
        missile.team = ship.teamSlot;
        missile.life = 200;
        missile.body = world.spawn(new Location(world, start.x, start.y, start.z), BlockDisplay.class, d -> {
            d.setBlock(Material.IRON_BLOCK.createBlockData());
            d.setTransformation(new Transformation(
                    new Vector3f(-0.15F, -0.15F, -0.6F),
                    new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F),
                    new Vector3f(0.3F, 0.3F, 1.2F),           // 길쭉한 탄체
                    new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F)));
            d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
        });
        MISSILES.add(missile);
        world.playSound(muzzle, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.2F, 0.7F);
        return true;
    }

    /** 미사일 비행 - 매 틱 호출 */
    public static void tickMissiles() {
        Iterator<Missile> it = MISSILES.iterator();
        while (it.hasNext()) {
            Missile missile = it.next();
            if (missile.body == null || !missile.body.isValid() || --missile.life <= 0
                    || missile.target == null || missile.target.isRemoved()) {
                if (missile.body != null) missile.body.remove();
                it.remove();
                continue;
            }

            // 유도 - 목표 방향으로 서서히 튼다
            Vec3 target = missile.target.anchor().add(0.0, Raft.DECK_TOP / 2.0, 0.0);
            Vec3 desired = target.subtract(missile.pos).normalize().scale(1.4);
            missile.velocity = missile.velocity.scale(0.75).add(desired.scale(0.25));
            missile.pos = missile.pos.add(missile.velocity);

            World world = missile.body.getWorld();
            Location at = new Location(world, missile.pos.x, missile.pos.y, missile.pos.z);
            at.setDirection(new Vector(missile.velocity.x, missile.velocity.y, missile.velocity.z));
            missile.body.teleport(at);
            world.spawnParticle(Particle.SMOKE, at, 2, 0.05, 0.05, 0.05, 0.0);

            // 명중 판정 - 선체 반경 안에 들어오면
            Vec3 anchor = missile.target.anchor();
            double half = missile.target.footprint() / 2.0;
            boolean hit = Math.abs(missile.pos.x - anchor.x) <= half
                    && Math.abs(missile.pos.z - anchor.z) <= half
                    && missile.pos.y >= anchor.y - 1.0
                    && missile.pos.y <= anchor.y + Raft.TILE + 1.5;
            if (!hit) continue;

            boolean destroyed = missile.target.damage(MISSILE_DAMAGE);
            world.createExplosion(at.getX(), at.getY(), at.getZ(), 0.0F, false, false);
            world.spawnParticle(Particle.EXPLOSION, at, 2);
            missile.body.remove();
            it.remove();
            if (destroyed && missile.target.teamSlot >= 0) {
                Raft.ON_DESTROYED.accept(missile.target.teamSlot, missile.team);
            }
        }
    }

    public static void clearMissiles() {
        for (Missile missile : MISSILES) {
            if (missile.body != null) missile.body.remove();
        }
        MISSILES.clear();
    }
}
