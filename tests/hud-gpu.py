#!/usr/bin/env python3
"""Compile the shipped GLSL against Mojang includes and exercise it on surfaceless EGL.

This is resource-pack GPU QA, not a Minecraft client or a browser screenshot.
Requires Linux Mesa EGL/OpenGL, NumPy and Pillow. Run after test.sh.
"""
import ctypes as C
import io
import json
import pathlib
import re
import sys
import urllib.request
import zipfile
import numpy as np
from PIL import Image

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()
refs=json.loads((ROOT/'tests/shader-reference.json').read_text())

E=C.CDLL('libEGL.so.1');G=C.CDLL('libGL.so.1')
def bind(lib,name,result,args):
    f=getattr(lib,name);f.restype=result;f.argtypes=args;return f
ptr=C.c_void_p;uint=C.c_uint;integer=C.c_int;flt=C.c_float
getproc=bind(E,'eglGetProcAddress',ptr,[C.c_char_p])
platform=C.CFUNCTYPE(ptr,uint,ptr,C.POINTER(integer))(getproc(b'eglGetPlatformDisplayEXT'))
display=platform(0x31DD,None,None);major,minor=integer(),integer()
assert bind(E,'eglInitialize',uint,[ptr,ptr,ptr])(display,C.byref(major),C.byref(minor))
assert bind(E,'eglBindAPI',uint,[uint])(0x30A2)
config=ptr();count=integer();attrs=(integer*13)(0x3033,1,0x3040,8,0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3038)
assert bind(E,'eglChooseConfig',uint,[ptr,ptr,ptr,integer,ptr])(display,attrs,C.byref(config),1,C.byref(count)) and count.value
context=bind(E,'eglCreateContext',ptr,[ptr,ptr,ptr,ptr])(display,config,None,(integer*1)(0x3038))
assert context and bind(E,'eglMakeCurrent',uint,[ptr,ptr,ptr,ptr])(display,None,None,context)
gl={}
for name,result,args in [
 ('GetString',C.c_char_p,[uint]),('GetError',uint,[]),('CreateShader',uint,[uint]),('ShaderSource',None,[uint,integer,ptr,ptr]),('CompileShader',None,[uint]),('GetShaderiv',None,[uint,uint,ptr]),('GetShaderInfoLog',None,[uint,integer,ptr,ptr]),
 ('CreateProgram',uint,[]),('AttachShader',None,[uint,uint]),('TransformFeedbackVaryings',None,[uint,integer,ptr,uint]),('LinkProgram',None,[uint]),('GetProgramiv',None,[uint,uint,ptr]),('GetProgramInfoLog',None,[uint,integer,ptr,ptr]),('UseProgram',None,[uint]),
 ('GetUniformLocation',integer,[uint,C.c_char_p]),('Uniform1i',None,[integer,integer]),('Uniform1f',None,[integer,flt]),('Uniform4f',None,[integer,flt,flt,flt,flt]),('UniformMatrix4fv',None,[integer,integer,uint,ptr]),('UniformMatrix3fv',None,[integer,integer,uint,ptr]),('GetUniformIndices',None,[uint,integer,ptr,ptr]),('GetActiveUniformsiv',None,[uint,integer,ptr,uint,ptr]),('GetUniformBlockIndex',uint,[uint,C.c_char_p]),('UniformBlockBinding',None,[uint,uint,uint]),('GetActiveUniformBlockiv',None,[uint,uint,uint,ptr]),
 ('GenBuffers',None,[integer,ptr]),('BindBuffer',None,[uint,uint]),('BufferData',None,[uint,C.c_ssize_t,ptr,uint]),('BindBufferBase',None,[uint,uint,uint]),('GetBufferSubData',None,[uint,C.c_ssize_t,C.c_ssize_t,ptr]),
 ('GenVertexArrays',None,[integer,ptr]),('BindVertexArray',None,[uint]),('GetAttribLocation',integer,[uint,C.c_char_p]),('EnableVertexAttribArray',None,[uint]),('DisableVertexAttribArray',None,[uint]),('VertexAttribPointer',None,[uint,integer,uint,uint,integer,ptr]),('VertexAttribI2i',None,[uint,integer,integer]),
 ('GenTextures',None,[integer,ptr]),('ActiveTexture',None,[uint]),('BindTexture',None,[uint,uint]),('TexParameteri',None,[uint,uint,integer]),('TexImage2D',None,[uint,integer,integer,integer,integer,integer,uint,uint,ptr]),
 ('Enable',None,[uint]),('Disable',None,[uint]),('BlendFuncSeparate',None,[uint,uint,uint,uint]),('BeginTransformFeedback',None,[uint]),('EndTransformFeedback',None,[]),('DrawArrays',None,[uint,integer,integer]),('Finish',None,[]),
 ('GenFramebuffers',None,[integer,ptr]),('BindFramebuffer',None,[uint,uint]),('FramebufferTexture2D',None,[uint,uint,uint,uint,integer]),('CheckFramebufferStatus',uint,[uint]),('Viewport',None,[integer,integer,integer,integer]),('ClearColor',None,[flt,flt,flt,flt]),('Clear',None,[uint]),('ReadPixels',None,[integer,integer,integer,integer,uint,uint,ptr]),('DeleteTextures',None,[integer,ptr]),('DeleteBuffers',None,[integer,ptr]),('DeleteFramebuffers',None,[integer,ptr]),('DeleteProgram',None,[uint])]:
    gl[name]=bind(G,'gl'+name,result,args)
def call(name,*args):return gl[name](*args)
checks=0
def check(value,message):
    global checks
    if not value:raise AssertionError(message)
    checks+=1
def gen(kind):
    value=uint();call('Gen'+kind,1,C.byref(value));return value.value
def shader(source,kind):
    s=call('CreateShader',kind);data=C.c_char_p(source.encode());call('ShaderSource',s,1,C.byref(data),None);call('CompileShader',s);ok=integer();call('GetShaderiv',s,0x8B81,C.byref(ok))
    if not ok.value:
        log=C.create_string_buffer(32768);call('GetShaderInfoLog',s,len(log),None,log);raise AssertionError(log.value.decode())
    return s
def program(source,fragment,version,defines=()):
    def expand(code):
        def include(match):
            name=match.group(1).split(':')[-1].removesuffix('.glsl')
            return expand(re.sub(r'^#version[^\n]*','',refs[version+'/'+name],flags=re.M))
        return re.sub(r'#(?:moj_import|include) <([^>]+)>',include,code)
    source,fragment=expand(source),expand(fragment)
    for define in defines:
        source=source.replace('\n','\n#define '+define+'\n',1)
        fragment=fragment.replace('\n','\n#define '+define+'\n',1)
    p=call('CreateProgram');call('AttachShader',p,shader(source,0x8B31))
    call('AttachShader',p,shader(fragment,0x8B30));varyings=(C.c_char_p*4)(b'gl_Position',b'tabprefixBounds',b'tabprefixKind',b'tabprefixCore');call('TransformFeedbackVaryings',p,4,varyings,0x8C8C);call('LinkProgram',p);ok=integer();call('GetProgramiv',p,0x8B82,C.byref(ok))
    if not ok.value:
        log=C.create_string_buffer(32768);call('GetProgramInfoLog',p,len(log),None,log);raise AssertionError(log.value.decode())
    call('UseProgram',p)
    for name,unit in [(b'Sampler0',0),(b'Sampler2',2)]:call('Uniform1i',call('GetUniformLocation',p,name),unit)
    return p
def matrices(p,projection=None):
    identity=np.eye(4,dtype=np.float32);projection=identity if projection is None else projection
    for binding,(name,matrix) in enumerate([(b'DynamicTransforms',identity),(b'Projection',projection),(b'Fog',identity)]):
        block=call('GetUniformBlockIndex',p,name)
        if block!=0xffffffff:
            size=integer();call('GetActiveUniformBlockiv',p,block,0x8A40,C.byref(size));data=np.zeros(max(64,size.value)//4,dtype=np.float32);data[:16]=matrix.T.flatten()[:len(data)];
            if name==b'DynamicTransforms':
                names=(C.c_char_p*1)(b'ColorModulator');index=uint();call('GetUniformIndices',p,1,names,C.byref(index));offset=integer();call('GetActiveUniformsiv',p,1,C.byref(index),0x8A3B,C.byref(offset));data[offset.value//4:offset.value//4+4]=1
            if name==b'Fog':data[:]=0;data[4:10]=[1e9,1e10,1e9,1e10,1e10,1e10]
            buffer=gen('Buffers');call('BindBuffer',0x8A11,buffer);call('BufferData',0x8A11,data.nbytes,data.ctypes.data,0x88E4);call('UniformBlockBinding',p,block,binding);call('BindBufferBase',0x8A11,binding,buffer)
    for name,matrix in [(b'ModelViewMat',identity),(b'ProjMat',projection)]:
        matrix=np.ascontiguousarray(matrix.T);location=call('GetUniformLocation',p,name)
        if location>=0:call('UniformMatrix4fv',location,1,0,matrix.ctypes.data)
    call('UniformMatrix3fv',call('GetUniformLocation',p,b'IViewRotMat'),1,0,np.eye(3,dtype=np.float32).ctypes.data)
    call('Uniform1i',call('GetUniformLocation',p,b'FogShape'),0)
    call('Uniform4f',call('GetUniformLocation',p,b'ColorModulator'),1,1,1,1)
    call('Uniform1f',call('GetUniformLocation',p,b'FogStart'),1e9);call('Uniform1f',call('GetUniformLocation',p,b'FogEnd'),1e10)
def texture(image,unit=0):
    image=np.ascontiguousarray(image.convert('RGBA'),dtype=np.uint8);tex=gen('Textures');call('ActiveTexture',0x84C0+unit);call('BindTexture',0x0DE1,tex)
    for option,value in [(0x2801,0x2600),(0x2800,0x2600),(0x2802,0x812F),(0x2803,0x812F)]:call('TexParameteri',0x0DE1,option,value)
    call('TexImage2D',0x0DE1,0,0x8058,image.shape[1],image.shape[0],0,0x1908,0x1401,image.ctypes.data);return tex
def vertices(p,rows):
    data=np.asarray(rows,dtype=np.float32);call('BindBuffer',0x8892,vbo);call('BufferData',0x8892,data.nbytes,data.ctypes.data,0x88E0)
    for name,length,offset in [(b'Position',3,0),(b'Color',4,3),(b'UV0',2,7)]:
        loc=call('GetAttribLocation',p,name)
        if loc>=0:call('EnableVertexAttribArray',loc);call('VertexAttribPointer',loc,length,0x1406,0,36,ptr(offset*4))
    loc=call('GetAttribLocation',p,b'UV2')
    if loc>=0:call('DisableVertexAttribArray',loc);call('VertexAttribI2i',loc,0,0)
def feedback(p,image,box,color=(1,1,1,1)):
    tex=texture(image);w,h=image.size;x,y,cw,ch=box;uv=[((x+.01)/w,(y+.01)/h),((x+.01)/w,(y+ch-.01)/h),((x+cw-.01)/w,(y+ch-.01)/h),((x+cw-.01)/w,(y+.01)/h)]
    vertices(p,[[.2,.3,0,*color,*coords]for coords in uv]);call('BindBuffer',0x8C8E,tf);call('BufferData',0x8C8E,208,None,0x88E0);call('BindBufferBase',0x8C8E,0,tf);call('Enable',0x8C89);call('BeginTransformFeedback',0);call('DrawArrays',0,0,4);call('EndTransformFeedback');call('Disable',0x8C89);result=np.empty((4,13),np.float32);call('GetBufferSubData',0x8C8E,0,result.nbytes,result.ctypes.data);call('DeleteTextures',1,C.byref(uint(tex)))
    marker=image.getpixel((x,y));signed=marker[3]==1 and marker[2] in (214,215,216,217) and image.getpixel((x,y+1))==(84,80,72,1) and image.getpixel((x,y+2))[2]==200
    check(np.all(result[:,4:8].view(np.int32)==([x,y,cw,ch] if signed else [0,0,0,0])),f'every vertex reports the same isolated glyph bounds: {box}, signed={signed}, marker={marker}, raw={result.view(np.int32).tolist()}')
    check(np.all(result[:,8].view(np.int32)==(marker[2] if signed else 0)),'every vertex agrees on the private sampling path')
    core=(*image.getpixel((x,y+3))[:3],image.getpixel((x,y+4))[0]) if signed and marker[2]==217 else (0,0,0,0)
    check(np.all(result[:,9:13].view(np.int32)==core),'every vertex agrees on tile core, density and edge ownership')
    return result[:,:4]
def glyphs(pack):
    images={};result={};spaces={}
    for p in json.loads(pack.read('assets/minecraft/font/default.json'))['providers']:
        if p['type']=='space':spaces.update({ord(c):v for c,v in p['advances'].items()});continue
        if p.get('file','').endswith('hud-space.png'):
            spaces.update({ord(c):int(p['height']+.5)+1 for line in p['chars'] for c in line if c!='\0'});continue
        if 'file' not in p:continue
        path=p['file'].replace('tabprefix:','assets/tabprefix/textures/')
        if path not in images:images[path]=Image.open(io.BytesIO(pack.read(path))).convert('RGBA')
        image=images[path];cols=len(p['chars'][0]);cw=image.width//cols;ch=image.height//len(p['chars'])
        for row,line in enumerate(p['chars']):
            for col,char in enumerate(line):
                if char=='\0':continue
                crop=image.crop((col*cw,row*ch,(col+1)*cw,(row+1)*ch));bbox=crop.getchannel('A').getbbox();ink=bbox[2] if bbox else 0
                result[ord(char)]=(image,(col*cw,row*ch,cw,ch),p['height'],p['ascent'],int(ink*p['height']/ch+.5)+1)
    return result,spaces
def render(p,profile,frame,table,spaces,width,height,scale,blend=False):
    pixel_width,pixel_height=round(width*scale),round(height*scale)
    fbo=gen('Framebuffers');call('BindFramebuffer',0x8D40,fbo);outtex=texture(Image.new('RGBA',(pixel_width,pixel_height)),1);call('FramebufferTexture2D',0x8D40,0x8CE0,0x0DE1,outtex,0);check(call('CheckFramebufferStatus',0x8D40)==0x8CD5,'framebuffer complete');call('Viewport',0,0,pixel_width,pixel_height);call('ClearColor',0,0,0,0);call('Clear',0x4000)
    projection=np.array([[2/width,0,0,-1],[0,-2/height,0,1],[0,0,-1,0],[0,0,0,1]],dtype=np.float32);matrices(p,projection)
    if blend:call('Enable',0x0BE2);call('BlendFuncSeparate',0x0302,0x0303,1,0x0303)
    cursor=0;run=profile['glyphRuns'][frame] if 'glyphRuns' in profile else chr(profile['base']+frame);i=0;originx,originy=profile.get('x',12),12-profile['y']
    while i<len(run):
        c=ord(run[i]);i+=1
        if c==167:i+=1;continue
        if c in spaces:cursor+=spaces[c];continue
        image,box,logical_height,ascent,advance=table[c];x,y,cw,ch=box;logical_width=cw*logical_height/ch;top=originy+7-ascent;left=originx+cursor
        tex=texture(image);points=[]
        for dx,dy in [(0,0),(0,1),(1,1),(0,0),(1,1),(1,0)]:points.append([left+dx*logical_width,top+dy*logical_height,0,1,1,1,1,(x+(.01 if dx==0 else cw-.01))/image.width,(y+(.01 if dy==0 else ch-.01))/image.height])
        vertices(p,points);call('DrawArrays',4,0,6);call('DeleteTextures',1,C.byref(uint(tex)));cursor+=advance
    check(cursor==profile['advances'][frame],'GPU input cursor matches the persisted profile')
    result=np.empty((pixel_height,pixel_width,4),dtype=np.uint8);call('ReadPixels',0,0,pixel_width,pixel_height,0x1908,0x1401,result.ctypes.data);call('DeleteTextures',1,C.byref(uint(outtex)));call('DeleteFramebuffers',1,C.byref(uint(fbo)));call('BindFramebuffer',0x8D40,dummy_fbo);call('Disable',0x0BE2);return Image.fromarray(np.flipud(result))

def area_reference(source,width,height,scale,origin_x,origin_y,density):
    rgba=np.asarray(source,dtype=np.float64)/255
    rgba[:,:,:3]*=rgba[:,:,3:4]
    def axis(data,out_count,origin,axis):
        data=np.moveaxis(data,axis,0);prefix=np.concatenate([np.zeros_like(data[:1]),np.cumsum(data,axis=0)],axis=0)
        centre=((np.arange(out_count)+.5)/scale-origin)*density
        footprint=max(1,min(8,density/scale))
        def integral(points):
            points=np.clip(points,0,len(data));base=np.floor(points).astype(int);fraction=points-base
            shape=(len(points),)+(1,)*(data.ndim-1)
            return prefix[base]+data[np.minimum(base,len(data)-1)]*fraction.reshape(shape)
        return np.moveaxis((integral(centre+footprint/2)-integral(centre-footprint/2))/footprint,0,axis)
    return np.clip(np.rint(axis(axis(rgba,round(width*scale),origin_x,1),round(height*scale),origin_y,0)*255),0,255).astype(np.uint8)

vao=gen('VertexArrays');call('BindVertexArray',vao);vbo=gen('Buffers');tf=gen('Buffers');texture(Image.new('RGBA',(1,1),'white'),2)
dummy_fbo=gen('Framebuffers');call('BindFramebuffer',0x8D40,dummy_fbo);dummy_tex=texture(Image.new('RGBA',(16,16)),1);call('FramebufferTexture2D',0x8D40,0x8CE0,0x0DE1,dummy_tex,0);check(call('CheckFramebufferStatus',0x8D40)==0x8CD5,'offscreen transform feedback framebuffer complete')
proof=json.loads((ROOT/'.build/hud-gpu-proof.json').read_text());pictures=[]
if (ROOT/'.build/text-quality-proof.json').exists():proof+=json.loads((ROOT/'.build/text-quality-proof.json').read_text())
for item in proof:
    with zipfile.ZipFile(item['pack']) as pack:
        shader_name='text' if item['version'] in ['26.2','26.3'] else 'rendertype_text'
        vertex=pack.read('assets/minecraft/shaders/core/'+shader_name+'.vsh').decode();fragment=pack.read('assets/minecraft/shaders/core/'+shader_name+'.fsh').decode()
        table,spaces=glyphs(pack);hud=json.loads(pack.read('tabprefix-hud.json'));profiles={v['id']:v for v in hud['profiles']}
        if shader_name=='text':
            for flags in [('IS_GUI',),('IS_GUI','IS_GRAYSCALE'),('IS_SEE_THROUGH',),('IS_SEE_THROUGH','IS_GRAYSCALE')]:
                variant=program(vertex,fragment,item['version'],flags);matrices(variant);check(variant!=0,'native GUI/grayscale/see-through shader variants link')
                image,box,*_=next(value for value in table.values() if value[0].getpixel((value[1][0],value[1][1]))[2] in (214,217))
                marker=image.getpixel(box[:2]);out=feedback(variant,image,box)
                check(np.allclose(out[:,1],.3+marker[0]*256+marker[1]-1024,atol=.0001),'GUI variants keep the exact private glyph displacement')
                if flags==('IS_GUI',) and 'screen:media' in profiles:
                    sample=render(variant,profiles['screen:media'],0,table,spaces,96,48,3)
                    check(sample.getchannel('A').getbbox() is not None,'the native GUI pipeline actually draws HD media')
                if 'IS_GUI' in flags:
                    font=profiles['tab:compact'];letter=ord('A')-33
                    sample=render(variant,font,letter,table,spaces,48,40,4)
                    if flags==('IS_GUI',):private_gui=np.asarray(sample)
                    elif flags==('IS_GUI','IS_GRAYSCALE'):
                        check(np.array_equal(private_gui,np.asarray(sample)),'private HD text keeps its coverage through native grayscale variants')
                call('DeleteProgram',variant)
        p=program(vertex,fragment,item['version']);matrices(p)
        for graphic in [False,True]:
            candidates=[value for value in table.values() if value[0].getpixel((value[1][0],value[1][1]))[2] in ((214,217) if graphic else (216,))]
            check(bool(candidates),'signed glyphs exist')
            for image,box,logical_height,ascent,advance in candidates[:3]:
                mark=image.getpixel(box[:2]);offset=mark[0]*256+mark[1]-1024;out=feedback(p,image,box);check(np.allclose(out[:,1],.3+offset,atol=.0001),f'all four actual shader vertices use the exact signed Y offset: {item["version"]}, {box}, {mark}, {offset}, {out.tolist()}, GPU result')
                shadow=feedback(p,image,box,(.25,.25,.25,1));check(np.allclose(shadow,[3,3,3,1]) if graphic else np.allclose(shadow[:,1],.3+offset,atol=.0001),'graphic shadows disappear; dark text remains valid')
        marked,box,*_=candidates[0];bx,by,bw,bh=box
        neighbour=Image.new('RGBA',(bw*2,bh),'white');neighbour.paste(marked.crop((bx,by,bx+bw,by+bh)),(0,0));out=feedback(p,neighbour,(bw,0,bw,bh));check(np.allclose(out,[.2,.3,0,1]),'a marked neighbour never changes an ordinary glyph')
        plain=Image.new('RGBA',(12,12),'white');out=feedback(p,plain,(0,0,12,12));check(np.allclose(out,[.2,.3,0,1]),'unmarked game text and textures retain vanilla positions')
        ordinary=Image.new('RGBA',(8,8));ordinary_pixels=ordinary.load()
        for oy in range(8):
            for ox in range(8):ordinary_pixels[ox,oy]=[(255,0,0,255),(0,255,0,255),(0,0,255,255),(255,255,0,255)][(oy//4)*2+ox//4]
        ordinary_code=0xe999;ordinary_out=render(p,{'y':0,'base':ordinary_code,'advances':[9]},0,{ordinary_code:(ordinary,(0,0,8,8),8,7,9)},{},32,32,3)
        check(ordinary_out.getpixel((13*3,13*3))==(255,0,0,255) and ordinary_out.getpixel((17*3,17*3))==(255,255,0,255),'unmarked image sampling and colours remain native');matrices(p)
        # A marker without its signature must never activate the private path.
        plain.putpixel((0,0),(3,75,214,1));out=feedback(p,plain,(0,0,12,12));check(np.allclose(out,[.2,.3,0,1]),'unpaired marker colours do not affect ordinary assets')
        if item['version']=='1.21.11':
            panel=profiles['sidebar:background'];panelheight=160
            for scale in [1,2,3,4]:
                image=render(p,panel,0,table,spaces,256,300,scale);image.save(ROOT/f'.build/hud-gpu-scoreboard-{scale}x.png');check(image.getchannel('A').getbbox() is not None,'GPU scoreboard is visible at each GUI scale')
            alphabet=''.join(chr(c)for lo,hi in [(33,126),(160,383),(1024,1327),(8192,8303)]for c in range(lo,hi+1))+'★☆♡♥✦✧◆◇●○■□▪▫▲▼↑↓←→✓✕⚔⚙'
            font=profiles['sidebar:title'];word='TESTSERVER · Привет';run='';advance=0
            for char in word:
                if char==' ':
                    amount=max(2,font['height']//2)
                    for bit in range(12,-1,-1):
                        if amount&(1<<bit):run+=chr(0xff000+bit)
                    advance+=amount
                else:
                    code=font['base']+alphabet.index(char);run+=chr(code);advance+=table[code][4]
            textprofile={'y':font['y'],'glyphRuns':[run],'advances':[advance]}
            for scale in [1,3,4]:
                text=render(p,textprofile,0,table,spaces,advance+24,48,scale);text.save(ROOT/f'.build/hud-gpu-text-{scale}x.png');check(text.getchannel('A').getbbox() is not None,'GPU Latin and Cyrillic text stays visible')
            for frame in [0,1,6,7]:
                width=(280-5*(frame//2))//(frame//2+1)-22-6+13+(9 if frame%2 else 0)
                image=render(p,profiles['tab:cell:0:row'],frame,table,spaces,width+24,32,4);image.save(ROOT/f'.build/hud-gpu-tab-{frame}.png');check(image.getchannel('A').getbbox()[2]<=width*4+49,'TAB frame ends at the intended row edge')
        if 'screen:media' in profiles:
            for key in ['screen:media','tab:image:00000000-0000-0000-0000-000000000305']:
                photo=profiles[key];pictures=[]
                for frame in [0,1]:
                    sample=render(p,photo,frame,table,spaces,96,48,3)
                    check(sample.getchannel('A').getbbox() is not None,'HD PNG/GIF stays visible at its authored Y')
                    pictures.append(np.asarray(sample))
                    if item['version']=='26.3':sample.save(ROOT/('.build/hud-gpu-'+key.split(':')[0]+'-media-'+str(frame)+'.png'))
                check(not np.array_equal(pictures[0],pictures[1]),'different GIF frames reach the framebuffer')
            if item['version']=='1.21.11':
                source_dir=pathlib.Path(item['pack']).parent.parent/'dataDirectory/assets/00000000-0000-0000-0000-000000000305'
                for frame in [0,1]:
                    source=Image.open(source_dir/f'frame-{frame:04d}.png').convert('RGBA')
                    check(source.size==(256,64),'media reference has exactly four source samples per GUI pixel')
                    for scale in [.75,1,1.25,2,4,8]:
                        for offset in [0,.37,.75]:
                            photo=profiles['screen:media'];placed=dict(photo,x=12+offset,y=photo['y']-offset)
                            actual=np.asarray(render(p,placed,frame,table,spaces,96,48,scale,blend=True)).astype(int)
                            expected=area_reference(source,96,48,scale,12+offset,12+offset,4).astype(int)
                            check(np.abs(actual-expected).max()<=2,f'actual 4x GIF tiles preserve continuous coverage, colours and gutters: frame={frame}, scale={scale}, offset={offset}, max={np.abs(actual-expected).max()}')
        if item['version']=='1.21.11':
            for thickness in [.25,.5,.75,1,1.5,2]:
                synthetic=Image.new('RGBA',(32,40));pixels=synthetic.load()
                for y in range(4,36):
                    for x in range(round(thickness*4)):pixels[x,y]=(124,220,209,255)
                for x in [0,31]:
                    for y,side in [(0,1),(39,-1)]:
                        pixels[x,y]=(3,255,214,1);pixels[x,y+side]=(84,80,72,1);pixels[x,y+2*side]=(32,40,200 if x==0 else 201,1)
                code=0xf1234;small={code:(synthetic,(0,0,32,40),10,7,9)};profile={'y':0,'base':code,'advances':[9]}
                for scale in [1,2,3,4]:
                    sample=render(p,profile,0,small,{},32,32,scale)
                    area=sum(sample.getpixel((x,16*scale))[3]/255 for x in range(32*scale))
                    check(abs(area-thickness*scale)<.025,f'thin stroke survives GPU minification: {thickness}, GUI {scale}, area {area}')
            isolation=Image.new('RGBA',(64,40));pixels=isolation.load()
            for column,color in [(0,(255,0,0,255)),(32,(0,0,255,255))]:
                for y in range(4,36):
                    for x in range(column,column+32):pixels[x,y]=color
                for x in [column,column+31]:
                    for y,side in [(0,1),(39,-1)]:
                        pixels[x,y]=(3,255,214,1);pixels[x,y+side]=(84,80,72,1);pixels[x,y+2*side]=(32,40,200 if x==column else 201,1)
            isolated=render(p,{'y':0,'x':12.75,'base':0xf1234,'advances':[9]},0,{0xf1234:(isolation,(0,0,32,40),10,7,9)},{},32,32,1)
            color=isolated.getpixel((20,16));check(color[0]==255 and color[2]==0 and abs(color[3]-191)<=2,'area filtering never leaks the neighbouring atlas glyph into a subpixel edge')
        if item['version']=='1.21.11' and 'screen:size4' in profiles:
            alphabet=''.join(chr(c)for lo,hi in [(33,126),(160,383),(1024,1327),(8192,8303)]for c in range(lo,hi+1))+'★☆♡♥✦✧◆◇●○■□▪▫▲▼↑↓←→✓✕⚔⚙'
            for size in [4,7,8,12,16,24,32]:
                for bold in [False,True]:
                    font=profiles['screen:size'+str(size)];run='';advance=0
                    for char in 'АпельсинчикДаня jW':
                        if char==' ':
                            amount=max(2,font['height']//2)
                            for bit in range(12,-1,-1):
                                if amount&(1<<bit):run+=chr(0xff000+bit)
                            advance+=amount
                        else:
                            code=font['base']+(0x10000 if bold else 0)+alphabet.index(char);run+=chr(code);advance+=table[code][4]
                    profile={'y':font['y'],'glyphRuns':[run],'advances':[advance]}
                    for scale in [.75,1,1.25,1.5,2,3,4,6,8]:
                        sample=render(p,profile,0,table,spaces,advance+32,size+40,scale);alpha=np.asarray(sample)[:,:,3]
                        check(alpha.max()>0,'Latin/Cyrillic font remains visible across text sizes, weights and fractional GUI scales')
                        check(np.any((alpha>0)&(alpha<255)),'text edges retain antialiased coverage at every scale')
                        bbox=sample.getchannel('A').getbbox();check(bbox[0]>0 and bbox[2]<sample.width and bbox[3]<sample.height,'enlarged glyphs retain margins and descenders without clipping')
                        if size==16 and scale==4:sample.save(ROOT/('.build/text-quality-'+('bold' if bold else 'regular')+'.png'))
            # Synthetic distance field: enlarged vertical edges cover at most one output pixel each.
            field=Image.new('RGBA',(32,40));pixels=field.load();left,right=6.3,18.7
            for sy in range(40):
                for sx in range(32):
                    distance=min(sx+.5-left,right-sx-.5,sy+.5-4,36-sy-.5)
                    coverage=max(0,min(sx+1,right)-max(sx,left)) if 4<=sy<36 else 0
                    pixels[sx,sy]=(round(max(0,min(255,128+distance*16))),round(coverage*255),0,255)
            for sx in [0,31]:
                for sy,side in [(0,1),(39,-1)]:
                    pixels[sx,sy]=(3,255,216,1);pixels[sx,sy+side]=(84,80,72,1);pixels[sx,sy+2*side]=(32,40,200 if sx==0 else 201,1)
            for scale in [1,2,3,4,6,8,12]:
                code=0xf4321;sample=render(p,{'y':0,'x':12.37,'base':code,'advances':[9]},0,{code:(field,(0,0,32,40),10,7,9)},{},32,32,scale)
                row=np.asarray(sample)[16*scale,:,3];ink=row.sum()/255
                check(abs(ink-(right-left)*scale/4)<.16,'distance-field scaling preserves stroke width and subpixel placement')
                if scale>=8:check(np.count_nonzero((row>0)&(row<255))<=2,'magnified contours use one antialias pixel per edge without a doubled halo')
        call('DeleteProgram',p)
# Fractionally positioned, translucent real tile composites must equal one continuous image.
# A separable integral computes an independent premultiplied area-filter reference.
contour_file=ROOT/'.build/contour-quality-proof.json'
if contour_file.exists():
    item=json.loads(contour_file.read_text())
    with zipfile.ZipFile(item['pack'])as pack:
        p=program(pack.read('assets/minecraft/shaders/core/rendertype_text.vsh').decode(),pack.read('assets/minecraft/shaders/core/rendertype_text.fsh').decode(),item['version'])
        table,spaces=glyphs(pack);hud=json.loads(pack.read('tabprefix-hud.json'));profile=next(v for v in hud['profiles']if v['id']=='sidebar:background')
        source=Image.open(item['reference']).convert('RGBA')
        for scale in [.75,1,1.25,1.5,2,3,4,6,8]:
            for offset in [0,.125,.37,.5,.75]:
                placed=dict(profile,x=12+offset,y=profile['y']-offset)
                actual=render(p,placed,0,table,spaces,192,128,scale,blend=True)
                expected=area_reference(source,192,128,scale,12+offset,12+offset,8)
                difference=np.abs(np.asarray(actual).astype(int)-expected.astype(int))
                check(difference.max()<=2,f'continuous premultiplied area coverage across all horizontal/vertical tile seams: scale={scale}, offset={offset}, max={difference.max()}, pixel={np.unravel_index(difference.argmax(),difference.shape)}')
                alpha=np.asarray(actual)[:,:,3];interior=alpha[round((12+offset+15)*scale):round((12+offset+57)*scale),round((12+offset+15)*scale):round((12+offset+121)*scale)]
                check(interior.min()==interior.max()==191,'translucent interior has neither overlap bands nor transparent cracks')
                if offset==.37 and scale in [1,4,8]:actual.save(ROOT/f'.build/contour-gpu-{scale}x.png')
        call('DeleteProgram',p)
check(call('GetError')==0,'no OpenGL errors')
print(json.dumps({'checks':checks,'renderer':call('GetString',0x1F01).decode(),'OpenGL':call('GetString',0x1F02).decode(),'versions':[p['version']for p in proof]}))
