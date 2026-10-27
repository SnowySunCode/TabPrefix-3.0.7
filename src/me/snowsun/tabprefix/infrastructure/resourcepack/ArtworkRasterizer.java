package me.snowsun.tabprefix.infrastructure.resourcepack;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import me.snowsun.tabprefix.domain.*;

/** All geometry and stroke widths are expressed in GUI pixels, independent of raster density. */
public final class ArtworkRasterizer {
  private ArtworkRasterizer() {}
  public static BufferedImage render(DisplayDesign d,DisplayArtwork.Box box){return render(d,box,box==null?d.sidebarWidth:box.width);}
  public static BufferedImage render(DisplayDesign d,DisplayArtwork.Box box,int width){return render(d,box,width,box==null?d.artwork.height(d):box.height,1);}
  public static BufferedImage render(DisplayDesign d,DisplayArtwork.Box box,int width,int height,int density){
    return render(d,box,width,height,density,box==null?0:box.inset);
  }
  static BufferedImage render(DisplayDesign d,DisplayArtwork.Box box,int width,int height,int density,double inset){
    if(width<1||height<1||density<1||density>8)throw new IllegalArgumentException("Invalid artwork raster size");
    double sx=box==null?1:width/(double)box.width,sy=box==null?1:height/(double)box.height;
    BufferedImage image=new BufferedImage(width*density,height*density,BufferedImage.TYPE_INT_ARGB);
      if(box!=null||d.sidebarBackground){DisplayArtwork.Paint p=box==null?d.artwork.panel:box.paint;double w=Math.max(0,width-2*inset),h=Math.max(0,height-2*inset);ArtworkCoverage.rounded(image,p,density,inset,inset,w,h,Math.min(p.radius,Math.min(w,h)/2),true,true);}
      for(DisplayArtwork.Shape s:d.artwork.shapes)if(s.enabled&&s.surface.equals(box==null?"SIDEBAR":box.surface)&&s.index==(box==null?0:box.index)){
        double x=s.x*sx,y=s.y*sy,w=s.width*sx,h=s.height*sy;java.awt.Shape path;
        switch(s.kind){
          case "ELLIPSE":path=new Ellipse2D.Double(x,y,w,h);break;
          case "LINE":path=new Line2D.Double(x,y,x+w,y+h);break;
          case "POLYGON":case "PATH":Path2D contour=new Path2D.Double();boolean first=true;for(DisplayArtwork.Point p:s.points){double px=x+p.x*w/1000d,py=y+p.y*h/1000d;if(first||p.move){if(!first&&s.closed)contour.closePath();contour.moveTo(px,py);first=false;}else contour.lineTo(px,py);}if(s.closed)contour.closePath();path=contour;break;
          case "ROUND_RECT":double r=Math.min(s.paint.radius,Math.min(w,h)/2);path=new RoundRectangle2D.Double(x,y,w,h,r*2,r*2);break;
          default:path=new Rectangle2D.Double(x,y,w,h);
        }
        boolean filled=!s.kind.equals("LINE")&&(s.closed||!s.kind.equals("PATH"));
        if(s.kind.equals("ROUND_RECT")||s.kind.equals("RECT"))ArtworkCoverage.rounded(image,s.paint,density,x,y,w,h,s.kind.equals("ROUND_RECT")?Math.min(s.paint.radius,Math.min(w,h)/2):0,false,filled);
        else ArtworkCoverage.shape(image,path,s.paint,density,x,y,w,h,filled);
      }
    return image;
  }
}
