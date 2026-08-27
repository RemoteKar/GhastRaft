package ghastraft;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * 셜커 격자 뗏목 (v10).
 *
 * 해피가스트에서 셜커로 바꾼 이유 - 판정식이 근본적으로 다르다.
 *
 *   HappyGhast#canBeCollidedWith : 클라 전용 분기 + "발 Y >= 윗면" (오차 0 의 경계)
 *       -> 0.001 만 아래로 내려가도 발판 전체가 비고체가 되고, 조건이 영원히 거짓이라 복구 불가
 *       -> 게다가 모델이 히트박스보다 작아 타일 교차점에 시각적 구멍
 *
 *   Shulker#canBeCollidedWith    : return this.isAlive();   (조건 없음)
 *       -> 클라/서버 양쪽, 모든 면에서 항상 solid. 경계 조건 자체가 없다
 *       -> 클라와 서버가 같은 판단을 하므로 리바운드도 없다
 *       -> 닫힌 셜커 = 셜커 상자. 모델이 히트박스를 꽉 채워 유격이 없다
 *
 * 남는 문제는 하나뿐이다: 마인크래프트에 무빙 플랫폼 물리가 없다는 것.
 * 그래서 상판 위 플레이어를 뗏목 이동량만큼 밀어주는 캐리는 여전히 필요하다.
 * 다만 어긋나도 "관통해서 추락" 이 아니라 "상판에 걸림" 으로 끝나므로 훨씬 안전하다.
 */
public final class Raft {

    public static final String VERSION = "v76";

    /** 뗏목 한 변 길이(블록) */
    public static final double FOOTPRINT = 12.0;
    /** 셜커 한 기의 크기. 기본 1x1x1 에 SCALE 어트리뷰트 3배(셜커 상한 = sanitizeScale). */
    public static final double TILE = 3.0;
    /** 상판 높이(타일 Y 기준) */
    public static final double DECK_TOP = TILE;
    /** 캐리 패킷 ID. 서버의 awaitingTeleport(1부터 증가)와 겹치면 invalid_player_movement 로 킥된다. */
    private static final int CARRY_ID = -808;

    /**
     * 클라 보간 상쇄량(틱).
     * InterpolationHandler 는 매 틱 pos += (target-pos)/3 이라 정속에서 2틱x속도만큼 뒤처진다.
     * 진행 방향으로 이만큼 미리 내보내면 화면상 위치가 논리 위치와 일치한다.
     */
    public static double LEAD = 2.0;

    /**
     * 수평 캐리 방식. /raft mode 로 전환.
     *  false = 위치 패킷 (정확하지만 매 틱 absSnapTo 로 렌더 보간이 끊긴다)
     *  true  = 폭발 패킷 knockback (속도만 더하므로 클라 물리로 자연스럽게 굴러간다)
     */
    public static boolean VELOCITY_MODE = true;
    /** 지상 마찰(0.91 x 0.6 = 0.546) 보상 계수 */
    private static final double FRICTION_COMP = 0.454;
    /** 폭발 패킷 피드백 게인. 왕복 지연이 있어 1.0 이면 발산한다. */
    private static final double FEEDBACK_GAIN = 0.4;
    /** 한 틱에 줄 수 있는 최대 knockback(블록/틱) */
    private static final double KNOCK_LIMIT = 0.25;

    /** 폭발 패킷은 사운드 인자가 필수라 빈 사운드를 물려 무음으로 만든다. */
    private static final Holder<SoundEvent> SILENT = Holder.direct(
            SoundEvent.createVariableRangeEvent(ResourceLocation.withDefaultNamespace("intentionally_empty")));

    private static double clamp(double v, double limit) {
        return v > limit ? limit : (v < -limit ? -limit : v);
    }

    /** 최고 속도(블록/틱) */
    public static final double SPEED = 0.30;
    /** 가감속 계수 */
    public static final double ACCEL = 0.12;

    /** 상판보다 이만큼 아래까지는 끌어올린다(빠짐 복구) */
    private static final double SNAP_BELOW = 1.50;
    /** 상판보다 항상 이만큼 위에 둔다 */
    private static final double MARGIN = 0.02;

    /** 최대 선체 체력 */
    public static double MAX_HEALTH = 400.0;
    /** 함선 최대 고도 - 이 위로는 못 올라간다 */
    public static double CEILING = 256.0;

    /** 점령에 걸리는 시간(틱) */
    public static int CAPTURE_TICKS = 160;   // 점령 완료까지 8초 (방어자가 올라오면 2배 속도로 되감김)

    /** 플레이어 UUID -> 팀 번호. 팀이 없으면 -1. 플러그인이 주입한다. */
    public static ToIntFunction<UUID> TEAM_LOOKUP = uuid -> -1;
    /** 선박 격침 - (격침된 팀, 격침시킨 팀). 플러그인이 주입한다. */
    public static java.util.function.BiConsumer<Integer, Integer> ON_DESTROYED = (victim, killer) -> { };

    /**
     * 선체 피격 처리.
     *
     * 바닐라 셜커는 닫혀 있으면 화살을 통째로 무시한다(Shulker#hurtServer 첫 줄이
     * isClosed() && AbstractArrow 이면 return false). 우리 타일은 항상 닫혀 있어서
     * 화살이 아무 반응 없이 튕겨나갔다 - Bukkit 피해 이벤트조차 뜨지 않는다.
     * 그래서 hurtServer 를 직접 가로채 선체 체력으로 환산한다.
     */
    static boolean hitShip(Pilot ship, DamageSource source, float amount) {
        if (ship == null || ship.isRemoved() || ship.sinking) return false;
        // 엔더 진주처럼 피해 0 으로 부딪히는 투사체는 선체에 영향을 주지 않는다(액션바만 도배된다)
        if (amount <= 0.0F) return false;
        // 돌풍구는 대장갑용이다. 바닐라 피해(1)를 무시하고 고정값으로 환산한다.
        boolean wind = source.getDirectEntity()
                instanceof net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge;
        if (wind) amount = (float) Kits.WIND_DAMAGE_ARMORED;

        net.minecraft.world.entity.Entity attacker = source.getEntity();   // 화살이면 쏜 사람
        if (!(attacker instanceof net.minecraft.world.entity.player.Player p)) return false;
        if (!(p.getBukkitEntity() instanceof org.bukkit.entity.Player bukkitAttacker)) return false;

        if (ship.teamSlot >= 0 && TEAM_LOOKUP.applyAsInt(p.getUUID()) == ship.teamSlot) {
            // 아군은 피해를 주지 않는다. 다만 SCV 가 용접기로 때리면 수리된다.
            if (Kits.isWelder(bukkitAttacker.getInventory().getItemInMainHand())) repair(ship, bukkitAttacker);
            return false;
        }

        boolean destroyed = ship.damage(amount);
        bukkitAttacker.sendActionBar(net.kyori.adventure.text.Component.text(
                destroyed ? "선박 격침!" : "선체 " + (int) Math.ceil(ship.health) + " / " + (int) ship.maxHealth,
                net.kyori.adventure.text.format.NamedTextColor.RED));
        if (destroyed && ship.teamSlot >= 0) {
            ON_DESTROYED.accept(ship.teamSlot, TEAM_LOOKUP.applyAsInt(p.getUUID()));
        }
        return true;   // 화살이 소모되도록 true. 셜커 자신에게는 아무 피해도 주지 않는다.
    }

    /** 살아 있는 선박 목록. 선박끼리 겹치지 않게 검사할 때 쓴다. */
    private static final Set<Pilot> ACTIVE = new HashSet<>();

    private Raft() {
    }

    /** 용접기 재사용 대기 - 연타해도 수리 속도가 오르지 않게 한다 */
    private static final Map<UUID, Long> REPAIR_AT = new HashMap<>();

    /** 선박이 낄 자리를 미리 비운다(소환 지점에 소행성이 걸리면 못 움직인다) */
    public static void clearBerth(ServerLevel level, Vec3 at, int grid) {
        double half = footprintOf(grid) / 2.0 + 1.0;
        org.bukkit.World world = level.getWorld();
        for (int x = (int) -half; x <= half; x++) {
            for (int z = (int) -half; z <= half; z++) {
                for (int y = -2; y <= (int) TILE + 3; y++) {
                    org.bukkit.block.Block block = world.getBlockAt(
                            (int) at.x + x, (int) at.y + y, (int) at.z + z);
                    if (block.getType() != org.bukkit.Material.AIR) {
                        block.setType(org.bukkit.Material.AIR, false);
                    }
                }
            }
        }
    }

    /** 플라즈마 용접기 수리 */
    private static void repair(Pilot ship, org.bukkit.entity.Player welder) {
        long now = ship.level().getGameTime();
        Long last = REPAIR_AT.get(welder.getUniqueId());
        if (last != null && now - last < Kits.REPAIR_COOLDOWN) return;
        REPAIR_AT.put(welder.getUniqueId(), now);

        if (ship.health >= ship.maxHealth) {
            welder.sendActionBar(net.kyori.adventure.text.Component.text(
                    "선체가 이미 온전합니다", net.kyori.adventure.text.format.NamedTextColor.GRAY));
            return;
        }
        ship.health = Math.min(ship.maxHealth, ship.health + Kits.REPAIR_PER_HIT);
        welder.sendActionBar(net.kyori.adventure.text.Component.text(
                "수리 " + (int) Math.ceil(ship.health) + " / " + (int) ship.maxHealth,
                net.kyori.adventure.text.format.NamedTextColor.GREEN));
        org.bukkit.Location at = welder.getLocation();
        welder.getWorld().spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK,
                at.getX(), at.getY() + 1.2, at.getZ(), 12, 0.4, 0.4, 0.4, 0.05);
        welder.getWorld().playSound(at, org.bukkit.Sound.BLOCK_ANVIL_LAND, 0.35F, 1.8F);
    }

    /** 선체(hull) 판정 박스. 갑판 위 사람이 낀 채로 다른 배가 파고들지 않도록 위로 여유를 준다. */
    private static AABB hull(Vec3 center, double footprint) {
        double h = footprint / 2.0;
        return new AABB(center.x - h, center.y, center.z - h,
                        center.x + h, center.y + TILE + 2.5, center.z + h);
    }

    /** 타일은 항상 크기만큼 띄운다 - 빈틈도 겹침도 없다. 크기는 타일 수로 늘린다. */
    /**
     * 선체 최대 체력.
     * 내구도 업그레이드만 반영한다(크기는 체력과 무관).
     */
    public static double maxHealthFor(int hpLevel, int sizeLevel) {
        return MAX_HEALTH * (1.0 + 0.5 * hpLevel);
    }

    /** 살아 있는 선박 목록(읽기 전용) */
    public static java.util.Collection<Pilot> active() {
        return java.util.Collections.unmodifiableCollection(ACTIVE);
    }

    /** 포탑도 같은 받침대 방식을 쓰므로 외부에서 쓸 수 있게 연다 */
    static void dressMountPublic(Chassis chassis) {
        dressMount(chassis);
    }

    public static double spacing(int grid) {
        return TILE;
    }

    public static double footprintOf(int grid) {
        return grid * TILE;
    }

    /**
     * 셜커를 태우고 다니는 보이지 않는 받침대.
     *
     * Shulker#getInterpolation() 은 null 을 반환한다 - 셜커는 보간을 명시적으로 거부하는 몹이라
     * 좌표를 직접 쏘면 매 틱 뚝뚝 끊긴다. 반면 LivingEntity(=ArmorStand) 는 3틱 보간을 한다.
     * 그래서 좌표는 이 마커 아머스탠드에만 보내고, 셜커는 승객으로 붙여 따라오게 한다.
     * 마커 아머스탠드는 크기가 0(MARKER_DIMENSIONS = fixed(0,0)) 이라 승객 부착점도 (0,0,0),
     * 즉 셜커가 받침대 좌표에 정확히 1:1 로 붙는다.
     *
     * 승객의 좌표는 클라가 직접 계산하므로 패킷은 받침대 몫만 나간다(엔티티는 2배, 트래픽은 그대로).
     */
    public static final class Chassis extends ArmorStand {

        double offX;
        double offZ;

        Chassis(ServerLevel level) {
            super(EntityType.ARMOR_STAND, level);
        }

        @Override public boolean shouldBeSaved() { return false; }
        @Override public boolean isPushable() { return false; }
        @Override public void tick() { }   // 위치는 Pilot 이 넣어준다
    }

    /** 발판 타일 - 밟히는 것 외에는 아무것도 하지 않는다 */
    public static final class Deck extends Shulker {

        Chassis mount;
        Pilot owner;

        Deck(ServerLevel level) {
            super(EntityType.SHULKER, level);
        }

        @Override protected boolean canAddPassenger(Entity entity) { return false; }

        /** 타일 아무거나 눌러도 조종석에 태운다. */
        @Override
        public InteractionResult mobInteract(Player player, InteractionHand hand) {
            if (!this.level().isClientSide()
                    && this.owner != null && !this.owner.isRemoved()
                    && this.owner.getPassengers().isEmpty()      // 이미 탑승자가 있으면 아무 일 없음
                    && !player.isSecondaryUseActive()) {
                player.startRiding(this.owner);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }

        // canBeCollidedWith 는 건드리지 않는다. 셜커의 무조건 solid 가 이 설계의 핵심이다.
        @Override public boolean canBeLeashed() { return false; }
        @Override public boolean isPushable() { return false; }
        @Override public boolean shouldBeSaved() { return false; }
        @Override public void tick() { }   // 위치는 Pilot 이 넣어준다. 셜커의 부착/순간이동 AI 도 함께 차단.

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            return hitShip(this.owner, source, amount);
        }
    }

    /** 중앙 조종석 겸 편대/캐리 컨트롤러 */
    public static final class Pilot extends Shulker {

        final List<Deck> decks = new ArrayList<>();
        final List<Turrets.Turret> turrets = new ArrayList<>();
        Chassis mount;
        private Vec3 velocity = Vec3.ZERO;
        /** 뗏목의 논리 위치. 엔티티 실제 좌표는 여기에 수평 보간 상쇄분(LEAD)을 더한 값이다. */
        private Vec3 anchor;
        /** 클라이언트에 그려지고 있을 높이의 추정치 */
        private double renderY = Double.NaN;
        private long tickNo;
        public boolean debug;

        /** 소속 팀. -1 이면 자유 선박(테스트용) */
        public int teamSlot = -1;
        public int gridSize = 4;
        public org.bukkit.DyeColor color = org.bukkit.DyeColor.PURPLE;

        public double health = MAX_HEALTH;
        public double maxHealth = MAX_HEALTH;
        /** 업그레이드 단계 */
        public int sizeLevel;
        public int speedLevel;
        public int hpLevel;
        public boolean stabilized;      // 위치 기반 보정으로 전환
        public boolean regenerator;
        public boolean salvage;         // 긴급 인양 - 공허로 떨어진 아군 구조
        public double speedMul = 1.0;
        /** 격침 진행 중이면 조종 불가 + 천천히 하강 */
        public boolean sinking;
        private int sinkTicks;
        /** 적 점령 진행도 0.0 ~ 1.0 */
        public double capture;

        private final Map<UUID, Deque<String>> trace = new HashMap<>();
        private final Set<UUID> reported = new HashSet<>();
        private final Map<UUID, Vec3> prevPos = new HashMap<>();

        Pilot(ServerLevel level) {
            super(EntityType.SHULKER, level);
        }

        @Override protected boolean canAddPassenger(Entity entity) { return this.getPassengers().isEmpty(); }
        @Override public boolean canBeLeashed() { return false; }
        @Override public boolean isPushable() { return false; }
        @Override public boolean shouldBeSaved() { return false; }

        /** 적 팀은 조종석에 탑승할 수 없다(대신 점령이 진행된다) */
        public boolean canPilot(UUID uuid) {
            return this.teamSlot < 0 || TEAM_LOOKUP.applyAsInt(uuid) == this.teamSlot;
        }

        @Override
        public InteractionResult mobInteract(Player player, InteractionHand hand) {
            if (!this.level().isClientSide() && this.getPassengers().isEmpty()
                    && !player.isSecondaryUseActive() && canPilot(player.getUUID()) && !this.sinking) {
                player.startRiding(this);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }

        /** 마지막 blocked() 가 지형 때문이었는지 */
        private boolean lastBlockTerrain;
        /** 지형에 막힌 채로 버틴 틱 수 */
        private int stuckTicks;

        /** 다른 선박 또는 지형과 겹치는가 */
        private boolean blocked(Vec3 candidate) {
            this.lastBlockTerrain = false;
            AABB me = hull(candidate, footprint());
            for (Pilot other : ACTIVE) {
                if (other == this || other.isRemoved() || other.anchor == null) continue;
                if (me.intersects(hull(other.anchor, other.footprint()))) return true;
            }
            // 지형. 엔티티 충돌은 쓰면 안 된다 - 자기 갑판 타일과 부딪혀버린다.
            if (this.level().getBlockCollisions(this, me).iterator().hasNext()) {
                this.lastBlockTerrain = true;
                return true;
            }
            return false;
        }

        /**
         * 선체에 낀 블록을 부순다.
         * 소행성에 정박하려고 잠깐 닿는 것과 구분하려고, 1초 넘게 계속 밀고 있을 때만 작동한다.
         */
        private void carve() {
            AABB box = hull(this.anchor, footprint()).inflate(0.6);
            Vec3 dir = this.velocity.lengthSqr() > 1.0E-9 ? this.velocity.normalize() : Vec3.ZERO;
            org.bukkit.World world = this.level().getWorld();

            int removed = 0;
            for (int x = (int) Math.floor(box.minX + dir.x * 2); x <= (int) Math.ceil(box.maxX + dir.x * 2); x++) {
                for (int y = (int) Math.floor(box.minY + dir.y * 2); y <= (int) Math.ceil(box.maxY + dir.y * 2); y++) {
                    for (int z = (int) Math.floor(box.minZ + dir.z * 2); z <= (int) Math.ceil(box.maxZ + dir.z * 2); z++) {
                        org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                        if (block.getType() == org.bukkit.Material.AIR) continue;
                        world.spawnParticle(org.bukkit.Particle.BLOCK, x + 0.5, y + 0.5, z + 0.5,
                                6, 0.3, 0.3, 0.3, block.getBlockData());
                        block.setType(org.bukkit.Material.AIR, false);   // 아이템은 남기지 않는다
                        removed++;
                    }
                }
            }
            if (removed > 0) {
                world.playSound(new org.bukkit.Location(world, this.anchor.x, this.anchor.y + TILE, this.anchor.z),
                        org.bukkit.Sound.BLOCK_STONE_BREAK, 1.4F, 0.7F);
                if (this.getFirstPassenger() instanceof ServerPlayer rider
                        && rider.getBukkitEntity() instanceof org.bukkit.entity.Player bukkit) {
                    bukkit.sendActionBar(net.kyori.adventure.text.Component.text(
                            "장애물 " + removed + "개 파괴",
                            net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                }
            }
        }

        /** 바닐라 tick 을 통째로 대체한다. */
        @Override
        public void tick() {
            if (this.sinking) {
                sinkTick();
                return;
            }
            steer();
            if (this.anchor == null) this.anchor = this.position();

            // 선박끼리는 축별로 밀어내며 미끄러진다. 이동을 전부 서버에서 계산하므로
            // 클라이언트가 개입할 여지가 없어 겹침이 확실하게 차단된다.
            Vec3 a = this.anchor;
            Vec3 want = this.velocity;
            double vx = want.x, vy = want.y, vz = want.z;
            // 고도 제한 - 천장에 닿으면 상승만 막고 나머지 축은 그대로 둔다
            if (vy > 0.0 && a.y + vy > CEILING) {
                vy = Math.max(0.0, CEILING - a.y);
                if (vy <= 0.0 && this.getFirstPassenger() instanceof ServerPlayer rider
                        && rider.getBukkitEntity() instanceof org.bukkit.entity.Player bukkit) {
                    bukkit.sendActionBar(net.kyori.adventure.text.Component.text(
                            "고도 한계 " + (int) CEILING,
                            net.kyori.adventure.text.format.NamedTextColor.GOLD));
                }
            }

            boolean terrain = false;
            if (vx != 0.0 && blocked(new Vec3(a.x + vx, a.y, a.z))) {
                terrain |= this.lastBlockTerrain;
                vx = 0.0;
            }
            if (vy != 0.0 && blocked(new Vec3(a.x + vx, a.y + vy, a.z))) {
                terrain |= this.lastBlockTerrain;
                vy = 0.0;
            }
            if (vz != 0.0 && blocked(new Vec3(a.x + vx, a.y + vy, a.z + vz))) {
                terrain |= this.lastBlockTerrain;
                vz = 0.0;
            }
            this.velocity = new Vec3(vx, vy, vz);

            // 밀고 있는데 지형에 막혀 있으면 잠시 뒤 뚫는다
            if (terrain && want.lengthSqr() > 1.0E-6) {
                if (++this.stuckTicks > 20) {
                    this.velocity = want;      // 부순 뒤 원래 속도로 재개
                    carve();
                    this.stuckTicks = 0;
                }
            } else {
                this.stuckTicks = 0;
            }

            boolean moving = this.velocity.lengthSqr() > 1.0E-9;
            if (moving) this.anchor = new Vec3(a.x + vx, a.y + vy, a.z + vz);

            // 수평만 보간 상쇄한다. 수직은 renderY 로 따로 추정한다.
            Vec3 shown = new Vec3(
                    this.anchor.x + this.velocity.x * LEAD,
                    this.anchor.y,
                    this.anchor.z + this.velocity.z * LEAD);
            // 셜커가 아니라 받침대를 옮긴다. 셜커는 승객이라 클라가 알아서 따라 그린다.
            move(this.mount, shown, moving);

            if (Double.isNaN(this.renderY)) this.renderY = this.anchor.y;
            this.renderY += (this.anchor.y - this.renderY) / 3.0;   // InterpolationHandler 와 동일한 식
            this.tickNo++;

            syncDecks(shown, moving);
            carryRiders();
        }

        /** 격침 연출 - 아주 천천히 하강하며 가끔 폭발/연기/화염 */
        private void sinkTick() {
            if (this.anchor == null) this.anchor = this.position();
            this.sinkTicks++;
            this.velocity = new Vec3(0.0, -0.02, 0.0);
            this.anchor = this.anchor.add(this.velocity);

            Vec3 shown = this.anchor;
            move(this.mount, shown, true);
            syncDecks(shown, true);
            if (Double.isNaN(this.renderY)) this.renderY = this.anchor.y;
            this.renderY += (this.anchor.y - this.renderY) / 3.0;
            carryRiders();

            if (this.sinkTicks % 25 == 0) {
                org.bukkit.World world = this.level().getWorld();
                double r = FOOTPRINT / 2.0;
                double x = this.anchor.x + (this.sinkTicks % 7 - 3) * r / 3.0;
                double z = this.anchor.z + (this.sinkTicks % 5 - 2) * r / 2.0;
                double y = this.anchor.y + TILE;
                world.spawnParticle(org.bukkit.Particle.EXPLOSION, x, y, z, 1);
                world.spawnParticle(org.bukkit.Particle.LARGE_SMOKE, x, y, z, 12, 1.5, 0.5, 1.5, 0.02);
                world.spawnParticle(org.bukkit.Particle.FLAME, x, y, z, 8, 1.2, 0.4, 1.2, 0.01);
                world.playSound(new org.bukkit.Location(world, x, y, z),
                        org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.6F, 0.6F);
            }
            if (this.sinkTicks > 600 || this.anchor.y < this.level().getMinY() + 4) discardAll();
        }

        /** 선체 피해. 파괴되면 true */
        public boolean damage(double amount) {
            if (this.sinking) return false;
            this.health -= amount;
            if (this.health <= 0.0) {
                this.health = 0.0;
                startSinking();
                return true;
            }
            return false;
        }

        public void startSinking() {
            if (this.sinking) return;
            this.sinking = true;
            this.sinkTicks = 0;
            this.ejectPassengers();
            org.bukkit.World world = this.level().getWorld();
            Vec3 a = anchor();
            world.createExplosion(a.x, a.y + TILE, a.z, 0.0F, false, false);
            world.spawnParticle(org.bukkit.Particle.EXPLOSION_EMITTER, a.x, a.y + TILE, a.z, 3, 3, 1, 3, 0);
        }

        /** 갑판 위(선체 범위)에 있는 플레이어들 */
        public java.util.List<ServerPlayer> aboard() {
            Vec3 a = anchor();
            double half = footprint() / 2.0 + 0.5;
            double top = a.y + DECK_TOP;
            return this.level().getEntitiesOfClass(ServerPlayer.class,
                    new AABB(a.x - half, top - 0.8, a.z - half, a.x + half, top + 3.0, a.z + half));
        }

        /** 조종석 위(중앙 타일)에 서 있는 플레이어들 */
        public java.util.List<ServerPlayer> onCockpit() {
            Vec3 a = anchor();
            double h = TILE / 2.0 + 0.4;
            AABB box = new AABB(a.x - h, a.y + DECK_TOP - 0.6, a.z - h,
                                a.x + h, a.y + DECK_TOP + 2.4, a.z + h);
            return this.level().getEntitiesOfClass(ServerPlayer.class, box);
        }

        /** 조종사의 입력 패킷으로 직접 비행시킨다. */
        private void steer() {
            if (!(this.getFirstPassenger() instanceof ServerPlayer rider) || !canPilot(rider.getUUID())) {
                this.velocity = this.velocity.scale(0.6);
                if (this.velocity.lengthSqr() < 1.0E-6) this.velocity = Vec3.ZERO;
                return;
            }

            Input in = rider.getLastClientInput();
            float yaw = rider.getYRot();
            double yawRad = Math.toRadians(yaw);
            double pitchRad = Math.toRadians(rider.getXRot());
            double cy = Math.cos(yawRad), sy = Math.sin(yawRad);
            double cp = Math.cos(pitchRad), sp = Math.sin(pitchRad);

            Vec3 look = new Vec3(-sy * cp, -sp, cy * cp);   // 시선 방향(피치 포함)
            Vec3 left = new Vec3(cy, 0.0, sy);              // 바닐라 getInputVector 기준 좌측

            Vec3 dir = Vec3.ZERO;
            if (in.forward())  dir = dir.add(look);
            if (in.backward()) dir = dir.subtract(look);
            if (in.left())     dir = dir.add(left);
            if (in.right())    dir = dir.subtract(left);
            if (in.jump())     dir = dir.add(0.0, 1.0, 0.0);

            Vec3 target = dir.lengthSqr() > 1.0E-6 ? dir.normalize().scale(SPEED * this.speedMul) : Vec3.ZERO;
            this.velocity = this.velocity.scale(1.0 - ACCEL).add(target.scale(ACCEL));
            if (this.velocity.lengthSqr() < 1.0E-8) this.velocity = Vec3.ZERO;
        }

        private void syncDecks(Vec3 now, boolean moving) {
            for (Turrets.Turret turret : this.turrets) {
                if (turret.mount == null || turret.mount.isRemoved()) continue;
                move(turret.mount, new Vec3(now.x + turret.offX, now.y + DECK_TOP + turret.offY,
                        now.z + turret.offZ), moving);
            }
            for (Deck deck : this.decks) {
                if (deck.mount == null || deck.mount.isRemoved()) continue;
                move(deck.mount, new Vec3(now.x + deck.mount.offX, now.y, now.z + deck.mount.offZ), moving);
            }
        }

        private static void move(Chassis chassis, Vec3 to, boolean moving) {
            if (chassis == null || chassis.isRemoved()) return;
            chassis.setPos(to.x, to.y, to.z);
            chassis.setRequiresPrecisePosition(true);   // 매 틱 절대좌표 동기 -> 클라가 3틱 보간
            if (moving) chassis.hasImpulse = true;      // 브로드캐스트 강제
        }

        /**
         * 수평은 뗏목 이동량만큼 밀고, 수직은 "클라가 그리고 있는 상판" 높이에 맞춘다.
         * 아래로는 절대 밀지 않는다(하강은 중력에 맡긴다).
         */
        private void carryRiders() {
            double top = this.renderY + DECK_TOP;
            double half = footprint() / 2.0 + 0.5;
            AABB box = new AABB(
                    this.anchor.x - half, top - (SNAP_BELOW + 0.5), this.anchor.z - half,
                    this.anchor.x + half, top + 3.0, this.anchor.z + half);

            int carried = 0;
            for (ServerPlayer player : this.level().getEntitiesOfClass(ServerPlayer.class, box)) {
                if (player.getRootVehicle() == this) continue;   // 조종사는 이미 따라온다
                if (player.getAbilities().flying) continue;      // 크리에이티브 비행 중이면 건드리지 않는다

                // 상승 중에는 상판이 플레이어보다 빨리 올라온다. 발이 셜커 몸통 안에 들어가면
                // 모든 면이 solid 라 사방이 막혀 수평 이동이 통째로 차단된다(상승 중 못 움직이던 원인).
                // 그래서 오르는 속도의 1.5틱분을 미리 얹어 항상 상판 위에 떠 있게 한다.
                double lift = Math.max(0.0, this.velocity.y) * 1.5;
                double target = top + MARGIN + lift;
                double gap = target - player.getY();

                UUID id = player.getUUID();
                Vec3 prev = this.prevPos.put(id, player.position());
                boolean sent = false;

                if (VELOCITY_MODE && !this.stabilized) {
                    // 위치 패킷은 매 틱 absSnapTo 를 유발해서 플레이어 본인 입력을 뭉갠다.
                    // (상승 중 못 움직이던 원인) 셜커는 항상 solid 라 정밀도가 필요 없으므로
                    // 수평도 수직도 "속도"로 준다. 폭발 패킷의 knockback 은 클라 속도에 더해질 뿐이라
                    // 입력을 건드리지 않고, absSnapTo 도 없어서 렌더 보간이 살아 있다.
                    Input in = player.getLastClientInput();
                    boolean walking = in.forward() || in.backward() || in.left() || in.right();
                    Vec3 measured = prev == null ? Vec3.ZERO : player.position().subtract(prev);

                    // knockback 은 속도에 누적으로 더해지는데, measured 는 왕복 지연 때문에 2틱쯤 늦다.
                    // 지연 있는 피드백에 게인 1.0 을 쓰면 발산한다(탑승자가 튕겨 나가던 원인).
                    // 게인을 낮추고 한 틱 최대 보정량을 묶어둔다.
                    // 클라 물리는 매 틱  속도 = 이전속도 x 0.546 + knock  이다.
                    // 마찰을 상쇄하는 피드포워드 v x (1-f) = v x 0.454 를 항상 깔아야 정상상태가 v 가 된다.
                    // 피드백만 쓰면 정상상태가 v x (f g)/(1-f+f g) = v x 0.325 라 매 틱 68% 씩 밀린다(표류 원인).
                    // 마찰은 접지 여부에 따라 다르다. 상판 위에 살짝 떠 있으면 공중 마찰(0.91) 이라
                    // 지상값(0.546)으로 고정하면 피드포워드가 5배 과해져 앞으로 밀려나간다.
                    double friction = player.onGround() ? 0.546 : 0.91;
                    double comp = 1.0 - friction;
                    double kx = this.velocity.x * comp;
                    double kz = this.velocity.z * comp;
                    if (!walking) {          // 걷는 중이면 실측 피드백이 본인 입력까지 상쇄하므로 보정은 생략
                        kx += (this.velocity.x - measured.x) * FEEDBACK_GAIN;
                        kz += (this.velocity.z - measured.z) * FEEDBACK_GAIN;
                    }
                    kx = clamp(kx, KNOCK_LIMIT);
                    kz = clamp(kz, KNOCK_LIMIT);

                    // 수직: 이번 틱에 gap 만큼 오르도록 속도를 준다(중력 0.08 + 항력 0.98 보상).
                    // 조금 넉넉히 줘서 항상 상판 위에 있게 하고, 착지 정밀도는 셜커 충돌에 맡긴다.
                    // 수직은 위치 패킷으로 간다. knockback 은 속도에 누적되는 성질이라
                    // 대각선 상승에서 게인을 아무리 조여도 결국 발산한다(탑승자가 쏘아 올려짐).
                    // 위치 패킷은 "이번 틱에 이만큼" 이라 누적이 원리적으로 불가능하다.
                    // X/Z 는 건드리지 않으므로 수평 입력을 뭉개지 않는다.
                    Vec3 knock = new Vec3(kx, 0.0, kz);
                    if (knock.lengthSqr() > 1.0E-8) {
                        player.connection.send(new ClientboundExplodePacket(
                                new Vec3(player.getX(), -30000.0, player.getZ()),   // 파티클/소리가 안 닿는 곳
                                Optional.of(knock), ParticleTypes.EXPLOSION, SILENT));
                        sent = true;
                    }

                    // 수직: 정확히 필요한 만큼만. 끌어올리기만 하고 아래로는 절대 밀지 않는다.
                    double moveY = (gap > 0.01 && gap < SNAP_BELOW + 3.0) ? gap : 0.0;
                    if (moveY != 0.0) {
                        player.connection.send(new ClientboundPlayerPositionPacket(CARRY_ID,
                                new PositionMoveRotation(new Vec3(0.0, moveY, 0.0), Vec3.ZERO, 0.0F, 0.0F), Relative.ALL));
                        sent = true;
                    }
                } else {
                    double threshold = lift > 0.0 ? 0.01 : MARGIN + 0.01;   // 상승 중엔 지체 없이 따라 올린다
                    double moveY = (gap > threshold && gap < SNAP_BELOW) ? gap : 0.0;
                    Vec3 nudge = new Vec3(this.velocity.x, moveY, this.velocity.z);
                    if (nudge.lengthSqr() > 1.0E-8) {
                        player.connection.send(new ClientboundPlayerPositionPacket(
                                CARRY_ID, new PositionMoveRotation(nudge, Vec3.ZERO, 0.0F, 0.0F), Relative.ALL));
                        sent = true;
                    }
                }

                recordTrace(player, gap, top);
                if (!sent) continue;
                carried++;

                if (this.debug) {
                    player.displayClientMessage(Component.literal(String.format(
                            "[상판] 간격 %.3f  오프셋 %.2f,%.2f  뗏목v %.2f",
                            gap, player.getX() - this.anchor.x, player.getZ() - this.anchor.z,
                            this.velocity.length())), true);
                }
            }

            if (this.debug && this.getFirstPassenger() instanceof ServerPlayer rider) {
                rider.displayClientMessage(Component.literal(String.format(
                        "속도 %.2f  renderY차 %.3f  캐리 %d명  타일 %d기",
                        this.velocity.length(), this.anchor.y - this.renderY, carried, this.decks.size())), true);
            }
        }

        /** 상판 아래로 빠지기 시작하는 순간 직전 8틱을 콘솔에 덤프한다. */
        private void recordTrace(ServerPlayer player, double gap, double top) {
            UUID id = player.getUUID();
            Deque<String> lines = this.trace.computeIfAbsent(id, k -> new ArrayDeque<>());
            lines.addLast(String.format(
                    "t=%d y=%.3f gap=%+.3f top=%.3f anchorY=%.3f renderY=%.3f v=(%.3f,%.3f,%.3f) off=(%.2f,%.2f)",
                    this.tickNo, player.getY(), gap, top, this.anchor.y, this.renderY,
                    this.velocity.x, this.velocity.y, this.velocity.z,
                    player.getX() - this.anchor.x, player.getZ() - this.anchor.z));
            while (lines.size() > 8) lines.removeFirst();

            if (gap > 0.5) {
                if (this.reported.add(id)) {
                    System.out.println("[GhastRaft] 이탈 감지: " + player.getScoreboardName());
                    for (String line : lines) System.out.println("[GhastRaft]   " + line);
                }
            } else if (gap < 0.1) {
                this.reported.remove(id);
            }
        }

        /** 팀 색을 선박 전체에 입힌다. 동기화 데이터라 tick 이 no-op 이어도 브로드캐스트된다. */
        public void setColor(org.bukkit.DyeColor color) {
            this.color = color;
            ((org.bukkit.entity.Shulker) this.getBukkitEntity()).setColor(color);
            for (Deck deck : this.decks) {
                if (!deck.isRemoved()) ((org.bukkit.entity.Shulker) deck.getBukkitEntity()).setColor(color);
            }
        }

        public double footprint() {
            return footprintOf(this.gridSize);
        }

        public Vec3 anchor() {
            return this.anchor == null ? this.position() : this.anchor;
        }

        /** 타일을 새 크기로 다시 깐다(크기 확장 업그레이드) */
        public void resize(int newGrid) {
            for (Deck deck : this.decks) {
                if (deck.mount != null) deck.mount.discard();
                deck.discard();
            }
            this.decks.clear();
            this.gridSize = Math.max(3, Math.min(8, newGrid));
            buildDecks((ServerLevel) this.level(), this, anchor(), this.gridSize);
            setColor(this.color);
        }

        public void discardAll() {
            ACTIVE.remove(this);
            for (Turrets.Turret turret : this.turrets) turret.discard();
            this.turrets.clear();
            for (Deck deck : this.decks) {
                if (deck.mount != null) deck.mount.discard();
                deck.discard();
            }
            this.decks.clear();
            if (this.mount != null) this.mount.discard();
            this.discard();
        }
    }

    /**
     * @param grid 한 변 타일 수(3~6). 기본 4 면 간격 3.0 = 타일 크기와 같아 빈틈 없이 맞물린다.
     */
    public static Pilot spawn(ServerLevel level, Vec3 at, int grid) {
        int n = Math.max(3, Math.min(8, grid));
        Pilot pilot = new Pilot(level);
        pilot.gridSize = n;
        buildDecks(level, pilot, at, n);

        Chassis pilotMount = new Chassis(level);
        pilotMount.setPos(at.x, at.y, at.z);
        dressMount(pilotMount);
        level.addFreshEntity(pilotMount);

        pilot.mount = pilotMount;
        pilot.setPos(at.x, at.y, at.z);      // 조종석은 격자 한가운데에 겹쳐 둔다(겹침은 무해)
        dress(pilot);
        level.addFreshEntity(pilot);
        pilot.startRiding(pilotMount, true);
        ACTIVE.add(pilot);
        return pilot;
    }

    /** 받침대 + 셜커 타일을 n x n 으로 깐다 */
    private static void buildDecks(ServerLevel level, Pilot pilot, Vec3 at, int n) {
        double step = TILE;
        double base = -(n - 1) / 2.0;
        for (int ix = 0; ix < n; ix++) {
            for (int iz = 0; iz < n; iz++) {
                double ox = (base + ix) * step;
                double oz = (base + iz) * step;

                Chassis chassis = new Chassis(level);
                chassis.offX = ox;
                chassis.offZ = oz;
                chassis.setPos(at.x + ox, at.y, at.z + oz);
                dressMount(chassis);
                level.addFreshEntity(chassis);

                Deck deck = new Deck(level);
                deck.owner = pilot;
                deck.mount = chassis;
                deck.setPos(at.x + ox, at.y, at.z + oz);
                dress(deck);                 // 속성은 반드시 addFreshEntity 전에
                level.addFreshEntity(deck);
                deck.startRiding(chassis, true);
                pilot.decks.add(deck);
            }
        }
    }

    private static void dressMount(Chassis chassis) {
        zeroRotation(chassis);
        chassis.setMarker(true);          // 크기 0, 충돌/히트박스 없음
        chassis.setInvisible(true);
        chassis.setNoGravity(true);
        chassis.setInvulnerable(true);
        chassis.setSilent(true);
        chassis.setRequiresPrecisePosition(true);
    }

    /**
     * Shulker#finalizeSpawn 이 setYRot(0) / yHeadRot = 0 을 하고 recreateFromPacket 이 yBodyRot 을
     * 0 으로 리셋하는데, addFreshEntity 로 직접 스폰하면 finalizeSpawn 을 거치지 않아 회전이 정규화되지
     * 않는다. 타일마다 yaw 가 미세하게 달라 보이던 원인이라 명시적으로 0 으로 맞춘다.
     */
    private static void zeroRotation(net.minecraft.world.entity.LivingEntity entity) {
        entity.setYRot(0.0F);
        entity.setXRot(0.0F);
        entity.setYHeadRot(0.0F);
        entity.setYBodyRot(0.0F);
        entity.setOldPosAndRot();
    }

    private static void dress(Shulker shulker) {
        zeroRotation(shulker);
        AttributeInstance scale = shulker.getAttribute(Attributes.SCALE);
        if (scale != null) scale.setBaseValue(TILE);   // 셜커는 3.0 이 상한(sanitizeScale)
        shulker.setNoAi(true);
        shulker.setNoGravity(true);
        shulker.setSilent(true);   // 무적은 걸지 않는다. 피해 이벤트를 받아 선체 체력으로 환산해야 한다.
        shulker.setPersistenceRequired();
        shulker.setRequiresPrecisePosition(true);
    }
}
