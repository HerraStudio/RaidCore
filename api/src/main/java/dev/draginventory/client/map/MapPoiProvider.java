package dev.draginventory.client.map;

/**
 * 对局系统（撤离点/首领/事件等预设图标）的注册接口。
 *
 * <p>地图左面板与主视图已预留渲染位，等对局系统上线后实现此接口并注册即可接入，
 * 无需改地图代码。本期（阶段5）仅预留接口：实现方通过 {@link #register} 挂入，
 * 地图侧每帧经 {@link #providers} 拉取快照，按 {@link Poi#name()} 取语言键
 * {@code draginventory.map.poi.<name>}。</p>
 */
public interface MapPoiProvider {
    /** 图标种类（对局系统定义，地图按 name 取语言键 draginventory.map.poi.<name>）。 */
    record Poi(String id, String name, double x, double y, double z, int color) {}

    /** 每帧快照；返回空列表表示无（当前恒为空——对局系统未实装）。 */
    java.util.List<Poi> pois();

    /** 注册（静态注册表，CopyOnWriteArrayList）；供对局系统调用。 */
    static void register(MapPoiProvider provider) { Registry.PROVIDERS.add(provider); }

    /** 注销（v2.5.7：对局系统关闭/重载时调用；幂等）。 */
    static void unregister(MapPoiProvider provider) { Registry.PROVIDERS.remove(provider); }

    static java.util.List<MapPoiProvider> providers() { return java.util.List.copyOf(Registry.PROVIDERS); }

    final class Registry {
        private static final java.util.List<MapPoiProvider> PROVIDERS = new java.util.concurrent.CopyOnWriteArrayList<>();
        private Registry() {}
    }
}
