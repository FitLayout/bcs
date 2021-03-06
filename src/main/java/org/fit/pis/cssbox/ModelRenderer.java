package org.fit.pis.cssbox;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.fit.cssbox.layout.BackgroundImage;
import org.fit.cssbox.layout.Box;
import org.fit.cssbox.layout.ContentImage;
import org.fit.cssbox.layout.ElementBox;
import org.fit.cssbox.layout.ReplacedBox;
import org.fit.cssbox.layout.TextBox;
import org.fit.cssbox.render.BoxRenderer;
import org.fit.cssbox.render.GraphicsRenderer;
import org.fit.cssbox.render.Transform;

import cz.vutbr.web.css.CSSProperty.TextDecoration;

public class ModelRenderer extends GraphicsRenderer implements BoxRenderer {
//    private final ElementBox root;

    private final BufferedImage img;
//    private final Graphics2D g;

//    private final Color bgColor;

    public static final double SIMILARITY_THRESHOLD = 7.5;

    public ModelRenderer(BufferedImage img, Graphics2D g) {
        super(g);
        this.img = img;
//        this.g = g;
    }

    @Override
    public void renderElementBackground(ElementBox box) {
        Rectangle rect;
        Color c;

        AffineTransform origAt = null;
        AffineTransform at = Transform.createTransform(box);
        if (at != null) {
            origAt = this.g.getTransform();
            this.g.transform(at);
        }

        rect = box.getAbsoluteBackgroundBounds(); // background is bounded by content and padding

        c = this.getBgColor(box);
        if (c != null) {
            this.g.setColor(c);
            this.g.drawRect(rect.x, rect.y, rect.width, rect.height);
            this.g.fillRect(rect.x, rect.y, rect.width, rect.height);
        }

        if (origAt != null) {
            this.g.setTransform(origAt);
        }
    }

    private Color getBgColor(ElementBox box)
    {
        List<BackgroundImage> images;
        BufferedImage bgImg;
        Color color;
        AverageColor imgColor;
        Rectangle rect;
        int x, y;

        images = box.getBackgroundImages();
        if (images != null && images.size() > 0)
        {
//            bgImg = images.get(0).getBufferedImage();
            bgImg = images.get(images.size()-1).getBufferedImage();
            if (bgImg == null)
            {
                imgColor = new AverageColor(Color.white, 1);
            }
            else
            {
                imgColor = new AverageColor(bgImg);
            }
            if (imgColor.getColor() == null) return null;
            /* DOC: mixing color of bg image with bg
             * - more precise -> if the bg is small compared to the box, it won't be so visual distinct
             * - also consider not mixing (original functionality)
             *   -> gives more distinct outline of the box
             *   -> even if small, the bg image may be used to visually higlight the box
             */
            rect = box.getAbsoluteBackgroundBounds();
            color = box.getBgcolor();
            if (color == null)
            { /* BG is transparent - detect current color on the image */
                x = (rect.x >= 0)?rect.x:0;
                y = (rect.y >= 0)?rect.y:0;
                if (x >= this.img.getWidth()) x = this.img.getWidth()-1;
                if (y >= this.img.getHeight()) y = this.img.getHeight()-1;
                color = new Color(this.img.getRGB(x, y));
            }

            return imgColor.mixWithBackground(color);
        }

        return box.getBgcolor();
    }

    @Override
    public void renderTextContent(TextBox box) {
        Color color;
        Color bgColor;
        float []hsb;
        float []bgHsb;
        int white_multiplier;
        int hsb_index;
        int x, y;
        Rectangle pos = box.getAbsoluteContentBounds();

        color = box.getVisualContext().getColor();
        hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);

        /* DOC: white and grey need special treatment */
        if (hsb[1] == 0)
        {
            if (hsb[2] == 1)
            {
                /* The text is white, we want to get the color of background ... */

                x = (box.getContentX()<0)?0:box.getContentX();
                y = (box.getContentY()<0)?0:box.getContentY();
                bgColor = new Color(this.img.getRGB(x, y));
                bgHsb = Color.RGBtoHSB(bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(), null);
                hsb[0] = bgHsb[0];

                /* ... we want to slightly modify the initial value (so bold can be actually emphasized) ... */
                hsb[1] = (float)0.2;

                /* ... we want to modify saturation ... */
                hsb_index = 1;
                /* ... and we want to subtract from it for emphasis */
                white_multiplier = -1;
            }
            else
            {
                /* The text is grey - we want to modify brightness ... */
                hsb_index = 2;
                /* ... and we want to subtract from it for emphasis ... */
                white_multiplier = -1;

                if (hsb[2] == 0)
                {
                    /* The color is black, set the initial value higher so bold can be actually emphasized */
                    hsb[2] = (float)0.2;
                }
            }
        }
        else
        {
            /* The text colored - we want to modify saturation ... */
            hsb_index = 1;
            /* ... and we want to add to it for emphasis */
            white_multiplier = 1;
        }

        for (TextDecoration dec: box.getVisualContext().getTextDecoration())
        {
            if (dec == TextDecoration.UNDERLINE)
            {
                hsb[hsb_index] += white_multiplier*0.2;
                break;
            }
        }
        if (box.getVisualContext().getFont().isItalic())
        {
            hsb[hsb_index] -= white_multiplier*0.2;
        }

        if (box.getVisualContext().getFont().isBold())
        {
            hsb[hsb_index] += white_multiplier*0.3;
        }

        if (hsb[hsb_index] > 1.0) hsb[hsb_index] = (float)1.0;
        else if (hsb[hsb_index] < 0.0) hsb[hsb_index] = (float)0.0;

        this.g.setColor(new Color(Color.HSBtoRGB(hsb[0], hsb[1], hsb[2])));
        this.g.drawRect(pos.x, pos.y, pos.width, pos.height);
        this.g.fillRect(pos.x, pos.y, pos.width, pos.height);
    }

    @Override
    public void renderReplacedContent(ReplacedBox box) {
        AverageColor avg;
        Rectangle pos = ((Box)box).getAbsoluteContentBounds();
        ContentImage imgObj = (ContentImage)box.getContentObj();

        AffineTransform origAt = null;
        if (box instanceof ElementBox) {
            AffineTransform at = Transform.createTransform((ElementBox) box);
            if (at != null) {
                origAt = this.g.getTransform();
                this.g.transform(at);
            }
        }

        try {
            avg = new AverageColor(imgObj.getBufferedImage());
        } catch (IllegalArgumentException e) {
            /* Sometimes the image can have width or height equal to zero */
            return;
        }
        if (avg.getColor() == null) this.g.setColor(Color.black);
        else this.g.setColor(avg.getColor());

        this.g.drawRect(pos.x, pos.y, pos.width, pos.height);
        this.g.fillRect(pos.x, pos.y, pos.width, pos.height);

        if (origAt != null) {
            this.g.setTransform(origAt);
        }
    }

    @Override
    public void close() {
    }

    public boolean save(String path) {
        File outputfile = new File(path);
        try {
            ImageIO.write(this.img, "png", outputfile);
        } catch (IOException e) {
            return false;
        }

        return true;
    }
}
