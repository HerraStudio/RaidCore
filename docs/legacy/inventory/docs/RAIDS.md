# 搜打撤对局配置

v1.0.14 为已有地图提供入场、撤离、死亡与结算流程。地图建筑和搜刮容器沿用当前世界；地图配置不会自动清空、复制或重建场景。

## 快速配置

以管理员身份站在希望结算后返回的位置，输入：

```mcfunction
/raid lobby
```

移动到玩家进图出生的位置：

```mcfunction
/raid spawn add alpha
```

移动到绿色烟雾所在的撤离点中心：

```mcfunction
/raid extract add gate 3
```

`3` 为方形区域半径，水平边长 6 格，默认需要连续停留 20 秒。判定以玩家脚部坐标为准，高度范围为配置点 Y−1 到 Y+4，避免楼上、楼下直接触发。出生点、大厅和撤离点均保存各自的维度、坐标；大厅和出生点也保存朝向。可增加多个出生点，入场依次轮换；每个出生点所在维度必须至少有一个撤离点。

```mcfunction
/raid name 战术行动
/raid list
/raid join
```

使用相同名称再次添加会更新对应位置。新世界没有自动开启的对局。配置必须同时包含有效大厅、出生点和撤离点，才允许入场。

## 玩家操作

| 命令 | 功能 |
| --- | --- |
| `/raid join` | 进入已配置地图并出生；正在进行的对局不能重复加入 |
| `/raid status` | 查看地图、状态、行动时间及击杀数 |
| `/raid result` | 显示当前尚未确认的结算 |

进入撤离区自动计时，离开区域立即重置；从一个撤离点移动到另一个也重新计时。成功后返回大厅，携带物品与原背包数据保持一致。客户端数字、虚线分隔、进度条、淡入滑动及脉冲均只用于展示，实际完成由服务器决定。

死亡只保留近战，其余物品掉落；即使世界打开了 `keepInventory` 也执行这个对局规则。GWO 近战武器、剑、斧及 `tactical_inventory:melee` 标签物品受到保护，名称、耐久、附件数据等组件随原物品保留。刀鞘物品仍在刀鞘；背包、胸挂内的近战因装备丢失而进入“待整理”区。正常死亡界面和复活流程仍会执行，复活后才传送回大厅、打开结算。对局外死亡沿用原有世界规则。

结算按“返回”或 Esc 确认，不暂停单人世界；确认只关闭当前结果，不生成战利品副本。断线和停服会中断未完成对局；重登后返回大厅并显示同一结算。重启不恢复部分撤离进度。

旁观模式不能加入对局。对局中被切换为旁观模式时，中断行动并返回大厅，保留管理员设置的游戏模式；恢复可参与的模式后可以再次入场。

## 管理命令

需要权限等级 2。

| 命令 | 功能 |
| --- | --- |
| `/raid lobby` | 当前位置设为大厅 |
| `/raid name <地图名>` | 设置结算中的战区名称 |
| `/raid spawn add <名称>` | 当前位置添加或更新出生点 |
| `/raid spawn remove <名称>` | 删除出生点 |
| `/raid extract add <名称> <半径> [秒数]` | 当前位置添加或更新撤离点；半径 0.5–128，秒数 1–3600，默认 20 |
| `/raid extract remove <名称>` | 删除撤离点 |
| `/raid extract fx <名称> <namespace:path>` | 更换对应 Photon 特效资源 |
| `/raid list` | 列出地图配置及就绪状态 |
| `/raid spawn list`、`/raid extract list` | 列出当前地图配置 |
| `/raid start <玩家选择器>` | 安排指定玩家入场，例如 `@a` |
| `/raid abort [玩家选择器]` | 中断指定玩家的进行中对局并返回大厅；省略选择器时作用于自己 |

同一世界最多配置 64 个出生点和 64 个撤离点。重叠撤离区按配置顺序取第一个；调整撤离时间会重置受影响倒计时。配置保存于主世界 `data/tactical_inventory_raids.dat`，包含大厅、区域、会话和每人最新已确认结果，服务端独立管理。

## Photon 烟雾

发布包内置用户制作的绿色烟雾原始 `.fx` 导出，默认资源 ID：

```text
tactical_inventory:evacuation_smoke
```

它自身循环播放，并使用 Photon 内置烟雾材质。客户端必须安装 Photon 2.2.7+ 与兼容 LDLib2 2.2.x。128 格范围内已加载的撤离点拥有独立播放实例，同时显示最近最多 16 个；离开显示范围、换维度、断线或重载资源时清理，防止重复发射。

可通过资源包覆盖 `assets/tactical_inventory/fx/evacuation_smoke.fx`，或者用管理命令指定其他特效。例如使用本机编辑器中原先导出的资源：

```mcfunction
/raid extract fx gate photon:evacuation_smoke.fxproj
```

自定义特效及引用资源需在所有客户端可用。Photon 按 `assets/<namespace>/fx/<path>.fx` 查找资源；API/命令中的 ID 省略 `fx/` 前缀和最后的 `.fx`。参考 [Photon 官方 Java API](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/java-api/) 和 [特效分发文档](https://low-drag-mc.github.io/LowDragMC-Doc/en/photon2/distribution.html)。
