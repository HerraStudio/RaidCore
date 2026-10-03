package dev.draginventory.client.map;

/**
 * 战术地图的语义地形分类。
 *
 * <p>动态地图按语义而非原版方块颜色上色：分类结果交由 {@link FactoryMapPalette}
 * 映射到战术色板（对标《三角洲行动》地图工具的深色地形 + 浅白建筑风格），
 * 保留方块像素结构（"像素风套滤镜"）。</p>
 *
 * <ul>
 *   <li>{@link #VOID} —— 未加载区块或越界，地图保持深色底</li>
 *   <li>{@link #WALL} —— 行走平面被占据（脚部/头部空间有碰撞体），渲染为墙体</li>
 *   <li>{@link #FLOOR} —— 可通行地面（有地板且上方两格畅通）</li>
 *   <li>{@link #EDGE} —— 可通行但邻接落差或阻挡，画边界线提升可读性</li>
 *   <li>{@link #STAIR} —— 楼梯、梯子、脚手架等垂直连接</li>
 *   <li>{@link #DOOR} —— 门与活板门（战术出入点）</li>
 *   <li>{@link #WATER} —— 水体（地面上下数格内的流体）</li>
 *   <li>{@link #BLOCKED} —— 实心不可通行区域</li>
 *   <li>{@link #DECOR} —— 地面装饰（花草等非碰撞植物），默认隐藏</li>
 * </ul>
 */
public enum MapSemantics {
    VOID, WALL, FLOOR, EDGE, STAIR, DOOR, WATER, BLOCKED, DECOR;

    /** 该语义图层默认是否可见（DECOR 默认隐藏，避免像素噪声）。 */
    public boolean isVisibleByDefault() {
        return this != DECOR;
    }
}
