# RaidCore 功能模块

同一工程的源码、测试和资源按功能存放在根目录模块中。根 `build.gradle` 将所有模块的目录加入统一 main / test 源集，生成一个 RaidCore JAR。模组入口、依赖声明和 Mixin 配置集中在根 `src/main`。

```text
RaidCore/
├─ ui/          界面、HUD、地图、轮盘、渲染
├─ inventory/   库存、占格、放置规则、物品配置
├─ loot/        搜刮、保险箱、破译、战利品、模型
├─ action/      探头、趴下、蹲伏、快速切枪
├─ player/      生命值、体力、玩家附件
├─ network/     Payload 与同步桥接
├─ api/         对外联动入口与接口
├─ raid/        对局、撤离、结算、计时
├─ src/main/    统一入口和模组描述
├─ src/integration/  跨模块原生检查
├─ model-source/     可编辑模型工程
└─ build.gradle      统一构建
```

每个模块使用 `src/main/java`、`src/main/resources`、`src/test/java` / `src/test/resources`。没有资源或测试的模块可按需添加对应目录。

| 模块 | 职责 | Java 文件 | 测试类 | 资源文件 |
|---|---|---:|---:|---:|
| ui | 背包界面、HUD、地图、方位条、口袋轮盘、渲染与语言资源 | 92 | 14 | 19 |
| inventory | 库存持久化、占格与放置事务、槽位、物品配置 | 19 | 4 | 9 |
| loot | 容器搜刮、保险箱、破译状态、金条、模型与材质 | 13 | 4 | 25 |
| action | 玩家姿态、切枪时间轴、输入和破译期间的操作限制 | 35 | 7 | 1 |
| player | 100 基础生命值、体力、同步附件及对应 Mixin | 5 | 1 | 0 |
| network | 背包、物品配置、破译、对局、探头 Payload，选槽同步 | 6 | 0 | 0 |
| api | 地图/方位条门面、标点类型、监听器、提供者接口 | 7 | 1 | 0 |
| raid | 对局配置、进入、撤离、死亡、结算、倒计时、Photon 特效 | 11 | 2 | 1 |

根入口另有 1 个 Java 文件，共 189 个生产 Java 文件、33 个单元测试类。文件位置索引和调整前 hash 在 [module-layout.json](module-layout.json)，原项目来源在 [MERGE_SOURCES.json](MERGE_SOURCES.json)。

## 兼容与协作

Java 包名保留，使现有 API、反射、包内访问和 Mixin 类路径继续可用。目录表达功能归属；所有模块共享 Java 编译与运行时依赖。资产仍按原 `assets/{namespace}` / `data/{namespace}` 路径进入 JAR，配置文件、网络 ID 和存档键保持。

界面调用领域服务，网络包把请求交给对应功能的服务端校验；`api` 存放现有对外联动面。代码之间仍可使用原类型直接引用，跨模块原生检查统一放在 `src/integration`。保留的地图/方位条 API 属于客户端功能，调用线程约定见接口原有文档。

物品、方块、菜单注册由 `inventory` 中的 `Tactical.register` 汇总；HUD 与配置由 `ui` 中的 `DragInventory.register` 汇总；姿态网络与配置由 `action` 中的 `TacticalActions.register` 汇总。根 `RaidCore` 是唯一模组入口，并依次调用三者。

## 开发与检查

在根目录运行 `.\gradlew.bat clean build`。Gradle 和 IDE 导入会自动识别所有模块源码，不需要分别构建模块。

结构检查：`python tools/verify_module_layout.py`，验证唯一入口、Java 包路径、运行资源重复和移动后的生产文件一致性。附加 `--baseline-jar <调整前的JAR>` 可逐条比较编译类和全部 JAR 内容。

Blender MCP 素材工具已将保险箱与金条输出定位到 `loot/src/main/resources`；可编辑工程仍在根 `model-source/`。完整项目 ZIP 包含所有模块目录。
