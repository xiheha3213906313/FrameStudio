'use strict';
const $=s=>document.querySelector(s);
const e=v=>String(v==null?'':v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const paths={
 create:'M4 16.5V20h3.5L20 7.5l-3.5-3.5z M14.8 5.8l3.5 3.5',
 brush:'M9 14l8.3-9.3a1.8 1.8 0 0 1 2.6 2.5L10 16 M9 14c-2.2-.5-3.7.9-4 2.6-.2 1.2-.5 2.1-1.5 2.9 3.3.9 6.6-.4 6.9-3.1.1-1.1-.5-2.2-1.4-2.4z',
 erase:'m7 12 6 6m-2.5 1H8l-4.4-4.4a1.5 1.5 0 0 1 0-2.1l8.1-8.1a1.5 1.5 0 0 1 2.1 0l6.1 6.1a1.5 1.5 0 0 1 0 2.1l-6.4 6.4H21',
 move:'M12 3v18M3 12h18M9 6l3-3 3 3m-6 12 3 3 3-3M6 9l-3 3 3 3m12-6 3 3-3 3',
 undo:'M4 11h11a4 4 0 0 1 0 8h-2M9 6l-5 5 5 5',
 trash:'M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 10v6m4-6v6',
 gallery:'M4 5h16v14H4z M4 16l5-5 4 4 3-3 4 4 M15 9h.01',
 tasks:'M7 5h13M7 12h13M7 19h13M3 5h.02M3 12h.02M3 19h.02',
 settings:'M4 7h7m5 0h4M4 17h2m5 0h9 M14 4v6m-5 4v6',
 arrow:'M5 12h14m-6-6 6 6-6 6',upRight:'M6 18L18 6M7 6h11v11',
 down:'M6 9l6 6 6-6',next:'M9 5l7 7-7 7',spark:'M12 3l1.9 5.6L19 11l-5.1 2.4L12 19l-2-5.6L5 11l5-2.4z M19 15v5m-2.5-2.5h5',
 book:'M12 7C9 5 6 5 3 5v13c3 0 6 0 9 2 3-2 6-2 9-2V5c-3 0-6 0-9 2zm0 0v13',
 close:'M6 6l12 12M18 6L6 18',plus:'M12 5v14M5 12h14',check:'M4 12l5 5L20 6',
 sync:'M20 8a8 8 0 0 0-14-2L3 9m0-5v5h5m-4 7a8 8 0 0 0 14 2l3-3m0 5v-5h-5',
 save:'M12 3v13m-5-5 5 5 5-5M4 17v4h16v-4',
 enlarge:'M14 4h6v6M20 4l-6 6M10 20H4v-6m0 6 6-6',compare:'M3 5h18v14H3zM12 3v18m-6-8 2-2 2 2m4 1 2-3 2 3',
 filter:'M4 6h16M7 12h10m-7 6h4',
 layers:'M3 8l9-5 9 5-9 5zm0 5 9 5 9-5m-18 5 9 5 9-5',clock:'M12 4a8 8 0 1 0 .01 0m0 4v5l3 2',
 wallet:'M3 6h17v13H3zM15 11h5v4h-5z',key:'M15 4a5 5 0 1 1-3 9l-7 7H2v-3l7-7a5 5 0 0 1 6-6z',
 dice:'M4 4h16v16H4zM8 8h.1m3.9 4h.1m3.9 4h.1',frame:'M3 9V4h5m8 0h5v5m0 6v5h-5m-8 0H3v-5',
};
function icon(name){return '<svg class="ic" aria-hidden="true" viewBox="0 0 24 24"><path d="'+paths[name]+'"/></svg>';}
function button(action,label,cls,value,extra){return '<button type="button" class="'+(cls||'')+'" data-act="'+action+'" data-val="'+e(value)+'" '+(extra||'')+'>'+label+'</button>';}
function row(a,title,sub,v,sel){return button(a,'<span><b>'+e(title)+'</b><small class="sub">'+e(sub)+'</small></span>'+icon(sel?'check':'next'),'row'+(sel?' selected':''),v);}
function group(label,content){return '<div class="group"><span class="fieldtitle">'+e(label)+'</span>'+content+'</div>';}
const samples=[
 {title:'赤岩旷野',style:'西部风光 · 暖调胶片',img:'dunes',prompt:'旷野中的红砂岩孤峰，横向的地平线穿过画面下方，午后阳光让岩壁呈现层次丰富的赭红色。以 35mm 胶片拍摄，天空保留清透的蓝，细微颗粒，辽阔而安静。'},
 {title:'山谷来信',style:'高山风光 · 自然光',img:'alpine',prompt:'从松林之间远望高山峡谷，山脊的积雪在云影下若隐若现。前景以深绿色针叶林作框景，远处冰蓝色山峰渐淡，柔和自然光，旅行摄影。'},
 {title:'潮汐之间',style:'海面特写 · 冷色调',img:'coast',prompt:'镜头与海面齐平，捕捉一道舒缓起伏的青蓝色浪脊，远处天空由暖白渐变为淡蓝。自然散射光，浅景深，克制的留白和干净的色彩。'}
];
const navItems=[['create','创作台'],['gallery','作品集'],['tasks','任务'],['settings','设置']];
const stateNames={queued:'排队中',sending:'生成中',fetching:'保存中',done:'已完成',partial:'部分成功',failed:'生成失败',not_sent:'请求未提交',uncertain:'响应中断',recovered:'已导入原图',download_error:'待补下载',mac_queued:'Mac 排队中',mac_waiting:'等待连接 Mac',mac_remote:'Mac 推理中',mac_fetching:'保存中',mac_blocked:'需恢复 Mac 连接',cancelled:'已取消'};
const active=s=>['queued','sending','fetching','mac_queued','mac_waiting','mac_remote','mac_fetching'].includes(s);
const initial={source:'ark',prompt:'',promptRefs:[],refs:[],model:PROFILES[0].id,level:'2K',ratio:RATIOS[0],ratioWidth:1,ratioHeight:1,count:1,watermark:false,format:'auto',transparent:false,layers:false,layerMax:10};
let draft=Object.assign({},initial),config={models:[],custom:[],hasKey:false,synced:0},jobs=[],page='create',modal='',context='',previousPhotoTask='',sampleIndex=0,taskFilter='all',syncing=false,submitting=false,lastSyncError='',toastTimer,draftTimer,seq=0,refreshing=false;
const calls=new Map();
let jobRevision=-1;
let previousRefTask='';
const badgeHolds={tasks:null,gallery:null};
let galleryModel='',taskModel='',selecting=false;
const pickedTasks=new Set(),expandedStacks=new Set();
let autoSyncAt=0;
let referencesBusy=true,referenceRetry,modelChoice=0;
function api(action,data){
 return new Promise((resolve,reject)=>{
  if(!window.StudioNative){reject(new Error('请在映序安卓应用中打开'));return;}
  const id=String(++seq);calls.set(id,{resolve,reject});window.StudioNative.call(id,action,JSON.stringify(data||{}));
 });
}
window.nativeReply=function(id,ok,text){
 const entry=calls.get(id);if(!entry)return;calls.delete(id);
 if(!ok){entry.reject(new Error(text));return;}
 try{entry.resolve(JSON.parse(text));}catch(_){entry.resolve(text);}
};
function toast(text){const el=$('#toast');el.textContent=text;el.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.classList.remove('show'),3300);}
window.toast=toast;
function saveDraft(){draft.defaultsV2=true;clearTimeout(draftTimer);draftTimer=setTimeout(()=>api('draft',draft).catch(x=>toast(x.message)),120);}
function options(){
 const base=config.synced?config.models:PROFILES;
 return config.custom.concat(base.filter(m=>!config.custom.some(c=>c.id===m.id)));
}
function profile(){return modelProfile(draft.model,config.custom,config.models);}
function nameFor(id){
 if(id?.startsWith('mac:'))return jobs.find(j=>j.body.model===id)?.body.name||(id===draft.model&&(macModel()?.name||draft.name))||'我的 Mac';
 const custom=config.custom.find(x=>x.id===id),published=modelProfile(id,[],config.models);
 return custom?custom.name:published!==GENERIC?published.name:id||'选择模型';
}
function allowedRefs(){
 if(isMac())return macOp()==='inpaint'?14:capability(macModel(),'img2img')?.maxInputs||0;
 const p=profile();return draft.layers?1:p.refs;
}
function normalize(){
 if(isMac()){
  draft.count=Math.max(1,Math.min(15,Number(draft.count)||1));
  if(macOp()==='txt2img'&&draft.macRatio==='原图'){draft.macRatio='';draft.macEdge='';}
  return;
 }
 const p=profile();
 if(!p.levels.includes(draft.level))draft.level=p.levels.includes('2K')?'2K':p.levels[0]||'';
 if(!RATIOS.includes(draft.ratio))draft.ratio=RATIOS[0];
 if(!p.layers||!draft.refs.length)draft.layers=false;
 if(!p.format)draft.format='auto';
 if(!p.rgba || draft.layers || (p.family!=='55' && (!draft.refs.length || draft.refs.length!==1)))draft.transparent=false;
 if(draft.transparent)draft.format='png';
 if(draft.layers)draft.count=1;
 draft.count=Math.max(1,Math.min(Number(draft.count)||1,p.batch?15-draft.refs.length:15));
 draft.layerMax=Math.max(2,Math.min(Number(draft.layerMax)||10,p.layers||100));
}
function body(){
 if(isMac())return macBody();
 const p=profile(),size=requestSize(p,draft.level,draft.ratio,draft.ratioWidth,draft.ratioHeight);
 return {source:'ark',...promptSnapshot(draft.prompt,draft.promptRefs),model:draft.model,name:nameFor(draft.model),size:size,level:draft.level,ratio:draft.ratio,ratioWidth:draft.ratioWidth,ratioHeight:draft.ratioHeight,count:draft.count,stream:!!p.batch,separate:!p.batch&&!draft.layers,
 refs:draft.refs.slice(),watermark:draft.watermark,format:draft.format,transparent:draft.transparent,layers:draft.layers,
 layerMax:draft.layers?(p.family==='55'?draft.layerMax:p.layers):0,rate:{family:p.family,low:p.low,high:p.high,input:p.input,free:p.free}};
}
function date(t){return new Date(t).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false});}
function activeCount(){return jobs.filter(job=>active(job.state)).length;}
function unreadCount(){return photos().filter(item=>!item.p.seen).length;}
function nav(){
 const bar=$('#nav');
 if(!bar.childElementCount)bar.innerHTML=navItems.map(([key,label])=>{
  const badge=['tasks','gallery'].includes(key)?'<span class="nav-count" aria-hidden="true"></span>':'';
  return button('nav','<span class="navicon">'+icon(key)+badge+'</span>'+label,'nav-item',key);
 }).join('');
 const counts={tasks:badgeHolds.tasks===null?activeCount():badgeHolds.tasks,gallery:badgeHolds.gallery===null?unreadCount():badgeHolds.gallery};
 for(const [key,label] of navItems) {
  const item=bar.querySelector('[data-val="'+key+'"]'),activePage=page===key,count=counts[key]||0;
  item.classList.toggle('active',activePage);
  if(activePage)item.setAttribute('aria-current','page');else item.removeAttribute('aria-current');
  item.setAttribute('aria-label',label+(count?' '+count+(key==='gallery'?' 张未查看':' 个进行中任务'):''));
  const badge=item.querySelector('.nav-count');
  if(!badge)continue;
  if(count) {
   badge.textContent=count>99?'99+':count;
   badge.classList.toggle('single',count<10);
  }
  badge.classList.toggle('visible',count>0);
 }
}
function centerOf(element) {
 const rect=element.getBoundingClientRect();
 return {x:rect.left+rect.width/2,y:rect.top+rect.height/2};
}
function badgeCenter(key){
 return centerOf($('#nav [data-val="'+key+'"] .nav-count'));
}
function flyDots(origin,destination,count,arrived=()=>{}) {
 if(!origin||document.hidden||!Element.prototype.animate||matchMedia('(prefers-reduced-motion: reduce)').matches){
  for(let i=0;i<count;i++)arrived(i);
  return Promise.resolve();
 }
 const end=badgeCenter(destination);
 return Promise.all(Array.from({length:count},(_,index)=>new Promise(resolve=>{
  const dot=document.createElement('span');
  dot.className='nav-flight';
  dot.setAttribute('aria-hidden','true');
  dot.style.left=origin.x+'px';dot.style.top=origin.y+'px';
  document.body.appendChild(dot);
  const dx=end.x-origin.x,dy=end.y-origin.y;
  const arc=Math.min(54,25+Math.abs(dx)*.11);
  const animation=dot.animate([
   {transform:'translate(-50%,-50%) scale(.5)',opacity:0},
   {transform:'translate(calc(-50% + '+(dx*.45)+'px), calc(-50% + '+(dy*.42-arc)+'px)) scale(1)',opacity:1,offset:.47},
   {transform:'translate(calc(-50% + '+dx+'px), calc(-50% + '+dy+'px)) scale(.45)',opacity:.92}
  ],{duration:440,delay:index*40,fill:'both',easing:'cubic-bezier(.2,.6,.35,1)'});
  function settle(){dot.remove();arrived(index);resolve();}
  animation.onfinish=settle;
  animation.oncancel=settle;
 })));
}
function title(t,right){return '<div class="title"><h1>'+e(t)+'</h1>'+(right||'')+'</div>';}
function hero(){
 const s=samples[sampleIndex];
 return '<div class="hero"><img src="art/'+s.img+'.jpg" alt="'+e(s.title)+'"><div class="hero-top"><span class="feature-label">'+icon('book')+'提示词灵感</span><div class="dots">'+samples.map((x,i)=>button('slide','',i===sampleIndex?'sel':'',i,'aria-label="查看'+e(x.title)+'"')).join('')+'</div></div><div class="hero-bottom"><div><h2>'+e(s.title)+'</h2><p>'+e(s.style)+'</p></div>'+button('sample',icon('upRight'),'circle',sampleIndex,'aria-label="查看并使用提示词"')+'</div></div>';
}
function refstrip(){
 return '<div class="refstrip">'+draft.refs.map((r,i)=>{
  const base=isMac()&&macOp()==='inpaint'&&r===draft.macBase;
  return '<div class="refitem'+(base?' repair-base':'')+'">'+button('refPhoto',
   '<img src="refs/'+e(r)+'.thumb" alt="参考图 '+(i+1)+(base?'，局部编辑底图':'')+'">'+
   (base?'<span class="repair-label">修复底图</span>':''),'ref-preview',r)+button('unref','×','ref-remove',i,'aria-label="移除参考图"')+'</div>';
 }).join('')+(allowedRefs()>0?button('pick',icon('plus')+'<span class="ref-label">'+
  (draft.refs.length>=allowedRefs()?'选图':'参考图')+'</span>','refslot','',referencesBusy?'disabled':''):'')+'</div>';
}
function createPage(){
 normalize();const b=body(),local=isMac(),total=local?null:cost(b,null);
 return title('开始创作',button('samples',icon('book')+' 灵感库','textbtn small'))+
 refstrip()+'<div class="workarea"><div class="promptbox"><div class="spread"><label for="prompt">画面描述</label><span class="counter" id="chars">'+draft.prompt.length+' 字</span></div><textarea id="prompt" placeholder="写下主体、场景、光线，或你想修改的细节…">'+e(draft.prompt)+'</textarea><p id="referenceIssue" class="sub reference-issue" hidden></p></div>'+
 '<div class="right"><div class="gridparams">'+button('models','<span><small>生成模型</small><b>'+e(nameFor(draft.model))+'</b></span>'+icon('down'),'param')+
 button('params','<span><small>画布设置</small><b>'+e(local?(macOp()==='inpaint'?'局部编辑':b.size||'自动尺寸'):b.layers?'图层拆分':b.size?b.ratio+' · '+b.level:'模型默认')+'</b></span>'+icon('down'),'param')+'</div>'+
 button('generate','<span class="inline">'+icon('spark')+'生成图片</span><span class="end">'+(local?localGenerationStatus():total?'预计 '+money(total.total):'价格待核对')+'</span>','primary generate','',generationBlocked()?'disabled':'')+
 '<div class="more">'+button('params',icon('settings')+(b.layers?'图层上限 '+b.layerMax:'参数 · '+(b.count>1?'最多 ':'')+b.count+' 张'))+(!local?button('cost',icon('wallet')+'费用明细'):'')+'</div></div></div>';
}
function availablePhoto(image){return image.ready&&!image.deleted;}
function photos(){return jobs.reduce((all,j)=>all.concat((j.result.images||[]).filter(availablePhoto).map(p=>({p:p,j:j}))),[]);}
function empty(ic,t,description){return '<div class="empty">'+icon(ic)+'<h3>'+e(t)+'</h3><p class="sub">'+e(description)+'</p>'+button('nav','去创作 '+icon('arrow'),'secondary','create')+'</div>';}
function filterModelName(job){return job.body.name?.trim()||nameFor(job.body.model);}
function filterModelKey(job){
 const name=filterModelName(job).replace(/\s+/g,' ').toLowerCase();
 return (isMac(job.body)?'mac:':'ark:')+name;
}
function filterModels(items){
 const models=new Map();
 for(const job of items){
  const key=filterModelKey(job);
  if(!models.has(key))models.set(key,filterModelName(job));
 }
 return models;
}
function modelFilters(items,selected,action) {
 const models=filterModels(items);
 if(!models.size)return '';
 return '<div class="filter-row">'+[['',action==='galleryModel'?'全部':'全部模型'],...models].map(([id,name])=>button(action,e(name),'choice'+(id===selected?' selected':''),id)).join('')+'</div>';
}
function galleryPhotos(all=photos()) {
 return all.filter(x=>!galleryModel||filterModelKey(x.j)===galleryModel);
}
function galleryPage(){
 const all=photos(),list=galleryPhotos(all);
 return title('作品集','<span class="micro">'+list.length+' 张原图</span>')+modelFilters(all.map(x=>x.j),galleryModel,'galleryModel')+
 (list.length?'<div class="gallery">'+list.map(x=>button('photo',(!x.p.seen?'<span class="unseen-dot" aria-label="未查看"></span>':'')+
 '<img loading="lazy" src="images/'+e(x.p.file)+'.thumb" alt="作品预览"><b>'+e(x.j.body.prompt||'图层拆分')+'</b>','photo'+(x.p.file===highlightedFile?' return-highlight':''),x.p.file)).join('')+'</div>':
 empty('gallery',all.length?'这个模型暂无作品':'作品集还是空的','生成成功的图片会自动保存在这里。'));
}
const failedStates=['failed','not_sent','uncertain','download_error','mac_blocked'];
const successfulStates=['done','partial','recovered'];
function taskThumb(job) {
 const image=(job.result.images||[]).find(availablePhoto);
 const ref=(job.body.macInputs||job.body.refs||[])[0];
 if(ref&&failedStates.includes(job.state))return '<img src="refs/'+e(ref)+'.thumb" alt="首张输入参考图">';
 return image?'<img src="images/'+e(image.file)+'.thumb" alt="生成图片">':icon('frame');
}
function statusLabel(status) {
 const chars=Array.from(status||'');
 const middle=Math.ceil(chars.length/2);
 if(chars.length<=5)return e(status);
 return '<span>'+e(chars.slice(0,middle).join(''))+'</span><span>'+e(chars.slice(middle).join(''))+'</span>';
}
function taskCard(j){
 const chosen=pickedTasks.has(j.id);
 const phase=j.result.phase;
 const status=isMac(j.body)?macTaskState(j):j.state==='sending'?(phase==='connecting'?'连接中':phase==='uploading'?'上传中':'等待方舟出图'):stateNames[j.state];
 const label='<span class="badge '+(failedStates.includes(j.state)?'warn':'')+'">'+statusLabel(status)+'</span>';
 return button('task','<span class="tasklead"><span class="tile">'+taskThumb(j)+'</span>'+label+'</span>'+
 '<span class="tasktext"><b>'+e(j.body.prompt||(isMac(j.body)?macOperations[j.body.operation]:'图层拆分'))+'</b><small class="sub task-model">'+e(j.body.name)+'</small>'+
 '<time class="sub" datetime="'+new Date(j.created).toISOString()+'">'+e(date(j.created))+'</time></span>'+
 icon(selecting?(chosen?'check':'frame'):'next'),'taskcard'+(chosen?' selected':''),j.id,selecting?(active(j.state)?'disabled':'aria-pressed="'+chosen+'"'):'');
}
function matchesTaskFilter(job) {
 if(taskModel&&filterModelKey(job)!==taskModel)return false;
 if(taskFilter==='successful')return successfulStates.includes(job.state);
 if(taskFilter==='failed')return failedStates.includes(job.state);
 return true;
}
function taskStacks() {
 const stacks=[];
 let previousKey=null,group=null;
 for(const job of jobs) {
  const key=!isMac(job.body)&&failedStates.includes(job.state)?JSON.stringify([job.body.prompt||'',job.refKeys||job.body.refs||[]]):null;
  if(key===null || key!==previousKey){
   group=[];
   stacks.push(group);
  }
  group.push(job);
  previousKey=key;
 }
 return stacks.map(stack=>stack.filter(matchesTaskFilter)).filter(stack=>stack.length>0);
}
function stackCard(stack) {
 if(stack.length===1)return taskCard(stack[0]);
 const first=stack[0],shown=expandedStacks.has(first.id);
 const allSelected=stack.every(j=>pickedTasks.has(j.id));
 const someSelected=stack.some(j=>pickedTasks.has(j.id));
 const label=selecting?'aria-pressed="'+(allSelected?'true':someSelected?'mixed':'false')+'"':'aria-expanded="'+shown+'"';
 const ref=first.body.refs&&first.body.refs[0];
 const thumb=ref?'<img src="refs/'+e(ref)+'.thumb" alt="">':icon('layers');
 const toggle=button('taskStack','<span class="tasklead"><span class="tile">'+thumb+'</span><span class="badge warn">'+
  stack.length+'条失败</span></span><span class="tasktext"><b>'+e(first.body.prompt||'图层拆分')+'</b>'+
  '<time class="sub" datetime="'+new Date(first.created).toISOString()+'">'+e(date(first.created))+'</time></span>'+
  icon(selecting?(allSelected?'check':'frame'):(shown?'down':'next')),'taskcard stack-toggle'+(someSelected?' selected':''),first.id,label);
 return '<div class="task-stack'+(shown?' expanded':'')+'">'+toggle+
  (shown?'<div class="stack-entries">'+stack.map(taskCard).join('')+'</div>':'')+'</div>';
}
function tasksPage(){
 const stacks=taskStacks();
 const actions=selecting?'<div class="task-actions">'+button('pickFailed','全选失败','secondary')+
 button('deleteBatchAsk','删除 '+pickedTasks.size+' 项','dangerbtn','',pickedTasks.size?'':'disabled')+button('multi','取消','textbtn')+'</div>':'';
 const label=taskModel?'筛选模型，已选 '+filterModels(jobs).get(taskModel):'筛选模型';
 const filterIcon=button('taskModelFilter',icon('filter'),'circle model-filter'+(taskModel?' selected':''),'','aria-label="'+e(label)+'"');
 return title('生成任务',filterIcon)+
 '<div class="seg">'+[['all','全部'],['successful','成功'],['failed','失败']].map(x=>button('filter',x[1],taskFilter===x[0]?'active':'',x[0])).join('')+'</div>'+actions+
 '<div class="list">'+(stacks.length?stacks.map(stackCard).join(''):empty('tasks',jobs.length?'当前筛选下没有任务':'还没有生成任务','任务状态、原图和费用明细会在这里更新。'))+'</div>';
}
function settingsPage(){return title('设置')+'<div class="section-title"><h2>局域网连接</h2></div>'+
 row('macConnect','连接 MFlux Studio',config.mac?.endpoint||'配对后使用 Mac 上已安装的模型')+
 '<div class="section-title"><h2>方舟连接</h2></div>'+row('key','API Key',config.hasKey?'密钥已加密保存在本机':'添加个人 API Key，连接北京地域')+
 row('models','图片模型',config.synced?'上次同步 '+date(config.synced)+' · '+config.models.length+' 个账号可见模型 · 每日自动同步':'尚未同步 · 当前使用内置目录')+
 row('custom','自定义推理接入点','添加 Endpoint ID 或新的 Model ID')+'<div class="section-title"><h2>使用与计费</h2></div>'+
 row('ledger','本机费用统计','按任务提交时的单价快照汇总')+row('doc','官方模型价格','查看火山方舟最新公布的计费规则','docs/model-pricing')+
 row('doc','错误码手册','按错误码排查权限、配额、请求参数等问题','docs/error-codes')+row('doc','图片生成指南','查看模型能力、参考图限制和调用示例','docs/image-generation-tutorial')+
 '<div class="panel"><h3>映序 · Frame Studio</h3><p class="sub">版本 1.8.6 · 火山方舟北京地域，标准按量 API。图片和任务记录保存在此设备。</p></div>';}
function animateIn(element) {
 if(!element.animate || matchMedia('(prefers-reduced-motion: reduce)').matches)return;
 const animation=element.animate([{opacity:0,transform:'translateY(9px)'},{opacity:1,transform:'translateY(0)'}],
  {duration:190,easing:'cubic-bezier(.22,.61,.36,1)'});
 animation.onfinish=scheduleSeen;
}
function draw(animate=false){
 endReferencePick();
 nav();
 const view=$('#app');
 view.innerHTML=({create:createPage,gallery:galleryPage,tasks:tasksPage,settings:settingsPage}[page])();
 if(page==='create')showReferenceIssue();
 if(animate)animateIn(view);
 scheduleSeen();
}
function navigate(p){
 if(!navItems.some(n=>n[0]===p))return;
 close();
 if(p!=='tasks'){selecting=false;pickedTasks.clear();}
 const changed=page!==p;
 page=p;draw(changed);window.scrollTo(0,0);
 if(p==='tasks'||p==='gallery')refresh();
}
window.navigate=navigate;
let overlayTimer;
function show(type,val){
 endReferencePick();
 const overlay=$('#overlay');
 const wasOpen=overlay.classList.contains('is-visible');
 clearTimeout(overlayTimer);
 modal=type;context=val||'';
 overlay.hidden=false;
 renderModal(false);
 document.body.style.overflow='hidden';
 if(!wasOpen) {
  void overlay.offsetWidth;
  overlay.classList.add('is-visible');
 }
 scheduleSeen();
}
function close(){
 const overlay=$('#overlay');
 if(!modal)return;
 const returnFile=modal==='photo'&&page==='gallery'&&!previousPhotoTask?context:'';
 if(modal==='macConnect'&&!config.mac?.endpoint)clearMacPairing();
 window.blurInputs();
 maskCanvas?.observer.disconnect();maskCanvas=null;
 modal='';context='';
 overlay.classList.remove('is-visible');
 function finish(){
  overlay.hidden=true;document.body.style.overflow='';
  if(returnFile)revealGalleryPhoto(returnFile);
  scheduleSeen();
 }
 if(matchMedia('(prefers-reduced-motion: reduce)').matches)finish();
 else overlayTimer=setTimeout(finish,205);
}
window.setStatusInset=function(pixels){
 document.documentElement.style.setProperty('--status-inset',Math.max(0,pixels/(window.devicePixelRatio||1))+'px');
 scheduleSeen();
};
window.blurInputs=function(){
 endReferencePick();
 const focused=document.activeElement;
 if(focused&&focused.matches('input,textarea,[contenteditable="true"]'))focused.blur();
};
function entry(k,v){return '<div class="entry"><span>'+e(k)+'</span><span>'+e(v)+'</span></div>';}
function sel(a,list,val){return '<div class="choices">'+list.map(x=>button(a,e(x),x===val?'choice selected':'choice',x)).join('')+'</div>';}
function check(a,label,value){return '<label class="entry"><span>'+e(label)+'</span><input class="switch" type="checkbox" data-change="'+a+'" '+(value?'checked':'')+'></label>';}
function modelSheet(){
 const list=options();
 return macModelSheet()+'<p class="sub">'+(config.synced?'账号可见模型 · '+date(config.synced)+' 同步 · 每日自动同步' :'内置模型目录。配置密钥后可同步账号可见模型。')+'</p>'+
 button('sync',icon('sync')+(syncing?'同步中…':'同步账号模型'),'secondary wide','',syncing?'disabled':'')+
 (lastSyncError?'<p class="problem">'+e(lastSyncError)+'</p>':'')+
 list.map(m=>row('chooseModel',nameFor(m.id),(m.status==='Retiring'?'即将下线 · ':'')+m.id+' · '+priceLabel(modelProfile(m.id,config.custom,config.models)),m.id,!isMac()&&m.id===draft.model)).join('')+
 (!list.length?'<p class="notice">该密钥没有返回图片模型。请检查项目权限、开通模型，或添加已授权的接入点。</p>':'')+
 button('custom',icon('plus')+'自定义接入点','secondary wide');
}
function paramSheet(){
 if(isMac())return macParamSheet();
 const p=profile(),b=body();
 let controls=p.levels.length?group('分辨率',sel('level',p.levels,draft.level)):'<p class="notice">未知模型使用服务端默认尺寸。</p>';
 if(!draft.layers && p.levels.length){
  controls+='<div class="group"><div class="ratio-header"><span class="fieldtitle">画面比例</span>'+
   '<span class="sub ratio-size" id="sizeHint">'+sizeHint(b)+'</span></div>'+sel('ratio',RATIOS,draft.ratio)+'</div>';
  if(draft.ratio==='自定义')controls+='<div class="ratio-fields"><label><span>宽</span><input id="ratioWidth" type="number" min="1" step="1" value="'+e(draft.ratioWidth)+'"></label>'+
   '<span aria-hidden="true">:</span><label><span>高</span><input id="ratioHeight" type="number" min="1" step="1" value="'+e(draft.ratioHeight)+'"></label></div>'+
   '<p class="sub ratio-error" id="ratioError"'+(b.size?' hidden':'')+'>请输入有效的宽高比例（1:16 至 16:1）。</p>';
 }
 if(!draft.layers)controls+='<div class="entry"><span>生成张数</span><div class="countstep">'+
 button('step','−','',-1,'aria-label="减少张数" '+(draft.count<=1?'disabled':''))+
 '<output>'+draft.count+'</output>'+button('step','+','',1,'aria-label="增加张数" '+(draft.count>=(p.batch?15-b.refs.length:15)?'disabled':''))+'</div></div>'+
 (draft.count>1&&p.batch?'<p class="sub">关联组图，实际张数可能少于所设上限。</p>':'');
 if(p.layers && draft.refs.length)controls+=check('layers','将单张参考图拆分为图层',draft.layers);
 if(draft.layers && p.family==='55')controls+=group('最大输出张数（含底图，2–100）','<input type="number" id="layerMax" min="2" max="100" value="'+draft.layerMax+'">');
 if(p.format && !draft.layers)controls+=group('输出格式','<div class="choices">'+[['auto','默认'],['jpeg','JPEG'],['png','PNG']].map(x=>button('format',x[1],'choice'+(draft.format===x[0]?' selected':''),x[0])).join('')+'</div>');
 if(p.rgba && !draft.layers && (p.family==='55'||draft.refs.length===1))controls+=check('transparent','透明背景 PNG',draft.transparent)+(p.family==='55'?'':'<p class="sub">此模型需要一张带透明通道的 PNG 参考图。</p>');
 return controls+check('watermark','显示 AI 生成水印',draft.watermark);
}
function sizeHint(b) {
 return b.ratio!==RATIOS[0]&&b.size?e(b.size.replace('x','×')):'';
}
function lines(b,r){const c=cost(b,r);return entry('参考图费用',c?money(c.input):'待核价')+entry(r?'输出图片':'最多输出',c?c.count+' 张 · '+money(c.output):'待核价')+entry(r?'按用量估算合计':'预计最高费用',c?money(c.total):'待核价');}
function taskSheet(j){
 const heading='任务详情';
 if(!j)return [heading,'<p>记录已删除。</p>'];
 const b=j.body,r=j.result,done=['done','partial','download_error','fetching'].includes(j.state),images=r.images||[];
 const inputs=(b.macInputs||b.refs||[]).map((ref,index)=>
  button('taskRef','<img src="refs/'+e(ref)+'.thumb" alt="输入图片 '+(index+1)+'">','',ref));
 const outputs=images.filter(availablePhoto).map((item,index)=>
  button('photo','<img src="images/'+e(item.file)+'.thumb" alt="输出图片 '+(index+1)+'">','',item.file));
 const mediaGroup=(label,items)=>'<section class="task-media-group"><h3>'+label+'</h3><div class="task-media-items">'+
  (items.length?items.join(''):'<span class="task-media-empty">无</span>')+'</div></section>';
 let info='<div class="task-media" aria-label="输入和输出图片">'+mediaGroup('输入图片',inputs)+mediaGroup('输出图片',outputs)+'</div>'+
  '<section class="task-prompt"><h3>提示词</h3><p class="summary">'+e(b.prompt||(isMac(b)?macOperations[b.operation]:'按图片内容自动拆分图层'))+'</p></section>'+
  entry('状态',isMac(b)?macTaskState(j):stateNames[j.state])+entry('创建时间',date(j.created));
 if(isMac(b)){
  info+=macParameterDetails(b,r);
  if(r.downloadFailure||j.state==='download_error')info+=entry('Mac 任务 ID',j.id);
  if(r.message)info+='<p class="problem">'+e(r.message)+'</p>';
 }else{
  info+=entry('模型',b.model)+entry('请求尺寸',b.layers?b.level:b.size||'默认');
  if(r.trace)info+=entry('方舟 Request ID',r.trace);
  if(r.httpStatus)info+=entry('HTTP 状态',r.httpStatus);
  if(j.state==='not_sent')info+='<p class="notice">本次生成请求体尚未发送，因此这条任务没有提交生图。</p>';
  if(j.state==='uncertain'||r.incomplete){
   const stage=r.phase==='awaiting'||r.phase==='response'?'手机已发送请求体，方舟可能已出图并计费。':
    r.phase==='uploading'?'上传期间连接中断，无法判断方舟是否收到完整请求。':'记录缺少完整的发送或响应阶段，请查方舟账单核对实际用量。';
   info+=entry('客户端请求编号',j.id)+'<p class="problem">'+stage+' 方舟图片 API 在原响应中返回图片。可按时间和模型核对账单，已有日志投递时可用请求编号查 TLS 日志。</p>'+
    '<div class="buttons">'+button('doc','查看账单','secondary','usage')+button('doc','单次对账指引','secondary','request-usage')+
    button('doc','查看日志指引','secondary','docs/log-collection')+button('doc','TOS 转存指引','secondary','tos')+'</div>';
   if(['uncertain','partial','recovered'].includes(j.state))info+='<div class="buttons">'+button('restorePhoto','导入原图','secondary',j.id)+button('restoreLink','下载原图链接','secondary',j.id)+'</div>';
  }
  if(j.state==='download_error')info+='<p class="problem">方舟已返回图片。本机可以在临时链接有效期内重新下载，补下载不会再次生成。</p>';
  if(r.message)info+='<p class="problem">'+e(r.message)+'</p>';
  (r.errors||[]).concat(images.filter(p=>p.downloadError).map(p=>p.downloadError)).forEach(err=>{info+='<p class="problem">'+e(err)+'</p>';});
  if(r.incomplete)info+=entry('费用','仅收到部分响应，请以方舟账单为准');else if(done||r.usage)info+=lines(b,r);else if(['cancelled','not_sent'].includes(j.state))info+=entry('费用','¥0.00');else info+=entry('提交前估算',money((cost(b,null)||{}).total));
 }
 let actions='';
 if(isMac(b)&&(active(j.state)&&j.state!=='mac_fetching'||j.state==='mac_blocked')&&!j.cancelRequested&&!r.remote?.output)
  actions+=button('cancel','取消任务','secondary',j.id);
 else if(j.state==='queued')actions+=button('cancel','取消排队','secondary',j.id);
 if(j.state==='download_error')actions+=button('recover','补下载原图','primary',j.id);
 if(!active(j.state))actions+=button('reuse','复用参数','secondary',j.id)+button('deleteAsk','删除记录','dangerbtn',j.id);
 return [heading,info,actions?'<div class="buttons fill-action-row">'+actions+'</div>':undefined];
}
function ledger(){
 let sum=0,pending=0,confirmed=0;
 jobs.forEach(j=>{if(isMac(j.body))return;if(j.result.incomplete){pending++;return;}if(['done','partial','fetching','download_error'].includes(j.state)||j.result.usage){const c=cost(j.body,j.result);if(c){sum+=c.total;confirmed++;}else pending++;}else if(['uncertain','recovered'].includes(j.state))pending++;});
 return entry('已取得用量的任务',confirmed+' 个')+entry('按本机费率估算',money(sum))+entry('待确认结果或价格',pending+' 个')+'<p class="sub">汇总当前保留的记录。免费额度、折扣、套餐及账单调整请以方舟账单为准。</p>';
}
function modalContent(){
 switch(modal){
 case 'models':return ['选择模型',modelSheet()];
 case 'macConnect':return ['连接 MFlux Studio',macConnectionSheet()];
 case 'macForgetAsk':return ['忘记配对？','<p>本机作品和任务记录会保留。原配对设备的远端任务需要原凭证继续查询。</p>'+
  button('macForget','忘记本机配对','dangerbtn wide')];
 case 'mask':return ['局部编辑',maskSheet(),button('maskApply','使用遮罩','primary wide')];
 case 'upscale':return ['高清放大',upscaleSheet(),upscaleSource&&macAvailable('upscale').length?
  button('submitUpscale','加入任务队列','primary wide'):undefined];
 case 'compare':{
  const it=photos().find(x=>x.p.file===context);
  if(!it)return ['图片对比','作品原图不可用'];
  const input=photoInput(it.j.body);
  return ['图片对比',imageViewer(compareInput?'refs/'+input+'.thumb':'images/'+context,compareInput?'输入底图':'生成原图')+
   '<div class="buttons">'+button('compareSide','输入底图','choice'+(compareInput?' selected':''),'input','aria-pressed="'+compareInput+'"')+
   button('compareSide','生成原图','choice'+(!compareInput?' selected':''),'output','aria-pressed="'+!compareInput+'"')+'</div>'];
 }
 case 'params':return ['画布与生成参数',paramSheet(),button('apply','完成','primary wide')];
 case 'cost':return ['费用明细',entry('模型',nameFor(draft.model))+lines(body(),null)+'<p class="sub">按北京地域标准按量价格估算。分档模型根据实际输出像素计算，账单可能受折扣、套餐影响。费率核对日期为 2026-11-18。</p>'+button('doc','查看官方定价 '+icon('upRight'),'textbtn','docs/model-pricing')];
 case 'samples':return ['提示词灵感库',hero()+'<div class="inspiration-layout">'+samples.map((s,i)=>row('sample',s.title,s.style,i)).join('')+'</div>'];
 case 'sample':{const s=samples[Number(context)||0];return [s.title,'<div class="sample"><img src="art/'+s.img+'.jpg" alt="'+e(s.title)+'"><p class="sub">'+e(s.style)+'</p><p>'+e(s.prompt)+'</p></div>'+button('useSample','使用这段提示词','primary wide',context)];}
 case 'key':return ['连接火山方舟','<p class="sub">填写北京地域标准按量 API Key。密钥由 Android Keystore 加密保存，请先在方舟开通所需图片模型。</p>'+group(config.hasKey?'更换 API Key':'API Key','<input id="secret" type="password" autocomplete="off" placeholder="'+(config.hasKey?'输入新的密钥':'粘贴 API Key')+'">')+'<p class="inlineerr" id="formError"></p>'+button('paste','粘贴密钥','textbtn','secret')+button('saveKey','保存并同步模型','primary wide')+'<div class="buttons">'+button('doc','获取 API Key '+icon('upRight'),'secondary','apikey')+(config.hasKey?button('forgetAsk','移除密钥','dangerbtn'):'')+'</div>'];
 case 'forgetAsk':return ['移除本机密钥','<p>下次生成图片时需要重新连接方舟。</p>'+button('forget','移除密钥','dangerbtn wide')];
 case 'custom':return ['自定义接入点',config.custom.map((c,i)=>row('removeCustom',c.name,'移除 '+c.id,i)).join('')+group('Model ID 或 Endpoint ID','<input id="customId" type="text" placeholder="ep-…">')+group('显示名称','<input id="customName" type="text" placeholder="例如：我的设计接入点">')+group('对应基础模型','<select id="family">'+['<option value="generic">其他模型 · 基础图片生成</option>'].concat(PROFILES.map(p=>'<option value="'+p.family+'">'+e(p.name)+'</option>')).join('')+'</select>')+group('其他模型输出单价（元/张，可留空）','<input id="customFee" type="number" step=".01" min="0" placeholder="未知时显示待核价">')+'<div class="sub">已知模型按对应官方规则估算。自填单价仅适用于参考图免费、输出按张统一收费的接入点。</div><p id="formError" class="inlineerr"></p>'+button('saveCustom','保存接入点','primary wide')];
 case 'taskModelFilter':{
  const models=filterModels(jobs);
  return ['筛选模型',row('taskModel','全部模型','','',!taskModel)+Array.from(models,([id,name])=>row('taskModel',name,'',id,id===taskModel)).join('')];
 }
 case 'task':return taskSheet(jobs.find(j=>j.id===context));
 case 'deleteAsk':return ['删除这条任务？','<p>将删除本机任务记录及其作品集原图。已导出至系统相册的副本可继续使用。</p>'+button('delete','删除任务与原图','dangerbtn wide',context)];
 case 'deletePhotoAsk':return ['删除这张图片？','<p>本机作品集中的这张图片将被删除。任务记录和已保存到系统相册的副本会保留。</p>'+
  button('deletePhoto','确认删除','dangerbtn wide',context)];
 case 'removeCustom':return ['移除接入点？','<p>将从本机模型选择列表中移除此接入点。</p>'+button('doRemove','移除接入点','dangerbtn wide',context)];
 case 'deleteBatchAsk':return ['删除 '+pickedTasks.size+' 条任务？','<p>将删除选中任务和对应的作品集原图。已保存到系统相册的副本会保留。</p>'+button('deleteBatch','确认删除','dangerbtn wide')];
 case 'restoreLink':return ['下载找回的链接','<p class="sub">粘贴 TLS 日志或 TOS 控制台里的原图链接，下载不会再次提交生图。</p>'+
 group('原图链接','<input id="recoveryUrl" type="text" inputmode="url" placeholder="https://…">')+
 '<p id="formError" class="inlineerr"></p>'+button('paste','粘贴链接','textbtn','recoveryUrl')+button('applyRestoreLink','开始下载','primary wide',context)];
 case 'refPhoto':return ['参考图预览',imageViewer('refs/'+context+(context.endsWith('.source')||context.endsWith('.original')?'.thumb':''),'参考图')];
 case 'photo':{
  const it=photos().find(x=>x.p.file===context);
  if(!it)return ['作品预览','原图不可用'];
  const b=it.j.body,r=it.j.result;
  const footer='<div class="buttons">'+button('reuse',icon('sync')+'<span class="action-label">复用参数</span>','primary',it.j.id)+
   button('save',icon('save')+'<span class="action-label">保存到相册</span>','primary',context)+'</div>';
  const prompt=b.prompt||(isMac(b)?macOperations[b.operation]:'按图片内容自动拆分图层');
  return ['作品预览',imageViewer('images/'+context,'生成原图',photoNeighbors())+photoActions(context,photoInput(b))+
   '<section class="preview-meta preview-prompt"><h3>提示词</h3><p class="summary collapsed" id="previewPrompt">'+e(prompt)+'</p>'+
   button('togglePrompt','展开','textbtn','','aria-controls="previewPrompt" aria-expanded="false" hidden')+'</section>'+
   '<section class="preview-meta"><h3>生成参数</h3>'+entry('尺寸',it.p.size||'未知')+
   (it.p.name?entry('图层',it.p.name):'')+
   (isMac(b)?macParameterDetails(b,r,false):entry('模型',b.name||b.model)+photoParams(b))+'</section>'+
   button('deletePhotoAsk','删除图片','dangerbtn wide preview-delete',context),footer];
 }
 case 'editPhoto':return ['编辑图片',photoEditSheet(),button('applyEdit','带入创作台','primary wide','',photoEdit.loading?'disabled':'')];
 case 'ledger':return ['本机费用统计',ledger()];
 }
 return ['映序',''];
}
function photoInput(b){return (b.macInputs||b.refs)?.[0];}
function photoActions(id,input){
 const action=(name,glyph,label)=>button(name,icon(glyph)+'<span>'+label+'</span>','viewer-action',id,'data-kind="file"');
 return '<div class="preview-actions">'+action('editPhoto','create','编辑')+action('upscaleOpen','enlarge','放大')+
  (input?action('compare','compare','对比'):'')+'</div>';
}
let photoEdit=null;
function photoEditModels(){
 const local=macModels().filter(model=>model.ready&&(capability(model,'img2img')||capability(model,'inpaint')));
 const cloud=options().filter(model=>modelProfile(model.id,config.custom,config.models).refs>0);
 return local.map(model=>({id:macModelKey(config.mac.serverId,model.id),name:model.name,
  masked:!!capability(model,'inpaint'),normal:!!capability(model,'img2img'),model})).concat(
  cloud.map(model=>({id:model.id,name:nameFor(model.id),normal:true})));
}
function normalPhotoEditModel(edit){
 return edit.selected.normal?edit.selected:
  edit.models.find(model=>model.normal&&model.model)||edit.models.find(model=>model.normal);
}
function photoEditSheet(){
 const edit=photoEdit;
 const choices=edit.models.map(model=>{
  const selected=model===edit.selected;
  const label=model.name+(model.masked?(model.normal?' · 支持涂抹':' · 仅支持涂抹'):'');
  return button('choosePhotoEditModel','<span>'+e(label)+'</span>'+(selected?icon('check'):''),
   'photo-model-option',model.id,'aria-pressed="'+selected+'"');
 }).join('');
 const picker='<details class="photo-model-picker"><summary aria-label="编辑模型，'+e(edit.selected.name)+'"><span>'+
  e(edit.selected.name)+'</span>'+icon('down')+'</summary><div class="photo-model-options">'+choices+'</div></details>';
 let tools='';
 if(edit.selected.masked){
  const normal=normalPhotoEditModel(edit);
  if(normal&&normal!==edit.selected)tools='<p class="sub">未涂抹时使用「'+e(normal.name)+'」进行普通编辑。</p>';
  if(edit.loading)tools+='<p class="sub">正在准备涂抹画布…</p>';
  else if(edit.mask)tools+=group(normal?'涂抹范围（可选）':'涂抹范围',maskSheet());
 }
 return group('编辑模型',picker)+
  group('编辑提示词','<textarea id="editPrompt" class="edit-prompt" placeholder="描述要修改的内容">'+e(edit.prompt)+'</textarea>')+
  button('paste','粘贴提示词','textbtn','editPrompt')+tools;
}
function positionPhotoModelOptions(){
 const picker=$('.photo-model-picker[open]');
 if(!picker)return;
 const list=picker.querySelector('.photo-model-options');
 const field=picker.querySelector('summary').getBoundingClientRect();
 const body=picker.closest('.sheet-body').getBoundingClientRect();
 if(field.top>=body.bottom||field.bottom<=body.top){picker.open=false;return;}
 const below=body.bottom-field.bottom-8,above=field.top-body.top-8;
 const preferAbove=above>below&&below<Math.min(list.scrollHeight,window.innerHeight*.34);
 picker.classList.toggle('opens-up',preferAbove);
 list.style.setProperty('--model-menu-space',Math.max(0,preferAbove?above:below)+'px');
}
async function openPhotoEdit(kind,id){
 const source=kind==='file'?photos().find(x=>x.p.file===id)?.j.body:draft;
 if(!source)throw new Error('原图不可用');
 const models=photoEditModels();
 const selected=models.find(m=>m.id===source.model)||
  models.find(m=>m.normal&&!!m.model===isMac(source))||models.find(m=>m.normal)||models[0];
 if(!selected)throw new Error('请先配置支持图片编辑的模型。');
 photoEdit={kind,id,context,source,models,selected,prompt:'',mask:null,loading:false,applying:false};
 maskEdit=null;
 show('editPhoto');
 await loadPhotoEditModel(selected.id);
}
async function loadPhotoEditModel(id){
 const edit=photoEdit;
 edit.selected=edit.models.find(model=>model.id===id);
 if(!edit.selected.masked||edit.mask){
  maskEdit=edit.selected.masked?edit.mask:null;
  renderModal();return;
 }
 if(edit.loading)return;
 edit.loading=true;
 renderModal();
 try{
  const data=edit.kind==='file'?{file:edit.id}:{ref:edit.id};
  const source=await api('macImage',data);
  edit.mask=newMaskEdit(source);
 }finally{
  edit.loading=false;
  if(photoEdit===edit&&modal==='editPhoto'){
   maskEdit=edit.selected.masked?edit.mask:null;
   renderModal();
  }
 }
}
async function applyPhotoEdit(){
 const edit=photoEdit;
 if(edit.applying||edit.loading)return;
 const prompt=edit.prompt.trim();
 if(!prompt)throw new Error('请描述要修改的内容');
 const mask=edit.selected.masked&&edit.mask&&hasMask(edit.mask);
 const selected=mask?edit.selected:normalPhotoEditModel(edit);
 if(!selected)throw new Error('当前没有可用的普通编辑模型，请涂抹要修改的区域。');
 edit.applying=true;
 try{
  let ref=edit.id;
  if(mask)ref=edit.mask.source.ref;
  else if(edit.kind==='file'||(!selected.model&&edit.id.endsWith('.source')))ref=await api('editPhoto',{[edit.kind]:edit.id});
  if(photoEdit!==edit||modal!=='editPhoto')return;
  rememberMacLoras();
  const {macLoraSelections,macLoraStrengths}=draft;
  draft=Object.assign({},initial,selected.id===edit.source.model?edit.source:{},{
   source:selected.model?'mac':'ark',model:selected.id,prompt,promptRefs:[],refs:[ref],operation:undefined,macBase:undefined,
   macBaseSize:undefined,strokes:[],macOptions:{},macLoraSelections,macLoraStrengths,macEdge:'',count:1,watermark:false,layers:false,transparent:false});
  if(selected.model)selectMac(selected.model);
  if(mask)setDraftMask(edit.mask);
  navigate('create');redraw();prepareMacDimensions().catch(()=>{});$('#prompt').focus();
 }finally{edit.applying=false;}
}
function photoParams(b){
 return entry('模型 ID',b.model)+entry('请求尺寸',b.layers?b.level:b.size||'模型默认')+
 entry('画面比例',b.layers?'参考图比例':b.ratio==='自定义'?b.ratioWidth+':'+b.ratioHeight:b.ratio||'模型默认')+
 entry('生成方式',b.layers?'图层拆分':b.refs.length?'参考图生成':'文生图')+
 entry('参考图',b.refs.length+' 张')+entry('输出张数'+(b.layers?'上限':''),b.layers?b.layerMax:b.count)+
 entry('输出格式',b.format==='auto'||!b.format?'模型默认':b.format.toUpperCase())+
 entry('透明背景',b.transparent?'开启':'关闭')+entry('水印',b.watermark?'开启':'关闭');
}
function renderModal(preserveScroll=true) {
 if(!modal)return;
 maskCanvas?.observer.disconnect();maskCanvas=null;
 const sheet=$('#sheet'),oldBody=sheet.querySelector('.sheet-body');
 const modelFocused=modal==='editPhoto'&&document.activeElement?.matches('.photo-model-picker summary');
 const scroll=preserveScroll&&oldBody?oldBody.scrollTop:0;
 const [heading,content,footer]=modalContent();
 sheet.classList.toggle('task-detail-sheet',modal==='task');
 sheet.classList.toggle('photo-preview-sheet',modal==='photo'&&!!footer);
 sheet.innerHTML='<div class="sheethead"><h2>'+e(heading)+'</h2>'+button('close',icon('close'),'circle','','aria-label="关闭"')+'</div>'+
  '<div class="sheet-body">'+content+'</div>'+(footer?'<div class="sheet-footer">'+footer+'</div>':'');
 const sheetBody=sheet.querySelector('.sheet-body');
 sheetBody.scrollTop=scroll;
 if(modelFocused)sheet.querySelector('.photo-model-picker summary').focus({preventScroll:true});
 const picker=sheet.querySelector('.photo-model-picker');
 if(picker){
  picker.addEventListener('toggle',positionPhotoModelOptions);
  sheetBody.addEventListener('scroll',positionPhotoModelOptions,{passive:true});
 }
 setupViewer(sheet.querySelector('.viewer'),modal==='photo'?slidePhoto:null);
 if(modal==='mask'||modal==='editPhoto')setupMask($('#maskCanvas'));
 updatePreviewPrompt();
 sheet.querySelector('.mac-advanced')?.addEventListener('toggle',event=>{draft.macAdvanced=event.target.open;saveDraft();});
}
function updatePreviewPrompt(){
 const prompt=$('#previewPrompt');
 if(prompt?.classList.contains('collapsed'))
  $('#sheet [data-act="togglePrompt"]').hidden=prompt.scrollHeight<=prompt.clientHeight;
}
function photoNeighbors() {
 const list=previousPhotoTask?photos().filter(item=>item.j.id===previousPhotoTask):galleryPhotos();
 const index=list.findIndex(item=>item.p.file===context);
 const idAt=position=>index>=0&&list[position]?list[position].p.file:'';
 return {previous:idAt(index-1),next:idAt(index+1)};
}
function slidePhoto(direction) {
 if(modal!=='photo')return;
 const adjacent=photoNeighbors();
 const next=direction<0?adjacent.previous:adjacent.next;
 if(!next)return;
 context=next;
 renderModal(false);
 scheduleSeen();
}
let highlightedFile='';
let highlightTimer;
function revealGalleryPhoto(file) {
 const card=Array.from(document.querySelectorAll('.gallery .photo')).find(item=>item.dataset.val===file);
 if(!card)return;
 const bounds=card.getBoundingClientRect();
 const top=$('#statusFill').getBoundingClientRect().bottom;
 const bottom=$('#nav').getBoundingClientRect().top;
 if(bounds.top<top||bounds.bottom>bottom)
  window.scrollBy({top:(bounds.top+bounds.bottom-top-bottom)/2,behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'});
 document.querySelectorAll('.return-highlight').forEach(item=>item.classList.remove('return-highlight'));
 highlightedFile=file;
 card.classList.add('return-highlight');
 clearTimeout(highlightTimer);
 highlightTimer=setTimeout(()=>{
  highlightedFile='';
  document.querySelectorAll('.return-highlight').forEach(item=>item.classList.remove('return-highlight'));
 },1900);
}
function redraw(){draw();if(modal)renderModal();saveDraft();}
function feedback(err){if($('#formError')&&!$('#overlay').hidden)$('#formError').textContent=err.message;else toast(err.message);}
async function refresh(){
 if(refreshing)return;
 refreshing=true;
 try{
  const fresh=await api('jobs',{since:jobRevision});
  if(!fresh)return;
  const firstLoad=jobRevision<0;
  const existing=new Set(photos().map(x=>x.p.file)),previousUnread=unreadCount();
  jobRevision=fresh.revision;
  jobs=fresh.rows||[];
  const images=photos();
  if(galleryModel&&!images.some(x=>filterModelKey(x.j)===galleryModel))galleryModel='';
  if(taskModel&&!jobs.some(j=>filterModelKey(j)===taskModel))taskModel='';
  for(const id of pickedTasks)if(!jobs.some(j=>j.id===id&&!active(j.state)))pickedTasks.delete(id);
  const delivered=!firstLoad&&images.some(x=>!x.p.imported&&!existing.has(x.p.file)&&!x.p.seen);
  if(delivered&&!document.hidden){
   badgeHolds.gallery=previousUnread;
   nav();
   try {await flyDots(badgeCenter('tasks'),'gallery',1);}
   finally {badgeHolds.gallery=null;}
  }
  if(page==='tasks'&&delivered&&!modal){
   galleryModel='';selecting=false;pickedTasks.clear();navigate('gallery');return;
  }
  if(page==='tasks'||page==='gallery')draw();else nav();
  if(['task','ledger'].includes(modal))renderModal();
 } catch(err){toast(err.message);}
 finally{refreshing=false;}
}
const reading=new Set();
let seenFrame=0;
function scheduleSeen(){
 if(!seenFrame)seenFrame=requestAnimationFrame(checkVisiblePhotos);
}
function checkVisiblePhotos(){
 seenFrame=0;
 if(document.hidden)return;
 const overlay=$('#overlay');
 const showingPhoto=!overlay.hidden&&modal==='photo';
 if(!showingPhoto && (page!=='gallery'||!overlay.hidden))return;
 const frame=window.visualViewport;
 const viewport={left:frame?frame.offsetLeft:0,top:frame?frame.offsetTop:0,
  right:frame?frame.offsetLeft+frame.width:innerWidth,bottom:frame?frame.offsetTop+frame.height:innerHeight};
 if(showingPhoto){
  const sheet=$('#sheet .sheet-body').getBoundingClientRect();
  viewport.top=Math.max(viewport.top,sheet.top,$('#statusFill').getBoundingClientRect().bottom);
  viewport.bottom=Math.min(viewport.bottom,sheet.bottom);
 }else{
  viewport.top=Math.max(viewport.top,$('#statusFill').getBoundingClientRect().bottom);
  viewport.bottom=Math.min(viewport.bottom,$('#nav').getBoundingClientRect().top);
 }
 const images=showingPhoto?document.querySelectorAll('#sheet .viewer-current'):document.querySelectorAll('.photo .unseen-dot + img');
 const files=[];
 for(const image of images){
  const area=image.getBoundingClientRect();
  if(!showingPhoto&&area.top>viewport.bottom)break;
  if(!image.complete||!image.naturalWidth||area.width<=0||area.height<=0)continue;
  if(area.top<viewport.top-.5||area.bottom>viewport.bottom+.5||area.left<viewport.left-.5||area.right>viewport.right+.5)continue;
  const file=showingPhoto?context:image.closest('.photo').dataset.val;
  if(file&&!reading.has(file))files.push(file);
 }
 if(files.length)markViewed(files).catch(()=>{});
}
async function markViewed(files) {
 const pending=new Set(files);
 const unseen=photos().filter(x=>pending.has(x.p.file)&&!x.p.seen&&!reading.has(x.p.file));
 if(!unseen.length)return;
 const ids=unseen.map(x=>x.p.file);
 for(const id of ids)reading.add(id);
 try{
  const completed=new Set(await api('readPhotos',{files:ids}));
  for(const x of photos())if(completed.has(x.p.file))x.p.seen=true;
  for(const card of document.querySelectorAll('.photo')){
   if(!completed.has(card.dataset.val))continue;
   const dot=card.querySelector('.unseen-dot');
   if(dot)dot.remove();
  }
  nav();
 } finally {
  for(const id of ids)reading.delete(id);
 }
}
window.addEventListener('scroll',scheduleSeen,{passive:true,capture:true});
window.addEventListener('resize',scheduleSeen,{passive:true});
window.addEventListener('resize',updatePreviewPrompt,{passive:true});
window.addEventListener('resize',positionPhotoModelOptions,{passive:true});
document.addEventListener('load',event=>{if(event.target.tagName==='IMG')scheduleSeen();},true);
document.addEventListener('animationend',scheduleSeen);
function updateModels(result) {
 if(config.synced===result.synced)return;
 config.models=result.models;config.synced=result.synced;
 const list=options();
 if(!isMac()&&!list.some(m=>m.id===draft.model))setDraftModel(list.length?list[0].id:'');
 if(page!=='create'||document.activeElement!==$('#prompt'))draw();
 if(modal==='models')renderModal();
 saveDraft();
}
async function dailySync(){
 if(!config.hasKey || syncing)return;
 try {
  updateModels(await api('modelCache'));
  const now=Date.now();
  if(now-config.synced>=86400000 && now-autoSyncAt>=900000){autoSyncAt=now;await sync(false);}
 }catch(err){lastSyncError=err.message;}
}
async function sync(showList){
 if(!config.hasKey){show('key');return;}
 if(syncing)return;syncing=true;lastSyncError='';if(showList)show('models');else if(modal==='models')renderModal();
 try{const result=await api('sync',{manual:showList});updateModels(result);if(showList)toast('已同步 '+result.models.length+' 个图片模型');}
 catch(err){lastSyncError=err.message;if(!showList)toast('模型同步失败，可在模型列表查看详情');}
 finally{syncing=false;if(modal==='models')renderModal();}
}
function setDraftModel(id){if(id!==draft.model){draft.model=id;draft.count=1;}}
function referenceResolution(){
 if(!draft.refs.length)return null;
 let size,ratio,aspect;
 if(isMac()){
  const op=macOp();
  if(!draft.macEdge&&draft.macRatio!=='自由')return null;
  size=macDraftSize(capability(macModel(),op),op)?.join('x')||draft.macEdge;
  ratio=op==='inpaint'||!draft.macRatio?'原图':draft.macRatio==='自由'?'自定义':draft.macRatio;
 }else{
  const previous=body();
  size=draft.layers?previous.level:previous.size;
  ratio=draft.layers||draft.ratio===RATIOS[0]?'原图':draft.ratio;
  if(ratio==='自定义')aspect=[Number(draft.ratioWidth),Number(draft.ratioHeight)];
 }
 const area=resolutionPixels(size);
 if(!area)return null;
 const [width,height]=aspect||(ratio.includes(':')?ratio.split(':'):(SIZES[size]?.[0]||size).split('x')).map(Number);
 return {area,ratio,width,height};
}
function matchArkResolution(target){
 if(!target)return;
 const p=profile();
 if(!p.levels.length)return;
 draft.ratio=target.ratio==='原图'?RATIOS[0]:RATIOS.includes(target.ratio)?target.ratio:'自定义';
 if(draft.ratio==='自定义')[draft.ratioWidth,draft.ratioHeight]=[target.width,target.height];
 const nearest=()=>nearestResolution(p.levels,target.area,level=>
  resolutionPixels(requestSize(p,level,draft.ratio,draft.ratioWidth,draft.ratioHeight)));
 let level=nearest();
 if(!level&&draft.ratio==='自定义'){
  draft.ratio=RATIOS[0];level=nearest();
 }
 if(level)draft.level=level;
}
function relabelDraft(){Object.assign(draft,relabelReferences(draft.prompt,draft.promptRefs,draft.refs,draft.source));}
function updateReferenceOrder(){
 endReferencePick();relabelDraft();prepareMacDimensions().catch(()=>{});redraw();
}
function changeModel(id){
 modelChoice++;
 const resolution=draft.model===id?null:referenceResolution();
 rememberMacLoras();
 setDraftModel(id);draft.source='ark';draft.operation=undefined;draft.layers=false;draft.transparent=false;draft.format='auto';
 matchArkResolution(resolution);
 relabelDraft();normalize();close();draw();saveDraft();
}
function setReferencesBusy(value){
 referencesBusy=value;
 updateGenerateButton();
 const pickButton=$('#app [data-act="pick"]');
 if(pickButton)pickButton.disabled=value;
}
function generationBlocked(){return submitting||referencesBusy||isMac()&&macStartupChecking;}
function localGenerationStatus(){return macStartupChecking?'正在检查 Mac':'本地推理';}
function updateGenerateButton(){
 const generateButton=$('#app .generate');
 if(!generateButton)return;
 generateButton.disabled=generationBlocked();
 if(isMac())generateButton.querySelector('.end').textContent=localGenerationStatus();
}
window.addReferences=function(names,failed){
 const n=Math.max(0,allowedRefs()-draft.refs.length);draft.refs=draft.refs.concat(names.slice(0,n));normalize();updateReferenceOrder();
 if(failed||names.length>n)toast(failed?'部分图片格式或宽高比不支持':'已达到当前模型参考图上限');
};
window.receiveReferences=async function(failed){
 clearTimeout(referenceRetry);
 try{
  const changes=await api('pendingRefs');
  if(changes.busy){
   setReferencesBusy(true);
   referenceRetry=setTimeout(()=>window.receiveReferences(failed),350);
   return;
  }
  const removed=new Set(changes.removed||[]);
  const added=Array.isArray(changes)?changes:changes.added||[];
  if(removed.size)draft.refs=draft.refs.filter(file=>!removed.has(file));
  if(added.length||removed.size||failed||changes.order?.length){
   window.addReferences(added,failed);
   if(changes.order?.length){
    const ordered=changes.order.filter(file=>draft.refs.includes(file));
    draft.refs=ordered.concat(draft.refs.filter(file=>!ordered.includes(file)));
    updateReferenceOrder();
   }
   await api('draft',draft);
  }
 }catch(err){toast(err.message);}
 setReferencesBusy(false);
};
function idForRequest(){const bytes=new Uint8Array(16);crypto.getRandomValues(bytes);return Array.from(bytes,n=>n.toString(16).padStart(2,'0')).join('');}
async function generate(){
 if(generationBlocked())return;
 normalize();
 if(!draft.prompt.trim()&&!draft.layers){toast('先写下想生成的画面');$('#prompt').focus();return;}
 const issue=referenceIssue();
 if(issue)throw new Error(issue);
 if(isMac()){
  if(!config.mac?.endpoint){show('macConnect');return;}
  const model=macModel(),op=macOp(),cap=capability(model,op);
  if(!model?.ready||!cap){show('models');throw new Error('请选择支持'+macOperations[op]+'的就绪模型。');}
  if(op==='inpaint'&&!draft.refs.includes(draft.macBase))throw new Error('修复底图已移除，请重新选择并涂抹。');
  if(op!=='inpaint'&&draft.refs.length>cap.maxInputs)throw new Error('此模型最多接受 '+cap.maxInputs+' 张参考图。');
  if(op!=='inpaint'&&draft.macRatio==='自由'&&!macDraftSize(cap,op)){show('params');throw new Error(macSizeHint());}
 }else{
  if(!config.hasKey){show('key');return;}
  if(!draft.model||!options().some(o=>o.id===draft.model)){show('models');return;}
  if(draft.refs.length>allowedRefs()){toast('请选取 1 至 '+allowedRefs()+' 张参考图');return;}
  if(!draft.layers&&draft.ratio==='自定义'&&profile().levels.length&&!body().size){show('params');toast('请检查自定义比例');return;}
 }
 const origin=centerOf($('#app .generate'));
 const b=body();
 submitting=true;draw();
 try{
  await api('draft',draft);
  await api('submit',{id:idForRequest(),body:b});
  taskFilter='all';
  badgeHolds.tasks=activeCount();
  const pendingRefresh=refresh();
  try {
   await flyDots(origin,'tasks',b.count,index=>{
    if(b.separate||index===b.count-1)badgeHolds.tasks=Math.min(activeCount(),badgeHolds.tasks+1);
    nav();
   });
   await pendingRefresh;
  } finally {
   badgeHolds.tasks=null;nav();
  }
  toast('任务已加入队列');
 }finally{
  submitting=false;
  if(page==='create')draw();
 }
}
function studioBack(){
 if(modal==='editPhoto'){
  const picker=$('.photo-model-picker[open]');
  if(picker){picker.open=false;picker.querySelector('summary').focus();return true;}
  const {kind,context:origin}=photoEdit;
  show(kind==='file'?'photo':'refPhoto',origin);return true;
 }
 if(modal==='deletePhotoAsk'||modal==='compare'){show('photo',context);return true;}
 if(modal==='refPhoto'&&previousRefTask){
  const id=previousRefTask;
  previousRefTask='';show('task',id);return true;
 }
 if(modal){if(modal==='restoreLink'){show('task',context);return true;}if(modal==='photo'&&previousPhotoTask){show('task',previousPhotoTask);previousPhotoTask='';}else close();return true;}
 if(page==='tasks'&&selecting){selecting=false;pickedTasks.clear();draw();return true;}
 if(page!=='create'){navigate('create');return true;}return false;
}
window.studioBack=studioBack;
let compareInput=false;
async function act(a,v,element){
 switch(a){
 case 'nav':navigate(v);break;
 case 'connection':show('key');break;
 case 'close':studioBack();break;
 case 'slide':sampleIndex=Number(v);renderModal();break;
 case 'useSample':draft.promptRefs=[];draft.prompt=samples[Number(v)||0].prompt;close();redraw();toast('已填入画面描述');break;
 case 'params':
  show(a,v);prepareMacDimensions().catch(()=>{});break;
 case 'macConnect':case 'macForgetAsk':case 'models':case 'key':case 'cost':case 'sample':case 'samples':case 'ledger':case 'deleteAsk':case 'custom':case 'removeCustom':case 'forgetAsk':show(a,v);break;
 case 'photo':previousPhotoTask=modal==='task'?context:'';show('photo',v);break;
 case 'refPhoto':
  if(insertPickedReference(v))break;
  previousRefTask='';show(a,v);break;
 case 'chooseMac':{
  const model=macModels().find(x=>x.id===v);
  if(!model?.ready)throw new Error('模型未就绪，请在 Mac 检查权重。');
  if(!model.operations.some(x=>x.id!=='upscale'))throw new Error('请从图片预览打开「高清放大」以使用 SeedVR2。');
  const choice=++modelChoice,ref=draft.refs[0];
  const resolution=draft.model===macModelKey(config.mac.serverId,model.id)?null:referenceResolution();
  if(resolution&&ref&&!macSizes.has(ref)){
   await loadMacReferenceSize(ref).catch(()=>{});
   if(choice!==modelChoice||modal!=='models'||draft.refs[0]!==ref)return;
  }
  selectMac(model);matchMacResolution(resolution);
  relabelDraft();close();redraw();prepareMacDimensions().catch(()=>{});break;
 }
 case 'macSelectService':{
  clearMacPairing();
  macPairTarget=macServices.find(service=>service.endpoint===v)||null;
  if(macPairTarget)await advanceMacPairing('macPairInspect',{endpoint:v});
  break;
 }
 case 'macManual':
  clearMacPairing();macPairTarget={manual:true};macError='';macNotice='';renderModal();break;
 case 'macSelectAgain':
  clearMacPairing();macError='';macNotice='';renderModal();break;
 case 'macDiscover':await api(a);break;
 case 'macPairInspect':{
  if(!macPairTarget)break;
  if(macPairTarget.manual&&!macPairLocked)macManualAddress=$('#macManualAddress').value.trim();
  const endpoint=macPairTarget.manual?macManualAddress:macPairTarget.endpoint;
  if(!endpoint)throw new Error('请填写 Mac 显示的局域网服务地址。');
  await advanceMacPairing(a,{endpoint});break;
 }
 case 'macPairConfirm':
  if(macPairAttempt&&!macPairAttempt.confirmed)await advanceMacPairing(a,{attemptId:macPairAttempt.attemptId});
  break;
 case 'macPair':{
  if(!macPairAttempt?.confirmed||macPairLocked)break;
  const code=$('#macPairing').value;
  if(!/^[0-9]{6}$/.test(code))throw new Error('请输入 Mac 显示的六位数字配对码。');
  await updateMacConnection(a,{attemptId:macPairAttempt.attemptId,code});break;
 }
 case 'macAddress':await updateMacConnection(a,{endpoint:$('#macEndpoint').value});break;
 case 'macRefresh':case 'macForget':await updateMacConnection(a);break;
 case 'macRatio':draft.macRatio=v==='自动'?'':v;draft.macEdge='';redraw();prepareMacDimensions().catch(()=>{});break;
 case 'macEdge':draft.macEdge=v==='自动'?'':v;redraw();break;
 case 'maskOpen':await openMacImage('inpaint','ref',v);break;
 case 'upscaleOpen':await openMacImage('upscale',element?.dataset.kind||'ref',v);break;
 case 'submitUpscale':await submitUpscale();break;
 case 'maskApply':applyMask();break;
 case 'maskExit':
  clearDraftMask();
  close();redraw();break;
 case 'maskTool':
  maskEdit.erases=v==='erase';maskEdit.pan=v==='pan';
  for(const tool of document.querySelectorAll('[data-act="maskTool"]'))tool.setAttribute('aria-pressed',String(tool===element));
  break;
 case 'maskUndo':case 'maskClear':maskCanvas?.remove(a==='maskClear');break;
 case 'compare':compareInput=false;show('compare',v);break;
 case 'compareSide':{
  const showInput=v==='input';
  if(showInput===compareInput)break;
  const item=photos().find(x=>x.p.file===context);
  if(!item)break;
  const image=$('#sheet .viewer-current');
  compareInput=showInput;
  image.alt=showInput?'输入底图':'生成原图';
  image.parentElement.setAttribute('aria-label',image.alt+'，支持双指缩放和拖动');
  image.src=showInput?'refs/'+encodeURIComponent(photoInput(item.j.body))+'.thumb':'images/'+encodeURIComponent(context);
  for(const toggle of document.querySelectorAll('#sheet [data-act="compareSide"]')){
   const selected=toggle===element;
   toggle.classList.toggle('selected',selected);toggle.setAttribute('aria-pressed',String(selected));
  }
  break;
 }
 case 'togglePrompt':{
  const collapsed=$('#previewPrompt').classList.toggle('collapsed');
  element.textContent=collapsed?'展开':'收起';
  element.setAttribute('aria-expanded',String(!collapsed));break;
 }
 case 'taskRef':previousRefTask=context;show('refPhoto',v);break;
 case 'editPhoto':await openPhotoEdit(element.dataset.kind,v);break;
 case 'choosePhotoEditModel':{
  const picker=element.closest('.photo-model-picker');
  picker.open=false;
  picker.querySelector('summary').focus({preventScroll:true});
  if(v!==photoEdit.selected.id||photoEdit.selected.masked&&!photoEdit.mask)await loadPhotoEditModel(v);
  break;
 }
 case 'restoreLink':case 'deleteBatchAsk':case 'deletePhotoAsk':show(a,v);break;
 case 'galleryModel':galleryModel=v;draw();break;
 case 'taskModelFilter':show(a,v);break;
 case 'taskModel':taskModel=v;pickedTasks.clear();expandedStacks.clear();close();draw();break;
 case 'multi':selecting=!selecting;pickedTasks.clear();draw();break;
 case 'task':if(selecting){toggleTask(v);}else show('task',v);break;
 case 'taskStack': {
  const stack=taskStacks().find(group=>group[0].id===v);
  if(!stack)return;
  if(selecting)toggleStack(stack);
  else if(expandedStacks.has(v))expandedStacks.delete(v);
  else expandedStacks.add(v);
  draw();break;
 }
 case 'pickFailed':{
  taskModel='';taskFilter='failed';selecting=true;pickedTasks.clear();
  for(const item of jobs)if(failedStates.includes(item.state))pickedTasks.add(item.id);
  draw();if(!pickedTasks.size)toast('没有失败任务');break;
 }
 case 'deleteBatch':{
  const removed=await api('deleteMany',{ids:Array.from(pickedTasks)});
  selecting=false;pickedTasks.clear();close();await refresh();draw();toast('已删除 '+removed+' 条任务');break;
 }
 case 'restorePhoto':await api('restorePhoto',{id:v});break;
 case 'applyRestoreLink':
  await api('restoreLink',{id:v,link:$('#recoveryUrl').value});show('task',v);await refresh();break;
 case 'applyEdit':await applyPhotoEdit();break;
 case 'paste':{
  const field=document.getElementById(v);if(!field)break;
  const contents=await api('paste');if(!contents)throw new Error('剪贴板里没有文字');
  if(!field.isConnected)break;
  const from=field.selectionStart==null?field.value.length:field.selectionStart;
  const to=field.selectionEnd==null?from:field.selectionEnd;
  field.setRangeText(contents,from,to,'end');field.dispatchEvent(new Event('input',{bubbles:true}));break;
 }
 case 'filter':taskFilter=v;pickedTasks.clear();expandedStacks.clear();draw();break;
 case 'level':case 'ratio':case 'format':draft[a]=v;if(a==='format'&&v!=='png')draft.transparent=false;redraw();break;
 case 'step':draft.count+=Number(v);redraw();break;
 case 'apply':
  if(isMac())$('#sheet').querySelectorAll(macDraftNumbers).forEach(field=>storeMacDraftNumber(field,true));
  if(isMac()&&draft.macRatio==='自由'&&!macDraftSize(capability(macModel(),macOp()),macOp())){toast(macSizeHint());break;}
  if(!isMac()&&!draft.layers&&draft.ratio==='自定义'&&profile().levels.length&&!body().size){toast('请检查自定义比例');break;}close();redraw();break;
 case 'sync':await sync(true);break;
 case 'chooseModel':changeModel(v);break;
 case 'saveKey':{const key=$('#secret').value.trim();if(!key)throw new Error('请填写 API Key');await api('key',{key:key});config.hasKey=true;config.models=[];config.synced=0;lastSyncError='';nav();await sync(true);break;}
 case 'forget':await api('key',{key:''});config.hasKey=false;config.synced=0;config.models=[];lastSyncError='';close();redraw();toast('已移除本机密钥');break;
 case 'saveCustom':{const id=$('#customId').value.trim(),name=$('#customName').value.trim()||id,family=$('#family').value,fee=$('#customFee').value.trim();
  if(!/^[A-Za-z0-9][A-Za-z0-9._:-]{1,159}$/.test(id))throw new Error('请填写有效的 Model ID 或 Endpoint ID');
  if(known(id))throw new Error('该基础模型已内置。请选择模型，或填写自定义接入点 ID。');
  if(family==='generic' && fee && (!/^\d+(\.\d{1,2})?$/.test(fee)||Number(fee)>10000))throw new Error('单价应为 0 至 10000 元，最多两位小数');
  const list=config.custom.filter(x=>x.id!==id).concat([{id:id,name:name,family:family,cents:fee?Math.round(Number(fee)*100):null}]);
  await api('custom',{list:list});config.custom=list;changeModel(id);toast('已保存并选用接入点');break;}
 case 'doRemove':{const list=config.custom.filter((_,i)=>i!==Number(v));await api('custom',{list:list});config.custom=list;if(!isMac()&&!options().some(m=>m.id===draft.model))setDraftModel((options()[0]||{}).id||'');show('custom');redraw();break;}
 case 'pick':
  if(referencesBusy)return;
  setReferencesBusy(true);
  try{
   await api('draft',draft);
   await api('pick',{limit:allowedRefs()-draft.refs.length});
  }catch(error){setReferencesBusy(false);throw error;}
  break;
 case 'unref':draft.refs=draft.refs.filter((_,i)=>i!==Number(v));updateReferenceOrder();break;
 case 'doc':await api('open',{page:v});break;
 case 'generate':await generate();break;
 case 'reuse':{
  const j=jobs.find(x=>x.id===v);if(!j)break;
  const b=j.body,opts=j.result.remote?.options||b.macOptions||{};
  if(isMac(b)&&b.operation==='upscale'){
   if(config.mac?.serverId!==b.serverId||!macAvailable('upscale').some(m=>m.id.toLowerCase()===b.modelId.toLowerCase()))
    throw new Error('请连接原 Mac 并刷新 SeedVR2 模型目录。');
   const source=await api('macImage',{ref:b.macBase});
   upscaleSource={...source,model:macAvailable('upscale').find(m=>m.id.toLowerCase()===b.modelId.toLowerCase()).id,
    scale:opts.shortEdge?'edge':opts.scale,edge:opts.shortEdge,seed:opts.seed,softness:opts.softness};
   show('upscale');break;
  }
  const count=b.model===draft.model?b.count:1;
  const strokes=isMac(b)&&b.operation==='inpaint'?await api('macMask',{id:j.id}):[];
  rememberMacLoras();
  const {macLoraSelections,macLoraStrengths}=draft;
  draft=Object.assign({},initial,b,{refs:b.refs.slice(),count,macOptions:{...opts,loras:opts.loras||[]},macLoraSelections,macLoraStrengths,strokes});
  relabelDraft();navigate('create');redraw();prepareMacDimensions().catch(()=>{});
  toast('参数已填入，确认后可再次生成');break;
 }
 case 'save':await api('save',{file:v});toast('已保存到系统相册 · Pictures/映序');break;
 case 'cancel':if(!await api('cancel',{id:v}))toast('任务已开始处理');await refresh();break;
 case 'recover':await api('recover',{id:v});await refresh();break;
 case 'delete':if(!await api('delete',{id:v}))throw new Error('任务仍在处理中或已删除');close();await refresh();break;
 case 'deletePhoto':{
  const item=photos().find(x=>x.p.file===v);
  if(!item||!await api('deletePhoto',{id:item.j.id,file:v}))throw new Error('图片仍在保存或已删除');
  item.p.deleted=true;
  draw();
  if(previousPhotoTask){show('task',previousPhotoTask);previousPhotoTask='';}else close();
  await refresh();break;
 }
 }
}
function toggleStack(stack){
 const selected=stack.every(item=>pickedTasks.has(item.id));
 for(const item of stack) {
  if(selected)pickedTasks.delete(item.id);else pickedTasks.add(item.id);
 }
}
function toggleTask(id){
 if(!jobs.some(j=>j.id===id&&!active(j.state)))return;
 if(pickedTasks.has(id))pickedTasks.delete(id);else pickedTasks.add(id);
 draw();
}
let pressTimer,pressStart,ignoreTaskUntil=0;
document.addEventListener('pointerdown',event=>{
 const task=event.target.closest('.taskcard');
 if(!task||task.disabled||selecting||active((jobs.find(j=>j.id===task.dataset.val)||{}).state))return;
 const id=task.dataset.val;
 pressStart=[event.clientX,event.clientY];
 pressTimer=setTimeout(()=>{
  ignoreTaskUntil=Date.now()+650;
  selecting=true;
  if(task.dataset.act==='taskStack'){
   const stack=taskStacks().find(group=>group[0].id===id);
   if(stack)toggleStack(stack);
   draw();
  }else toggleTask(id);
 },480);
});
document.addEventListener('pointermove',event=>{
 if(pressStart&&Math.hypot(event.clientX-pressStart[0],event.clientY-pressStart[1])>12)clearTimeout(pressTimer);
});
for(const type of ['pointerup','pointercancel'])document.addEventListener(type,()=>{clearTimeout(pressTimer);pressStart=null;});
for(const type of ['copy','cut','contextmenu','dragstart'])document.addEventListener(type,event=>event.preventDefault());
document.addEventListener('click',event=>{
 const b=event.target.closest('[data-act]');
 if(!b||b.disabled || ['task','taskStack'].includes(b.dataset.act)&&Date.now()<ignoreTaskUntil)return;
 act(b.dataset.act,b.dataset.val,b).catch(feedback);
});
window.restoredPhoto=async function(){await refresh();toast('原图已导入作品集');};
let pendingReference=null, previousSelection=null, composition=null;
function availableReference(ref){
 if(page!=='create'||modal||!draft.refs.includes(ref))return false;
 if(!isMac())return draft.refs.length<=allowedRefs();
 const op=macOp(),model=macModel();
 if(!model?.ready||!capability(model,op))return false;
 return op==='inpaint'?ref===draft.macBase:op==='img2img'&&draft.refs.length<=allowedRefs();
}
function endReferencePick(){
 pendingReference=null;
 for(const thumb of document.querySelectorAll('.ref-pick'))thumb.classList.remove('ref-pick');
}
function showReferenceIssue(){
 const note=$('#referenceIssue');
 if(!note)return;
 const issue=referenceIssue();
 note.textContent=issue;
 note.hidden=!issue;
}
function referenceIssue(){
 if(draft.promptRefs?.some(x=>!draft.refs.includes(x.ref)))return '提示词中有已删除的参考图，请删掉失效标记或重新插入引用。';
 if(isMac()&&macOp()==='inpaint'&&draft.promptRefs?.some(x=>x.ref!==draft.macBase))
  return '局部编辑只能引用正在修复的底图，请修改其他图片引用。';
 return '';
}
function activeReferencePick(field){
 return pendingReference&&field===pendingReference.field&&document.activeElement===field&&
  field.value[pendingReference.at]==='@'&&field.selectionStart===pendingReference.at+1&&field.selectionEnd===field.selectionStart;
}
function beginReferencePick(field,at){
 if(!field.isConnected||document.activeElement!==field||field.selectionStart!==at+1||
    field.selectionEnd!==at+1||!standaloneAt(field.value,at))return;
 const references=Array.from(document.querySelectorAll('.refstrip .ref-preview')).filter(x=>availableReference(x.dataset.val));
 if(!references.length){toast('请先添加可引用的参考图');return;}
 pendingReference={field,at};
 for(const thumb of references)thumb.classList.add('ref-pick');
}
function insertPickedReference(ref){
 if(!pendingReference||!availableReference(ref)||!activeReferencePick(pendingReference.field))return false;
 const {field,at}=pendingReference;
 const result=insertReference(field.value,draft.promptRefs,at,ref,draft.refs,draft.source);
 draft.prompt=result.prompt;draft.promptRefs=result.promptRefs;
 endReferencePick();
 field.value=draft.prompt;
 field.focus({preventScroll:true});
 field.setSelectionRange(result.caret,result.caret);
 $('#chars').textContent=draft.prompt.length+' 字';
 showReferenceIssue();saveDraft();
 return true;
}
window.setReducedMotion=function(value){document.documentElement.classList.toggle('reduce-motion',value);};
document.addEventListener('pointerdown',event=>{
 const picker=$('.photo-model-picker[open]');
 if(picker&&!picker.contains(event.target))picker.open=false;
 if(event.target.closest('.ref-pick')&&pendingReference&&activeReferencePick(pendingReference.field))event.preventDefault();
 else if(pendingReference&&!event.target.closest('#prompt'))endReferencePick();
});
document.addEventListener('mousedown',event=>{
 if(event.target.closest('.ref-pick')&&pendingReference)event.preventDefault();
});
document.addEventListener('selectionchange',()=>{
 if(pendingReference&&!activeReferencePick(pendingReference.field))endReferencePick();
});
document.addEventListener('focusout',event=>{
 if(event.target.id==='prompt'){endReferencePick();composition=null;previousSelection=null;}
});
document.addEventListener('beforeinput',event=>{
 if(event.target.id!=='prompt')return;
 const field=event.target;
 previousSelection={field,range:[field.selectionStart,field.selectionEnd]};
 endReferencePick();
});
document.addEventListener('compositionstart',event=>{
 if(event.target.id!=='prompt')return;
 endReferencePick();
 composition={field:event.target,prompt:event.target.value,selection:[event.target.selectionStart,event.target.selectionEnd]};
});
document.addEventListener('compositionend',event=>{
 const current=composition;
 composition=null;
 if(!current||current.field!==event.target||event.data!=='@')return;
 requestAnimationFrame(()=>{
  if(composition||current.field.value!==draft.prompt)return;
  const edit=promptEdit(current.prompt,current.field.value,current.selection);
  if(edit.text==='@')beginReferencePick(current.field,edit.start);
 });
});
document.addEventListener('input',event=>{
 const field=event.target;
 if(field.id==='prompt'){
  const edit=promptEdit(draft.prompt,field.value,previousSelection?.field===field?previousSelection.range:null);
  previousSelection=null;
  endReferencePick();
  draft.promptRefs=shiftReferences(draft.promptRefs,edit);
  draft.prompt=field.value;
  $('#chars').textContent=draft.prompt.length+' 字';
  showReferenceIssue();saveDraft();
  if(!event.isComposing&&!composition&&event.inputType==='insertText'&&edit.text==='@')beginReferencePick(field,edit.start);
 }else if(field.id==='ratioWidth'||field.id==='ratioHeight'){
  draft[field.id]=field.value;
  const data=body();
  $('#sizeHint').textContent=sizeHint(data);$('#ratioError').hidden=!!data.size;saveDraft();
 }else if(modal==='params'&&isMac()&&field.matches(macDraftNumbers)){
  storeMacDraftNumber(field);
 }else if(modal==='params'&&isMac()&&field.dataset.macOption){
  const opts=draft.macOptions??={};
  const key=field.dataset.macOption;
  if(field.value==='')delete opts[key];
  else opts[key]=field.value;
  saveDraft();
 }else if(field.id==='maskBrush'&&maskEdit){maskEdit.diameter=Number(field.value);}
 else if(field.id==='editPrompt'&&photoEdit){photoEdit.prompt=field.value;}
});
document.addEventListener('change',event=>{
 const t=event.target,a=t.dataset.change;
 if(modal==='params'&&isMac()&&t.matches(macDraftNumbers))storeMacDraftNumber(t,true);
 if(modal==='upscale'&&(t.id==='upscaleEdge'||t.matches('input[type=number][data-mac-option]')))macInputNumber(t,true);
 if(a==='macLowRAM'){
  (draft.macOptions??={}).lowRAM=t.checked;saveDraft();
 }else if(a){draft[a]=t.checked;redraw();}
 if(t.id==='layerMax'){draft.layerMax=Math.min(100,Math.max(2,Number(t.value)||2));redraw();}
 if(modal==='params'&&t.dataset.macLora){
  const cap=capability(macModel(),macOp()),item=cap?.loras?.find(l=>l.id===t.dataset.macLora);
  if(!item)return;
  const loras=currentMacLoras().filter(l=>l.id!==item.id);
  const field=t.closest('.mac-lora').querySelector('[data-mac-strength]');
  const strength=macInputNumber(field,true)??1;
  if(t.checked){
   if(loras.length>=16){t.checked=false;toast('最多选择 16 个 LoRA');return;}
   loras.push({id:item.id,strength});
  }
  rememberMacLoraStrength(item.id,strength);
  (draft.macOptions??={}).loras=loras;
  saveDraft();
 }
 if(t.id==='upscaleScale'){
  upscaleSource.scale=t.value;$('#upscaleShort').hidden=t.value!=='edge';
 }
 if(t.id==='upscaleModel')upscaleSource.model=t.value;
});
document.addEventListener('keydown',event=>{
 if(event.key==='Escape'&&pendingReference)endReferencePick();
 else if(event.key==='Escape'&&modal)studioBack();
});
async function boot(){draw();try{const data=await api('init');config=Object.assign(config,data);if(data.macServices)setMacServices(data.macServices);draft=Object.assign({},initial,data.draft,{count:1});if(draft.mode==='text')draft.refs=[];delete draft.mode;if(!data.draft.defaultsV2){draft.watermark=false;draft.defaultsV2=true;saveDraft();}relabelDraft();
 macStartupChecking=!!config.mac?.endpoint;scheduleMacRefresh(0);draw();
 await window.receiveReferences(0);await refresh();dailySync();
 prepareMacDimensions().catch(()=>{});}catch(err){toast(err.message);}}
setInterval(()=>{if(!document.hidden&&(jobs.some(j=>active(j.state))||page==='tasks'||page==='gallery'))refresh();},2000);
document.addEventListener('visibilitychange',()=>{if(!document.hidden){refresh();dailySync();scheduleSeen();scheduleMacRefresh(0);}else{clearTimeout(macRefreshTimer);endReferencePick();if(draftTimer)api('draft',draft).catch(()=>{});}});
setInterval(()=>{if(!document.hidden)dailySync();},60000);
boot();
