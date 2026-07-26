import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** Generates the PlayerControl++ mod icon. */
public class MakeIcon {

    static final int S = 128;

    // Palette
    static final Color BG_TOP    = new Color(0x2B3A52);
    static final Color BG_BOTTOM = new Color(0x121821);
    static final Color ACCENT    = new Color(0x35D6C8);   // teal: the route
    static final Color WHITE     = new Color(0xF2F6FA);
    static final Color REC       = new Color(0xE8484A);   // red: recording

    public static void main(String[] args) throws Exception {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        drawBackground(g);
        drawRoute(g);
        drawPlayGlyph(g);
        drawPlusPlus(g);

        g.dispose();
        File out = new File(args[0]);
        ImageIO.write(img, "PNG", out);
        System.out.println("wrote " + out.getAbsolutePath() + " (" + out.length() + " bytes)");

        // ModMenu renders the icon small; check legibility at that size too.
        if (args.length > 1) {
            ImageIO.write(scaled(img, 32), "PNG", new File(args[1]));
            System.out.println("wrote 32px preview " + args[1]);
        }
    }

    static BufferedImage scaled(BufferedImage src, int size) {
        BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return dst;
    }

    /** Rounded-square plate with a vertical gradient and a lighter top rim. */
    static void drawBackground(Graphics2D g) {
        RoundRectangle2D plate = new RoundRectangle2D.Float(4, 4, S - 8, S - 8, 26, 26);
        g.setPaint(new GradientPaint(0, 4, BG_TOP, 0, S - 4, BG_BOTTOM));
        g.fill(plate);

        g.setPaint(new Color(0xFF, 0xFF, 0xFF, 34));
        g.setStroke(new BasicStroke(2.4f));
        g.draw(plate);
    }

    /**
     * The route: a zig-zag path with waypoint dots. This is the feature that gives the mod its
     * shape — waypoints the player walks between.
     */
    static void drawRoute(Graphics2D g) {
        Point2D[] pts = {
            new Point2D.Float(26,  96),
            new Point2D.Float(52,  74),
            new Point2D.Float(76,  92),
            new Point2D.Float(102, 62),
        };

        Path2D path = new Path2D.Float();
        path.moveTo(pts[0].getX(), pts[0].getY());
        for (int i = 1; i < pts.length; i++) {
            path.lineTo(pts[i].getX(), pts[i].getY());
        }

        // Soft glow under the line so it stays visible when scaled down.
        g.setPaint(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 60));
        g.setStroke(new BasicStroke(11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);

        g.setPaint(ACCENT);
        g.setStroke(new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(path);

        // Waypoint dots: hollow for intermediate stops, solid for the ends.
        for (int i = 0; i < pts.length; i++) {
            double x = pts[i].getX(), y = pts[i].getY();
            boolean end = (i == 0 || i == pts.length - 1);
            double r = end ? 8.0 : 6.0;

            g.setPaint(BG_BOTTOM);
            g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
            g.setPaint(end ? WHITE : ACCENT);
            g.setStroke(new BasicStroke(3.2f));
            g.draw(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        }

        // Recording marker on the first waypoint.
        g.setPaint(REC);
        g.fill(new Ellipse2D.Double(26 - 3.6, 96 - 3.6, 7.2, 7.2));
    }

    /** Playback triangle, top-left, the other half of the mod's identity. */
    static void drawPlayGlyph(Graphics2D g) {
        Path2D tri = new Path2D.Float();
        tri.moveTo(28, 19);
        tri.lineTo(28, 59);
        tri.lineTo(62, 39);
        tri.closePath();

        g.setPaint(new Color(0, 0, 0, 70));
        g.translate(0, 2);
        g.fill(tri);
        g.translate(0, -2);

        g.setPaint(WHITE);
        g.fill(tri);
    }

    /**
     * The "++" that makes it PlayerControl++ rather than PlayerControl. Kept thin and well
     * separated: at ModMenu's 32 px the two signs merge into one blob otherwise.
     */
    static void drawPlusPlus(Graphics2D g) {
        g.setPaint(ACCENT);
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        drawPlus(g, 80, 30, 8);
        drawPlus(g, 104, 30, 8);
    }

    static void drawPlus(Graphics2D g, double cx, double cy, double arm) {
        g.draw(new Line2D.Double(cx - arm, cy, cx + arm, cy));
        g.draw(new Line2D.Double(cx, cy - arm, cx, cy + arm));
    }
}
