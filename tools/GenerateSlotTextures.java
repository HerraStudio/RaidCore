import java.awt.BasicStroke;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

public final class GenerateSlotTextures {
    private static final Path OUTPUT=Path.of("src/main/resources/assets/tactical_inventory/textures/gui/sprites/equipment");
    private static final Color BODY=new Color(255,255,255,112);
    private static final Color DETAIL=new Color(255,255,255,155);
    private static final Color CUTOUT=new Color(18,27,36,128);

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        var panel=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
        var painter=panel.createGraphics();
        painter.setComposite(AlphaComposite.Src);
        // Keep the one-pixel border separate so its alpha is not composited over the slot fill.
        painter.setColor(new Color(38,55,67,160)); painter.fillRect(1,1,14,14);
        painter.setColor(new Color(107,129,141,148)); painter.drawRect(0,0,15,15);
        painter.dispose(); ImageIO.write(panel,"png",OUTPUT.resolve("slot.png").toFile());
        icon("helmet",128,128,GenerateSlotTextures::helmet);
        icon("vest",128,128,GenerateSlotTextures::vest);
        icon("pistol",128,128,GenerateSlotTextures::pistol);
        icon("knife",128,128,GenerateSlotTextures::knife);
        icon("rifle",256,96,GenerateSlotTextures::rifle);
        icon("rig",128,128,GenerateSlotTextures::rig);
        icon("backpack",128,128,GenerateSlotTextures::backpack);
        icon("pocket",128,128,GenerateSlotTextures::pocket);
        icon("search",64,64,GenerateSlotTextures::search);
    }

    private static void search(Graphics2D painter) {
        painter.setColor(new Color(255,255,255,235));
        painter.setStroke(new BasicStroke(5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        painter.drawOval(10,9,31,31); painter.drawLine(37,37,54,54);
    }

    private static void icon(String name,int width,int height,Consumer<Graphics2D> draw) throws Exception {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        var painter=image.createGraphics();
        painter.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        painter.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
        painter.setColor(BODY); painter.setStroke(new BasicStroke(2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        draw.accept(painter); painter.dispose();
        ImageIO.write(image,"png",OUTPUT.resolve(name+".png").toFile());
    }

    private static Path2D shape(double... coordinates) {
        var outline=new Path2D.Double(); outline.moveTo(coordinates[0],coordinates[1]);
        for(int index=2;index<coordinates.length;index+=2) outline.lineTo(coordinates[index],coordinates[index+1]);
        outline.closePath(); return outline;
    }

    private static void helmet(Graphics2D painter) {
        var shell=new Path2D.Double();
        shell.moveTo(20,71); shell.curveTo(15,34,34,15,64,15); shell.curveTo(97,15,114,37,108,71);
        shell.lineTo(103,88); shell.lineTo(86,93); shell.lineTo(82,71); shell.lineTo(47,71);
        shell.lineTo(42,93); shell.lineTo(24,87); shell.closePath(); painter.fill(shell);
        painter.setColor(DETAIL); painter.drawArc(26,23,74,72,18,143);
        painter.fillRoundRect(22,61,85,8,3,3); painter.fillRoundRect(59,25,10,12,2,2);
        painter.setColor(BODY); painter.fillRoundRect(17,68,12,24,4,4); painter.fillRoundRect(99,68,12,24,4,4);
        painter.setStroke(new BasicStroke(5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        painter.drawPolyline(new int[]{31,42,51,77,87,98},new int[]{87,101,109,109,101,87},6);
        painter.setColor(DETAIL); painter.drawLine(51,104,77,104);
    }

    private static void vest(Graphics2D painter) {
        painter.fill(shape(22,23,45,15,52,31,76,31,83,15,106,23,111,52,100,59,101,109,27,109,28,59,17,52));
        painter.setColor(CUTOUT); painter.fill(shape(44,17,53,37,75,37,84,17,76,14,69,25,59,25,52,14));
        painter.setColor(DETAIL); painter.drawRoundRect(37,44,54,42,7,7);
        painter.drawLine(34,94,94,94); painter.drawLine(34,100,94,100);
        painter.fillRect(33,63,4,17); painter.fillRect(91,63,4,17);
        painter.setColor(CUTOUT); painter.drawLine(63,42,63,89);
    }

    private static void pistol(Graphics2D painter) {
        painter.fill(shape(13,32,106,32,112,39,112,57,76,57,69,63,80,100,69,108,50,106,39,66,22,62,15,54));
        painter.fillRect(18,27,10,7); painter.fillRect(99,27,8,7);
        painter.setColor(DETAIL); painter.fillRect(23,39,78,3);
        for(int step=0;step<5;step++) painter.drawLine(25+step*5,44,23+step*5,53);
        painter.setColor(CUTOUT); painter.fillRoundRect(62,59,21,17,7,7);
        painter.setColor(BODY); painter.drawRoundRect(62,58,22,19,6,6); painter.drawLine(67,59,70,68);
        painter.setColor(DETAIL); painter.drawLine(52,78,65,77); painter.drawLine(55,87,68,86); painter.drawLine(58,96,71,95);
    }

    private static void knife(Graphics2D painter) {
        painter.fill(shape(14,56,43,54,43,50,48,50,49,55,114,55,96,69,49,65,48,72,43,72,43,66,14,65,10,61));
        painter.setColor(DETAIL); painter.drawLine(52,62,97,62);
        for(int step=0;step<5;step++) painter.drawLine(16+step*5,56,16+step*5,64);
        painter.setColor(CUTOUT); painter.fillOval(13,59,3,3);
    }

    private static void rifle(Graphics2D painter) {
        painter.fill(shape(10,31,39,31,54,42,80,42,86,32,156,32,163,36,205,36,210,41,241,41,241,47,205,47,197,54,148,54,139,62,107,62,97,82,81,79,86,59,60,52,40,50,19,63,10,62));
        painter.fill(shape(119,56,138,56,147,80,144,86,126,86,122,72));
        painter.fillRect(236,37,10,14); painter.fillRect(85,27,78,5);
        painter.fillRoundRect(102,15,32,9,3,3); painter.fillRect(108,23,5,7); painter.fillRect(125,23,5,7);
        painter.setColor(CUTOUT); painter.fill(shape(18,38,35,38,49,45,35,45,19,55));
        painter.fillRoundRect(100,55,17,12,3,3);
        painter.setColor(DETAIL); painter.drawLine(87,37,152,37); painter.drawLine(125,64,141,82);
        for(int step=0;step<6;step++) painter.fillRect(159+step*6,40,3,7);
        painter.drawLine(19,34,35,34);
    }

    private static void rig(Graphics2D painter) {
        vest(painter); painter.setColor(BODY);
        for(int column=0;column<3;column++) {painter.fillRoundRect(30+column*24,55,20,42,4,4);}
        painter.setColor(DETAIL);
        for(int column=0;column<3;column++) painter.drawRoundRect(30+column*24,55,20,42,4,4);
    }

    private static void backpack(Graphics2D painter) {
        painter.fillRoundRect(28,20,72,94,15,15); painter.fillRoundRect(16,49,14,47,5,5); painter.fillRoundRect(98,49,14,47,5,5);
        painter.setStroke(new BasicStroke(6f)); painter.drawRoundRect(50,13,28,17,7,7);
        painter.setColor(DETAIL); painter.setStroke(new BasicStroke(2f));
        painter.drawRoundRect(37,60,54,44,8,8); painter.drawLine(37,73,91,73); painter.drawLine(39,40,89,40);
        painter.fillRoundRect(39,23,5,21,2,2); painter.fillRoundRect(84,23,5,21,2,2);
    }

    private static void pocket(Graphics2D painter) {
        painter.fillRoundRect(25,30,78,80,14,14);
        painter.fillRoundRect(33,16,13,25,4,4); painter.fillRoundRect(82,16,13,25,4,4);
        painter.setColor(CUTOUT); painter.fillRoundRect(30,35,68,27,7,7);
        painter.setColor(DETAIL); painter.setStroke(new BasicStroke(2f));
        painter.drawRoundRect(25,30,78,80,14,14);
        painter.draw(shape(30,36,98,36,96,58,64,70,32,58));
        painter.fillRoundRect(59,56,10,17,3,3);
        painter.drawLine(36,91,92,91);
    }
}
