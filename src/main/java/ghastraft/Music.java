package ghastraft;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

/**
 * 경기 중 배경음악.
 *
 * 파오캐 배경음악.sk 와 같은 방식이다 - 곡마다 길이를 들고 있다가, 끝날 때가 되면
 * 다음 곡을 고른다. 다만 playsound 볼륨을 극단적으로 키우는 대신 Adventure 의
 * 비위치 사운드를 쓴다(듣는 사람 기준으로 재생되므로 거리와 무관하다).
 */
public final class Music {

    /** 곡 ID 와 길이(초). 리소스팩의 sounds.json 과 맞춰야 한다. */
    private record Track(String id, int seconds, String title) {
    }

    private static final List<Track> TRACKS = List.of(
            new Track("ship.bgm.1", 247, "1"),
            new Track("ship.bgm.4", 226, "4"));

    private static final Random RNG = new Random();
    /**
     * 볼륨을 극단적으로 키워 거리 감쇠를 무력화한다(= 어디서든 같은 크기로 들린다).
     * 배경음악.sk 가 playsound 볼륨 1000000000 을 쓰던 것과 같은 방식이다.
     * Adventure 의 Sound 객체로는 재생되지 않아 /playsound 와 동일한 경로를 쓴다.
     */
    private static final float VOLUME = 1_000_000.0F;
    private static final SoundCategory CATEGORY = SoundCategory.VOICE;

    private final List<String> recent = new ArrayList<>();
    private Track current;
    private int secondsLeft;

    /** 재생 로그용 - 플러그인이 주입한다 */
    public java.util.function.Consumer<String> log = msg -> { };

    /** 1초마다 호출 */
    public void tick(Collection<? extends Player> listeners) {
        if (this.current != null && --this.secondsLeft > 0) return;
        play(pick(), listeners);
    }

    private Track pick() {
        if (this.recent.size() >= TRACKS.size()) this.recent.clear();
        Track track;
        do {
            track = TRACKS.get(RNG.nextInt(TRACKS.size()));
        } while (TRACKS.size() > 1 && this.recent.contains(track.id()));
        this.recent.add(track.id());
        return track;
    }

    private void play(Track track, Collection<? extends Player> listeners) {
        stop(listeners);
        for (Player player : listeners) command(player, "playsound", track.id());
        this.current = track;
        this.secondsLeft = track.seconds();
        this.log.accept("BGM 재생: " + track.id() + " (" + track.seconds() + "초, 대상 "
                + listeners.size() + "명)");
    }

    /** 재생 중인 곡을 끊는다 */
    public void stop(Collection<? extends Player> listeners) {
        if (this.current == null) return;
        for (Player player : listeners) command(player, "stopsound", this.current.id());
        this.current = null;
        this.secondsLeft = 0;
    }

    /** 경기 도중 접속한 사람에게도 현재 곡을 들려준다 */
    public void resume(Player player) {
        if (this.current == null) return;
        command(player, "playsound", this.current.id());
    }

    /**
     * 콘솔 명령으로 재생한다.
     * CraftPlayer#playSound(Location, String, ...) 로는 커스텀 사운드가 울리지 않는데
     * 같은 ID 를 /playsound 로 치면 정상적으로 들린다. 참고 스크립트(배경음악.sk)도
     * 같은 이유로 콘솔 명령을 쓴다 - 확실히 동작하는 경로를 그대로 따른다.
     */
    private static void command(Player player, String verb, String sound) {
        org.bukkit.Location at = player.getLocation();
        String cmd = verb.equals("playsound")
                ? String.format(java.util.Locale.ROOT, "playsound %s voice %s %.2f %.2f %.2f %.0f 1",
                        sound, player.getName(), at.getX(), at.getY(), at.getZ(), VOLUME)
                : String.format("stopsound %s voice %s", player.getName(), sound);
        org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), cmd);
    }

    /** 테스트용 - 경기와 무관하게 즉시 재생 */
    public void force(Collection<? extends Player> listeners) {
        play(pick(), listeners);
    }

    public boolean isPlaying() {
        return this.current != null;
    }
}
