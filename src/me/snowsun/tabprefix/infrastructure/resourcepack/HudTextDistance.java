package me.snowsun.tabprefix.infrastructure.resourcepack;

import java.awt.image.BufferedImage;

/** Coverage plus a signed distance field: area filtering when small, smooth contours when enlarged. */
final class HudTextDistance {
  private static final double INF=1e20;
  private HudTextDistance() {}

  static void encode(BufferedImage image) {
    int w=image.getWidth(),h=image.getHeight();
    double[] outside=new double[w*h],inside=new double[w*h];
    int[] coverage=new int[w*h];
    for(int y=0;y<h;y++)for(int x=0;x<w;x++){
      int i=y*w+x,a=image.getRGB(x,y)>>>24;coverage[i]=a;
      double edge=.5-a/255.0;
      outside[i]=a==0?INF:a==255?0:Math.max(0,edge)*Math.max(0,edge);
      inside[i]=a==255?INF:a==0?0:Math.min(0,edge)*Math.min(0,edge);
    }
    transform(outside,w,h);transform(inside,w,h);
    for(int y=0;y<h;y++)for(int x=0;x<w;x++){
      int i=y*w+x;
      double distance=Math.sqrt(inside[i])-Math.sqrt(outside[i]);
      int signed=(int)Math.round(Math.max(0,Math.min(255,128+distance*16)));
      // Opaque storage preserves distances outside the visible ink during font atlas uploads.
      image.setRGB(x,y,0xff000000|signed<<16|coverage[i]<<8);
    }
  }

  private static void transform(double[] grid,int w,int h) {
    int max=Math.max(w,h);double[] line=new double[max],out=new double[max],limits=new double[max+1];int[] sites=new int[max];
    for(int x=0;x<w;x++){for(int y=0;y<h;y++)line[y]=grid[y*w+x];line(line,out,limits,sites,h);for(int y=0;y<h;y++)grid[y*w+x]=out[y];}
    for(int y=0;y<h;y++){System.arraycopy(grid,y*w,line,0,w);line(line,out,limits,sites,w);System.arraycopy(out,0,grid,y*w,w);}
  }

  /** Linear-time squared Euclidean distance transform of a sampled line. */
  private static void line(double[] f,double[] d,double[] z,int[] v,int n) {
    int k=0;v[0]=0;z[0]=-INF;z[1]=INF;
    for(int q=1;q<n;q++){
      double s;
      do{int p=v[k];s=((f[q]+q*q)-(f[p]+p*p))/(2.0*(q-p));if(s<=z[k])k--;else break;}while(k>=0);
      k++;v[k]=q;z[k]=s;z[k+1]=INF;
    }
    k=0;for(int q=0;q<n;q++){while(z[k+1]<q)k++;double delta=q-v[k];d[q]=delta*delta+f[v[k]];}
  }
}
