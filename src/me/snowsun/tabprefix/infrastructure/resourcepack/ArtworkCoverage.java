package me.snowsun.tabprefix.infrastructure.resourcepack;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import me.snowsun.tabprefix.domain.DisplayArtwork;

/** Fill and outline share one coverage field and one opacity, including their antialiased join. */
final class ArtworkCoverage {
  private ArtworkCoverage() {}
  static void rounded(BufferedImage image,DisplayArtwork.Paint p,int density,double x,double y,double w,double h,
      double radius,boolean panel,boolean filled) {
    Colors colors=new Colors(p);
    double stroke=p.stroke.equals("none")?0:p.strokeWidth;
    if(panel)stroke=Math.min(stroke,Math.min(w,h)/2);
    double grow=panel?0:stroke/2,shrink=panel?stroke:stroke/2;
    RoundedCoverage outer=new RoundedCoverage((x-grow)*density,(y-grow)*density,(w+2*grow)*density,(h+2*grow)*density,(radius+grow)*density);
    RoundedCoverage inner=new RoundedCoverage((x+shrink)*density,(y+shrink)*density,Math.max(0,w-2*shrink)*density,Math.max(0,h-2*shrink)*density,Math.max(0,radius-shrink)*density);
    double pad=Math.min(3,h/4),aw=Math.min(p.accentWidth,w);
    RoundedCoverage accent=panel&&!p.accent.equals("none")&&aw>0
        ?new RoundedCoverage(x*density,(y+pad)*density,aw*density,Math.max(0,h-2*pad)*density,aw*density/2):null;
    boolean fill=filled&&!p.fill.equals("none");
    int startX=Math.max(0,(int)Math.floor(outer.left)),endX=Math.min(image.getWidth(),(int)Math.ceil(outer.right));
    int startY=Math.max(0,(int)Math.floor(outer.top)),endY=Math.min(image.getHeight(),(int)Math.ceil(outer.bottom));
    for(int py=startY;py<endY;py++)for(int px=startX;px<endX;px++) {
      double out=outer.at(px,py);if(out<=0)continue;
      double in=stroke==0?out:Math.min(out,inner.at(px,py)),s=stroke==0?0:out-in,f=fill?in:0,a=0;
      if(accent!=null&&accent.at(px,py)>0) {
        // Only accent intersections need joint classification. The panel boundary remains analytic.
        int af=0,as=0,empty=0;
        for(int sy=0;sy<16;sy++)for(int sx=0;sx<16;sx++) {
          double qx=px+(sx+.5)/16,qy=py+(sy+.5)/16;
          if(!outer.contains(qx,qy)||!accent.contains(qx,qy))continue;
          if(stroke>0&&!inner.contains(qx,qy))as++;else if(fill)af++;else empty++;
        }
        double removeF=Math.min(f,af/256d),removeS=Math.min(s,as/256d);
        f-=removeF;s-=removeS;a=removeF+removeS+empty/256d;
      }
      blend(image,px,py,colors,f,s,a,(px+.5)/density,(py+.5)/density,panel?0:x,panel?0:y,panel?image.getWidth()/(double)density:w,panel?image.getHeight()/(double)density:h);
    }
  }
  static void shape(BufferedImage image,java.awt.Shape path,DisplayArtwork.Paint p,int density,double x,double y,double w,double h,boolean filled) {
    Colors colors=new Colors(p);
    boolean fill=filled&&!p.fill.equals("none"),stroke=!p.stroke.equals("none")&&p.strokeWidth>0;
    if(!fill&&!stroke)return;
    Area outline=stroke?new Area(new BasicStroke(p.strokeWidth,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND).createStrokedShape(path)):new Area();
    Area union=new Area(outline);if(fill)union.add(new Area(path));
    Rectangle2D b=union.getBounds2D();int left=Math.max(0,(int)Math.floor(b.getMinX()*density)-1),top=Math.max(0,(int)Math.floor(b.getMinY()*density)-1);
    int right=Math.min(image.getWidth(),(int)Math.ceil(b.getMaxX()*density)+1),bottom=Math.min(image.getHeight(),(int)Math.ceil(b.getMaxY()*density)+1);
    if(right<=left||bottom<=top)return;
    BufferedImage mask=mask(union,right-left,bottom-top,left,top,density),ring=stroke?mask(outline,right-left,bottom-top,left,top,density):null;
    for(int py=top;py<bottom;py++)for(int px=left;px<right;px++) {
      double cov=(mask.getRGB(px-left,py-top)>>>24)/255d;if(cov==0)continue;
      double s=ring==null?0:Math.min(cov,(ring.getRGB(px-left,py-top)>>>24)/255d);
      blend(image,px,py,colors,cov-s,s,0,(px+.5)/density,(py+.5)/density,x,y,w,h);
    }
  }
  private static BufferedImage mask(java.awt.Shape path,int width,int height,int x,int y,int density) {
    BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
    try{g.setComposite(AlphaComposite.Src);g.translate(-x,-y);g.scale(density,density);g.setColor(Color.WHITE);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);g.fill(path);}finally{g.dispose();}
    return image;
  }
  private static final class Colors {
    final int fill,end,stroke,accent;final double opacity;final boolean horizontal;
    Colors(DisplayArtwork.Paint p){fill=rgb(p.fill);end=p.end.equals("none")?fill:rgb(p.end);stroke=rgb(p.stroke);accent=rgb(p.accent);opacity=p.opacity/100d;horizontal=p.gradient.equals("HORIZONTAL");}
    private static int rgb(String s){return s.equals("none")?0:Integer.parseInt(s.substring(1),16);}
  }
  private static void blend(BufferedImage image,int x,int y,Colors p,double f,double s,double a,double px,double py,double gx,double gy,double w,double h) {
    double cov=f+s+a,alpha=Math.min(1,cov)*p.opacity;if(alpha<=0)return;
    int fc=p.fill,ec=p.end,sc=p.stroke,ac=p.accent;
    double t=Math.max(0,Math.min(1,p.horizontal?(px-gx)/Math.max(w,.00001):(py-gy)/Math.max(h,.00001)));
    int old=image.getRGB(x,y);double oldAlpha=(old>>>24)/255d,carry=oldAlpha*(1-alpha),out=alpha+carry;
    int color=(int)Math.round(out*255)<<24;
    for(int shift=0;shift<=16;shift+=8) {
      double fill=((fc>>>shift)&255)*(1-t)+((ec>>>shift)&255)*t;
      double channel=(fill*f+((sc>>>shift)&255)*s+((ac>>>shift)&255)*a)/cov;
      color|=Math.max(0,Math.min(255,(int)Math.round((channel*alpha+((old>>>shift)&255)*carry)/out)))<<shift;
    }
    image.setRGB(x,y,color);
  }
}
