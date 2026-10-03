package dev.draginventory.client.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * 玩家箭头纹理资源守卫（v2.5.8 引入，v2.5.9 增补 mcmeta 守卫）：PNG 必须在打包
 * 路径上、256×256、带 alpha 通道，且主体像素为 SVG 原色 #32CD32——防止纹理缺失/
 * 重栅格化走样导致游戏内回退紫黑缺失纹理或颜色漂移；v2.5.9 起纹理按原版
 * SimpleTexture + .mcmeta blur=true 线性过滤加载（无锯齿的保障），mcmeta 必须在
 * 打包路径上且声明 blur=true。纯 JDK ImageIO 断言，不加载任何 MC 类（测试
 * classpath 无 MC）。
 */
final class PlayerMarkerIconAssetTest {
    private static final Path PNG =
            Path.of("ui/src/main/resources/assets/draginventory/textures/gui/player_marker.png");
    private static final Path MCMETA =
            Path.of("ui/src/main/resources/assets/draginventory/textures/gui/player_marker.png.mcmeta");

    @Test
    void playerMarkerPngBundledWithExpectedGeometry() throws IOException {
        assertTrue(Files.exists(PNG), "缺少玩家箭头纹理: " + PNG);
        BufferedImage img = ImageIO.read(PNG.toFile());
        assertEquals(256, img.getWidth(), "宽度应为 256");
        assertEquals(256, img.getHeight(), "高度应为 256");
        assertTrue(img.getColorModel().hasAlpha(), "必须带 alpha 通道（抗锯齿边缘 + 透明画布）");
    }

    @Test
    void playerMarkerMainColorIsSvgOriginalGreen() throws IOException {
        assertTrue(Files.exists(PNG), "缺少玩家箭头纹理: " + PNG);
        BufferedImage img = ImageIO.read(PNG.toFile());
        // 众数取样：不透明（α>200）像素中最常见的颜色必须是 SVG 原色 #32CD32；
        // 抗锯齿边缘的半透明过渡像素被 alpha 门槛过滤，不影响判定。
        Map<Integer, Integer> counts = new HashMap<>();
        for (int y = 0; y < 256; y += 4) {
            for (int x = 0; x < 256; x += 4) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) > 200) counts.merge(argb & 0xFFFFFF, 1, Integer::sum);
            }
        }
        assertTrue(!counts.isEmpty(), "纹理中没有任何不透明像素（栅格化产物为空？）");
        int main = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey();
        assertEquals(0x32CD32, main, "主色必须是 SVG 原色 #32CD32，实际 0x" + Integer.toHexString(main));
    }

    @Test
    void playerMarkerMcmetaDeclaresLinearBlur() throws IOException {
        // v2.5.9：纹理走原版 SimpleTexture 管线，线性过滤（无锯齿）完全依赖同目录
        // .mcmeta 的 blur=true 声明——缺失或 false 时纹理按 NEAREST 最近邻采样，
        // 边缘回到硬锯齿。守卫住这张 6 行小文件，防止打包遗漏。
        assertTrue(Files.exists(MCMETA), "缺少玩家箭头纹理元数据: " + MCMETA);
        String raw = Files.readString(MCMETA);
        assertTrue(raw.contains("\"texture\""), "mcmeta 必须含 texture 元数据段");
        assertTrue(raw.replaceAll("\\s", "").contains("\"blur\":true"),
                "mcmeta 必须 blur: true（线性过滤，无锯齿的保障）");
    }
}
