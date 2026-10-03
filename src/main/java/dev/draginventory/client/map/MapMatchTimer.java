package dev.draginventory.client.map;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对局倒计时（v2.5.5 从静态占位升级为真实倒计时）。
 *
 * <p>三层优先级：</p>
 * <ol>
 *   <li><b>Provider</b>（最高）：对局系统（如 GWO 服务器下发真实剩余时间）实现
 *       {@link Provider} 并 {@link #register} 后，地图/小地图自动改用真实值，
 *       无需改任何渲染代码；</li>
 *   <li><b>手动倒计时</b>：{@code /map timer <时长>} 立刻开始倒计时（deadline 时间戳制，
 *       帧率无关、暂停菜单也不停表，符合对局计时的真实语义）；</li>
 *   <li><b>自动开始</b>：进入世界时按默认时长（{@code general.match_timer_seconds}，
 *       {@code /map timer default <时长>} 可改）自动开始——这是无 Provider 时的常态。</li>
 * </ol>
 *
 * <p>小地图 HUD（{@code FactoryMinimapHud}）每帧经 {@link #remainingSeconds} 读取：
 * 返回 -1（{@code /map timer off} 且未注册 Provider）时整行隐藏；归零后显示
 * "对局已结束"。本类为纯 JVM 类（无 Minecraft 依赖，{@code System.currentTimeMillis()}
 * 与 {@code Util.getMillis()} 同源），可被单元测试直接覆盖。</p>
 *
 * <p>v2.5.7 联动扩展：{@link #addSeconds}（加时/减时）、{@link #deadlineMillis}
 * （截止时刻对表）、{@link #hasProvider}（自省）、{@link #setEndListener}
 * （"对局结束"回调，沿检测由 {@link #tickEndDetection} 每客户端 tick 驱动）。</p>
 *
 * <p>v2.6.1：{@link #endedAtMillis}（归零时刻）——小地图倒计时行归零后的结束
 * 字样仅停留约 2.5 秒即整行消失；该时刻由结束沿检测顺带记录，停止（off）与
 * 重新开始时复位。</p>
 */
public final class MapMatchTimer {
    /** 对局系统实现此接口提供剩余秒数；注册后地图/小地图自动改用真实值。 */
    public interface Provider { long remainingSeconds(); }

    /** 默认对局时长（10 分钟）：进入世界自动按此时长开始倒计时；与旧版占位显示值一致。 */
    public static final long DEFAULT_DURATION_SECONDS = 600L;
    /** 时长上限（24 小时）：解析与配置范围共用的硬顶。 */
    public static final long MAX_DURATION_SECONDS = 86_400L;

    /** 后缀式时长：1h30m / 90s / 2h（大小写不敏感；三组任选但至少一组）。 */
    private static final Pattern SUFFIX = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$");

    private static volatile Provider provider;
    private static volatile long defaultDurationSeconds = DEFAULT_DURATION_SECONDS;
    /** 倒计时截止时刻（epoch 毫秒）；running=false 时不参与计算。 */
    private static volatile long deadlineMillis;
    private static volatile boolean running;

    private MapMatchTimer() {}

    /** 注册提供者（对局系统启动时调用一次；传 null 注销，回到手动倒计时模式）。 */
    public static void register(Provider p) { provider = p; }

    /** 当前默认对局时长（秒）。 */
    public static long defaultDuration() { return defaultDurationSeconds; }

    /** 设置默认对局时长（秒，夹进 [1, 24h]）；进入世界时按此时长自动开始倒计时。 */
    public static void setDefaultDuration(long seconds) {
        defaultDurationSeconds = Math.max(1L, Math.min(MAX_DURATION_SECONDS, seconds));
    }

    /** 手动开始倒计时（秒）。 */
    public static void start(long seconds) {
        deadlineMillis = System.currentTimeMillis()
                + Math.max(1L, Math.min(MAX_DURATION_SECONDS, seconds)) * 1000L;
        running = true;
    }

    /** 按默认时长重新开始倒计时（/map timer reset 与进入世界时调用）。 */
    public static void restartDefault() { start(defaultDurationSeconds); }

    /** 停止倒计时（/map timer off 与退出世界时调用）：小地图计时行整行隐藏；
     * 归零时刻一并复位（隐藏不是结束，下次重新开始时重新走停留计时）。 */
    public static void stop() {
        running = false;
        endedAtMillis = 0L;
    }

    /** 是否正在手动倒计时（Provider 注册期间此值不影响显示）。 */
    public static boolean isRunning() { return running; }

    // ==================== v2.5.7 联动扩展：加时 / 截止时刻 / 结束回调 ====================

    /**
     * 加时/减时（秒，可负）：手动倒计时进行中时平移截止时刻；减时越过当前剩余则立即
     * 归零（结束回调由 tick 检测在 &le;1 拍内触发）。未运行或 Provider 注册期间为
     * 无操作——Provider 模式下时间归对局系统所有（如需加减时请在对局系统侧调整后
     * 由 Provider 下发新值）。
     */
    public static void addSeconds(long seconds) {
        if (!running || provider != null || seconds == 0) return;
        long shifted = deadlineMillis + seconds * 1000L;
        deadlineMillis = Math.max(System.currentTimeMillis(), shifted);
    }

    /** 手动倒计时的截止时刻（epoch 毫秒）；未运行或 Provider 模式为 0（供外部系统对表/显示）。 */
    public static long deadlineMillis() { return running && provider == null ? deadlineMillis : 0L; }

    /** 是否已注册对局系统 Provider（外部系统自省用）。 */
    public static boolean hasProvider() { return provider != null; }

    /**
     * "对局结束"回调（v2.5.7）：剩余时间从 &gt; 0 首次到达 0 时触发一次（手动与 Provider
     * 两种模式均覆盖）；重新开始倒计时后可再次触发；停止（off）不触发（隐藏不是结束）。
     * 由客户端 tick 驱动检测（{@link #tickEndDetection}），最大延迟约 1 拍；传 null 注销。
     */
    public static void setEndListener(Runnable listener) { endListener = listener; }

    /** 结束沿判定（纯函数，供 tick 驱动与单测）：上一拍剩余 &gt; 0 且本拍剩余恰为 0。 */
    public static boolean endedBetween(long lastRemaining, long nowRemaining) {
        return lastRemaining > 0 && nowRemaining == 0;
    }

    /** 每客户端 tick 调用（MapClientEvents 接线）：沿检测 + 回调触发 + 归零时刻维护
     * （v2.6.1：结束沿时记录 endedAtMillis；恢复 >0 时复位）；包内可见。 */
    static void tickEndDetection() {
        long now = remainingSeconds();
        if (endedBetween(lastSeenRemaining, now)) {
            endedAtMillis = System.currentTimeMillis();
            Runnable listener = endListener;
            if (listener != null) listener.run();
        }
        if (now > 0) endedAtMillis = 0L; // 重新开始/加时回到计时态：归零时刻复位
        lastSeenRemaining = now;
    }

    private static volatile Runnable endListener;
    private static long lastSeenRemaining = -1L;
    /** 归零时刻（epoch 毫秒）：结束沿触发时记录，恢复计时或 stop 时复位；0 = 当前不在归零停留态。 */
    private static volatile long endedAtMillis;

    /**
     * 归零时刻（epoch 毫秒，v2.6.1）：剩余时间从 &gt; 0 首次到达 0 的时刻，供展示层
     * 实现结束字样停留后消失（System.currentTimeMillis 与 Util.getMillis 同源，可直接
     * 相减）。0 = 当前不在归零态（未开始 / 进行中 / 已停止；或 Provider 注册即返回 0
     * 的保守回退——沿检测从未观察到 &gt;0 到 0 的转变，此时展示层可自行选择继续显示）。
     */
    public static long endedAtMillis() { return endedAtMillis; }

    /**
     * 剩余秒数：已注册 Provider 时恒取其值并夹到 &ge;0（最高优先级）；
     * 否则手动倒计时返回向上取整的剩余秒数（夹到 &ge;0，最后一秒完整走完才归零）；
     * 未开始 / 已停止返回 -1（调用方据此隐藏计时行）。
     */
    public static long remainingSeconds() {
        Provider p = provider;
        if (p != null) return Math.max(0L, p.remainingSeconds());
        if (!running) return -1L;
        long ms = deadlineMillis - System.currentTimeMillis();
        return Math.max(0L, (ms + 999L) / 1000L);
    }

    /** 当前剩余时间的 "m:ss" 文本（分钟可超过 59，如 61:05；未运行时为 "0:00"，展示层应先判 -1）。 */
    public static String format() {
        return formatSeconds(Math.max(0L, remainingSeconds()));
    }

    /** 秒数 &rarr; "m:ss"（30&rarr;0:30、1500&rarr;25:00、3665&rarr;61:05；超一小时不进位 h:mm:ss，沿用旧口径）。 */
    public static String formatSeconds(long seconds) {
        long s = Math.max(0L, seconds);
        return s / 60 + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /**
     * 解析时长文本为秒数，供 /map timer 与 /map timer default 使用。三种写法：
     * <ul>
     *   <li>冒号式：{@code 25:00}（25 分）、{@code 1:30:00}（1 时 30 分）、{@code 0:30}（30 秒），
     *       1~3 段数字、各段 0~9999；</li>
     *   <li>后缀式：{@code 30m}、{@code 90s}、{@code 2h}、{@code 1h30m}、{@code 1h30m15s}
     *       （大小写不敏感，至少一段）；</li>
     *   <li>纯数字：<b>分钟</b>（{@code 25} = 25 分钟——对局时长语境下最自然的输入）。</li>
     * </ul>
     * 总时长必须落在 (0, 24h]，否则抛 {@link IllegalArgumentException}（由指令层转为
     * 带示例的失败反馈，不静默回退）。
     */
    public static long parseSeconds(String input) {
        if (input == null) throw new IllegalArgumentException("empty duration");
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) throw new IllegalArgumentException("empty duration");

        // 纯数字：分钟。
        if (s.matches("\\d+")) return checked(parseLong(s) * 60L);
        // 冒号式：1~3 段，从左到右为（时）分秒的六十进制累加。
        if (s.matches("\\d{1,4}(:\\d{1,4}){0,2}")) {
            long total = 0;
            for (String part : s.split(":")) total = total * 60 + parseLong(part);
            return checked(total);
        }
        // 后缀式：h/m/s 组合。
        Matcher m = SUFFIX.matcher(s);
        if (m.matches() && (m.group(1) != null || m.group(2) != null || m.group(3) != null)) {
            long total = 0;
            if (m.group(1) != null) total += parseLong(m.group(1)) * 3600L;
            if (m.group(2) != null) total += parseLong(m.group(2)) * 60L;
            if (m.group(3) != null) total += parseLong(m.group(3));
            return checked(total);
        }
        throw new IllegalArgumentException("unrecognized duration: " + input);
    }

    private static long parseLong(String digits) { return Long.parseLong(digits); }

    private static long checked(long seconds) {
        if (seconds <= 0L) throw new IllegalArgumentException("duration must be positive");
        if (seconds > MAX_DURATION_SECONDS) throw new IllegalArgumentException("duration exceeds 24h");
        return seconds;
    }
}
