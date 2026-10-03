package dev.draginventory.client.map;

/** 瓦片渲染器对"地图视图状态"的最小依赖（大地图会话与小地图各自实现）。 */
interface FactoryMapView {
    /** 当前活动层（可能为 null：尚未扫描 / 无层）。 */
    FactoryMapLayerResolver.Layer activeLayer();

    /** 楼层目录（叠加层查找等用；可能为 null 或空）。 */
    FactoryMapLayerResolver.LayerCatalog catalog();
}
