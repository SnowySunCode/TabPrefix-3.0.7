vec4 tabprefix_sample(ivec2 p) {
  if(any(lessThan(p,tabprefixBounds.xy))||any(greaterThanEqual(p,tabprefixBounds.xy+tabprefixBounds.zw))) return vec4(0.0);
  vec4 c=texelFetch(Sampler0,p,0);
  if(c.a<=1.5/255.0) return vec4(0.0);
  if(tabprefixKind==216) return vec4(vec3(c.g),c.g);
  return vec4(c.rgb*c.a,c.a);
}
float tabprefix_distance(ivec2 p) {
  if(any(lessThan(p,tabprefixBounds.xy))||any(greaterThanEqual(p,tabprefixBounds.xy+tabprefixBounds.zw))) return -8.0;
  vec4 c=texelFetch(Sampler0,p,0);
  return c.a<=1.5/255.0?-8.0:(c.r*255.0-128.0)/16.0;
}
vec4 tabprefix_color() {
  vec2 size=vec2(textureSize(Sampler0,0)),cell=vec2(tabprefixBounds.zw);
  // Vanilla insets atlas UVs by .01 texel. Undo it for a true one-to-one pixel footprint.
  vec2 local=(texCoord0*size-vec2(tabprefixBounds.xy)-0.01)*cell/(cell-0.02);
  vec2 center=vec2(tabprefixBounds.xy)+local,pixels=fwidth(center);
  vec2 footprint=clamp(pixels,vec2(1.0),vec2(8.0));
  if(tabprefixKind==217) {
    vec2 start=vec2(tabprefixCore.z),end=(vec2(tabprefixCore.xy)+1.0)*float(tabprefixCore.z);
    // Gutters supply real neighbours; exactly one tile owns each fragment centre.
    vec2 owned=mix(local,start,lessThan(abs(local-start),vec2(0.01)));
    owned=mix(owned,end,lessThan(abs(owned-end),vec2(0.01)));
    int edges=tabprefixCore.w;
    if(((edges&1)==0&&owned.x<start.x)||((edges&2)==0&&owned.x>=end.x)
        ||((edges&4)==0&&owned.y<start.y)||((edges&8)==0&&owned.y>=end.y))discard;
  }
  vec2 lo=center-footprint*0.5,hi=center+footprint*0.5;
  ivec2 first=ivec2(floor(lo));
  vec4 sum=vec4(0.0);
  for(int y=0;y<9;y++){if(float(first.y+y)>=hi.y)break;for(int x=0;x<9;x++){if(float(first.x+x)>=hi.x)break;
    ivec2 p=first+ivec2(x,y);
    vec2 weight=max(vec2(0.0),min(vec2(p)+1.0,hi)-max(vec2(p),lo));
    sum+=tabprefix_sample(p)*weight.x*weight.y;
  }}
  if(tabprefixKind==216&&max(pixels.x,pixels.y)<1.15) {
    vec2 point=center-0.5,weight=fract(point);ivec2 q=ivec2(floor(point));
    float distance=mix(mix(tabprefix_distance(q),tabprefix_distance(q+ivec2(1,0)),weight.x),
                       mix(tabprefix_distance(q+ivec2(0,1)),tabprefix_distance(q+ivec2(1,1)),weight.x),weight.y);
    float edge=clamp(0.5+distance/max(length(vec2(dFdx(distance),dFdy(distance))),0.001),0.0,1.0);
    float alpha=mix(sum.a/(footprint.x*footprint.y),edge,1.0-smoothstep(0.65,1.15,max(pixels.x,pixels.y)));
    return vec4(vec3(1.0),alpha);
  }
  if(sum.a<=0.0)return vec4(0.0);
  return vec4(sum.rgb/sum.a,sum.a/(footprint.x*footprint.y));
}
