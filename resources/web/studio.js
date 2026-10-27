'use strict';
(() => {
const $ = id => document.getElementById(id);
const token = location.hash.slice(1);
let language = 'ru', session, design, revision = 1, persisted = '', selected = null;
let panel = 'overview', undo = [], redo = [], busy = false, expired = false, paused = false;
let preview = null, previewGeneration=-1, previewTimer, previewBusy = false, previewPending = false, generation = 0, drag = null, touch = null;
let previewClock = performance.now(), knownGroups = [], autoSaver=null, prefSaver=null, prefJournal=null, stateSync=null, syncTimer=null, initAttempts=0;
const copy = value => JSON.parse(JSON.stringify(value));
const ru = {};
document.querySelectorAll('[data-i18n]').forEach(el => ru[el.dataset.i18n] = el.textContent);
ru.heroTitle='Соберите оформление. Сразу увидьте результат.';
const en = {color:'Color',clearStyle:'Clear style',
calm:'Calm TAB',calmHint:'Roles, suffixes and a tidy player list',playerFormatting:'Player names in TAB',playerFormatHint:'One template for the normal player list. Use it in layout cells with {tab_name}.',enablePlayerFormat:'Custom player format',editPlayerFormat:'Edit player format',reverseOrder:'Reverse order',nativeSort:'Sort the normal TAB',nativeSortHint:'Group order applies to the normal list too. Existing teams from other plugins keep priority.',byPriority:'By group priority',byWeight:'By LuckPerms weight',byPing:'By ping',byWorld:'By world and name',groupStyles:'Groups & suffixes',groupStylesHint:'Drag groups to set their priority. Select a group to customize its name color and suffix.',showSuffixes:'Show suffixes',luckSuffixFallback:'LuckPerms suffix for other groups',groupEmpty:'Import groups from LuckPerms or add one manually.',importLuckGroups:'Import LuckPerms groups',addGroupStyle:'+ Group',roleGroup:'Group name in LuckPerms',roleOwnColor:'Custom name color',suffixSource:'Suffix source',customSuffix:'Custom suffix',noSuffix:'Hide suffix',suffixSpacing:'A space before the suffix is added automatically.',suffixBadge:'[Group]',suffixWorld:'World',
bossbars:'Boss bars',bossHint:'Up to 8 bars. Select a bar to edit it, drag to reorder.',bossDisplay:'Display mode',stacked:'All at once',rotate:'One at a time',rotateSeconds:'Switch interval, seconds',bossEmpty:'Your first bar.',bossEmptyHint:'Start with a message, health bar or timer. Every detail can be changed.',addBoss:'+ Boss bar',healthBar:'♡ Health',timerBar:'◷ Timer',bossGamePreview:'In-game boss bars',bossHidden:'No bars are currently visible.',bossPreviewHint:'Preview uses your data, world, permissions and personal settings. Preview timers restart when you edit the draft.',thisBarEnabled:'This bar is enabled',barColor:'Bar color',pink:'Pink',blue:'Blue',red:'Red',green:'Green',yellow:'Yellow',purple:'Purple',white:'White',barStyle:'Bar style',solid:'Solid',barFill:'Fill',manualFill:'Manual',healthFill:'Player health',foodFill:'Player food',experienceFill:'Progress to the next level',onlineFill:'Online / server capacity',customFill:'Custom value',fillTimer:'Filling timer',drainTimer:'Countdown',pulseTimer:'Pulse',fillSource:'Placeholder',fillMax:'Value for a full bar',durationSeconds:'Duration, seconds',repeatTimer:'Repeat timer',hideAfter:'Hide when finished',bossConditions:'Where and who can see it',bossWorlds:'Worlds: one per line',allWorlds:'Empty list means all worlds.',bossPermission:'Required permission',allPlayers:'Empty means all players.',bossEffects:'Minecraft effects',darkenSky:'Darken the sky',bossMusic:'Boss music',bossFog:'Create fog',duplicateBoss:'Duplicate bar',
prefixes:'Prefixes ↗',workspace:'WORKSPACE',overview:'Overview',tab:'TAB & layout',scoreboard:'Scoreboard',nametags:'Name tags',settings:'My settings',connecting:'Connecting…',eyebrow:'WORKSPACE',reload:'Reload',savedState:'Saved',apply:'Save now',visualEditor:'VISUAL EDITOR',heroTitle:'Build your display. See it take shape.',heroText:'Start with a template, drag blocks into place and adjust the details. Changes save and apply automatically.',start:'Open the builder →',templates:'Start with a template',templateHint:'Every part can be customized',minimal:'Minimal',minimalHint:'Clean text and quiet colors',neon:'Neon',neonHint:'Gradients and a scrolling message',classic:'Classic',classicHint:'Gold, groups and server information',serverSettings:'Server settings',serverName:'Server name',refresh:'Update interval',yourTemplates:'Your templates',transferHint:'Export your display as JSON or move it to another server.',export:'Export',import:'Import',header:'TAB Header',footer:'TAB Footer',enabled:'Enabled',addLine:'+ Add a line',tabLayout:'TAB layout',layoutHint:'4 columns × 20 rows. Drag cells or blocks from the palette.',fixedLayout:'Fixed layout',textBlock:'+ Text',playerBlock:'+ Player',emptyBlock:'+ Empty',sort:'Players',byName:'By name',byGroup:'By group and name',normalTab:'The normal player list is active. Enable the fixed layout to display this grid.',slotHint:'Select a cell to edit it. ↑ ↓ ← → move the selected cell.',scoreboardTitle:'Sidebar',title:'Title',scoreHint:'Up to 15 lines, including blank lines. Drag to change their order.',preview:'Preview',scoreNumbers:'Hiding scores depends on the client version. Existing scoreboards keep priority.',beforeName:'Before the name',afterName:'After the name',nameHint:'{prefix} uses the group text or image prefix. The player name is added automatically.',visibility:'Visibility',always:'Always',never:'Hidden',otherTeams:'Other teams',ownTeam:'Own team',collision:'Player collisions',neverCollision:'Disabled',namePack:'PNG and GIF prefixes are shown to recipients who have loaded the resource pack. Other recipients keep the configured text fallback.',justYou:'JUST FOR YOU',personalTitle:'Choose your own view.',personalHint:'These switches affect only you. The server must enable the corresponding feature.',saveSettings:'Save my settings',blockSettings:'Block settings',blockType:'Type',text:'Text',player:'Player',empty:'Empty cell',playerIndex:'Player position in the sorted list',formatHelp:'MiniMessage or & color codes. Example: <gold>VIP</gold>.',animation:'Animation',static:'Static',scroll:'Scrolling text',frames:'Frame cycle',speed:'Frame step, seconds',window:'Window width',gap:'Gap, characters',framesHint:'One frame per line',delete:'Delete',result:'RESULT',tabPreview:'In-game TAB',pause:'Pause',previewHint:'The server fills in your data. Fonts and image glyphs depend on the client resource pack.'
};
const dynamic = {
ru:{titles:{overview:'Ваш сервер. Ваш стиль.',tab:'Соберите свой TAB.',scoreboard:'Всё важное — на виду.',nametags:'Группа видна сразу.',settings:'Ваше отображение.'},subs:{overview:'Шаблоны, настройки и визуальный конструктор в одном месте.',tab:'Нажмите блок, измените текст, перетащите на нужное место.',scoreboard:'Соберите боковую панель из простых строк.',nametags:'Текст, изображения и GIF рядом с именем игрока.',settings:'Включите только то, что вам нужно.'},unsaved:'Есть изменения',connected:'Подключено',missing:'Откройте /lptab design или /lptab settings в Minecraft.',expired:'Сессия истекла. Откройте новую ссылку в Minecraft.',saved:'Оформление применено к серверу.',prefsSaved:'Ваши настройки сохранены.',imported:'Шаблон загружен. Сохраняем автоматически.',limit:'Достигнут лимит строк.',resumed:'Продолжить',loading:'Применяем…',overwrite:'Этот шаблон заменит текущий черновик. Продолжить?',reloadConfirm:'Загрузить сохранённое оформление и удалить изменения черновика?',select:'Нажмите строку или ячейку, чтобы настроить её.',blank:'Пустая строка',overflow:'Не помещаются в макет: ',conflict:'Другой плагин управляет частью отображения. Посмотрите /lptab doctor.',revision:'Ревизия ',personal:{sidebar:['Скорборд','Боковая панель с информацией'],headerFooter:['Header & Footer','Текст над и под списком TAB'],layout:['Макет TAB','Использовать макет сервера вместо обычного списка'],nameTags:['Префиксы над головой','Видеть оформление рядом с именами'],animations:['Анимация','Бегущие строки, смена кадров и GIF-префиксы']},invalid:'Некорректный файл шаблона.',offline:'Оставайтесь на сервере, пока настраиваете оформление.'},
en:{titles:{overview:'Your server. Your style.',tab:'Build your own TAB.',scoreboard:'Keep the essentials in sight.',nametags:'Make every group visible.',settings:'Your own view.'},subs:{overview:'Templates, settings and a visual builder in one place.',tab:'Select a block, change its text and drag it into place.',scoreboard:'Build a sidebar from simple, reusable lines.',nametags:'Text, images and GIFs beside player names.',settings:'Enable only what you want to see.'},unsaved:'Unsaved changes',connected:'Connected',missing:'Open /lptab design or /lptab settings in Minecraft.',expired:'Session expired. Open a new link in Minecraft.',saved:'Display applied to the server.',prefsSaved:'Your preferences have been saved.',imported:'Template loaded. Saving automatically.',limit:'Line limit reached.',resumed:'Resume',loading:'Applying…',overwrite:'This template replaces your current draft. Continue?',reloadConfirm:'Reload the saved display and discard your draft changes?',select:'Select a line or cell to edit it.',blank:'Blank line',overflow:'Players outside the layout: ',conflict:'Another plugin controls part of the display. Check /lptab doctor.',revision:'Revision ',personal:{sidebar:['Scoreboard','The sidebar with server information'],headerFooter:['Header & Footer','Text above and below the TAB list'],layout:['TAB layout','Use the server layout instead of the normal player list'],nameTags:['Name tags','Prefixes beside player names'],animations:['Animation','Scrolling text, frame cycles and GIF prefixes']},invalid:'Invalid template file.',offline:'Stay online while editing your server display.'}
};
dynamic.ru.titles.bossbars='Настройте свои боссбары.'; dynamic.en.titles.bossbars='Customize your boss bars.';
dynamic.ru.subs.bossbars='Сообщения, здоровье и таймеры — с вашим оформлением.'; dynamic.en.subs.bossbars='Messages, health and timers, with your own style.';
dynamic.ru.personal.bossBars=['Боссбары','Полосы с сообщениями и показателями сверху экрана'];
dynamic.en.personal.bossBars=['Boss bars','Messages and progress bars at the top of the screen'];
dynamic.ru.personal.suffixes=['Суффиксы','Текст и значки после имени в оформлении сервера'];
dynamic.en.personal.suffixes=['Suffixes','Text and badges after player names'];
dynamic.ru.personal.sorting=['Сортировка TAB','Порядок игроков, заданный сервером'];
dynamic.en.personal.sorting=['TAB sorting','Player order chosen by the server'];
dynamic.ru.personal.screen=['Экранные блоки','Текст и картинки в title, subtitle и action bar'];dynamic.en.personal.screen=['Screen elements','Text and images in title, subtitle and action bar'];
dynamic.ru.groupWeight='вес';dynamic.en.groupWeight='weight';
dynamic.ru.duplicateGroup='Такая группа уже добавлена.';dynamic.en.duplicateGroup='This group has already been added.';
dynamic.ru.invalidGroup='Имя группы: латинские буквы, цифры, точка, дефис или подчёркивание.';dynamic.en.invalidGroup='Use letters, digits, dots, hyphens or underscores for the group name.';
dynamic.ru.barReasons={DISABLED:'Полоса выключена',WORLD:'Другой мир',PERMISSION:'Нет права',COMPLETED:'Таймер завершён',SERVER_DISABLED:'Боссбары выключены',PERSONAL_DISABLED:'Выключено в личных настройках',ROTATION:'Ждёт своей очереди',SCHEDULE:'Вне расписания',RANDOM_WAIT:'Ждёт следующей попытки',RANDOM_MISS:'В этой попытке не выпало',BOSS_MISSING:'Босс не найден в радиусе'};
dynamic.en.barReasons={DISABLED:'Bar disabled',WORLD:'Different world',PERMISSION:'Permission required',COMPLETED:'Timer finished',SERVER_DISABLED:'Boss bars disabled',PERSONAL_DISABLED:'Disabled in personal settings',ROTATION:'Waiting for its turn',SCHEDULE:'Outside schedule',RANDOM_WAIT:'Waiting for the next draw',RANDOM_MISS:'Not selected in this draw',BOSS_MISSING:'No matching boss in range'};
function t(key) { return (language === 'en' ? en[key] : ru[key]) || key; }
function d(key) { return dynamic[language][key]; }
function translate() {
 document.documentElement.lang = language;
 document.querySelectorAll('[data-i18n]').forEach(el => el.textContent = t(el.dataset.i18n));
 $('language').textContent = language === 'ru' ? 'EN' : 'RU';
 $('page-title').textContent = d('titles')[panel]; $('page-subtitle').textContent = d('subs')[panel];
 $('pause').textContent = paused ? d('resumed') : t('pause');
 $('boss-pause').textContent = paused ? d('resumed') : t('pause');
 if (session) $('session-state').textContent = d('connected');
 if (session && (session.rgbSupported === false || session.bitmapFontsSupported === false)) {
  $('compatibility-hint').hidden = false;
  $('compatibility-hint').textContent = language === 'ru'
   ? 'Minecraft ' + session.minecraftVersion + ': обычные цвета. ' + (session.bitmapFontsSupported === false ? 'PNG/GIF используют Unicode-текстуры после загрузки пака; один глиф — до 8 пикселей. Строки скорборда и подписи над головой сокращаются по лимитам 1.12.' : 'RGB и градиенты переводятся в ближайшие обычные цвета.')
   : 'Minecraft ' + session.minecraftVersion + ': standard colors. ' + (session.bitmapFontsSupported === false ? 'PNG/GIF use Unicode textures after pack loading; each glyph is up to 8 pixels. Scoreboard lines and name tags fit the 1.12 limits.' : 'RGB and gradients use the nearest standard colors.');
 }
 if (design) { renderLists(); renderGrid(); renderBossBars(); renderGroupRows(); updateDirty(); $('revision').textContent = d('revision') + revision; }
 if (session?.canSettings) renderPersonal();
}
function notice(message, type = '') { $('notice').hidden = false; $('notice').className = 'notice ' + type; $('notice').textContent = message; }
async function api(path, body) {
 const options = {headers:{Authorization:'Bearer ' + token},cache:'no-store'};
 if (body !== undefined) { options.method = 'POST'; options.headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(body); }
 const abort=typeof AbortController==='undefined'?null:new AbortController(),deadline=abort?setTimeout(()=>abort.abort(),path==='display/artwork'?45000:10000):null;if(abort)options.signal=abort.signal;let response;try{response=await fetch('api/'+path,options);}catch(cause){const error=new Error(cause.name==='AbortError'?(language==='ru'?'Сервер не ответил вовремя. Повторяем сохранение.':'Server response timed out. Retrying.') : cause.message);error.status=503;throw error;}finally{clearTimeout(deadline);}
 let data; try { data = await response.json(); } catch (_) { throw new Error('HTTP ' + response.status); }
 if (!response.ok) {
  if (response.status === 401) { expired = true; updateDirty(); const error=new Error(d('expired'));error.status=401;throw error; }
  const error=new Error(data.error || 'HTTP '+response.status);error.status=response.status;throw error;
 }
 return data;
}
function updateDirty() {
 const changed = !!design && (autoSaver?autoSaver.dirty:JSON.stringify(design)!==persisted);
 const phases={ru:{saved:'Сохранено · авто',waiting:'Изменения в очереди',saving:'Сохраняется…',offline:'Нет связи · повторяем',invalid:'Проверьте поля',conflict:'Совпали правки',expired:'Сессия истекла'},en:{saved:'Saved · auto',waiting:'Changes queued',saving:'Saving…',offline:'Offline · retrying',invalid:'Check fields',conflict:'Conflicting edits',expired:'Session expired'}};
 $('dirty').textContent = autoSaver?phases[language][autoSaver.phase]:(changed?d('unsaved'):t('savedState')); $('dirty').classList.toggle('unsaved',changed);
 $('apply').disabled = !changed || busy || expired; $('apply').textContent = busy ? d('loading') : language==='ru'?'Сохранить сейчас':'Save now';
 $('undo').disabled = !undo.length; $('redo').disabled = !redo.length;
}
function record() { undo.push(copy(design)); if (undo.length > 40) undo.shift(); redo = []; }
function commit(work, rebuild = false) {
 if (!design || expired || busy) return;
 const focused=document.activeElement;
 const lineFocus=focused?.dataset.lineInput ? {section:focused.dataset.lineInput,index:focused.dataset.lineIndex,start:focused.selectionStart,end:focused.selectionEnd} : null;
 const editedZone=selected&&['header','footer'].includes(selected.section)?selected.section:null,previousBand=editedZone?JSON.stringify(design[editedZone]):null;
 const previous = {header:[...design.header],footer:[...design.footer],sidebar:[...design.sidebar],slots:[...design.slots]};
 const previousArtwork=design.artwork,previousRows=previousArtwork?.rows?.slice();
 record(); work();
 if(editedZone&&JSON.stringify(design[editedZone])!==previousBand){design[editedZone+'Enabled']=true;$(editedZone+'-enabled').checked=true;}
 if(design.artwork&&design.artwork===previousArtwork){
  const remap=(surface,index)=>{const section={HEADER:'header',FOOTER:'footer',CELL:'slots'}[surface];if(!section)return index;const next=design[section].indexOf(previous[section][index]);return next<0&&surface==='CELL'?index:next;};
  design.artwork.boxes=design.artwork.boxes.filter(b=>{b.index=remap(b.surface,b.index);return b.index>=0;});
  design.artwork.shapes=design.artwork.shapes.filter(b=>{b.index=remap(b.surface,b.index);return b.index>=0;});
  if(previousRows?.length&&previous.sidebar.some((line,n)=>design.sidebar[n]!==line))design.artwork.rows=design.sidebar.map(line=>previousRows[previous.sidebar.indexOf(line)]||{x:7,y:-1,size:0,width:0,align:'LEFT'});
 }
 generation++; autoSaver?.change(); previewClock = performance.now(); renderLists(); renderGrid(); renderBossBars(); renderGroupRows(); if (rebuild) inspector(); updateDirty(); schedulePreview(); window.dispatchEvent(new Event('studio-change'));
 if(lineFocus){const input=document.querySelector('[data-line-input="'+lineFocus.section+'"][data-line-index="'+lineFocus.index+'"]');if(input){input.focus({preventScroll:true});input.setSelectionRange(lineFocus.start,lineFocus.end);}}
}

function show(name) {
 if (!session) return;
 if (name !== 'settings' && !session.canDesign || name === 'settings' && !session.canSettings) return;
 panel = name; document.querySelectorAll('.panel').forEach(el => el.hidden = el.id !== 'panel-' + name);
 document.querySelectorAll('.nav').forEach(el => el.classList.toggle('active',el.dataset.panel === name));
 $('design-actions').hidden = name === 'settings';
 if (selected && !((name === 'tab'||name === 'dream') && ['header','footer','slots','groupStyles','tabPlayerFormat'].includes(selected.section) || name === 'scoreboard' && selected.section === 'sidebar' || name === 'bossbars' && selected.section === 'bossBars')) selected = null;
 const mount = name === 'bossbars' ? $('boss-inspector') : name === 'scoreboard' ? $('score-inspector') : (panel === 'dream' ? $('dream-inspector') : $('tab-inspector'));
 mount.append($('inspector'));const previewMount=$('tab-preview-mount');if(name==='tab'&&previewMount)previewMount.append($('live-tab'));else mount.append($('live-tab'));$('live-tab').hidden=name!=='tab'&&name!=='dream';
 $('inspector').hidden = !selected || !['tab','dream','scoreboard','bossbars'].includes(name);
 translate();window.dispatchEvent(new Event('studio-change'));
}
function line(text = '') { return {id:'line-'+Array.from(crypto.getRandomValues(new Uint8Array(10)),n=>n.toString(16).padStart(2,'0')).join(''),text,animation:'NONE',speed:6,width:28,gap:5,frames:[]}; }
function slot(kind = 'EMPTY', index = 1) { return {kind,playerIndex:index,line:line(kind === 'PLAYER' ? '{tab_name}' : '')}; }
function nextPlayerIndex() { const used = new Set(design.slots.filter(s => s.kind === 'PLAYER').map(s => s.playerIndex)); for (let i = 1; i <= 80; i++) if (!used.has(i)) return i; return 1; }
function lineText(l){return l.animation==='FRAMES'?(l.frames[0]||''):l.text;}
function setLineText(l,text){l.text=text;if(l.animation==='FRAMES')l.frames[0]=text;}
function selectedLine() { if (!selected) return null; if(selected.section==='tabPlayerFormat')return design.tabPlayerFormat;if(selected.section==='groupStyles')return design.groupStyles[selected.index]?.suffix;return ['slots','bossBars'].includes(selected.section) ? design[selected.section][selected.index]?.line : design[selected.section][selected.index]; }
function selectedBoss() { return selected?.section === 'bossBars' ? design.bossBars[selected.index] : null; }
function selectedRole() { return selected?.section === 'groupStyles' ? design.groupStyles[selected.index] : null; }
function groupStyle(group) { return {group,groups:[group],prefixMode:'INHERIT',prefix:line(''),prefixImage:'',nameColor:'DEFAULT',suffixMode:'CUSTOM',suffix:line('')}; }
function recommendedStyle(group) {
 const style=groupStyle(group), name=group.toLowerCase();
 const themes=[[/^(owner|founder|создатель)$/, '#f5d58d','✦'],[/^(admin|administrator)$/, '#f3a2ad','◆'],[/^(mod|moderator)$/, '#a1bcf5','✧'],[/^(helper|support)$/, '#93efc4','+'],[/^(vip|premium)$/, '#f5d58d','★'],[/^(mvp|legend)$/, '#9ce3ed','✦']];
 const match=themes.find(([pattern])=>pattern.test(name));
 if(match){style.nameColor=match[1];style.suffix=line('<'+match[1]+'>'+match[2]+'</'+match[1]+'>');}
 else if(!['default','player','user','member'].includes(name))style.suffix=line('<gray>· {group}</gray>');
 return style;
}
function normalizeStyles(value) {
 for(const [key,fallback] of Object.entries({tabFormatEnabled:false,nativeSortEnabled:false,sortReverse:false,suffixEnabled:true,luckSuffixFallback:true}))if(value[key]===undefined)value[key]=fallback;
 value.tabPlayerFormat={...line('{prefix}{display_name}{suffix}'),...value.tabPlayerFormat};
 value.groupStyles=(value.groupStyles||[]).map(role=>({...groupStyle(role.group),...role,prefix:{...line(''),...role.prefix},suffix:{...line(''),...role.suffix}}));return value;
}
function updateGroups(groups) {
 knownGroups=groups;$('known-groups').replaceChildren();
 for(const role of knownGroups){const option=document.createElement('option');option.value=role.group;option.label=d('groupWeight')+' '+role.weight;$('known-groups').append(option);}
 renderGroupRows();
}
async function loadGroups() {
 const snapshot=await stateSync.read(true);updateGroups(snapshot.groups||[]);return knownGroups;
}
function renderGroupRows() {
 if(!design)return;const list=$('group-order-list');list.replaceChildren();
 design.groupStyles.forEach((role,index)=>{
  const button=document.createElement('button');button.type='button';button.draggable=true;button.className='group-block'+(selected?.section==='groupStyles'&&selected.index===index?' selected':'');button.dataset.section='groupStyles';button.dataset.index=index;
  const grab=document.createElement('span');grab.className='grab';grab.textContent='⠿';const number=document.createElement('span');number.className='group-priority';number.textContent=String(index+1).padStart(2,'0');
  const content=document.createElement('span');content.className='group-content';const name=document.createElement('strong');name.textContent=(role.groups||[role.group]).join(" · ");name.title=name.textContent;if(/^#[0-9a-f]{6}$/i.test(role.nameColor))name.style.color=role.nameColor;
  const meta=document.createElement('small');const known=knownGroups.find(g=>g.group===role.group);meta.textContent=known?d('groupWeight')+' '+known.weight:'';content.append(name,meta);
  const suffix=document.createElement('span');suffix.className='group-suffix';const sample=preview?.groupStyles?.find(g=>g.group===role.group);if(sample)component(sample.suffix,suffix);else suffix.textContent=role.suffixMode==='LUCKPERMS'?'LuckPerms':role.suffixMode==='NONE'?'—':labelText(role.suffix.text||role.suffix.frames[0]||'');
  button.append(grab,number,content,suffix);button.onclick=()=>select('groupStyles',index);list.append(button);
 });
 $('group-count').textContent=design.groupStyles.length+' / 64';$('group-empty').hidden=design.groupStyles.length>0;$('add-group-style').disabled=design.groupStyles.length>=64;$('import-luck-groups').disabled=design.groupStyles.length>=64;
}
const barColors = {PINK:'#ed69b4',BLUE:'#5b83ed',RED:'#e85858',GREEN:'#73cd56',YELLOW:'#e9cf53',PURPLE:'#af61e3',WHITE:'#eeeeee'};
function barId() { const bytes = new Uint8Array(12); crypto.getRandomValues(bytes); return 'boss-'+Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join(''); }
function newBoss(preset = '') {
 const health=preset==='health',timer=preset==='timer';
 return {id:barId(),enabled:true,color:health?'RED':timer?'YELLOW':'PURPLE',style:health?'SEGMENTED_10':'SOLID',line:line(health?'<red>♡ {player} · {health}/{max_health}</red>':timer?'<gold>◷ {remaining} s · {progress}%</gold>':'<aqua><bold>{server}</bold></aqua> <gray>· {online}/{max} online</gray>'),progressMode:health?'HEALTH':timer?'DRAIN':'STATIC',progress:100,progressSource:'online',progressMax:100,durationTicks:200,loop:true,hideAfter:false,darkenSky:false,playMusic:false,createFog:false,worlds:[],permission:''};
}
function normalizeBosses(value) {
 if(!value.artwork)value.artwork={panel:{fill:'#0e1623',end:'#0e1623',gradient:'VERTICAL',stroke:'none',accent:'#68dec0',opacity:75,strokeWidth:1,radius:4,accentWidth:2},panelHeight:0,title:{x:7,y:-1,size:0,width:0,align:'LEFT'},rows:[],boxes:[],shapes:[]};
 if(!value.sidebarMode)value.sidebarMode='NATIVE';if(value.sidebarX===undefined)value.sidebarX=72;if(value.sidebarY===undefined)value.sidebarY=-110;if(value.sidebarFontSize===undefined)value.sidebarFontSize=10;if(value.sidebarLineHeight===undefined)value.sidebarLineHeight=14;
 if(Array.isArray(value.screen))value.screen=value.screen.map(e=>({...e,x:e.x??0,y:e.y??-60,height:e.height??8}));
 if(!Array.isArray(value.screen))value.screen=[];if(value.screenEnabled===undefined)value.screenEnabled=false;
 if (!Array.isArray(value.bossBars)) value.bossBars=[];
 value.bossBars=value.bossBars.map(bar=>({...newBoss(),...bar,line:{...line(''),...bar.line}}));
 if(value.bossBarsEnabled===undefined)value.bossBarsEnabled=false;if(!value.bossBarMode)value.bossBarMode='STACKED';if(value.bossBarRotateTicks===undefined)value.bossBarRotateTicks=200;return value;
}
function barGraphic(title,progress,color,style) {
 const root=document.createElement('span');root.className='boss-graphic';
 const text=document.createElement('span');text.className='boss-title';component(title,text);
 const track=document.createElement('span');track.className='boss-track';track.style.setProperty('--bar-color',barColors[color]||barColors.PURPLE);track.setAttribute('role','progressbar');track.setAttribute('aria-valuemin','0');track.setAttribute('aria-valuemax','100');track.setAttribute('aria-valuenow',String(Math.round(progress*1000)/10));
 const fill=document.createElement('span');fill.className='boss-fill';fill.style.width=(Math.max(0,Math.min(1,progress))*100)+'%';track.append(fill);
 if(style!=='SOLID'){const cuts=document.createElement('span');cuts.className='boss-segments';cuts.style.setProperty('--segments',Number(style.split('_')[1])||10);track.append(cuts);}
 root.append(text,track);return root;
}
function renderBossBars() {
 if(!design)return;const list=$('boss-list');list.replaceChildren();
 design.bossBars.forEach((bar,index)=>{
  const frame=preview?.bossBars?.find(f=>f.id===bar.id),button=document.createElement('button');button.type='button';button.draggable=true;button.className='boss-block'+(selected?.section==='bossBars'&&selected.index===index?' selected':'');button.dataset.section='bossBars';button.dataset.index=index;
  const grab=document.createElement('span');grab.className='grab';grab.textContent='⠿';const content=document.createElement('span');content.className='boss-card-content';
  content.append(barGraphic(frame?.title||labelText(bar.line.text||bar.line.frames[0]||t('text')),frame?.progress??bar.progress/100,bar.color,bar.style));
  const meta=document.createElement('span');meta.className='boss-meta';meta.textContent=String(index+1).padStart(2,'0')+' · '+(frame?.reason?d('barReasons')[frame.reason]||frame.reason:Math.round((frame?.progress??bar.progress/100)*1000)/10+'%');content.append(meta);
  button.append(grab,content);button.onclick=()=>select('bossBars',index);list.append(button);
 });
 $('boss-empty').hidden=design.bossBars.length!==0;$('boss-count').textContent=design.bossBars.length+' / 8';
 $('boss-rotate-field').hidden=design.bossBarMode!=='ROTATE';
 document.querySelectorAll('#add-boss,[data-boss-preset]').forEach(el=>el.disabled=design.bossBars.length>=8);
}
// Preview pulses preserve the draggable roots and pointer capture.
function refreshBossFrames() {
 document.querySelectorAll('#boss-list .boss-block').forEach(button=>{
  const index=Number(button.dataset.index),bar=design.bossBars[index];if(!bar)return;
  const frame=preview?.bossBars?.find(f=>f.id===bar.id),content=button.querySelector('.boss-card-content');
  content.replaceChildren(barGraphic(frame?.title||labelText(bar.line.text||bar.line.frames[0]||t('text')),frame?.progress??bar.progress/100,bar.color,bar.style));
  const meta=document.createElement('span');meta.className='boss-meta';meta.textContent=String(index+1).padStart(2,'0')+' · '+(frame?.reason?d('barReasons')[frame.reason]||frame.reason:Math.round((frame?.progress??bar.progress/100)*1000)/10+'%');content.append(meta);
 });
}
function refreshGroupPreviews() {
 document.querySelectorAll('#group-order-list .group-block').forEach(button=>{
  const role=design.groupStyles[Number(button.dataset.index)];if(!role)return;
  const sample=preview?.groupStyles?.find(g=>g.group===role.group),suffix=button.querySelector('.group-suffix');
  if(sample)fill(suffix,sample.suffix);
 });
}
function addBoss(preset='') {
 if(!design||design.bossBars.length>=8)return;
 commit(()=>{design.bossBars.push(newBoss(preset));design.bossBarsEnabled=true;selected={section:'bossBars',index:design.bossBars.length-1};},true);$('bossbars-enabled').checked=true;
}
const colors = {black:'#000000',dark_blue:'#0000aa',dark_green:'#00aa00',dark_aqua:'#00aaaa',dark_red:'#aa0000',dark_purple:'#aa00aa',gold:'#ffaa00',gray:'#aaaaaa',dark_gray:'#555555',blue:'#5555ff',green:'#55ff55',aqua:'#55ffff',red:'#ff5555',light_purple:'#ff55ff',yellow:'#ffff55',white:'#ffffff'};
let previewAssetSource=null,previewAssetGlyphs=new Map();
function previewGlyphs(){const source=stateSync?.data?.assets||[];if(source!==previewAssetSource){previewAssetSource=source;previewAssetGlyphs=new Map();for(const a of source){const codes=a.glyphs||[a.glyph?.codePointAt(0)];codes.forEach((code,frame)=>{if(code!==undefined)previewAssetGlyphs.set(code,{asset:a,frame});});}}return previewAssetGlyphs;}
function component(node, parent) {
 if (Array.isArray(node)) { node.forEach(n => component(n,parent)); return; }
 const span = document.createElement('span');
 if (typeof node === 'string') span.textContent = node;
 else if (node && typeof node === 'object') {
  const text=node.text||node.translate||'',glyphs=previewGlyphs();
  if(window.TabPrefixDream?.assetImage&&glyphs.size){let run='';const flush=()=>{if(run){const n=document.createElement('span');n.textContent=run;span.append(n);run='';}};for(const char of text){const match=glyphs.get(char.codePointAt(0));if(match){flush();window.TabPrefixDream.assetImage(match.asset,span,match.frame);}else run+=char;}flush();}else span.textContent=text;
  const color = colors[node.color] || node.color; if (color && /^#[0-9a-f]{6}$/i.test(color)) span.style.color = color;
  if (node.bold !== undefined) span.style.fontWeight = node.bold ? 'bold' : 'normal';
  if (node.italic !== undefined) span.style.fontStyle = node.italic ? 'italic' : 'normal';
  const decorations = []; if (node.underlined) decorations.push('underline'); if (node.strikethrough) decorations.push('line-through');
  if (decorations.length) span.style.textDecoration = decorations.join(' ');
  if (node.extra) node.extra.forEach(n => component(n,span));
 }
 parent.append(span);
}
function labelText(source) { return source.replace(/<[^>]+>/g,'').replace(/[&§][0-9a-fklmnorx]/gi,''); }
function fill(el, value) { el.replaceChildren(); component(value,el); }
function renderLists() {
 if (!design) return;
 for (const section of ['header','footer','sidebar']) {
  const container = $(section + '-lines'); container.replaceChildren();
  design[section].forEach((item,index) => {
   const row=document.createElement('div');row.draggable=true;row.className='line-block'+(selected?.section===section&&selected.index===index?' selected':'');row.dataset.section=section;row.dataset.index=index;
   const grab=document.createElement('button');grab.type='button';grab.className='grab line-grab';grab.textContent='⠿';grab.title=language==='ru'?'Перетащить или настроить строку':'Drag or edit line';grab.onclick=()=>select(section,index);
   const number=document.createElement('span');number.className='line-number';number.textContent=String(index+1).padStart(2,'0');
   const fields=document.createElement('div');fields.className='line-inline-fields';
   const input=document.createElement('input');input.maxLength=512;input.spellcheck=false;input.value=lineText(item);input.dataset.lineInput=section;input.dataset.lineIndex=index;input.placeholder=language==='ru'?'Введите текст строки…':'Enter line text…';input.setAttribute('aria-label',section+' '+(index+1));
   input.onfocus=()=>{selected={section,index};inspector();};
   input.oninput=()=>commit(()=>{setLineText(design[section][index],input.value);selected={section,index};design[section+'Enabled']=true;$(section+'-enabled').checked=true;});
   input.ondragstart=e=>e.preventDefault();
   const content=document.createElement('small');content.className='line-content';content.textContent=labelText(item.text)||d('blank');
   const edit=document.createElement('button');edit.type='button';edit.className='line-settings';edit.textContent='⋯';edit.setAttribute('aria-label',language==='ru'?'Настройки строки':'Line settings');edit.onclick=()=>{select(section,index);$('inspector').scrollIntoView({block:'nearest',behavior:'smooth'});};
   fields.append(input,content);row.append(grab,number,fields,edit);row.onclick=e=>{if(!e.target.closest('input,button'))select(section,index);};container.append(row);
  });
  document.querySelector('[data-add="' + section + '"]').disabled = design[section].length >= (section === 'sidebar' ? 15 : 12);
 }
}
function renderGrid() {
 if (!design) return; const grid = $('tab-grid'); grid.replaceChildren();
 design.slots.forEach((cell,index) => {
  const button = document.createElement('button'); button.type = 'button'; button.draggable = true;
  button.className = 'tab-slot' + (selected?.section === 'slots' && selected.index === index ? ' selected' : '');
  button.dataset.section = 'slots'; button.dataset.index = index; button.dataset.kind = cell.kind;
  button.setAttribute('aria-label', (language === 'ru' ? 'Столбец ' : 'Column ') + (Math.floor(index / 20) + 1) + ', ' + (index % 20 + 1));
  const grab = document.createElement('span'); grab.className = 'grab'; grab.textContent = '⠿';
  const content = document.createElement('span'); content.className = 'slot-text';
  if (cell.kind === 'PLAYER') content.textContent = '♙ ' + cell.playerIndex;
  else if (cell.kind === 'TEXT') content.textContent = labelText(cell.line.text || cell.line.frames[0] || t('text')); 
  else content.textContent = '·';
  const number = document.createElement('span'); number.className = 'index'; number.textContent = index + 1;
  button.append(grab,content,number); button.onclick = () => select('slots',index); grid.append(button);
 });
 $('layout-disabled').hidden = design.layoutEnabled;
}
function deselect(){selected=null;renderLists();renderGrid();inspector();window.dispatchEvent(new Event('studio-selection'));}
function select(section,index) { selected = {section,index}; renderLists(); renderGrid(); renderBossBars(); renderGroupRows(); inspector(); window.dispatchEvent(new Event('studio-selection')); }
function inspector() {
 const l = selectedLine(); $('inspector').hidden = !l || !['tab','dream','scoreboard','bossbars'].includes(panel); if (!l) return;
 const isSlot = selected.section === 'slots', cell = isSlot ? design.slots[selected.index] : null;
 const role=selectedRole(),global=selected.section==='tabPlayerFormat';
 $('selected-label').textContent = global?'PLAYER FORMAT':role?role.group:isSlot ? (Math.floor(selected.index / 20) + 1) + ':' + (selected.index % 20 + 1) : String(selected.index + 1);
 $('slot-fields').hidden = !isSlot;
 $('role-fields').hidden=!role;
 if(role){$('role-group').value=role.group;$('role-color-enabled').checked=role.nameColor!=='DEFAULT';$('role-name-color').value=role.nameColor==='DEFAULT'?'#93efc4':role.nameColor;$('role-name-color').disabled=role.nameColor==='DEFAULT';$('role-suffix-mode').value=role.suffixMode;}
 const editable=!role||role.suffixMode==='CUSTOM';
 for(const id of ['block-text','animation','text-color','format-bold','format-italic','format-reset','animation-speed','animation-width','animation-gap','animation-frames'])$(id).disabled=!editable;
 document.querySelectorAll('#variable-chips button').forEach(el=>{el.disabled=!editable;el.hidden=el.dataset.variable==='tab_name'&&global||el.dataset.variable==='suffix'&&!!role;});
 const boss=selectedBoss();$('boss-fields').hidden=!boss;
 document.querySelectorAll('[data-boss-variable]').forEach(el=>el.hidden=!boss);
 if(boss) {
  $('boss-enabled').checked=boss.enabled;$('boss-color').value=boss.color;$('boss-style').value=boss.style;$('boss-progress-mode').value=boss.progressMode;
  $('boss-progress').value=$('boss-progress-slider').value=boss.progress;$('boss-progress-source').value=boss.progressSource;$('boss-progress-max').value=boss.progressMax;
  $('boss-duration').value=boss.durationTicks/20;$('boss-loop').checked=boss.loop;$('boss-hide-after').checked=boss.hideAfter;
  $('boss-worlds').value=boss.worlds.join('\n');$('boss-permission').value=boss.permission;
  $('boss-darken-sky').checked=boss.darkenSky;$('boss-play-music').checked=boss.playMusic;$('boss-create-fog').checked=boss.createFog;
  $('boss-static-fields').hidden=boss.progressMode!=='STATIC';$('boss-custom-fields').hidden=boss.progressMode!=='CUSTOM';$('boss-timer-fields').hidden=!['FILL','DRAIN','PULSE'].includes(boss.progressMode);$('boss-hide-after-field').hidden=boss.loop;
  $('duplicate-boss').disabled=design.bossBars.length>=8;
  document.querySelectorAll('[data-bar-color]').forEach(el=>{el.classList.toggle('active',el.dataset.barColor===boss.color);el.setAttribute('aria-pressed',String(el.dataset.barColor===boss.color));});
 }
 if (isSlot) { $('slot-kind').value = cell.kind; $('player-index').value = cell.playerIndex; $('player-index-label').hidden = cell.kind !== 'PLAYER'; }
 $('block-text').value = lineText(l);const color = lineText(l).match(/<(?:color:)?(#[0-9a-f]{6})>/i);$('text-color').value = color ? color[1] : '#ffffff';$('format-bold').classList.toggle('active',/<bold>/i.test(lineText(l)));$('format-italic').classList.toggle('active',/<italic>/i.test(lineText(l))); $('animation').value = l.animation; $('animation-speed').value = l.speed/20;
 $('animation-width').value = l.width; $('animation-gap').value = l.gap; $('animation-frames').value = l.frames.join('\n');
 $('animation-fields').hidden = l.animation === 'NONE'; $('scroll-fields').hidden = !['SCROLL','BOUNCE','TYPEWRITER'].includes(l.animation); $('frames-field').hidden = l.animation !== 'FRAMES';frameHint(l);
 $('move-left').hidden = $('move-right').hidden = !isSlot;
 $('move-up').hidden=$('move-down').hidden=$('delete-block').hidden=global;
 updateBlockPreview();
}
function frameHint(l){let hint=$('frames-distinct-hint');if(!hint){hint=document.createElement('p');hint.id='frames-distinct-hint';hint.className='small muted';$('frames-field').append(hint);}hint.hidden=l.animation!=='FRAMES'||new Set(l.frames).size>1;hint.textContent=language==='ru'?'Для смены кадров нужны хотя бы две разные строки.':'Frame animation needs at least two different lines.';}
function updateBlockPreview() { if (!selected) return; const value = selected.section==='groupStyles'?preview?.groupStyles?.find(g=>g.group===selectedRole()?.group)?.suffix:selected.section==='tabPlayerFormat'?preview?.playerFormat:selected.section==='bossBars'?preview?.bossBars?.find(f=>f.id===selectedBoss()?.id)?.title:preview?.[selected.section]?.[selected.index]; if (value !== undefined && value !== null) fill($('block-preview'),value); else $('block-preview').textContent = selectedLine()?.text || ''; }
function repaintForm() {
 normalizeStyles(normalizeBosses(design));previewClock=performance.now();
 $('server-name').value = design.serverName; $('refresh-ticks').value = design.refreshTicks; $('sort').value = design.sort;
 for (const [id,key] of Object.entries({'header-enabled':'headerEnabled','footer-enabled':'footerEnabled','sidebar-enabled':'sidebarEnabled','layout-enabled':'layoutEnabled','nametags-enabled':'nameTagsEnabled','bossbars-enabled':'bossBarsEnabled','tab-format-enabled':'tabFormatEnabled','sort-reverse':'sortReverse','native-sort-enabled':'nativeSortEnabled','suffix-enabled':'suffixEnabled','luck-suffix-fallback':'luckSuffixFallback'})) $(id).checked = design[key];
 $('boss-mode').value=design.bossBarMode;$('boss-rotate-seconds').value=design.bossBarRotateTicks/20;
 for (const [id,key] of Object.entries({'sidebar-title':'title','sidebar-mode':'sidebarMode','sidebar-x':'sidebarX','sidebar-y':'sidebarY','name-prefix':'nameTagPrefix','name-suffix':'nameTagSuffix','name-visibility':'visibility','name-collision':'collision'})) $(id).value = design[key];
 renderLists(); renderGrid(); renderBossBars(); renderGroupRows(); inspector(); $('revision').textContent = d('revision') + revision; updateDirty(); schedulePreview(); window.dispatchEvent(new Event('studio-change'));
}
function reorder(from,to) {
 if (!from || !to || from.section !== to.section) return;
 if (from.index === to.index) return;
 commit(() => {
  const items = design[from.section];
  if (from.section === 'slots') [items[from.index],items[to.index]] = [items[to.index],items[from.index]];
  else items.splice(to.index,0,items.splice(from.index,1)[0]);
  selected = to;
 },true);
}
function move(delta) {
 if (!selected||selected.section==='tabPlayerFormat') return; const items = design[selected.section], to = selected.index + delta;
 if (to < 0 || to >= items.length) return;
 if (selected.section === 'slots' && Math.abs(delta) === 1 && Math.floor(to / 20) !== Math.floor(selected.index / 20)) return;
 reorder(selected,{section:selected.section,index:to});
}
function dropBlock(data,target) {
 if (!target) return;
 if (data.kind) {
  if (target.section !== 'slots') return;
  commit(() => { design.slots[target.index] = slot(data.kind,nextPlayerIndex()); selected = target; },true);
 } else reorder(data,target);
}
const targetOf = el => { const block = el?.closest('[data-section][data-index]'); return block ? {section:block.dataset.section,index:Number(block.dataset.index)} : null; };
document.addEventListener('dragstart',e => {
 if(e.target.closest('input,textarea,select')){e.preventDefault();return;}
 const target = targetOf(e.target), palette = e.target.closest('[data-kind]');
 drag = target || (palette ? {kind:palette.dataset.kind} : null); if (!drag) return;
 e.dataTransfer.setData('application/x-tabprefix',JSON.stringify(drag)); e.dataTransfer.effectAllowed = 'move';
});
document.addEventListener('dragover',e => { if (!drag) return; const target = targetOf(e.target); if (!target || !drag.kind && drag.section !== target.section || drag.kind && target.section !== 'slots') return; e.preventDefault(); e.target.closest('[data-section]').classList.add('drop-target'); });
document.addEventListener('dragleave',e => e.target.closest('[data-section]')?.classList.remove('drop-target'));
document.addEventListener('drop',e => { if (!drag) return; e.preventDefault(); dropBlock(drag,targetOf(e.target)); drag = null; document.querySelectorAll('.drop-target').forEach(el => el.classList.remove('drop-target')); });
document.addEventListener('dragend',() => { drag = null; document.querySelectorAll('.drop-target').forEach(el => el.classList.remove('drop-target')); });
document.addEventListener('pointerdown',e => {
 if (e.pointerType === 'mouse' || !e.target.closest('.grab')) return;
 const source = targetOf(e.target); if (!source) return;
 e.preventDefault(); touch = {source,startX:e.clientX,startY:e.clientY,target:source,moved:false}; e.target.setPointerCapture(e.pointerId);
});
document.addEventListener('pointermove',e => {
 if (!touch) return; if (Math.hypot(e.clientX-touch.startX,e.clientY-touch.startY) > 8) touch.moved = true;
 if (!touch.moved) return; const el = document.elementFromPoint(e.clientX,e.clientY), target = targetOf(el);
 document.querySelectorAll('.drop-target').forEach(x => x.classList.remove('drop-target'));
 if (target?.section === touch.source.section) { touch.target = target; el.closest('[data-section]').classList.add('drop-target'); }
});
function endTouch() { if (!touch) return; if (touch.moved) reorder(touch.source,touch.target); else select(touch.source.section,touch.source.index); touch = null; document.querySelectorAll('.drop-target').forEach(el => el.classList.remove('drop-target')); }
document.addEventListener('pointerup',endTouch); document.addEventListener('pointercancel',() => touch = null);
function schedulePreview() { clearTimeout(previewTimer); previewTimer = setTimeout(fetchPreview,400); }
async function fetchPreview() {
 if (!design || !session?.canDesign || expired || document.hidden) return;
 if (previewBusy) { previewPending = true; return; }
 previewBusy = true; const version = generation;
 try {
  const time=paused?0:Math.floor((performance.now()-previewClock)/50)%100000000;
  const data = await api('display/preview',autoSaver&&!autoSaver.dirty?{revision,tick:time}:{design,tick:time});
  if (version !== generation) { previewPending = true; return; }
  preview = data;previewGeneration=version; renderPreview(); window.dispatchEvent(new Event('studio-preview'));
 } catch (error) { if(error.status===409)autoSaver?.refresh();else notice(error.message,'error'); }
 finally { previewBusy = false; if (previewPending) { previewPending = false; schedulePreview(); } }
}
function legacyGlyphs(source){const glyphs=[];let color='white',style={};for(let n=0;n<source.length;){if(source[n]==='§'&&n+1<source.length){const code=source[n+1].toLowerCase();if(code==='x'&&/^(?:§[0-9a-f]){6}/i.test(source.slice(n+2,n+14))){color='#'+source.slice(n+2,n+14).replace(/§/g,'');style={};n+=14;continue;}if(code==='r'){color='white';style={};}else if('0123456789abcdef'.includes(code)){color=['black','dark_blue','dark_green','dark_aqua','dark_red','dark_purple','gold','gray','dark_gray','blue','green','aqua','red','light_purple','yellow','white'][parseInt(code,16)];style={};}else{const names={l:'bold',o:'italic',n:'underlined',m:'strikethrough',k:'obfuscated'};if(names[code])style[names[code]]=true;}n+=2;continue;}const cp=source.codePointAt(n);glyphs.push({text:String.fromCodePoint(cp),color,...style});n+=cp>65535?2:1;}return glyphs;}
function animatedComponent(line,sources,tick){const animate=preview.animationsEnabled!==false&&!paused,index=line.animation==='FRAMES'&&animate?Math.floor(tick/line.speed)%sources.length:0;let glyphs=legacyGlyphs(sources[index]||'');const step=Math.floor(tick/line.speed);if(animate&&glyphs.length){if(line.animation==='SCROLL'){const padding=Math.max(line.gap,line.width-glyphs.length+line.gap);for(let n=0;n<padding;n++)glyphs.push({text:' ',color:'white'});const offset=step%glyphs.length;glyphs=Array.from({length:line.width},(_,n)=>glyphs[(offset+n)%glyphs.length]);}else if(line.animation==='BOUNCE'){const distance=Math.max(0,glyphs.length-line.width),phase=distance?step%(distance*2):0,offset=phase<=distance?phase:distance*2-phase;glyphs=glyphs.slice(offset,offset+line.width);}else if(line.animation==='TYPEWRITER'){const phase=step%((glyphs.length+1)*2+line.gap),count=Math.min(glyphs.length,phase<=glyphs.length+line.gap?phase:Math.max(0,(glyphs.length+1)*2+line.gap-phase));glyphs=glyphs.slice(0,count);}else if(line.animation==='BLINK'){if(step%12>=8)glyphs=glyphs.map(()=>({text:' ',color:'white'}));}else if(['SHIMMER','WAVE','PULSE'].includes(line.animation)){const palette=line.animation==='WAVE'?['red','gold','yellow','green','aqua','blue','light_purple']:line.animation==='PULSE'?['dark_gray','gray','dark_gray','white','gray','dark_gray','gray','dark_aqua']:['dark_aqua','dark_aqua','dark_aqua','aqua','aqua','white','white','aqua','aqua','dark_aqua','dark_aqua','dark_aqua'];glyphs=glyphs.map((g,n)=>({...g,color:palette[(step+(line.animation==='PULSE'?0:n))%palette.length]}));}}
 const result=[];for(const glyph of glyphs){const previous=result.at(-1);if(previous&&previous.color===glyph.color&&['bold','italic','underlined','strikethrough','obfuscated'].every(k=>previous[k]===glyph[k]))previous.text+=glyph.text;else result.push({...glyph});}return result;}
function animatePreview(){if(document.hidden)return;window.TabPrefixDream?.animateImages(paused?0:Math.floor((performance.now()-previewClock)/50),!paused&&preview?.animationsEnabled!==false);if(!preview?.animationSources||previewGeneration!==generation||!design||document.hidden)return;let updated=false;const tick=paused?0:Math.floor((performance.now()-previewClock)/50);for(const zone of ['header','footer']){
 const root=$('tab-preview-'+zone),sources=preview.animationSources[zone];if(!sources)continue;
 for(let n=0;n<design[zone].length;n++){const line=design[zone][n];if(line.animation==='NONE'||!sources[n])continue;const value=animatedComponent(line,sources[n],tick);if(window.TabPrefixLiveSave.equal(value,preview[zone][n]))continue;updated=true;preview[zone][n]=value;const child=root.children[n];if(child&&design.tabLayoutMode!=='COMPACT'){child.replaceChildren();component(value,child);}const small=document.querySelector('.line-block[data-section="'+zone+'"][data-index="'+n+'"] .line-content');if(small){small.replaceChildren();component(value,small);}}
 }if(design.tabLayoutMode==='COMPACT')renderBands();if(updated){if(selected&&['header','footer'].includes(selected.section))updateBlockPreview();window.dispatchEvent(new Event('studio-animation'));}
}
function footerHint(){
 let hint=$('footer-visibility-hint');if(!hint){hint=document.createElement('div');hint.id='footer-visibility-hint';hint.className='notice';hint.hidden=true;$('footer-lines').before(hint);}hint.replaceChildren();
 const hasText=design.footer.some(l=>l.text||l.frames.length),animated=design.footer.some(l=>l.animation!=='NONE');let message='',action=null;
 if(!design.footerEnabled&&hasText){message=language==='ru'?'Текст футера выключен.':'Footer text is disabled.';action=()=>commit(()=>{design.footerEnabled=true;$('footer-enabled').checked=true;});}
 else if(design.footerEnabled&&preview.headerFooterEnabled===false){message=language==='ru'?'Header и Footer выключены в ваших личных настройках.':'Header and footer are disabled in your personal settings.';action=prefSaver?()=>{personal.headerFooter=true;prefSaver.change();}:null;}
 else if(design.footerEnabled&&animated&&preview.animationsEnabled===false){message=language==='ru'?'Анимация выключена в ваших личных настройках.':'Animation is disabled in your personal settings.';action=prefSaver?()=>{personal.animations=true;prefSaver.change();}:null;}
 else if(preview.footerConflict)message=language==='ru'?'Другой плагин перезаписывает футер. Отключите его вывод футера; /lptab doctor покажет конфликт.':'Another plugin overwrites the footer. Disable its footer output; /lptab doctor reports the conflict.';
 hint.hidden=!message;if(!message)return;const text=document.createElement('span');text.textContent=message;hint.append(text);if(action){const button=document.createElement('button');button.type='button';button.className='secondary';button.textContent=language==='ru'?'Включить':'Enable';button.onclick=action;hint.append(button);}
}
function renderBands(){
 if(!preview||!design)return;
 for(const section of ['header','footer']){const root=$('tab-preview-'+section);root.replaceChildren();if(!design[section+'Enabled'])continue;let indices=Array.from({length:preview[section]?.length||0},(_,i)=>i);
  if(design.tabLayoutMode==='COMPACT'&&indices.length){const rows=design.tabBandRows||2,costs=indices.map(index=>{const b=design.artwork?.boxes?.find(b=>b.surface===section.toUpperCase()&&b.index===index);return b?Math.max(1,Math.ceil(Math.min(b.height,Math.max(9,(rows-1)*9))/9)):1;});if(costs.reduce((a,b)=>a+b,0)>rows){const room=rows-costs[0],whole=room<=0||costs.slice(1).some(n=>n>room),animate=preview.animationsEnabled!==false&&!paused;let used=0,out=[];if(!whole||!animate){out=[0];used=costs[0];}const count=whole?indices.length:indices.length-1,phase=animate?Math.floor((performance.now()-previewClock)/5000)%Math.max(1,count):0;for(let n=0;n<count;n++){const i=whole?(phase+n)%indices.length:1+(phase+n)%(indices.length-1);if(!animate&&i===0)continue;if(used+costs[i]>rows)continue;out.push(i);used+=costs[i];}indices=out;}}
  for(const index of indices){const el=document.createElement('div');el.className='tab-band-row';component(preview[section][index],el);const box=design.artwork?.boxes?.find(b=>b.surface===section.toUpperCase()&&b.index===index);if(box){const p=box.paint||{};el.classList.add('tab-band-art');el.style.background='linear-gradient('+(p.gradient==='HORIZONTAL'?'90':'180')+'deg,'+(p.fill||'#15202c')+','+(p.end||p.fill||'#15202c')+')';el.style.borderColor=p.stroke==='none'?'transparent':p.stroke||'transparent';el.style.borderRadius=Math.min(12,p.radius||0)+'px';el.style.textAlign=(box.align||'CENTER').toLowerCase();if(design.tabLayoutMode==='COMPACT')el.style.minHeight=(Math.min(box.height,Math.max(9,((design.tabBandRows||2)-1)*9))*1.5)+'px';}el.tabIndex=0;el.setAttribute('role','button');el.onclick=()=>select(section,index);el.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();el.click();}};root.append(el);}
 }
}
function renderPreview() {
 if (!preview || !design) return;footerHint();
 fill($('score-preview-title'),preview.title); const lines = $('score-preview-lines'); lines.replaceChildren();
 preview.sidebar.forEach((value,index) => { const el = document.createElement('div'); el.className = 'score-line'; const text = document.createElement('span'); component(value,text); el.append(text);if(design.sidebarMode==='NATIVE'&&(!session.blankScoresSupported||preview.scoreNumbers==='adapter-unavailable')){const score=document.createElement('em');score.textContent=15-index;el.append(score);} lines.append(el); });
 fill($('nametag-preview'),preview.nameTag); $('nametag-preview').style.opacity = design.visibility === 'NEVER' ? '.25' : '1';
 const stack=$('boss-preview-stack');stack.replaceChildren();for(const bar of preview.bossBars||[]){if(bar.active)stack.append(barGraphic(bar.title,bar.progress,bar.color,bar.style));}$('boss-preview-empty').hidden=stack.childElementCount>0;refreshBossFrames();
 renderBands();
 const grid=$('tab-preview-slots');grid.replaceChildren();const layout=preview.tabLayout;
 const values=design.layoutEnabled?(layout?.cells||preview.slots):(preview.players||preview.slots),columns=design.layoutEnabled?(layout?.columns||4):Math.max(1,Math.min(4,Math.ceil(values.length/20))),rows=design.layoutEnabled?(layout?.rows||20):Math.max(1,Math.ceil(values.length/columns));
 grid.style.gridTemplateColumns='repeat('+columns+',minmax(0,1fr))';grid.style.gridTemplateRows='repeat('+rows+',minmax(22px,auto))';grid.dataset.columns=columns;
 values.forEach((value,n)=>{const el=document.createElement('div');const source=layout?.source?.[n]??n,box=design.layoutEnabled?design.artwork?.boxes?.find(b=>b.surface==='CELL'&&b.index===source):null;
 const heads=design.tabHeads==='ON'||design.tabHeads!=='OFF'&&layout?.heads===true,player=layout?.playerCells?.[n]??true;
 const content=document.createElement('span');content.className='tab-cell-content';component(value,content);
 const face=heads?document.createElement('span'):null;if(face){face.className='tab-cell-face';face.setAttribute('aria-hidden','true');face.textContent='';}
 const ping=document.createElement('span');ping.className='tab-cell-ping';ping.setAttribute('aria-hidden','true');if(player)for(let i=0;i<5;i++){const bar=document.createElement('i');bar.style.height=(3+i*2)+'px';ping.append(bar);}
 if(box){const card=document.createElement('span'),p=box.paint||{},full=(box.fit||'ROW')==='ROW',rgba=(hex)=>{if(!hex||hex==='none')return 'transparent';const n=parseInt(hex.slice(1),16);return 'rgba('+[(n>>16)&255,(n>>8)&255,n&255,(p.opacity??90)/100].join(',')+')';};card.className='tab-cell-card';card.classList.toggle('tab-cell-full',full);card.style.margin=(full?0:(box.inset??.5)*1.5)+'px';card.style.borderWidth=(p.stroke==='none'?0:Math.min(p.strokeWidth??1,4)*1.5)+'px';card.style.borderColor=rgba(p.stroke);card.style.borderRadius=(Math.min(p.radius??4,4)*1.5)+'px';card.style.background=p.fill==='none'?'none':'linear-gradient('+(p.gradient==='HORIZONTAL'?'90':'180')+'deg,'+rgba(p.fill)+','+rgba(p.end&&p.end!=='none'?p.end:p.fill)+')';content.style.textAlign=(box.align||'CENTER').toLowerCase();if(full){if(face)card.append(face);card.append(content,ping);el.append(card);}else{if(face)el.append(face);card.append(content);el.append(card,ping);}el.classList.add('tab-cell-styled');}else{if(face)el.append(face);el.append(content,ping);}
 if(design.layoutEnabled&&source>=0){el.dataset.source=source;el.tabIndex=0;el.setAttribute('role','button');el.setAttribute('aria-label',(language==='ru'?'Редактировать ячейку ':'Edit cell ')+(source+1));el.onclick=()=>select('slots',source);el.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();el.click();}};}grid.append(el);});
 const summary=$('tab-layout-summary');if(summary)summary.textContent=(language==='ru'?'В игре: ':'In game: ')+columns+' × '+rows+(design.tabLayoutMode==='COMPACT'?(language==='ru'?' · по содержимому':' · fits content'):'');
 for(const section of ['header','footer','sidebar'])document.querySelectorAll('.line-block[data-section="'+section+'"]').forEach(el=>{const value=preview[section]?.[Number(el.dataset.index)];if(value){const content=el.querySelector('.line-content');fill(content,value);if(!content.textContent)content.textContent=d('blank');}});
 document.querySelectorAll('.tab-slot[data-kind="TEXT"]').forEach(el=>{const value=preview.slots?.[Number(el.dataset.index)];if(value)fill(el.querySelector('.slot-text'),value);});
 $('overflow').textContent = preview.overflow ? d('overflow') + preview.overflow : '';
 fill($('player-format-preview'),preview.playerFormat||'');refreshGroupPreviews();$('sort-conflicts').hidden=!preview.sortConflicts;$('sort-conflicts').textContent=d('conflict');
 $('conflicts').hidden = !preview.sidebarConflicts && !preview.nameTagConflicts && preview.layoutAvailable;
 $('conflicts').textContent = d('conflict');$('score-conflicts').hidden=!preview.sidebarConflicts;$('score-conflicts').textContent=d('conflict');$('layout-conflicts').hidden=preview.layoutAvailable&&!preview.headerConflicts;$('layout-conflicts').textContent=d('conflict');updateBlockPreview();
}
async function preset(name) {
 if(!design||busy||expired)return;
 if (JSON.stringify(design) !== persisted && !window.confirm(d('overwrite'))) return;
 if(name==='calm'){
  try{await loadGroups();}catch(error){notice(error.message,'error');return;}
  commit(()=>{
   design.headerEnabled=design.footerEnabled=design.tabFormatEnabled=design.nativeSortEnabled=design.suffixEnabled=design.luckSuffixFallback=true;
   design.layoutEnabled=false;design.sort='PRIORITY';design.sortReverse=false;
   design.header=[line('<#93efc4><bold>{server}</bold></#93efc4>'),line('<gray>{online}<dark_gray> / </dark_gray>{max} <dark_gray>·</dark_gray> {world}</gray>')];
   design.footer=[line('<dark_gray>───────────────</dark_gray>'),line('<gray>{player} <dark_gray>·</dark_gray> <#93efc4>{ping} ms</#93efc4></gray>')];
   design.tabPlayerFormat=line('{prefix}{display_name}{suffix}');design.groupStyles=knownGroups.slice(0,64).map(g=>recommendedStyle(g.group));
   design.slots=Array.from({length:80},(_,i)=>slot('PLAYER',i+1));if(!design.nameTagSuffix)design.nameTagSuffix='{suffix}';selected=null;
  });repaintForm();show('tab');return;
 }
 commit(() => {
  const accent = name === 'classic' ? 'gold' : name === 'neon' ? 'aqua' : 'white';
  design.headerEnabled = design.footerEnabled = design.sidebarEnabled = design.nameTagsEnabled = true;
  design.layoutEnabled=true;design.tabLayoutMode='COMPACT';design.tabWidth=260;design.tabBandRows=2;design.tabColumnGap=6;
  design.header = [line('<'+accent+'><bold>{server}</bold></'+accent+'>'),line('<gray>{online}/{max} online · {world}</gray>')];
  if (name === 'neon') design.header[0] = line('<gradient:#93efc4:#95acff><bold>{server}</bold></gradient>');
  design.footer = [line('<gray>{player} · <'+accent+'>{ping} ms</'+accent+'></gray>')];
  design.title = '<'+accent+'><bold>{server}</bold></'+accent+'>';
  if (name === 'neon') design.title = '<gradient:#93efc4:#95acff><bold>{server}</bold></gradient>';
  design.sidebar = [line(''),line('<gray>Player <white>{player}</white></gray>'),line('<gray>Group <'+accent+'>{group}</'+accent+'></gray>'),line(''),line('<gray>Online <green>{online}/{max}</green></gray>'),line('<gray>World <white>{world}</white></gray>'),line('<gray>Ping <green>{ping} ms</green></gray>'),line(''),line('<'+accent+'>{server}</'+accent+'>')];
  design.slots = Array.from({length:80},(_,i) => slot('PLAYER',i+1));
  if (name === 'neon') { const l = line('<aqua>✦ Welcome to {server} · Build, explore and play together!</aqua>'); l.animation = 'SCROLL'; l.width = 40; design.footer.push(l); }
  design.nameTagPrefix = '{prefix}'; design.nameTagSuffix = ''; selected = null;
 }); repaintForm(); show('tab');
}
let personal = {};
function renderPersonal() {
 const root = $('personal-toggles'); root.replaceChildren();
 for (const [key,labels] of Object.entries(d('personal'))) {
  const row = document.createElement('div'); row.className = 'personal-toggle';
  const label = document.createElement('label'); label.htmlFor = 'pref-' + key; label.textContent = labels[0];
  const hint = document.createElement('small'); hint.textContent = labels[1]; label.append(hint);
  const input = document.createElement('input'); input.type = 'checkbox'; input.id = 'pref-'+key; input.checked = personal[key] !== false;
  input.onchange = () => {personal[key]=input.checked;prefSaver?.change();}; row.append(label,input); root.append(row);
 }
}
$('save-settings').onclick = async () => {if(prefSaver){try{await prefSaver.flush();}catch(error){notice(error.message,'error');}return;}
 if (busy || expired) return; busy = true; notice(d('loading')); $('save-settings').disabled = true;
 try { personal = await api('preferences',personal); notice(d('prefsSaved'),'success'); }
 catch (error) { notice(error.message,'error'); }
 finally { busy = false; $('save-settings').disabled = expired; }
};
document.querySelectorAll('[data-panel]').forEach(el => el.onclick = () => show(el.dataset.panel));
document.querySelectorAll('[data-go]').forEach(el => el.onclick = () => show(el.dataset.go));
document.querySelectorAll('[data-preset]').forEach(el => el.onclick = () => preset(el.dataset.preset));
document.querySelectorAll('[data-add]').forEach(el => el.onclick = () => {
 const section = el.dataset.add; if (design[section].length >= (section === 'sidebar' ? 15 : 12)) { notice(d('limit')); return; }
 commit(() => { design[section].push(line(''));if(['header','footer'].includes(section)){design[section+'Enabled']=true;$(section+'-enabled').checked=true;} selected = {section,index:design[section].length-1}; },true);
});
document.querySelectorAll('.palette [data-kind]').forEach(el => el.onclick = () => {
 if (selected?.section !== 'slots') select('slots',0);
 dropBlock({kind:el.dataset.kind},selected);
});
for (const [id,key] of Object.entries({'header-enabled':'headerEnabled','footer-enabled':'footerEnabled','sidebar-enabled':'sidebarEnabled','layout-enabled':'layoutEnabled','nametags-enabled':'nameTagsEnabled','bossbars-enabled':'bossBarsEnabled','tab-format-enabled':'tabFormatEnabled','sort-reverse':'sortReverse','native-sort-enabled':'nativeSortEnabled','suffix-enabled':'suffixEnabled','luck-suffix-fallback':'luckSuffixFallback'})) $(id).onchange = () => commit(() => design[key] = $(id).checked);
$('edit-player-format').onclick=()=>select('tabPlayerFormat',0);
$('role-group').onchange=()=>{
 const role=selectedRole(),name=$('role-group').value.trim().toLowerCase();if(!role)return;
 if(!/^[a-z0-9_.-]{1,128}$/.test(name)){notice(d('invalidGroup'),'error');$('role-group').value=role.group;return;}
 if(design.groupStyles.some(r=>r!==role&&r.group===name)){notice(d('duplicateGroup'),'error');$('role-group').value=role.group;return;}
 commit(()=>{role.groups=(role.groups||[role.group]).map(g=>g===role.group?name:g);role.group=name;},true);
};
$('role-color-enabled').onchange=()=>commit(()=>selectedRole().nameColor=$('role-color-enabled').checked?$('role-name-color').value:'DEFAULT',true);
$('role-name-color').oninput=()=>commit(()=>selectedRole().nameColor=$('role-name-color').value);
$('role-suffix-mode').onchange=()=>commit(()=>selectedRole().suffixMode=$('role-suffix-mode').value,true);
document.querySelectorAll('[data-suffix-snippet]').forEach(el=>el.onclick=()=>commit(()=>{const role=selectedRole();role.suffixMode='CUSTOM';role.suffix=line(el.dataset.suffixSnippet==='badge'?'<gold>[{group}]</gold>':el.dataset.suffixSnippet==='world'?'<gray>· {world}</gray>':'<aqua>✦</aqua>');},true));
$('add-group-style').onclick=()=>{
 if(design.groupStyles.length>=64)return;const available=knownGroups.find(g=>!design.groupStyles.some(r=>(r.groups||[r.group]).includes(g.group)));let name=available?.group||'new-group',i=2;while(design.groupStyles.some(r=>r.group===name))name='new-group-'+i++;
 commit(()=>{design.groupStyles.push(groupStyle(name));selected={section:'groupStyles',index:design.groupStyles.length-1};},true);$('role-group').focus();$('role-group').select();
};
$('import-luck-groups').onclick=async()=>{
 if(busy||expired)return;busy=true;updateDirty();
 try{await loadGroups();busy=false;commit(()=>{for(const role of knownGroups){if(design.groupStyles.length>=64)break;if(!design.groupStyles.some(r=>(r.groups||[r.group]).includes(role.group)))design.groupStyles.push(recommendedStyle(role.group));}},true);}
 catch(error){notice(error.message,'error');}finally{busy=false;updateDirty();}
};
$('add-boss').onclick=()=>addBoss();document.querySelectorAll('[data-boss-preset]').forEach(el=>el.onclick=()=>addBoss(el.dataset.bossPreset));
$('boss-mode').onchange=()=>commit(()=>design.bossBarMode=$('boss-mode').value);
$('boss-rotate-seconds').onchange=()=>commit(()=>design.bossBarRotateTicks=Math.round(Number($('boss-rotate-seconds').value)*20));
for(const [id,key] of Object.entries({'boss-color':'color','boss-style':'style','boss-progress-mode':'progressMode','boss-progress-source':'progressSource'}))$(id).onchange=()=>commit(()=>selectedBoss()[key]=$(id).value,true);
for(const [id,key] of Object.entries({'boss-enabled':'enabled','boss-loop':'loop','boss-hide-after':'hideAfter','boss-darken-sky':'darkenSky','boss-play-music':'playMusic','boss-create-fog':'createFog'}))$(id).onchange=()=>commit(()=>selectedBoss()[key]=$(id).checked,true);
for(const [id,key] of Object.entries({'boss-progress-max':'progressMax','boss-duration':'durationTicks'}))$(id).onchange=()=>commit(()=>selectedBoss()[key]=key==='durationTicks'?Math.round(Number($(id).value)*20):Number($(id).value));
for(const id of ['boss-progress','boss-progress-slider'])$(id).oninput=()=>commit(()=>{const value=Number($(id).value);selectedBoss().progress=value;$('boss-progress').value=$('boss-progress-slider').value=value;});
$('boss-worlds').oninput=()=>commit(()=>selectedBoss().worlds=$('boss-worlds').value.split('\n').map(s=>s.trim()).filter(Boolean));
$('boss-permission').oninput=()=>commit(()=>selectedBoss().permission=$('boss-permission').value.trim());
$('duplicate-boss').onclick=()=>{if(design.bossBars.length>=8)return;commit(()=>{const bar=copy(selectedBoss());bar.id=barId();design.bossBars.splice(selected.index+1,0,bar);selected.index++;},true);};
for(const [name,color] of Object.entries(barColors)){const button=document.createElement('button');button.type='button';button.dataset.barColor=name;button.style.setProperty('--swatch',color);button.title=t(name.toLowerCase());button.setAttribute('aria-label',name);button.onclick=()=>commit(()=>selectedBoss().color=name,true);$('boss-swatches').append(button);}
for(const key of ['online','max','health','max_health','food','experience','level','ping','x','y','z','layout_overflow']){const option=document.createElement('option');option.value=key;option.textContent='{'+key+'}';$('boss-progress-source').append(option);}
$('inspector').insertBefore($('boss-fields'),$('variable-chips'));
$('inspector').insertBefore($('role-fields'),$('slot-fields'));
const tabCanvas=document.querySelector('#panel-tab .canvas-column'),layoutCard=tabCanvas.querySelector('.layout-card');
for(const id of ['tab-format-card','group-styles-card']){tabCanvas.insertBefore($(id),layoutCard);$(id).hidden=false;}
$('sort-mount').append($('sort').closest('label'));
for (const [id,key] of Object.entries({'server-name':'serverName','sidebar-title':'title','name-prefix':'nameTagPrefix','name-suffix':'nameTagSuffix'})) $(id).oninput = () => commit(() => design[key] = $(id).value);
for (const [id,key] of Object.entries({'name-visibility':'visibility','name-collision':'collision','sort':'sort','refresh-ticks':'refreshTicks'})) $(id).onchange = () => commit(() => design[key] = id === 'refresh-ticks' ? Number($(id).value) : $(id).value);
$('sidebar-mode').onchange=()=>commit(()=>design.sidebarMode=$('sidebar-mode').value);
for(const [id,key,min,max]of[['sidebar-x','sidebarX',-512,512],['sidebar-y','sidebarY',-512,352]])$(id).oninput=()=>{const n=Number($(id).value);if(Number.isInteger(n)&&n>=min&&n<=max)commit(()=>design[key]=n);};
$('slot-kind').onchange = () => commit(() => { const cell = design.slots[selected.index]; cell.kind = $('slot-kind').value; if (cell.kind === 'PLAYER' && !cell.line.text) cell.line.text = '{tab_name}'; },true);
$('player-index').onchange = () => commit(() => design.slots[selected.index].playerIndex = Math.max(1,Math.min(80,Number($('player-index').value))),true);

function clearFormatting(source) { return source.replace(/<\/?(?:bold|italic|underlined|strikethrough|gradient(?::[^>]*)?|rainbow(?::[^>]*)?|color(?::[^>]*)?|#[0-9a-f]{6}|black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white)>/gi,'').replace(/[&§][0-9a-fklmnor]/gi,''); }
$('text-color').oninput = () => commit(() => { const l=selectedLine();setLineText(l,'<'+$('text-color').value+'>'+clearFormatting(lineText(l))+'</'+$('text-color').value+'>'); },true);
$('format-bold').onclick = () => commit(() => { const l=selectedLine();setLineText(l,/<bold>/i.test(lineText(l))?lineText(l).replace(/<\/?bold>/gi,''):'<bold>'+lineText(l)+'</bold>'); },true);
$('format-italic').onclick = () => commit(() => { const l=selectedLine();setLineText(l,/<italic>/i.test(lineText(l))?lineText(l).replace(/<\/?italic>/gi,''):'<italic>'+lineText(l)+'</italic>'); },true);
$('format-reset').onclick = () => commit(() => setLineText(selectedLine(),clearFormatting(lineText(selectedLine()))),true);
$('block-text').oninput = () => {commit(()=>setLineText(selectedLine(),$('block-text').value));$('animation-frames').value=selectedLine().frames.join('\n');};
$('animation').onchange = () => commit(() => { const l = selectedLine(),previousText=lineText(l);if(l.animation==='FRAMES')l.text=previousText;l.animation = $('animation').value; if (l.animation === 'FRAMES' && !l.frames.length) l.frames = [l.text || 'Frame 1',l.text || 'Frame 2']; },true);
for (const [id,key] of Object.entries({'animation-speed':'speed','animation-width':'width','animation-gap':'gap'})) $(id).onchange = () => commit(() => selectedLine()[key] = id === 'animation-speed' ? Math.round(Number($(id).value)*20) : Number($(id).value));
$('animation-frames').oninput = () => {commit(()=>{const l=selectedLine();l.frames=$('animation-frames').value.split('\n').slice(0,32);l.text=l.frames[0]||'';});$('block-text').value=lineText(selectedLine());frameHint(selectedLine());};
for (const key of ['player','prefix','suffix','display_name','tab_name','group','weight','server','online','max','world','ping','time','date','health','max_health','food','experience','level','x','y','z','layout_overflow','progress','remaining']) {
 const button = document.createElement('button'); button.dataset.variable=key;button.textContent = '{'+key+'}'; button.onclick = () => {
  if (!selectedLine()) return; const input = $('block-text'),start = input.selectionStart,end = input.selectionEnd;
  commit(() => setLineText(selectedLine(),input.value.slice(0,start)+'{'+key+'}'+input.value.slice(end)));
  input.value = lineText(selectedLine()); input.focus(); input.setSelectionRange(start+key.length+2,start+key.length+2);
 }; if(['progress','remaining'].includes(key))button.dataset.bossVariable='true';$('variable-chips').append(button);
}
$('move-up').onclick = () => move(-1); $('move-down').onclick = () => move(1); $('move-left').onclick = () => move(-20); $('move-right').onclick = () => move(20);
$('delete-block').onclick = () => commit(() => {
 if (selected.section === 'slots') design.slots[selected.index] = slot();
 else { design[selected.section].splice(selected.index,1); selected = null; }
},true);
function travel(from,to) { if (!from.length) return; to.push(copy(design)); design = from.pop(); generation++; selected = null; repaintForm();autoSaver?.change(); }
$('undo').onclick = () => travel(undo,redo); $('redo').onclick = () => travel(redo,undo);
document.addEventListener('keydown',e => {
 if (session?.canDesign && (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault();autoSaver?.flush().catch(error=>notice(error.message,'error'));return; }
 if (['INPUT','TEXTAREA','SELECT'].includes(e.target.tagName)) return;
 if (session?.canDesign && (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); travel(e.shiftKey ? redo : undo,e.shiftKey ? undo : redo); }
 if (selected && e.target.closest('#tab-grid,.line-list,.boss-list,.group-list')) {
  const delta = {ArrowUp:-1,ArrowDown:1,ArrowLeft:-20,ArrowRight:20}[e.key];
  if (delta !== undefined) { e.preventDefault(); move(delta); const el = document.querySelector('[data-section="'+selected.section+'"][data-index="'+selected.index+'"]'); el?.focus(); }
 }
});
function applyRemote(next){
 const active=document.activeElement,focus=active?{id:active.id,section:active.dataset.lineInput,index:Number(active.dataset.lineIndex),start:active.selectionStart,end:active.selectionEnd}:null;
 const item=selected?design[selected.section]?.[selected.index]:null,id=item?.id||item?.group;
 next=normalizeStyles(normalizeBosses(next));const prior=copy(design);
 // Undo belongs to local edits. Incoming independent fields also update historical snapshots.
 undo=undo.map(entry=>window.TabPrefixLiveSave.merge(prior,entry,next));redo=redo.map(entry=>window.TabPrefixLiveSave.merge(prior,entry,next));
 window.TabPrefixLiveSave.reconcile(design,next);
 if(selected&&id){const n=design[selected.section]?.findIndex(v=>(v.id||v.group)===id);selected=n>=0?{...selected,index:n}:null;}
 generation++;repaintForm();
 if(focus){let input=focus.id?$(focus.id):null;if(!input&&focus.section){const n=selected?.section===focus.section?selected.index:focus.index;input=document.querySelector('[data-line-input="'+focus.section+'"][data-line-index="'+n+'"]');}if(input){input.focus({preventScroll:true});if(focus.start!==undefined&&focus.start!==null&&input.setSelectionRange&&['text','search','url','password'].includes(input.type||'text'))input.setSelectionRange(focus.start,focus.end);}}
}
function journal(scope='display'){
 return new window.TabPrefixLiveSave.Journal({workspace:session.workspaceId,owner:session.owner,scope,
  legacy:scope==='display'?['tabprefix-draft:'+session.owner+':']:[]});
}
function workspaceChanged(next){
 if(next.groups)updateGroups(next.groups);
 const event=new Event('workspace-state');event.state=next;window.dispatchEvent(event);
}
async function syncEditors(){
 if(expired||document.hidden)return;
 try{await stateSync.read();await Promise.allSettled([autoSaver?.refresh(),prefSaver?.refresh()]);}catch(error){if(error.status===401||error.status===403){expired=true;for(const saver of[autoSaver,prefSaver])saver?.emit('expired',error);}}
}
function startAutoSave(){const cache=journal();
 const banner=document.createElement('div');banner.id='sync-conflict';banner.className='notice sync-conflict';banner.hidden=true;const message=document.createElement('span');banner.append(message);
 for(const[local,a,b]of [[true,'Оставить мою версию','Keep my version'],[false,'Принять серверную','Use server version']]){const button=document.createElement('button');button.textContent=language==='ru'?a:b;button.onclick=()=>{banner.hidden=true;autoSaver.resolve(local);};banner.append(button);}$('notice').after(banner);
 autoSaver=new window.TabPrefixLiveSave.LiveSave({get:()=>design,apply:applyRemote,base:design,revision,read:async known=>{const data=(await stateSync.read(known===-1)).display;return data&&data.revision!==known?{document:data.design,revision:data.revision}:null;},write:async(document,version)=>{const data=await api('display/design',{design:document,revision:version,compact:true});stateSync.acknowledge('display',{design:data.design||document,revision:data.revision},data.revision);return {document:data.design,revision:data.revision};},journal:value=>cache.write(value),status:(phase,error)=>{revision=autoSaver.revision;if(!autoSaver.dirty)persisted=JSON.stringify(design);$('revision').textContent=d('revision')+revision;if(phase==='expired')expired=true;updateDirty();if(error&&['invalid','expired'].includes(phase))notice(error.message,'error');if(phase==='saved')window.dispatchEvent(new Event('studio-saved'));},conflict:paths=>{banner.hidden=false;message.textContent=(language==='ru'?'Одно поле изменено в двух редакторах: ':'Same field edited in two editors: ')+paths.join(', ');}});
 autoSaver.recover(cache.read());

 $('prefix-link').onclick=async e=>{e.preventDefault();try{await autoSaver.flush();location.href=$('prefix-link').href;}catch(error){notice(error.message,'error');}};
}
$('apply').onclick=async()=>{try{await autoSaver?.flush();if(autoSaver?.phase==='saved')notice(language==='ru'?'Сохранено и применено.':'Saved and applied.','success');}catch(error){notice(error.message,'error');}};
$('reload').onclick=()=>syncEditors();
$('export').onclick = () => {
 const pretty=JSON.stringify(design,null,2)+'\n',compact=JSON.stringify(design)+'\n';
 const blob = new Blob([new Blob([pretty]).size<=1048576?pretty:compact],{type:'application/json'}),url = URL.createObjectURL(blob),a = document.createElement('a');
 a.href = url; a.download = 'tabprefix-display.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url),1000);
};
$('import').onclick = () => $('import-file').click();
$('import-file').onchange = async e => {
 const file = e.target.files[0]; if (!file) return;
 try {
  if (file.size > 1048576) throw new Error(d('invalid'));
  const data = JSON.parse(await file.text());
  if (data.schema !== 1 || !Array.isArray(data.slots) || data.slots.length !== 80 || !['header','footer','sidebar'].every(k => Array.isArray(data[k]))) throw new Error(d('invalid'));
  await api('display/preview',{design:data,tick:0});
  record(); design = normalizeStyles(normalizeBosses(data)); selected = null; generation++; repaintForm(); autoSaver?.change();notice(language==='ru'?'Импортировано. Сохраняем автоматически.':'Imported. Saving automatically.','success');
 } catch (error) { notice(error.message,'error'); } finally { e.target.value = ''; }
};
$('language').onclick = () => { language = language === 'en' ? 'ru' : 'en'; translate(); inspector(); renderPreview();window.dispatchEvent(new Event('studio-change')); };
function pausePreview() { paused = !paused; $('pause').textContent = $('boss-pause').textContent = paused ? d('resumed') : t('pause'); schedulePreview(); }
$('pause').onclick = $('boss-pause').onclick = pausePreview;
window.addEventListener('beforeunload',e => { if (autoSaver?.dirty || design && !autoSaver && JSON.stringify(design) !== persisted) { e.preventDefault(); e.returnValue = ''; } });
async function initialize() {
 document.querySelector('main').inert=true;document.querySelectorAll('.nav').forEach(el=>el.disabled=true);
 translate();
 if (!/^[A-Za-z0-9_-]{43}$/.test(token)) { expired = true; notice(d('missing'),'error'); $('design-actions').hidden = true; document.querySelectorAll('main button,.nav').forEach(el => el.disabled = true); return; }
 try {
  stateSync=new window.TabPrefixLiveSave.StateSync({fetch:api,view:'studio',onState:workspaceChanged});const initial=await stateSync.read(true);session=initial.session; language = session.language === 'en' ? 'en' : 'ru'; $('owner').textContent = session.player;
  document.querySelectorAll('.nav').forEach(el => el.hidden = el.dataset.panel === 'settings' ? !session.canSettings : !session.canDesign);
  if (session.canPrefixes) { $('prefix-link').hidden = false; $('prefix-link').href = 'editor#'+token; }
  if (session.canDesign) { const data=initial.display;design=normalizeStyles(normalizeBosses(copy(data.design)));revision=data.revision;persisted=JSON.stringify(design);updateGroups(initial.groups||[]);repaintForm();startAutoSave(); }
  if (session.canSettings) { personal=copy(initial.preferences);renderPersonal();prefJournal=journal('preferences');prefSaver=new window.TabPrefixLiveSave.LiveSave({get:()=>personal,apply:next=>{personal=next;renderPersonal();},base:personal,revision:initial.versions.preferences,journal:value=>prefJournal.write(value),read:async known=>{const snapshot=await stateSync.read(known===-1);return snapshot.versions.preferences!==known?{document:snapshot.preferences,revision:snapshot.versions.preferences}:null;},write:async(value,version)=>{const data=await api('workspace/preferences',{document:value,revision:version});stateSync.acknowledge('preferences',data.document,data.revision);return data;},status:(phase,error)=>{const labels={ru:{saved:'Сохранено автоматически',waiting:'Изменения в очереди',saving:'Сохраняется…',offline:'Нет связи · повторяем',invalid:'Проверьте поля',conflict:'Совпали правки',expired:'Сессия истекла'},en:{saved:'Saved automatically',waiting:'Changes queued',saving:'Saving…',offline:'Offline · retrying',invalid:'Check fields',conflict:'Conflicting edits',expired:'Session expired'}};$('save-settings').textContent=labels[language][phase];if(phase==='expired'){expired=true;$('save-settings').disabled=true;updateDirty();}if(error&&['invalid','expired'].includes(phase))notice(error.message,'error');}});}
  if(prefSaver)prefSaver.recover(prefJournal.read());
  if (!session.canDesign && !session.canSettings) {const error=new Error(d('missing'));error.status=403;throw error;}
  document.querySelector('main').inert=false;document.querySelectorAll('.nav').forEach(el=>el.disabled=false);show(session.canDesign ? 'overview' : 'settings');
  if (session.canDesign) {setInterval(()=>{if(!paused)fetchPreview();},1500);setInterval(animatePreview,100);}
  initAttempts=0;clearInterval(syncTimer);syncTimer=setInterval(syncEditors,1000);
  window.addEventListener('focus',syncEditors);window.addEventListener('pageshow',syncEditors);document.addEventListener('visibilitychange',syncEditors);
  window.TabPrefixLiveSave.bindLifecycle(()=>[autoSaver,prefSaver]);

 } catch (error) { notice(error.message,'error'); $('design-actions').hidden = true;if(![400,401,403].includes(error.status))setTimeout(initialize,Math.min(6000,1000*++initAttempts)); }
}
window.TabPrefixStudio={get state(){return {savePhase:autoSaver?.phase,previewCurrent:previewGeneration===generation,design,session,language,panel,preview,expired,busy,selected,knownGroups,workspace:stateSync?.data,unsaved:!!design&&(autoSaver?autoSaver.dirty:JSON.stringify(design)!==persisted)};},commit,show,line,slot,select,deselect,repaintForm,api,notice,component,loadGroups,groupStyle,preview:schedulePreview,flush:()=>autoSaver?.flush()||Promise.resolve(),sync:()=>syncEditors(),acceptNavigation:()=>{persisted=JSON.stringify(design);}};
initialize();
})();
