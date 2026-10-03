package dev.draginventory.client.compass;

import com.lowdragmc.lowdraglib2.gui.hud.ModularHudLayer;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.util.Mth;

/**
 * 方位条 HUD 层：LDLib2 ModularHudLayer 的独立实现。
 *
 * <p>UI 构造延迟到第一次渲染（LDLib2 资源就绪后），并通过布局缓存
 * 在配置变化时同步位置/宽度/高度，而不重建 ModularUI——与现有
 * {@code ReferenceHotbar} 的做法保持一致。</p>
 *
 * <p>注意：布局热同步不重播入场动画（入场动画只由“不可见 -> 可见”的
 * 真实状态切换触发），否则设置界面里拖动位置/宽度滑条会连续重播动画，
 * 导致方位条闪烁。</p>
 */
final class CompassHud implements ModularHudLayer {

    private ModularUI ui;
    private CompassWidget widget;
    private int cachedWidth = -1;
    private int cachedHeight = -1;
    private int cachedOffsetX = Integer.MIN_VALUE;
    private int cachedOffsetY = Integer.MIN_VALUE;

    @Override
    public ModularUI getModularUI() {
        if (ui == null) {
            var root = new UIElement().layout(l -> l.widthPercent(100).heightPercent(100));
            widget = new CompassWidget(false);
            root.addChild(widget);
            ui = ModularUI.of(UI.of(root));
        }
        syncLayout();
        return ui;
    }

    /** 配置热同步：位置/宽度/皮肤高度变化时更新 Taffy 布局。 */
    private void syncLayout() {
        int width = CompassConfig.BAR_WIDTH.get();
        int offsetX = CompassConfig.OFFSET_X.get();
        int offsetY = CompassConfig.OFFSET_Y.get();
        // 高度跟随皮肤声明（与 CompassWidget 内部一致地夹在 40~72）。
        int height = Mth.clamp(Math.round(CompassWidget.currentStyle().widgetHeight()), 40, 72);
        if (width == cachedWidth && height == cachedHeight
                && offsetX == cachedOffsetX && offsetY == cachedOffsetY) {
            return;
        }
        cachedWidth = width;
        cachedHeight = height;
        cachedOffsetX = offsetX;
        cachedOffsetY = offsetY;
        widget.layout(l -> l.positionType(TaffyPosition.ABSOLUTE)
                .leftPercent(50)
                .marginLeft(-width / 2f + offsetX)
                .top(offsetY)
                .width(width)
                .height(height));
    }
}
