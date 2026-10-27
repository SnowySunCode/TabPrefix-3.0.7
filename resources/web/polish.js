'use strict';
(() => {
 const studio=window.TabPrefixStudio,$=id=>document.getElementById(id);
 const ru=()=> (studio?.state.language||document.documentElement.lang)==='ru';
 const say=(a,b)=>ru()?a:b;
 const element=(tag,className,text)=>{const n=document.createElement(tag);if(className)n.className=className;if(text!==undefined)n.textContent=text;return n;};
 const field=(id,label,type='text',options={})=>{const l=element('label','polish-field');l.append(element('span','',label));const input=element(type==='select'?'select':'input');input.id=id;if(type!=='select')input.type=type;for(const[k,v]of Object.entries(options))input.setAttribute(k,v);l.append(input);return l;};
 const option=(select,value,a,b)=>{const n=element('option','',say(a,b));n.value=value;n.dataset.ru=a;n.dataset.en=b;select.append(n);};
 const title=(parent,a,b,hintA,hintB)=>{const box=element('div','polish-section-heading');const h=element('h3','',say(a,b));h.dataset.pRu=a;h.dataset.pEn=b;box.append(h);if(hintA){const p=element('p','small muted',say(hintA,hintB));p.dataset.pRu=hintA;p.dataset.pEn=hintB;box.append(p);}parent.append(box);};
 const label=(parent,id,a,b,type='text',opts={})=>{const f=field(id,say(a,b),type,opts);f.firstChild.dataset.pRu=a;f.firstChild.dataset.pEn=b;parent.append(f);return f.lastElementChild;};
 const hint=(parent,a,b)=>{const p=element('p','small muted',say(a,b));p.dataset.pRu=a;p.dataset.pEn=b;parent.append(p);return p;};
 const textButton=(id,a,b)=>{const n=element('button','secondary',say(a,b));n.type='button';n.id=id;n.dataset.pRu=a;n.dataset.pEn=b;return n;};
 const details=(parent,a,b,open=false)=>{const n=element('details','polish-detail'),s=element('summary','',say(a,b));s.dataset.pRu=a;s.dataset.pEn=b;n.open=open;n.append(s);parent.append(n);return n;};
 const errors=new Set();
 const valid=(input,work)=>{try{work();input.setCustomValidity('');input.classList.remove('invalid');errors.delete(input.id);$('apply')&&( $('apply').disabled=studio?.state.busy||studio?.state.expired||errors.size>0||!studio?.state.unsaved );}catch(e){input.setCustomValidity(e.message);input.classList.add('invalid');errors.add(input.id);studio?.notice(e.message,'error');$('apply')&&($('apply').disabled=true);}};
 const ranges=(text,min,max)=>{const set=new Set();if(!text.trim()||text.trim()==='*')return [];for(const part of text.split(/[,;\s]+/).filter(Boolean)){const m=part.match(/^(\d+)(?:-(\d+))?$/);if(!m)throw Error(say('Используйте числа и диапазоны, например 1,3-5','Use numbers and ranges, e.g. 1,3-5'));const a=Number(m[1]),b=Number(m[2]??m[1]);if(a<min||b>max||b<a)throw Error(say('Допустимый диапазон: ','Allowed range: ')+min+'–'+max);for(let n=a;n<=b;n++)set.add(n);}return [...set].sort((a,b)=>a-b);};
 const schedule=()=>({mode:'ALWAYS',timezone:'Europe/Moscow',months:[],weekDays:[],monthDays:[],hours:[],minutes:[],startTime:'',endTime:'',chance:25,intervalMinutes:30,durationSeconds:60,rules:[]});
 const binding=()=>({mode:'NONE',uuid:'',entityType:'WITHER',name:'',tag:'',radius:64});
 let selectedGroups=new Set(),groupFilter='',assets=[],loadingAssets=false,lastGroups='',groupBusy=false,lastSelected='',lastLang='',groupTimer;
 if(!studio){
  // Shared prefix editor improvements do not need Display Studio APIs.
  const text=$('text');if(text){const h=element('p','polish-editor-hint',say('1. Выберите изображение или текст   2. Проверьте результат   3. Сохраните и примените код','1. Choose image or text   2. Check the result   3. Save and apply your code'));document.querySelector('header')?.after(h);}
  return;
 }
 // Put the two TAB text zones next to each other, ahead of advanced group controls.
 const canvasColumn=document.querySelector('#panel-tab .canvas-column');
 const headerCard=$('header-lines').closest('.card'),footerCard=$('footer-lines').closest('.card');
 const headerFooter=element('div','polish-header-footer');headerFooter.append(headerCard,footerCard);canvasColumn.prepend(headerFooter);
 // Typography for the scoreless panel.
 const scoreCard=$('sidebar-title').closest('.card');
 const typography=details(scoreCard,'Оформление панели','Panel appearance',true),grid=element('div','polish-grid');typography.append(grid);
 label(grid,'panel-font-size','Размер текста, px','Text size, px','number',{min:6,max:24,step:1});
 label(grid,'panel-line-height','Расстояние строк, px','Line spacing, px','number',{min:8,max:32,step:1});
 label(grid,'panel-width','Ширина подложки, px','Panel width, px','number',{min:80,max:400,step:1});
 const background=label(grid,'panel-background','Подложка панели','Panel background','checkbox');
 const optimize=textButton('panel-optimize','Чёткий макет без цифр','Clean layout without scores');typography.append(optimize);
 hint(typography,'На современных клиентах выбирается штатный скорборд с обычным шрифтом. На старых — шрифтовая панель из ресурс-пака.','Modern clients use the native sidebar and regular font. Older clients use the font panel from the resource pack.');
 for(const [id,key,min,max]of[['panel-font-size','sidebarFontSize',6,24],['panel-line-height','sidebarLineHeight',8,32]])$(id).onchange=()=>valid($(id),()=>{const n=Number($(id).value);if(!Number.isInteger(n)||n<min||n>max)throw Error(min+'–'+max);studio.commit(()=>{studio.state.design[key]=n;if(key==='sidebarFontSize'&&studio.state.design.sidebarLineHeight<n)studio.state.design.sidebarLineHeight=n;});});
 $('panel-width').onchange=()=>valid($('panel-width'),()=>{const n=Number($('panel-width').value);if(!Number.isInteger(n)||n<80||n>400)throw Error('80–400');studio.commit(()=>studio.state.design.sidebarWidth=n);});background.onchange=()=>studio.commit(()=>studio.state.design.sidebarBackground=background.checked);
 optimize.onclick=()=>{studio.commit(()=>{const d=studio.state.design;const art=d.artwork,custom=art&&(art.rows?.length||art.shapes?.some(v=>v.surface==='SIDEBAR')||art.title?.y>=0||art.panel?.fill!=='#0e1623');d.sidebarMode=studio.state.session.blankScoresSupported&&!custom?'NATIVE':'HUD';d.sidebarFontSize=10;d.sidebarLineHeight=14;d.sidebarX=45;d.sidebarY=Math.round(-Math.max(110,(d.sidebar.length+1)*7+70));},true);studio.repaintForm();};
 // Always-live group directory and bulk assignment.
 const groupsCard=element('div','card polish-group-directory');title(groupsCard,'Группы LuckPerms','LuckPerms groups','Выберите одну или несколько групп и задайте им общий префикс. Список обновляется автоматически.','Select one or more groups and set a shared prefix. The list updates automatically.');
 const tools=element('div','polish-directory-tools'),search=element('input');search.type='search';search.id='group-directory-search';search.placeholder=say('Найти группу…','Find a group…');search.setAttribute('aria-label',search.placeholder);
 const reload=textButton('groups-refresh','Обновить','Refresh');tools.append(search,reload);groupsCard.append(tools);
 const directory=element('div','polish-group-options');directory.id='group-directory';groupsCard.append(directory);
 const batch=element('div','polish-batch');groupsCard.append(batch);label(batch,'batch-prefix','Префикс выбранных групп','Prefix for selected groups','text',{maxlength:512});
 const batchFormat=element('div','polish-preset-chips');for(const [a,b,value]of[['Золотой','Gold','<gold>[VIP]</gold>'],['Мятный','Mint','<#93efc4>[VIP]</#93efc4>'],['Градиент','Gradient','<gradient:#9f91ff:#6be9d0>[VIP]</gradient>']]){const chip=textButton('',a,b);chip.className='chip';chip.onclick=()=>{$('batch-prefix').value=value;};batchFormat.append(chip);}batch.append(batchFormat);
 const assign=textButton('batch-prefix-assign','Назначить выбранным','Assign to selected');assign.className='primary';const selection=element('span','small muted');selection.id='groups-selected-count';batch.append(assign,selection);
 search.oninput=()=>{groupFilter=search.value.toLowerCase();renderDirectory(true);};
 reload.onclick=async()=>{if(groupBusy)return;groupBusy=true;reload.disabled=true;try{await studio.loadGroups();renderDirectory(true);}catch(e){studio.notice(e.message,'error');}finally{groupBusy=false;reload.disabled=false;}};
 assign.onclick=()=>{if(!selectedGroups.size)return;const targets=[...selectedGroups];
  const next=studio.state.design.groupStyles.map(original=>{const role=JSON.parse(JSON.stringify(original)),left=(role.groups?.length?role.groups:[role.group]).filter(g=>!selectedGroups.has(g));if(!left.length)return null;role.groups=left;role.group=left[0];return role;}).filter(Boolean);
  if(next.length>=64||targets.length>64){studio.notice(say('В одном правиле максимум 64 группы; в макете максимум 64 правила.','Up to 64 groups per rule and 64 rules per design.'),'error');return;}
  next.push({...studio.groupStyle(targets[0]),groups:targets,prefixMode:'CUSTOM',prefix:studio.line($('batch-prefix').value),prefixImage:''});
  studio.commit(()=>studio.state.design.groupStyles=next,true);studio.notice(say('Префикс назначен. Сохраняем автоматически.','Prefix set. Saving automatically.'),'success');};
 $('group-styles-card').before(groupsCard);
 const roleSection=element('div','polish-role-prefix');title(roleSection,'Префикс группы','Group prefix');
 const roleMode=label(roleSection,'role-prefix-mode','Источник','Source','select');for(const[value,a,b]of[['INHERIT','Из LuckPerms или прежних настроек','LuckPerms or existing settings'],['CUSTOM','Свой текст / картинка','Custom text / image'],['NONE','Без префикса','No prefix']])option(roleMode,value,a,b);
 label(roleSection,'role-prefix-text','Текст префикса','Prefix text','text',{maxlength:512});
 const roleImage=label(roleSection,'role-prefix-image','Картинка из каталога','Image from catalog','select');option(roleImage,'','Без картинки','No image');
 label(roleSection,'role-prefix-targets','Другие группы с этим оформлением','Other groups sharing this style');
 hint(roleSection,'Названия через запятую. Цвет имени и суффикс тоже применяются к указанным группам.','Comma-separated names. Name color and suffix also apply to these groups.');
 $('role-fields').prepend(roleSection);
 const role=()=>studio.state.selected?.section==='groupStyles'?studio.state.design.groupStyles[studio.state.selected.index]:null;
 roleMode.onchange=()=>studio.commit(()=>role().prefixMode=roleMode.value);
 $('role-prefix-text').oninput=()=>studio.commit(()=>{const r=role();r.prefix=r.prefix||studio.line();r.prefix.text=$('role-prefix-text').value;});
 roleImage.onchange=()=>studio.commit(()=>role().prefixImage=roleImage.value);
 $('role-prefix-targets').onchange=()=>valid($('role-prefix-targets'),()=>{const r=role(),targets=[...new Set([r.group,...$('role-prefix-targets').value.split(/[,\s]+/).filter(Boolean).map(n=>n.toLowerCase())])];if(!targets.every(n=>/^[a-z0-9_.-]{1,128}$/.test(n)))throw Error(say('Недопустимое название группы','Invalid group name'));if(studio.state.design.groupStyles.some(other=>other!==r&&(other.groups?.length?other.groups:[other.group]).some(g=>targets.includes(g))))throw Error(say('Эта группа уже входит в другое правило','Group belongs to another rule'));studio.commit(()=>r.groups=targets);});
 // Real entity health binding.
 const bossExtras=element('div','polish-boss-extras');$('boss-fields').append(bossExtras);
 const entity=details(bossExtras,'Привязать к настоящему боссу','Bind to a real boss');
 const bindMode=label(entity,'boss-bind-mode','Как найти моба','Find the entity by','select');
 for(const[value,a,b]of[['NONE','Без привязки','No binding'],['TYPE','Тип ближайшего моба','Nearest entity of a type'],['UUID','Точный UUID моба','Exact entity UUID'],['NAME','Имя моба','Entity custom name'],['TAG','Тег моба','Scoreboard tag'],['NEAREST','Ближайший живой моб','Nearest living entity']])option(bindMode,value,a,b);
 const bindFields=element('div');bindFields.id='boss-bind-fields';entity.append(bindFields);
 label(bindFields,'boss-bind-type','Тип (например WITHER)','Type (e.g. WITHER)');label(bindFields,'boss-bind-uuid','UUID моба','Entity UUID');label(bindFields,'boss-bind-name','Имя без цветовых кодов','Name without color codes');label(bindFields,'boss-bind-tag','Scoreboard-тег','Scoreboard tag');label(bindFields,'boss-bind-radius','Радиус поиска, блоки','Search radius, blocks','number',{min:1,max:256,step:1});
 hint(entity,'Полоса следует за здоровьем существующего моба и скрывается при его смерти, выгрузке или выходе из радиуса. Мобы не создаются.','The bar follows an existing mob’s health and hides when it dies, unloads or leaves the radius. This does not spawn mobs.');
 hint(entity,'В тексте доступны {boss_name}, {boss_health}, {boss_max_health}, {boss_type}, {boss_uuid}.','Use {boss_name}, {boss_health}, {boss_max_health}, {boss_type}, {boss_uuid} in the text.');
 const calendar=details(bossExtras,'Когда показывать','When to show',true);
 const schedMode=label(calendar,'boss-schedule-mode','Режим появления','Appearance mode','select');for(const[v,a,b]of[['ALWAYS','Всегда','Always'],['CALENDAR','По расписанию','On schedule'],['RANDOM','Случайные появления','Random appearances']])option(schedMode,v,a,b);
 const scheduleFields=element('div');scheduleFields.id='boss-schedule-fields';calendar.append(scheduleFields);
 label(scheduleFields,'boss-schedule-zone','Часовой пояс','Timezone');
 const days=element('div','polish-weekdays');days.id='boss-weekdays';scheduleFields.append(days);
 for(let n=1;n<=7;n++){const b=element('button','chip');b.type='button';b.dataset.day=n;b.textContent=['Пн','Вт','Ср','Чт','Пт','Сб','Вс'][n-1];b.onclick=()=>{const bar=selectedBoss(),set=new Set(bar.schedule.weekDays);set.has(n)?set.delete(n):set.add(n);studio.commit(()=>bar.schedule.weekDays=[...set].sort());};days.append(b);}
 hint(scheduleFields,'Все дни, если ни один не выбран. В полях можно писать диапазоны: 1,3-5. Пустое поле — любое значение.','No selection means every day. Fields accept ranges: 1,3-5. Empty means any value.');
 const dates=element('div','polish-grid');scheduleFields.append(dates);
 for(const[id,a,b,min,max]of[['months','Месяцы (1–12)','Months (1–12)',1,12],['monthDays','Числа месяца (1–31)','Dates (1–31)',1,31],['hours','Часы (0–23)','Hours (0–23)',0,23],['minutes','Минуты (0–59)','Minutes (0–59)',0,59]]){label(dates,'boss-schedule-'+id,a,b,'text',{placeholder:'*'});$('boss-schedule-'+id).onchange=()=>valid($('boss-schedule-'+id),()=>{const values=ranges($('boss-schedule-'+id).value,min,max);studio.commit(()=>selectedBoss().schedule[id]=values);});}
 const windowFields=element('div','polish-grid');scheduleFields.append(windowFields);label(windowFields,'boss-schedule-start','С','From','time');label(windowFields,'boss-schedule-end','До (не включая)','Until (exclusive)','time');
 hint(scheduleFields,'Например: пн–пт, 18:00–22:00. Интервалы через полночь поддерживаются.','Example: Monday–Friday, 18:00–22:00. Overnight windows are supported.');
 const random=element('div','polish-random-fields');random.id='boss-random-fields';scheduleFields.append(random);
 title(random,'Вероятность появления','Appearance probability');const randomGrid=element('div','polish-grid');random.append(randomGrid);
 label(randomGrid,'boss-random-chance','Вероятность, %','Chance, %','number',{min:0,max:100,step:.1});label(randomGrid,'boss-random-interval','Проверять каждые, минут','Draw every, minutes','number',{min:1,max:44640,step:1});label(randomGrid,'boss-random-duration','Показывать, секунд','Show for, seconds','number',{min:1,max:86400,step:1});
 hint(random,'Одна общая попытка для всех игроков в начале интервала. 25% раз в 30 минут означает в среднем одно появление за 2 часа.','One shared draw for all players at the start of each interval. 25% every 30 minutes averages one appearance per 2 hours.');
 const rules=element('div','polish-chance-rules');rules.id='boss-chance-rules';random.append(rules);const addRule=textButton('boss-add-chance-rule','+ Своя вероятность по дням и часам','+ Custom chance by day and hour');random.append(addRule);hint(random,'Правила проверяются сверху вниз. Первое совпавшее задаёт вероятность; иначе применяется общая.','Rules run from top to bottom. First match sets the probability; otherwise the default applies.');
 $('boss-fields').append(bossExtras);
 const selectedBoss=()=>studio.state.selected?.section==='bossBars'?studio.state.design.bossBars[studio.state.selected.index]:null;
 const updateBar=(input,fn)=>valid(input,()=>{if(selectedBoss())studio.commit(()=>{const b=selectedBoss();b.binding={...binding(),...b.binding};b.schedule={...schedule(),...b.schedule};fn(b);});render();});
 bindMode.onchange=()=>updateBar(bindMode,b=>{b.binding.mode=bindMode.value;entity.open=true;});schedMode.onchange=()=>updateBar(schedMode,b=>b.schedule.mode=schedMode.value);
 for(const[id,key]of[['boss-bind-type','entityType'],['boss-bind-uuid','uuid'],['boss-bind-name','name'],['boss-bind-tag','tag']])$(id).oninput=()=>updateBar($(id),b=>b.binding[key]=$(id).value.trim());
 $('boss-bind-radius').onchange=()=>updateBar($('boss-bind-radius'),b=>{const n=Number($('boss-bind-radius').value);if(n<1||n>256)throw Error('1–256');b.binding.radius=n;});
 for(const[id,key]of[['boss-schedule-zone','timezone'],['boss-schedule-start','startTime'],['boss-schedule-end','endTime']])$(id).onchange=()=>updateBar($(id),b=>b.schedule[key]=$(id).value.trim());
 for(const[id,key,min,max]of[['boss-random-chance','chance',0,100],['boss-random-interval','intervalMinutes',1,44640],['boss-random-duration','durationSeconds',1,86400]])$(id).onchange=()=>updateBar($(id),b=>{const n=Number($(id).value);if(!Number.isFinite(n)||n<min||n>max||key!=='chance'&&!Number.isInteger(n))throw Error(min+'–'+max);b.schedule[key]=n;});
 addRule.onclick=()=>updateBar(addRule,b=>{if(b.schedule.rules.length<16)b.schedule.rules.push({weekDays:[],hours:[],chance:b.schedule.chance});});
 // Render helpers avoid replacing controls while typing or dragging.
 function setValue(id,value){const node=$(id);if(document.activeElement!==node)node.value=value;}
 function renderDirectory(force=false){const state=studio.state,known=state.knownGroups||[],signature=JSON.stringify(known)+JSON.stringify(state.design.groupStyles)+state.language+groupFilter+[...selectedGroups].join();if(!force&&signature===lastGroups)return;lastGroups=signature;directory.replaceChildren();
  const names=new Set(known.map(g=>g.group));selectedGroups=new Set([...selectedGroups].filter(g=>names.has(g)));
  for(const g of known.filter(g=>g.group.toLowerCase().includes(groupFilter))){const row=element('label','polish-group-option'),check=element('input');check.type='checkbox';check.value=g.group;check.checked=selectedGroups.has(g.group);check.onchange=()=>{check.checked?selectedGroups.add(g.group):selectedGroups.delete(g.group);renderDirectory(true);};
   const text=element('span','polish-group-info');text.append(element('strong','',g.displayName||g.group),element('small','',g.group+' · '+say('вес ','weight ')+g.weight));const rule=state.design.groupStyles.find(r=>(r.groups||[r.group]).includes(g.group));const shown=rule?.prefixMode==='NONE'?'—':rule?.prefixMode==='CUSTOM'?(rule.prefix?.text|| (rule.prefixImage?say('Картинка','Image'):'')):g.prefix;const prefix=element('span','polish-group-prefix',String(shown||'').replace(/§[0-9a-fklmnorx]/gi,'').replace(/<[^>]+>/g,''));row.append(check,text,prefix);directory.append(row);}
  if(!directory.childElementCount)directory.append(element('p','small muted',say('Нет подходящих групп','No matching groups')));
  selection.textContent=say('Выбрано: ','Selected: ')+selectedGroups.size;assign.disabled=!selectedGroups.size||state.busy||state.expired;
 }
 function renderRules(bar){const signature=JSON.stringify(bar.schedule.rules);if(rules.dataset.signature===signature)return;if(rules.contains(document.activeElement))return;rules.dataset.signature=signature;rules.replaceChildren();bar.schedule.rules.forEach((r,index)=>{
   const row=element('div','polish-chance-row');const day=element('input'),hour=element('input'),chance=element('input'),remove=element('button','subtle','×');day.id='chance-days-'+index;hour.id='chance-hours-'+index;chance.id='chance-value-'+index;day.placeholder=say('Дни 1–7','Days 1–7');hour.placeholder=say('Часы 0–23','Hours 0–23');day.value=r.weekDays.join(',');hour.value=r.hours.join(',');chance.type='number';chance.min=0;chance.max=100;chance.step=.1;chance.value=r.chance;remove.type='button';remove.setAttribute('aria-label',say('Удалить правило','Remove rule'));row.append(day,hour,chance,element('span','','%'),remove);
   for(const[input,key,min,max]of[[day,'weekDays',1,7],[hour,'hours',0,23]])input.onchange=()=>valid(input,()=>{const a=ranges(input.value,min,max);studio.commit(()=>r[key]=a);});
   chance.onchange=()=>valid(chance,()=>{const n=Number(chance.value);if(!Number.isFinite(n)||n<0||n>100)throw Error('0–100');studio.commit(()=>r.chance=n);});remove.onclick=()=>updateBar(remove,b=>b.schedule.rules.splice(index,1));rules.append(row);
  });addRule.disabled=bar.schedule.rules.length>=16;}
 async function loadAssets(){if(loadingAssets||!studio.state.session?.canDesign)return;loadingAssets=true;try{assets=await studio.api('display/assets');const value=roleImage.value;roleImage.replaceChildren();option(roleImage,'','Без картинки','No image');for(const[a,i]of assets.map((a,i)=>[a,i]))option(roleImage,a.id,(a.groups?.join(', ')||'Картинка '+(i+1)),(a.groups?.join(', ')||'Image '+(i+1)));roleImage.value=value;render();}catch(e){studio.notice(e.message,'error');}finally{loadingAssets=false;}}
 function render(){const state=studio.state;if(!state.design||!state.session)return;
  if(lastLang!==state.language){lastLang=state.language;document.querySelectorAll('[data-p-ru]').forEach(n=>n.textContent=ru()?n.dataset.pRu:n.dataset.pEn);document.querySelectorAll('option[data-ru]').forEach(n=>n.textContent=ru()?n.dataset.ru:n.dataset.en);search.placeholder=say('Найти группу…','Find a group…');}
  setValue('panel-width',state.design.sidebarWidth??180);background.checked=state.design.sidebarBackground!==false;
  setValue('panel-font-size',state.design.sidebarFontSize??10);setValue('panel-line-height',state.design.sidebarLineHeight??14);
  renderDirectory();const r=role();if(r){setValue('role-prefix-mode',r.prefixMode||'INHERIT');setValue('role-prefix-text',r.prefix?.text||'');setValue('role-prefix-image',r.prefixImage||'');setValue('role-prefix-targets',(r.groups||[r.group]).filter(g=>g!==r.group).join(', '));$('role-prefix-text').closest('label').hidden=roleImage.closest('label').hidden=(r.prefixMode||'INHERIT')!=='CUSTOM';}
  const bar=selectedBoss();bossExtras.hidden=!bar;if(bar){const b={...binding(),...bar.binding},s={...schedule(),...bar.schedule};const newBar=lastSelected!==bar.id;if(newBar){lastSelected=bar.id;if(!bar.binding)bar.binding=b;if(!bar.schedule)bar.schedule=s;errors.clear();rules.dataset.signature='';}
   setValue('boss-bind-mode',b.mode);bindFields.hidden=b.mode==='NONE';for(const[id,mode]of[['boss-bind-type','TYPE'],['boss-bind-uuid','UUID'],['boss-bind-name','NAME'],['boss-bind-tag','TAG']])$(id).closest('label').hidden=b.mode!==mode;
   for(const[id,key]of[['boss-bind-type','entityType'],['boss-bind-uuid','uuid'],['boss-bind-name','name'],['boss-bind-tag','tag'],['boss-bind-radius','radius']])setValue(id,b[key]);
   setValue('boss-schedule-mode',s.mode);scheduleFields.hidden=s.mode==='ALWAYS';random.hidden=s.mode!=='RANDOM';for(const[id,key]of[['boss-schedule-zone','timezone'],['boss-schedule-start','startTime'],['boss-schedule-end','endTime'],['boss-random-chance','chance'],['boss-random-interval','intervalMinutes'],['boss-random-duration','durationSeconds']])setValue(id,s[key]);
   for(const key of ['months','monthDays','hours','minutes'])setValue('boss-schedule-'+key,s[key].join(','));days.querySelectorAll('button').forEach(n=>{n.textContent=(ru()?['Пн','Вт','Ср','Чт','Пт','Сб','Вс']:['Mo','Tu','We','Th','Fr','Sa','Su'])[Number(n.dataset.day)-1];n.classList.toggle('active',s.weekDays.includes(Number(n.dataset.day)));n.setAttribute('aria-pressed',String(s.weekDays.includes(Number(n.dataset.day))));});
   renderRules(bar);if(b.enabled||b.mode!=='NONE')entity.open=true;
  }
  if(errors.size)$('apply').disabled=true;
  if(!groupTimer){groupTimer=setInterval(async()=>{if(document.hidden||studio.state.busy||studio.state.expired||groupBusy)return;try{await studio.loadGroups();renderDirectory();}catch(e){if(studio.state.expired)clearInterval(groupTimer);}},12000);loadAssets();}
 }
 for(const name of ['studio-change','studio-selection','studio-preview','studio-saved'])window.addEventListener(name,render);
 window.addEventListener('studio-saved',loadAssets);
 document.addEventListener('visibilitychange',()=>{if(!document.hidden&&studio.state.session?.canDesign)reload.click();});
 window.addEventListener('beforeunload',()=>clearInterval(groupTimer));
 render();
})();
