# 战术地图 API（联动接口文档）

> 模组：Drag Inventory · Minecraft 1.21.1 · NeoForge 21.1.251
> 自 v2.5.7 起提供公开联动接口 · 统一入口：`dev.draginventory.client.map.TacticalMapApi`

面向**对局系统 / 联动模组开发者**的接口手册：把你的系统的标点、对局剩余时间、POI 图标接入战术地图（大地图 / 小地图 / 世界 HUD / 方位条），并接收玩家的标点事件与"对局结束"回调。玩家侧使用手册见 [战术地图](战术地图.md)。

**设计原则**：单一门面（`TacticalMapApi` 一个 import 解决全部联动）、渲染零耦合（你的系统只提供/读取数据，四个视图自动同步，一行渲染代码都不用写）、默认值兜底（所有 API 对非法输入静默拒绝而非崩溃）、事件异常隔离（联动方抛异常拖不垮标点系统与其他监听器）。

---

## 目录

1. [快速接入](#快速接入)
2. [标点联动（重点）](#标点联动重点)
3. [对局结束倒计时（重点）](#对局结束倒计时重点)
4. [POI 图标注册](#poi-图标注册)
5. [地图控制](#地图控制)
6. [其他接口说明](#其他接口说明)
7. [完整示例：对局系统联动模组骨架](#完整示例对局系统联动模组骨架)
8. [线程约定](#线程约定)
9. [兼容性与版本政策](#兼容性与版本政策)
10. [版本历史](#版本历史-1)

---

## 快速接入

依赖本模组（`compileOnly` 本模组 jar，运行时作为前置 `required-after` 或软依赖）。两个最小示例：

**① 放一枚会自动过期、四视图可见的敌情标点：**

```java
import dev.draginventory.client.map.TacticalMapApi;
import dev.draginventory.client.TacticalMarker;
import net.minecraft.world.phys.Vec3;

// 5 分钟存活；同 ID 重复调用 = 原地更新（位置/类型/时长刷新）
TacticalMapApi.placeExternalMarker("gwo:boss_alert",
        TacticalMarker.Type.ENEMY, new Vec3(120.5, 64, -88.5), 5 * 60 * 1000L);
```

**② 接管对局剩余时间（此后小地图/大地图倒计时恒显示你的真实值）：**

```java
// 你的对局系统里维护剩余秒数，每帧/每 tick 由 Provider 下发
TacticalMapApi.registerTimerProvider(() -> matchState.remainingSeconds());

// 对局结束瞬间（剩余从 > 0 首次到达 0）收到回调
TacticalMapApi.setCountdownEndListener(() -> showMatchResultScreen());
```

以上两段代码运行后：标点出现在大地图（右键可放的同类标点样式）、小地图（5×5 色块）、世界 HUD（3D 悬浮 + 边缘指示）与方位条（罗盘刻度标记）；倒计时以你的值为准并在归零时回调——**不需要接触任何渲染代码**。

---

## 标点联动（重点）

### 数据模型

| 类型 | 说明 |
| --- | --- |
| `TacticalMarker.Type.LOCATION` | 地点标记（蓝系） |
| `TacticalMarker.Type.ENEMY` | 敌情标记（红系） |
| `TacticalMarker.Type.ITEM` | 物资标记（黄系；外部标点无实体绑定，按位置渲染） |

`TacticalMarker` 是不可变 record：`type / position(Vec3) / target(Entity, 外部标点恒 null) / createdAt(毫秒) / ttlMs(存活时长)`。判定语义：`now - createdAt >= ttlMs` 即过期自动移除。

### 两池模型（先读这个再选 API）

|  | 玩家手动池 | 外部联动池（v2.5.7） |
| --- | --- | --- |
| 写入 API | 玩家中键 / 大地图右键 / `TacticalMapApi.placeMarker` | `TacticalMapApi.placeExternalMarker` |
| 名额 | 5 个 FIFO（最旧挤掉） | **独立 16 个**（互不挤占；超量逐出最旧外部标点） |
| 去重键 | type + 方块坐标（同目标刷新） | **你指定的稳定 ID**（同 ID 原地更新） |
| 生命周期 | 固定 60 秒 | **自定义 TTL**（≤0 按 60 秒；上限 24 小时） |
| 世界作用域 | 换世界清除 | 换世界清除（收到 CLEARED 事件，应重放） |
| 渲染视图 | 大地图 / 小地图 / 世界 HUD / 方位条 | **完全相同（同一合并视图）** |

> **为什么联动系统应该用外部池**：不占玩家 5 个手动名额（玩家标点被系统标点挤掉是事故）；ID 幂等更新适合网络同步（服务端重发同一事件不会翻倍）；TTL 可控（撤离点可以存活整局，短暂威胁可以 30 秒）。

### 外部标点 API

```java
// 放置 / 更新（幂等）：id 为你系统内的稳定标识，建议加命名空间前缀
TacticalMapApi.placeExternalMarker(String id, TacticalMarker.Type type, Vec3 position, long ttlMs);

// 主动撤销一枚（不存在返回 false，不触发事件）
boolean removed = TacticalMapApi.removeExternalMarker("gwo:boss_alert");

// 清空全部外部标点（玩家手动标点不动）；返回清除数量，逐枚触发 CLEARED 事件
int cleared = TacticalMapApi.clearExternalMarkers();
```

**`placeExternalMarker` 行为细则**：

- `id` 为 null/空白、`type`/`position` 为 null → 静默拒绝（不崩溃、不落点）；
- 世界未加载（主菜单/未进世界）→ 拒绝落点（与玩家标点同口径，防"幽灵标点"）；
- **同 ID 再调用 = 原地更新**：位置/类型/TTL/createdAt 全部刷新，条目数不变，触发一次 `ADDED` 事件；更新永不逐出自己（瞬时容量 +1 由后续放置回收）；
- TTL ≤ 0 → 按 60 秒；TTL 上限 24 小时（与对局计时同口径）；
- 容量超 16 → 按插入序逐出**最旧的外部标点**（逐枚触发 `EXPIRED`），玩家手动标点绝不受影响。

### 玩家口径放置 / 读取 / 清除

```java
// 与大地图右键完全同一存储：共享 5 名额 FIFO、60 秒 TTL、同 type+方块去重
TacticalMapApi.placeMarker(TacticalMarker.Type.LOCATION, position);

// 只读快照（玩家手动在前 + 外部在后；每次调用返回新 List，可随意持有/遍历）
List<TacticalMarker> all = TacticalMapApi.markers();

// 清除：clearMarkers 与 C 键同口径（全部两池）；clearLocationMarkers 仅玩家地点标点
TacticalMapApi.clearMarkers();
TacticalMapApi.clearLocationMarkers();
```

### 事件监听（联动核心）

```java
import dev.draginventory.client.TacticalMarkerListener;

TacticalMarkerListener listener = (cause, marker) -> {
    // 例如：玩家在地图上放了敌情标点 → 转发给队友（团队同步场景）
    if (cause == TacticalMarkerListener.Cause.ADDED
            && marker.type() == TacticalMarker.Type.ENEMY) {
        syncToTeammates(marker.position());
    }
};
TacticalMapApi.addMarkerListener(listener);     // 任意线程可注册
TacticalMapApi.removeMarkerListener(listener);  // 幂等注销
```

**四因语义与触发时机**：

| Cause | 触发时机 | 典型用途 |
| --- | --- | --- |
| `ADDED` | 玩家中键/地图右键/世界 HUD 双击升级/外部放置或更新，每枚新写入一次 | 转发玩家标点给队友 |
| `REMOVED` | 仅 `removeExternalMarker` 主动撤销成功 | 同步撤销给队友 |
| `EXPIRED` | TTL 到点（约 1 帧内）/ 外部容量逐出 | 本地 UI 清理 |
| `CLEARED` | C 键 / 弹窗按钮 / `clearMarkers` / `clearExternalMarkers` / 换世界，逐枚触发 | 换世界后重放标点 |

**约束**（务必遵守）：

- 回调在**客户端线程**触发（渲染或 tick 路径中）——回调内**不要同步调用标点写入 API**（place/remove/clear 族有并发修改风险），需要写入请 `Minecraft.getInstance().execute(() -> ...)` 转下一拍；
- 回调抛出的异常被逐监听器隔离（不影响标点系统本体与其他监听器），但请自行避免；
- 事件负载只有 `TacticalMarker`（不含外部 ID）——ID 与事件的对应关系由你的系统自行维护（放置时已知 ID，收到事件按快照比对即可）。

### 渲染联动拓扑（为什么"放一次、四处可见"）

```
        TacticalMarkerManager（单一数据源）
        ├─ 玩家手动池（5 FIFO）   ┐
        └─ 外部联动池（16 ID 键控）┴─ allMarkers() 合并视图
                │
    ┌───────────┼───────────┬─────────────┐
    ▼           ▼           ▼             ▼
 大地图      小地图      世界 HUD       方位条
(FactoryMapScreen)(FactoryMinimapHud)(TacticalMarkerHud)(CompassMarkerBridge)
```

任何写入路径（玩家操作 / 你的 API 调用）下一帧即同步到全部四个视图；楼层过滤（±3）、样式（tactical/minimal）、显示开关等渲染策略对两池一视同仁。

---

## 对局结束倒计时（重点）

### 三层优先级模型

```
① Provider（对局系统注册，最高）──恒取其剩余秒数（渲染层零改动自动接管）
② 手动倒计时（/map timer 或 API）──deadline 时间戳制，帧率无关、暂停菜单不停表
③ 自动开始（进世界按默认时长）──无 Provider 时的常态
```

读取口径恒为 `remainingSeconds()`：**Provider 注册期间恒取 Provider 值（夹到 ≥0）**；否则手动倒计时向上取整；未开始/已停止返回 **-1**（小地图计时行整行隐藏）。

### Provider 接入（对局系统首选）

```java
// 注册即接管（对局系统启动时调用一次）
TacticalMapApi.registerTimerProvider(new MapMatchTimer.Provider() {
    @Override public long remainingSeconds() {
        return gwoMatchState == null ? -1 : gwoMatchState.remainingSeconds();
    }
});

// 对局结束/退出时注销，回到玩家手动模式
TacticalMapApi.registerTimerProvider(null);
```

- Provider 由客户端每帧调用（渲染读值）+ 每客户端 tick 调用（结束沿检测）——实现应**无副作用、轻量**（查一个 volatile 字段为宜）；
- 返回负数会被夹到 0（显示"对局已结束"）；对局未开始时建议返回 -1（整行隐藏）或保持注册并返回正数；
- **Provider 模式下时间归你的系统所有**：`addCountdownSeconds` 无效（要加减时请在你的对局状态里改完由 Provider 自然下发新值）；`/map timer` 指令仍可执行但显示不变（以 Provider 为准）。

### 手动控制 API（无 Provider 时）

| API | 语义 | 对应指令 |
| --- | --- | --- |
| `startCountdown(seconds)` | 立刻开始（夹进 [1, 24h]） | `/map timer 25:00` |
| `addCountdownSeconds(+30)` | 加时 30 秒（平移 deadline） | —（指令无此项） |
| `addCountdownSeconds(-60)` | 减时 60 秒（越过剩余立即归零→触发结束回调） | — |
| `stopCountdown()` | 停止（整行隐藏） | `/map timer off` |
| `restartDefault` 语义 | 按默认时长重开 | `/map timer reset`（门面未暴露，走 `startCountdown(defaultDuration)`） |

手动倒计时为 **deadline 时间戳制**：暂停菜单、掉帧、切后台都不影响走表——符合对局计时的真实语义。

### "对局结束"回调

```java
TacticalMapApi.setCountdownEndListener(() -> {
    // 剩余时间从 > 0 首次到达 0 的那一拍触发（手动与 Provider 模式均覆盖）
});
```

| 场景 | 是否触发 |
| --- | --- |
| 倒计时正常走完归零 | ✅ 触发一次 |
| Provider 值从正数变为 0 | ✅ 触发一次 |
| `addCountdownSeconds` 减时越界立即归零 | ✅ 触发一次（≤1 拍延迟） |
| 停止（`stopCountdown` / `/map timer off`） | ❌ 隐藏不是结束 |
| 归零后持续为 0（重复零位） | ❌ 不重复触发 |
| 重新开始后再次走完 | ✅ 可再次触发 |
| 从未开始就为 0 | ❌ 不触发 |

回调由客户端 tick 驱动检测（`MapClientEvents` 每拍调用 `tickEndDetection`），**最大延迟约 1 拍（50ms）**；回调内同样不要同步调用重度的标点/地图 API（转 `mc.execute` 下一拍）。传 `null` 注销。沿判定本体是纯函数 `MapMatchTimer.endedBetween(last, now)`（仅 `>0 → 0` 触发），有单测覆盖。

### 读取 API

```java
long remaining = TacticalMapApi.remainingSeconds();   // -1 = 未运行且无 Provider（隐藏）
String text = TacticalMapApi.formattedRemaining();    // "24:37"（m:ss，分钟可 >59）
// 截止时刻（对表用，门面未暴露——直连计时本体）：
long deadline = dev.draginventory.client.map.MapMatchTimer.deadlineMillis(); // 手动模式 epoch 毫秒，未运行为 0
```

- `MapMatchTimer.deadlineMillis()`（门面外直连或经 `dev.draginventory.client.map.MapMatchTimer`）：手动倒计时的截止 epoch 毫秒，供你的系统**对表**（把你的结束时刻与本模组对齐显示）；未运行或 Provider 模式为 0；
- `MapMatchTimer.hasProvider()`：是否已有系统注册 Provider（多系统协商时先自省，避免互相覆盖）；
- 默认时长（进世界自动开始的值）：`/map timer default <时长>` 或配置 `general.match_timer_seconds`（600 秒默认）。

### HUD 显示行为（零耦合承诺）

倒计时渲染（加粗、金色醒目、末 60 秒红闪、秒位脉冲、冒号呼吸、归零"对局已结束"）完全由本模组渲染层负责，**你的系统只供数**——换任何显示风格都不需要联动方改代码。小地图隐藏条件：`remainingSeconds() == -1`（未运行且无 Provider）或小地图整体被关（`/map minimap off`）。

---

## POI 图标注册

对局系统的预设图标（撤离点/首领/事件点）经 `MapPoiProvider` 接入——地图左面板与主视图已预留渲染位：

```java
import dev.draginventory.client.map.MapPoiProvider;

MapPoiProvider provider = () -> List.of(
        new MapPoiProvider.Poi("extract_north", "extract", 100.5, 70, -200.5, 0xFF35D8A0));

TacticalMapApi.registerPoiProvider(provider);    // 注册（CopyOnWrite，任意线程）
TacticalMapApi.unregisterPoiProvider(provider);  // 注销（对局结束/模组重载时，幂等）
```

**`Poi` record 字段**：`id`（稳定标识）/ `name`（图标种类名，地图按 `draginventory.map.poi.<name>` 取本地化键）/ `x, y, z`（世界坐标）/ `color`（ARGB 强调色）。

**契约**：`pois()` 每帧被拉取快照——实现必须**轻量、无副作用、尽量零分配**（无 POI 时返回 `List.of()`）；语言键需由你的模组或资源包提供（本模组不代发）。当前渲染位已预留（列表为空时零开销），图标随对局系统上线逐步点亮。

---

## 地图控制

```java
TacticalMapApi.openMap();          // 打开大地图（与 M 键 / /map 同入口；内部转主线程，线程安全）
TacticalMapApi.openMapSettings();  // 打开设置界面（与 /map gui 同入口）
boolean open = TacticalMapApi.isMapOpen();  // 大地图是否打开（客户端线程调用）
```

典型用途：对局开局时自动打开大地图展示本局 POI；`isMapOpen` 决定你的 overlay 是否避让地图界面。

---

## 其他接口说明

### `/map` 指令（脚本化控制面）

全部地图行为可经指令驱动（tab 补全、非法值报错列合法值、纯客户端无需权限）——适合整合包脚本、地图作者与快速验证。全集见 [战术地图·指令参考](战术地图.md#指令参考)，速查：

| 指令 | 用途 |
| --- | --- |
| `/map timer <25:00/30m/90s/纯数字=分钟>` | 对局对表（三种写法） |
| `/map timer default <时长>` / `reset` / `off` | 默认时长 / 重置 / 关闭 |
| `/map marker ping <location/enemy/item>` | 右键放置类型 |
| `/map marker style <tactical/minimal>` / `show <on/off>` | 标点风格 / 显示 |
| `/map filter <none/delta/tarkov/darkzone/apex/pubg>` | 滤镜预设 |
| `/map minimap <on/off/zoom/size>`、`/map zoom min/max` | 小地图 / 缩放范围 |
| `/map info` | 十项状态汇总 |

### MapConfig（配置直读写，进阶）

`config/draginventory-map-client.toml` 的全部键经 `MapConfig` 暴露（`ModConfigSpec` 值对象）：指令与设置 GUI 的修改经 500ms 防抖落盘、外部编辑由 NeoForge 自动热重载。联动系统**读**配置（如判断标点是否显示 `markers.marker_show`）可直接 `MapConfig.MARKERS_VISIBLE.get()`；**写**建议走指令或设置 GUI 同口径（防抖落盘逻辑一致），避免绕过写盘协调。

### 内部类（不承诺兼容，不建议直连）

| 类 | 角色 | 说明 |
| --- | --- | --- |
| `FactoryMapScreen` / `FactoryMapSession` | 大地图屏与会话 | 生命周期由本模组管理 |
| `FactoryMinimapHud` / `FactoryMapTiles` / `FactoryMapSampler` | 小地图与瓦片引擎 | 纹理/缓存内部实现 |
| `PlayerMarkerFX` | 玩家图标动效状态机（纯 JVM 可测） | 参数（拉伸/尾迹/呼吸）集中在其常量区 |
| `MapCoordinateTransform` / `FactoryMapView` | 坐标变换 / 视图抽象 | 内部工具 |
| `TacticalMarkerManager` | 标点存储本体 | 门面为薄委托，直连等名静态方法语义一致（高级用法可用） |
| `TacticalMarkerHud` / `CompassMarkerBridge` | 世界 HUD / 方位条桥接 | 只读消费合并视图 |
| `MapMatchTimer` | 计时本体 | 门面直连等名方法语义一致；`endedBetween`/`tickEndDetection` 为包内实现细节 |

> 稳定面 = `TacticalMapApi` + `MapMatchTimer.Provider` + `MapPoiProvider` + `TacticalMarker`（含 Type）+ `TacticalMarkerListener`（见[兼容性与版本政策](#兼容性与版本政策)）。

---

## 完整示例：对局系统联动模组骨架

一个假想的 GWO 对局联动模组（演示全部四组能力协同）：

```java
@Mod("gwomaplink")
public class GwoMapLinkMod {
    public void onClientSetup(FMLClientSetupEvent event) {
        // ① 对局时间 Provider：对局中恒发真实值，大厅发 -1（整行隐藏）
        TacticalMapApi.registerTimerProvider(() ->
                GwoClientState.inMatch() ? GwoClientState.remainingSeconds() : -1L);

        // ② 对局结束回调：弹出本模组的结算界面
        TacticalMapApi.setCountdownEndListener(() ->
                Minecraft.getInstance().execute(GwoMapLinkMod::showMatchResult));

        // ③ POI：撤离点/首领图标（每帧快照，无 POI 时零分配）
        TacticalMapApi.registerPoiProvider(() -> GwoClientState.inMatch()
                ? GwoClientState.pois().stream()
                        .map(p -> new MapPoiProvider.Poi(p.id(), p.kind(), p.x(), p.y(), p.z(), p.color()))
                        .toList()
                : List.of());

        // ④ 事件转发：玩家的敌情标点同步给队友
        TacticalMapApi.addMarkerListener((cause, marker) -> {
            if (cause == TacticalMarkerListener.Cause.ADDED
                    && marker.type() == TacticalMarker.Type.ENEMY) {
                GwoClientState.sendTeamPing(marker.position());  // 网络层自行实现
            }
        });
    }

    public static void onMatchStart(GwoMatch match) {
        // 对局开始：放本局标点（幂等，服务端重发不翻倍）
        TacticalMapApi.placeExternalMarker("gwo:extract_north",
                TacticalMarker.Type.LOCATION, match.extractNorth(), 24 * 3600_000L);
        TacticalMapApi.placeExternalMarker("gwo:boss",
                TacticalMarker.Type.ENEMY, match.bossSpawn(), 30_000L);
        // 自动打开大地图看一眼本局布局
        TacticalMapApi.openMap();
    }

    public static void onMatchEnd() {
        TacticalMapApi.clearExternalMarkers();  // 清本局标点（玩家手动标点不动）
    }
}
```

---

## 线程约定

| API | 调用线程 |
| --- | --- |
| 标点写入/移除/清除、`markers()`、地图控制 | **客户端线程**（主线程网络包处理器天然满足；否则 `mc.execute` 转入） |
| 监听器注册/注销、计时 API（Provider 注册/加减时/结束回调设置） | 任意线程（volatile / CopyOnWrite） |
| 事件回调、结束回调 | 客户端线程（回调内重操作请转 `mc.execute` 下一拍） |

---

## 兼容性与版本政策

- **稳定公开面**（2.x 系列跨小版本不破坏）：`TacticalMapApi` 全部方法、`MapMatchTimer.Provider`、`MapPoiProvider`（含 `Poi`）、`TacticalMarker`（含 `Type` 与访问器）、`TacticalMarkerListener`（含 `Cause`）；
- **行为兼容**：玩家手动标点的 5 名额 / 60 秒 / 去重口径自 v1.x 起不变，联动扩展不改变玩家侧任何默认行为；
- **内部类**（FactoryMap* / PlayerMarkerFX / MapConfig 实现等）可能随版本调整，直连自负（门面等名委托方法保持可用）；
- **Roadmap（设想，未承诺）**：外部标点自定义颜色/图标、POI 渲染位点亮、对局阶段事件（开局/缩圈）、网络同步助手。

---

## 版本历史

| 版本 | 变更 |
| --- | --- |
| v2.5.7 | 初版：统一门面 `TacticalMapApi`；外部标点（稳定 ID + 自定义 TTL + 独立 16 名额）；标点事件监听器（四因，异常隔离）；对局倒计时扩展（加时/减时、截止时刻、Provider 自省、结束沿回调）；POI 注销；编程式地图开关 |

---

*Drag Inventory · kujojotarojojo · MIT License · 接口问题请提 issue 并 @ 作者*
