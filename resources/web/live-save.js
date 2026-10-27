'use strict';
// Shared persistence queue. It never locks editing or acknowledges changes made after a request began.
(() => {
 const copy=v=>v===undefined?undefined:JSON.parse(JSON.stringify(v));
 const equal=(a,b)=>{if(a===b)return true;if(Array.isArray(a)&&Array.isArray(b))return a.length===b.length&&a.every((v,n)=>equal(v,b[n]));if(a&&b&&typeof a==='object'&&typeof b==='object'&&!Array.isArray(a)&&!Array.isArray(b)){const keys=Object.keys(a);return keys.length===Object.keys(b).length&&keys.every(k=>Object.hasOwn(b,k)&&equal(a[k],b[k]));}return false;};
 const object=v=>v!==null&&typeof v==='object'&&!Array.isArray(v);
 const key=v=>object(v)?v.id||v.group|| (v.surface!==undefined?v.surface+':'+v.index:null):null;
 function merge(base,local,remote,path='',conflicts=[]) {
  if(equal(local,base))return copy(remote);if(equal(remote,base)||equal(local,remote))return copy(local);
  if(object(base)&&object(local)&&object(remote)) {
   const result={};for(const name of new Set([...Object.keys(base),...Object.keys(local),...Object.keys(remote)])) {
    const value=merge(base[name],local[name],remote[name],path+'/'+name,conflicts);if(value!==undefined)result[name]=value;
   }return result;
  }
  if(Array.isArray(base)&&Array.isArray(local)&&Array.isArray(remote)) {
   const keys=arr=>arr.map(key),b=keys(base),l=keys(local),r=keys(remote),stable=[b,l,r].every(a=>a.every(v=>v!==null)&&new Set(a).size===a.length);
   if(stable){
    const by=arr=>new Map(arr.map(v=>[key(v),v])),bm=by(base),lm=by(local),rm=by(remote);
    const survivors=order=>order.filter(id=>lm.has(id)&&rm.has(id)||!bm.has(id));
    const lb=survivors(l).filter(id=>bm.has(id)),rb=survivors(r).filter(id=>bm.has(id)),bb=b.filter(id=>lb.includes(id)&&rb.includes(id));
    const movedL=!equal(lb.filter(id=>bb.includes(id)),bb),movedR=!equal(rb.filter(id=>bb.includes(id)),bb);
    if(movedL&&movedR&&!equal(lb,rb)){conflicts.push(path);return copy(local);}
    const changedL=!equal(l,b),changedR=!equal(r,b);if(changedL&&changedR&&!equal(l,r)){conflicts.push(path);return copy(local);}const order=[...(changedL?l:r)];for(const id of [...l,...r])if(!order.includes(id))order.push(id);
    const result=[];for(const id of order){const v=merge(bm.get(id),lm.get(id),rm.get(id),path+'/'+id,conflicts);if(v!==undefined)result.push(v);}return result;
   }
   if(base.length===local.length&&base.length===remote.length)return base.map((v,n)=>merge(v,local[n],remote[n],path+'/'+n,conflicts));
  }
  conflicts.push(path||'/');return copy(local);
 }
 function reconcile(target,next){
  if(Array.isArray(target)&&Array.isArray(next)){
   const by=new Map(target.filter(v=>key(v)!==null).map(v=>[key(v),v]));
   const values=next.map((v,n)=>{const old=key(v)!==null?by.get(key(v)):target[n];if(object(old)&&object(v)||Array.isArray(old)&&Array.isArray(v)){reconcile(old,v);return old;}return copy(v);});target.splice(0,target.length,...values);return target;
  }
  for(const name of Object.keys(target))if(!(name in next))delete target[name];
  for(const [name,v]of Object.entries(next)){if(object(target[name])&&object(v)||Array.isArray(target[name])&&Array.isArray(v))reconcile(target[name],v);else target[name]=copy(v);}return target;
 }
 // One metadata request feeds all editor domains. Media is deliberately loaded separately.
 class StateSync {
  constructor({fetch,view,onState=()=>{}}){this.fetch=fetch;this.view=view;this.onState=onState;this.data={};this.versions={};this.inflight=null;this.epoch=0;this.scanned=0;}
  async read(force=false){
   if(this.inflight)return this.inflight;
   const epoch=this.epoch,query=new URLSearchParams({view:this.view});
   if(!force)for(const[key,value]of Object.entries(this.versions))query.set(key,value);
   const operation=this.fetch('workspace?'+query).then(next=>{
    if(epoch!==this.epoch)return null;
    this.versions={...this.versions,...next.versions};
    this.data={...this.data,...next};this.scanned=Date.now();this.onState(next,this.data);return this.data;
   });
   this.inflight=operation;
   let result;try{result=await operation;}finally{if(this.inflight===operation)this.inflight=null;}
   return result||this.read(true);
  }
  acknowledge(key,document,revision){this.epoch++;this.scanned=0;this.data[key]=copy(document);if(revision!==undefined)this.versions[key]=String(revision);else delete this.versions[key];}
 }
 // Records are scoped by server, owner and editor, and survive a new tab/session token.
 class Journal {
  constructor({workspace,owner,scope,client,storage=globalThis.localStorage,legacy=[]}){
   this.storage=storage;this.legacy=legacy;this.adopted=new Map();
   this.prefix='tabprefix-journal-v2:'+encodeURIComponent(workspace)+':'+owner+':'+encodeURIComponent(scope)+':';
   if(!client){try{client=sessionStorage.getItem('tabprefix-editor-client');if(!client){client=Array.from(crypto.getRandomValues(new Uint8Array(8)),v=>v.toString(16).padStart(2,'0')).join('');sessionStorage.setItem('tabprefix-editor-client',client);}}catch(_){client=Date.now().toString(36)+Math.random().toString(36).slice(2);}}
   this.key=this.prefix+client;
  }
  keys(){const storage=this.storage;if(!storage)return[];const keys=[];if(typeof storage.key==='function')for(let n=0;n<storage.length;n++){const key=storage.key(n);if(key&&(key.startsWith(this.prefix)&&!key.endsWith(':index')||this.legacy.some(prefix=>key.startsWith(prefix))))keys.push(key);}else{try{keys.push(...JSON.parse(storage.getItem(this.prefix+'index')||'[]'),this.key);}catch(_){keys.push(this.key);}}return [...new Set(keys)];}
  read(){const records=[];this.adopted.clear();try{for(const key of this.keys()){const raw=this.storage.getItem(key);if(!raw)continue;try{const record=JSON.parse(raw);if(!record||!record.base||!record.document||Date.now()-record.time>604800000){this.storage.removeItem(key);continue;}records.push(record);this.adopted.set(key,raw);}catch(_){this.storage.removeItem(key);}}}catch(_){}return records.sort((a,b)=>a.time-b.time);}
  write(value){try{if(value){this.storage.setItem(this.key,JSON.stringify({...value,time:Date.now()}));const key=this.prefix+'index',index=JSON.parse(this.storage.getItem(key)||'[]');if(!index.includes(this.key))this.storage.setItem(key,JSON.stringify([...index.slice(-15),this.key]));}else{this.storage.removeItem(this.key);for(const[key,raw]of this.adopted)if(this.storage.getItem(key)===raw)this.storage.removeItem(key);this.adopted.clear();}}catch(_){} }
 }
 function bindLifecycle(getSavers){
  const flush=()=>{for(const saver of getSavers().filter(Boolean))if(saver.dirty){saver.emit(saver.phase);saver.flush().catch(()=>{});}};
  window.addEventListener('pagehide',flush);window.addEventListener('beforeunload',flush);
  document.addEventListener('visibilitychange',()=>{if(document.hidden)flush();});
  return flush;
 }

 class LiveSave {
  constructor({get,apply,read,write,revision,base,status=()=>{},conflict=()=>{},journal=()=>{},delay=650,maxDelay=3000}){
   Object.assign(this,{get,apply,read,write,revision,status,conflict,journal,delay,maxDelay});this.base=copy(base);this.phase='saved';this.notifiedRevision=undefined;this.first=0;this.inflight=null;this.timer=null;this.conflicts=[];this.retry=500;this.closed=false;this.error=null;
  }
  get dirty(){return !equal(this.get(),this.base);}
  emit(phase,error=null){const notify=phase!==this.phase||error!==this.error||this.notifiedRevision!==this.revision;this.phase=phase;this.error=error;if(notify){this.notifiedRevision=this.revision;this.status(phase,error);}this.journal(this.dirty?{base:copy(this.base),document:copy(this.get()),revision:this.revision}:null);}
  change(){if(this.closed)return;this.retry=500;if(this.conflicts.length){this.emit('conflict');return;}if(!this.dirty){clearTimeout(this.timer);this.first=0;this.emit('saved');return;}this.emit(this.inflight?'saving':'waiting');if(!this.first)this.first=Date.now();clearTimeout(this.timer);this.timer=setTimeout(()=>this.flush().catch(()=>{}),Math.max(0,Math.min(this.delay,this.maxDelay-(Date.now()-this.first))));}
  async absorb(data){if(!data||data.document===undefined)return false;
   const conflicts=[],local=copy(this.get()),merged=merge(this.base,local,data.document,'',conflicts),alternative=conflicts.length?merge(this.base,data.document,local):null;this.base=copy(data.document);this.revision=data.revision;
   if(!equal(merged,local))this.apply(merged);
   if(conflicts.length){this.conflicts=[...new Set(conflicts)];this.remote=alternative;this.emit('conflict');this.conflict(this.conflicts);return false;}
   this.emit(this.dirty?'waiting':'saved');return true;
  }
  async refresh(){if(this.closed||this.conflicts.length||this.phase==='invalid'||this.phase==='expired'||this.phase==='offline'&&this.dirty)return;if(this.inflight)return this.inflight;const operation=this.read(this.revision).then(async data=>{await this.absorb(data);if(!data)this.emit(this.dirty?'waiting':'saved');}).catch(e=>{this.emit(e.status===401||e.status===403?'expired':'offline',e);});this.inflight=operation;try{await operation;}finally{if(this.inflight===operation)this.inflight=null;if(this.dirty&&!this.conflicts.length&&this.phase==='waiting')this.change();}}
  flush(){clearTimeout(this.timer);this.first=0;if(this.closed)return Promise.reject(Error('Editor closed'));if(this.conflicts.length)return Promise.reject(Error('Conflicting fields'));
   if(this.inflight)return this.inflight.then(()=>this.dirty?this.flush():undefined);
   if(!this.dirty){this.emit('saved');return Promise.resolve();}
   const operation=this.save();this.inflight=operation;return operation.finally(()=>{if(this.inflight===operation)this.inflight=null;}).then(()=>{if(this.conflicts.length)throw Error('Conflicting fields');if(this.dirty)return this.flush();});
  }
  async save(){
   for(let attempt=0;attempt<3;attempt++){
    const sent=copy(this.get());this.emit('saving');
    try{
     const data=await this.write(sent,this.revision),canonical=data.document===undefined?sent:data.document;
     const conflicts=[],latest=copy(this.get()),merged=merge(sent,latest,canonical,'',conflicts),alternative=conflicts.length?merge(sent,canonical,latest):null;
     this.base=copy(canonical);this.revision=data.revision;this.retry=500;
     if(!equal(merged,latest))this.apply(merged);
     if(conflicts.length){this.conflicts=conflicts;this.remote=alternative;this.emit('conflict');this.conflict(conflicts);return;}
     this.emit(this.dirty?'waiting':'saved');if(this.dirty){this.first=Date.now();this.timer=setTimeout(()=>this.flush().catch(()=>{}),this.delay);}return;
    }catch(e){
     if(e.status===409){try{const before=this.revision;const latest=await this.read(-1);await this.absorb(latest);if(this.conflicts.length)return;if(before!==this.revision)continue;}catch(readError){e=readError;}}
     const phase=e.status===401||e.status===403?'expired':e.status===400||e.status===413?'invalid':'offline';this.emit(phase,e);
     if(phase==='offline'){this.timer=setTimeout(()=>this.flush().catch(()=>{}),this.retry);this.retry=Math.min(10000,this.retry*2);}throw e;
    }
   }
   const e=Error('Concurrent changes, retrying');this.emit('offline',e);this.timer=setTimeout(()=>this.flush().catch(()=>{}),this.retry);throw e;
  }
  resolve(useLocal){if(!this.conflicts.length)return;const next=useLocal?copy(this.get()):copy(this.remote);this.conflicts=[];this.apply(next);this.emit(this.dirty?'waiting':'saved');this.change();}
  recover(saved){
   const records=Array.isArray(saved)?saved:saved?[saved]:[];let next=copy(this.base),alternative=copy(this.base);const conflicts=[];
   for(const record of records){next=merge(record.base,record.document,next,'',conflicts);alternative=merge(record.base,alternative,record.document);}
   if(!equal(next,this.get()))this.apply(next);
   this.conflicts=[...new Set(conflicts)];
   if(this.conflicts.length){this.remote=alternative;this.emit('conflict');this.conflict(this.conflicts);}else this.change();
  }
  close(){this.closed=true;clearTimeout(this.timer);}
 }
 const api={LiveSave,StateSync,Journal,bindLifecycle,merge,reconcile,equal,copy};if(typeof window!=='undefined')window.TabPrefixLiveSave=api;if(typeof module!=='undefined')module.exports=api;
})();
