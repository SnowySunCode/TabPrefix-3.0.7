ivec2 tabprefix_marker(out ivec4 bounds,out ivec4 core) {
  bounds=ivec4(0);core=ivec4(0);
  ivec2 size=textureSize(Sampler0,0), q=ivec2(floor(UV0*vec2(size)));
    if(any(lessThan(q,ivec2(0)))||any(greaterThanEqual(q,size))) return ivec2(0);
    ivec4 m=ivec4(round(texelFetch(Sampler0,q,0)*255.0));
    if(m.a!=1||m.r>7||(m.b!=214&&m.b!=215&&m.b!=216&&m.b!=217)) return ivec2(0);
    for(int side=-1;side<=1;side+=2) {
      ivec2 n=q+ivec2(0,side);
      if(any(lessThan(n,ivec2(0)))||any(greaterThanEqual(n,size))) continue;
      ivec4 magic=ivec4(round(texelFetch(Sampler0,n,0)*255.0));
      ivec2 edge=q+ivec2(0,2*side);
      if(any(lessThan(edge,ivec2(0)))||any(greaterThanEqual(edge,size))) continue;
      ivec4 dim=ivec4(round(texelFetch(Sampler0,edge,0)*255.0));
      if(all(equal(magic,ivec4(84,80,72,1)))&&dim.a==1&&(dim.b==200||dim.b==201)) {
        ivec2 cell=ivec2(dim.r==0?256:dim.r,dim.g==0?256:dim.g);
        if(cell.x<2||cell.y<4) continue;
        if(m.b==217) {
          ivec2 meta=q+ivec2(0,3*side),flag=q+ivec2(0,4*side);
          if(any(lessThan(flag,ivec2(0)))||any(greaterThanEqual(flag,size)))continue;
          ivec4 c=ivec4(round(texelFetch(Sampler0,meta,0)*255.0)),f=ivec4(round(texelFetch(Sampler0,flag,0)*255.0));
          if(c.a!=1||c.r<1||c.g<1||(c.b!=4&&c.b!=8)||f.a!=1||f.r>15||f.g!=84||f.b!=219)continue;
          if(cell.x!=(c.r+2)*c.b||cell.y!=max(8,c.g+2)*c.b)continue;
          core=ivec4(c.rgb,f.r);
        }
        bounds=ivec4(q-ivec2(dim.b==201?cell.x-1:0,side<0?cell.y-1:0),cell);
        return ivec2(m.r*256+m.g-1024,m.b);
      }
    }
  return ivec2(0);
}
