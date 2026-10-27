package me.snowsun.tabprefix.infrastructure.resourcepack;

/** Exact pixel area of a circular rounded rectangle, without polygonal corner approximations. */
final class RoundedCoverage {
  final double left,top,right,bottom,radius;
  RoundedCoverage(double x,double y,double width,double height,double radius) {
    left=x;top=y;right=x+Math.max(0,width);bottom=y+Math.max(0,height);
    this.radius=Math.max(0,Math.min(radius,Math.min(Math.max(0,width),Math.max(0,height))/2));
  }
  boolean contains(double x,double y) {
    if(x<left||x>=right||y<top||y>=bottom)return false;
    double dx=Math.max(Math.max(left+radius-x,x-right+radius),0);
    double dy=Math.max(Math.max(top+radius-y,y-bottom+radius),0);
    return dx*dx+dy*dy<=radius*radius;
  }
  double at(int x,int y) {
    double x0=Math.max(x,left),x1=Math.min(x+1d,right),y0=Math.max(y,top),y1=Math.min(y+1d,bottom);
    if(x1<=x0||y1<=y0)return 0;
    double area=(x1-x0)*(y1-y0),r=radius;
    if(r==0)return area;
    area-=outside(x0,y0,Math.min(x1,left+r),Math.min(y1,top+r),left+r,top+r,r);
    area-=outside(Math.max(x0,right-r),y0,x1,Math.min(y1,top+r),right-r,top+r,r);
    area-=outside(x0,Math.max(y0,bottom-r),Math.min(x1,left+r),y1,left+r,bottom-r,r);
    area-=outside(Math.max(x0,right-r),Math.max(y0,bottom-r),x1,y1,right-r,bottom-r,r);
    return Math.max(0,Math.min(1,area));
  }
  private static double outside(double x0,double y0,double x1,double y1,double cx,double cy,double r) {
    if(x1<=x0||y1<=y0)return 0;
    double a=Math.min(Math.abs(x0-cx),Math.abs(x1-cx)),b=Math.max(Math.abs(x0-cx),Math.abs(x1-cx));
    double c=Math.min(Math.abs(y0-cy),Math.abs(y1-cy)),d=Math.max(Math.abs(y0-cy),Math.abs(y1-cy));
    if(b*b+d*d<=r*r)return 0;
    double area=(b-a)*(d-c);
    if(a*a+c*c>=r*r)return area;
    double inside=quadrant(b,d,r)-quadrant(a,d,r)-quadrant(b,c,r)+quadrant(a,c,r);
    return Math.max(0,Math.min(area,area-inside));
  }
  private static double quadrant(double a,double b,double r) {
    a=Math.max(0,Math.min(r,a));b=Math.max(0,Math.min(r,b));
    if(a*a+b*b<=r*r)return a*b;
    double cut=Math.sqrt(Math.max(0,r*r-b*b));
    return b*cut+primitive(a,r)-primitive(cut,r);
  }
  private static double primitive(double x,double r) {
    return .5*(x*Math.sqrt(Math.max(0,r*r-x*x))+r*r*Math.asin(Math.max(-1,Math.min(1,x/r))));
  }
}
