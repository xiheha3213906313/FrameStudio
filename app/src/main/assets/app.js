'use strict';
const $=s=>document.querySelector(s);
const e=v=>String(v==null?'':v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const paths={
 create:'M4 16.5V20h3.5L20 7.5l-3.5-3.5z M14.8 5.8l3.5 3.5',
 gallery:'M4 5h16v14H4z M4 16l5-5 4 4 3-3 4 4 M15 9h.01',
 tasks:'M7 5h13M7 12h13M7 19h13M3 5h.02M3 12h.02M3 19h.02',
 settings:'M4 7h7m5 0h4M4 17h2m5 0h9 M14 4v6m-5 4v6',
 arrow:'M5 12h14m-6-6 6 6-6 6',upRight:'M6 18L18 6M7 6h11v11',
 down:'M6 9l6 6 6-6',next:'M9 5l7 7-7 7',spark:'M12 3l1.9 5.6L19 11l-5.1 2.4L12 19l-2-5.6L5 11l5-2.4z M19 15v5m-2.5-2.5h5',
 book:'M12 7C9 5 6 5 3 5v13c3 0 6 0 9 2 3-2 6-2 9-2V5c-3 0-6 0-9 2zm0 0v13',
 close:'M6 6l12 12M18 6L6 18',plus:'M12 5v14M5 12h14',check:'M4 12l5 5L20 6',
 sync:'M20 8a8 8 0 0 0-14-2L3 9m0-5v5h5m-4 7a8 8 0 0 0 14 2l3-3m0 5v-5h-5',
 save:'M12 3v13m-5-5 5 5 5-5M4 17v4h16v-4',share:'M12 15V3m-4 4 4-4 4 4M6 11H4v10h16V11h-2',
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
const stateNames={queued:'排队中',sending:'生成中',fetching:'保存中',done:'已完成',partial:'部分成功',failed:'生成失败',not_sent:'请求未提交',uncertain:'响应中断',recovered:'已导入原图',download_error:'待补下载',cancelled:'已取消'};
const active=s=>['queued','sending','fetching'].includes(s);
const initial={mode:'text',prompt:'',refs:[],model:PROFILES[0].id,level:'2K',ratio:RATIOS[0],count:1,watermark:false,format:'auto',transparent:false,layers:false,layerMax:10};
let draft=Object.assign({},initial),config={models:[],custom:[],hasKey:false,synced:0},jobs=[],page='create',modal='',context='',previousPhotoTask='',sampleIndex=0,taskFilter='all',syncing=false,submitting=false,lastSyncError='',toastTimer,draftTimer,seq=0,refreshing=false;
const calls=new Map();
let jobRevision=-1;
let galleryModel='',taskModel='',selecting=false;
const pickedTasks=new Set();
let autoSyncAt=0;
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
 const custom=config.custom.find(x=>x.id===id),published=modelProfile(id,[],config.models);
 return custom?custom.name:published!==GENERIC?published.name:id||'选择模型';
}
function allowedRefs(){const p=profile();return draft.layers?1:p.refs;}
function normalize(){
 const p=profile();
 if(!p.levels.includes(draft.level))draft.level=p.levels.includes('2K')?'2K':p.levels[0]||'';
 if(!RATIOS.includes(draft.ratio))draft.ratio=RATIOS[0];
 if(!p.layers||draft.mode!=='ref')draft.layers=false;
 if(!p.format)draft.format='auto';
 if(!p.rgba || draft.layers || (p.family!=='55' && (draft.mode!=='ref' || draft.refs.length!==1)))draft.transparent=false;
 if(draft.transparent)draft.format='png';
 if(draft.layers)draft.count=1;
 draft.count=Math.max(1,Math.min(Number(draft.count)||1,p.batch?15-(draft.mode==='ref'?draft.refs.length:0):15));
 draft.layerMax=Math.max(2,Math.min(Number(draft.layerMax)||10,p.layers||100));
}
function body(){
 const p=profile(),size=requestSize(p,draft.level,draft.ratio);
 return {prompt:draft.prompt.trim(),model:draft.model,name:nameFor(draft.model),size:size,level:draft.level,ratio:draft.ratio,count:draft.count,stream:!!p.batch,separate:!p.batch&&!draft.layers,
 refs:draft.mode==='ref'?draft.refs.slice():[],watermark:draft.watermark,format:draft.format,transparent:draft.transparent,layers:draft.layers,
 layerMax:draft.layers?(p.family==='55'?draft.layerMax:p.layers):0,rate:{family:p.family,low:p.low,high:p.high,input:p.input,free:p.free}};
}
function date(t){return new Date(t).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false});}
function nav(){
 const counts={tasks:jobs.length,gallery:photos().filter(x=>!x.p.seen).length};
 $('#nav').innerHTML=navItems.map(([key,label])=>{
  const count=counts[key]||0;
  const badge=count?'<span class="nav-count">'+(count>99?'99+':count)+'</span>':'';
  return button('nav','<span class="navicon">'+icon(key)+badge+'</span>'+label,page===key?'active':'',key,
   'aria-label="'+label+(count?' '+count+(key==='gallery'?' 张未查看':' 个任务'):'')+'" '+(page===key?'aria-current="page"':''));
 }).join('');
}
function title(t,right){return '<div class="title"><h1>'+e(t)+'</h1>'+(right||'')+'</div>';}
function hero(){
 const s=samples[sampleIndex];
 return '<div class="hero"><img src="art/'+s.img+'.jpg" alt="'+e(s.title)+'"><div class="hero-top"><span class="feature-label">'+icon('book')+'提示词灵感</span><div class="dots">'+samples.map((x,i)=>button('slide','',i===sampleIndex?'sel':'',i,'aria-label="查看'+e(x.title)+'"')).join('')+'</div></div><div class="hero-bottom"><div><h2>'+e(s.title)+'</h2><p>'+e(s.style)+'</p></div>'+button('sample',icon('upRight'),'circle',sampleIndex,'aria-label="查看并使用提示词"')+'</div></div>';
}
function refstrip(){
 return '<div class="refstrip">'+draft.refs.map((r,i)=>'<div class="refitem">'+button('refPhoto','<img src="refs/'+e(r)+'.thumb" alt="参考图 '+(i+1)+'">','ref-preview',r)+button('unref','×','ref-remove',i,'aria-label="移除参考图"')+'</div>').join('')+(draft.refs.length<allowedRefs()?button('pick',icon('plus')+'参考图','refslot'):'')+'</div>';
}
function createPage(){
 normalize();const b=body(),total=cost(b,null);
 return '<div class="section-title"><h2>开始创作</h2>'+button('samples',icon('book')+' 灵感库','textbtn small')+'</div>'+
 '<div class="tabs">'+button('mode',icon('create')+'文生图',draft.mode==='text'?'active':'','text')+button('mode',icon('gallery')+'参考图生成',draft.mode==='ref'?'active':'','ref')+'</div>'+
 (draft.mode==='ref'?refstrip():'')+'<div class="workarea"><div class="promptbox"><div class="spread"><label for="prompt">画面描述</label><span>'+button('paste', '粘贴','textbtn','prompt')+' <span class="counter" id="chars">'+draft.prompt.length+' 字</span></span></div><textarea id="prompt" placeholder="写下主体、场景、光线，或你想修改的细节…">'+e(draft.prompt)+'</textarea></div>'+
 '<div class="right"><div class="gridparams">'+button('models','<span><small>生成模型</small><b>'+e(nameFor(draft.model))+'</b></span>'+icon('down'),'param')+
 button('params','<span><small>画布设置</small><b>'+e(b.layers?'图层拆分':b.size?b.ratio+' · '+b.level:'模型默认')+'</b></span>'+icon('down'),'param')+'</div>'+
 button('generate','<span class="inline">'+icon('spark')+'生成图片</span><span class="end">'+(total?'预计 '+money(total.total):'价格待核对')+'</span>','primary generate','',submitting?'disabled':'')+
 '<div class="more">'+button('params',icon('settings')+(b.layers?'图层上限 '+b.layerMax:'参数 · '+(b.count>1?'最多 ':'')+b.count+' 张'))+button('cost',icon('wallet')+'费用明细')+'</div></div></div>';
}
function photos(){return jobs.reduce((all,j)=>all.concat((j.result.images||[]).filter(p=>p.ready).map(p=>({p:p,j:j}))),[]);}
function empty(ic,t,description){return '<div class="empty">'+icon(ic)+'<h3>'+e(t)+'</h3><p class="sub">'+e(description)+'</p>'+button('nav','去创作 '+icon('arrow'),'secondary','create')+'</div>';}
function modelFilters(items,selected,action) {
 const models=new Map(items.map(j=>[j.body.model,j.body.name||nameFor(j.body.model)]));
 if(!models.size)return '';
 return '<div class="filter-row">'+[['','全部模型'],...models].map(([id,name])=>button(action,e(name),'choice'+(id===selected?' selected':''),id)).join('')+'</div>';
}
function galleryPage(){
 const all=photos(),list=all.filter(x=>!galleryModel||x.j.body.model===galleryModel);
 return title('作品集','<span class="micro">'+list.length+' 张原图</span>')+modelFilters(all.map(x=>x.j),galleryModel,'galleryModel')+
 (list.length?'<div class="gallery">'+list.map(x=>button('photo',(!x.p.seen?'<span class="unseen-dot" aria-label="未查看"></span>':'')+
 '<img loading="lazy" src="images/'+e(x.p.file)+'.thumb" alt="作品预览"><b>'+e(x.j.body.prompt||'图层拆分')+'</b><small class="sub">'+e(x.p.size)+'</small>','photo',x.p.file)).join('')+'</div>':
 empty('gallery',all.length?'这个模型暂无作品':'作品集还是空的','生成成功的图片会自动保存在这里。'));
}
const failedStates=['failed','not_sent'];
const issueStates=[...failedStates,'uncertain','download_error','partial'];
function taskCard(j){
 const image=(j.result.images||[]).find(i=>i.ready),chosen=pickedTasks.has(j.id);
 const phase=j.result.phase;
 const status=j.state==='sending'?(phase==='connecting'?'连接中':phase==='uploading'?'上传中':'等待方舟出图'):stateNames[j.state];
 return button('task','<span class="tile">'+(image?'<img src="images/'+e(image.file)+'.thumb" alt="">':icon('frame'))+'</span>'+
 '<span class="tasktext"><b>'+e(j.body.prompt||'图层拆分')+'</b><small class="sub">'+e(j.body.name)+' · '+e(date(j.created))+'</small><br><span class="badge '+(issueStates.includes(j.state)?'warn':'')+'">'+e(status)+'</span></span>'+
 icon(selecting?(chosen?'check':'frame'):'next'),'taskcard'+(chosen?' selected':''),j.id,selecting?(active(j.state)?'disabled':'aria-pressed="'+chosen+'"'):'');
}
function filteredTasks(){
 return jobs.filter(j=>(!taskModel||j.body.model===taskModel)&&(taskFilter==='all'||(taskFilter==='active'?active(j.state):issueStates.includes(j.state))));
}
function tasksPage(){
 const visible=filteredTasks();
 const actions=selecting?'<div class="task-actions">'+button('pickFailed','全选失败','secondary')+
 button('deleteBatchAsk','删除 '+pickedTasks.size+' 项','dangerbtn','',pickedTasks.size?'':'disabled')+button('multi','取消','textbtn')+'</div>':'';
 return title('生成任务',jobs.length&&!selecting?button('multi','多选','textbtn'):'')+
 modelFilters(jobs,taskModel,'taskModel')+
 '<div class="seg">'+[['all','全部'],['active','处理中'],['issues','需关注']].map(x=>button('filter',x[1],taskFilter===x[0]?'active':'',x[0])).join('')+'</div>'+actions+
 '<div class="list">'+(visible.length?visible.map(taskCard).join(''):empty('tasks',jobs.length?'当前筛选下没有任务':'还没有生成任务','任务状态、原图和费用明细会在这里更新。'))+'</div>';
}
function settingsPage(){return title('设置')+'<div class="section-title"><h2>方舟连接</h2></div>'+row('key','API Key',config.hasKey?'密钥已加密保存在本机':'添加个人 API Key，连接北京地域')+
 row('models','图片模型',config.synced?'上次同步 '+date(config.synced)+' · '+config.models.length+' 个账号可见模型 · 每日自动同步':'尚未同步 · 当前使用内置目录')+
 row('custom','自定义推理接入点','添加 Endpoint ID 或新的 Model ID')+'<div class="section-title"><h2>使用与计费</h2></div>'+
 row('ledger','本机费用统计','按任务提交时的单价快照汇总')+row('doc','官方模型价格','查看火山方舟最新公布的计费规则','docs/model-pricing')+
 row('doc','错误码手册','按错误码排查权限、配额、请求参数等问题','docs/error-codes')+row('doc','图片生成指南','查看模型能力、参考图限制和调用示例','docs/image-generation-tutorial')+
 '<div class="panel"><h3>映序 · Frame Studio</h3><p class="sub">版本 1.1 · 火山方舟北京地域，标准按量 API。图片和任务记录保存在此设备。</p></div>';}
function draw(){nav();$('#app').innerHTML=({create:createPage,gallery:galleryPage,tasks:tasksPage,settings:settingsPage}[page])();}
function navigate(p){if(!navItems.some(n=>n[0]===p))return;close();if(p!=='tasks'){selecting=false;pickedTasks.clear();}page=p;draw();window.scrollTo(0,0);if(p==='tasks'||p==='gallery')refresh();}
window.navigate=navigate;
function show(type,val){modal=type;context=val||'';renderModal();$('#overlay').hidden=false;$('#sheet').scrollTop=0;document.body.style.overflow='hidden';}
function close(){modal='';context='';$('#overlay').hidden=true;document.body.style.overflow='';}
function entry(k,v){return '<div class="entry"><span>'+e(k)+'</span><span>'+e(v)+'</span></div>';}
function sel(a,list,val){return '<div class="choices">'+list.map(x=>button(a,e(x),x===val?'choice selected':'choice',x)).join('')+'</div>';}
function check(a,label,value){return '<label class="entry"><span>'+e(label)+'</span><input class="switch" type="checkbox" data-change="'+a+'" '+(value?'checked':'')+'></label>';}
function modelSheet(){
 const list=options();
 return '<p class="sub">'+(config.synced?'账号可见模型 · '+date(config.synced)+' 同步 · 每日自动同步' :'内置模型目录。配置密钥后可同步账号可见模型。')+'</p>'+
 button('sync',icon('sync')+(syncing?'同步中…':'同步账号模型'),'secondary wide','',syncing?'disabled':'')+
 (lastSyncError?'<p class="problem">'+e(lastSyncError)+'</p>':'')+
 list.map(m=>row('chooseModel',nameFor(m.id),(m.status==='Retiring'?'即将下线 · ':'')+m.id+' · '+priceLabel(modelProfile(m.id,config.custom,config.models)),m.id,m.id===draft.model)).join('')+
 (!list.length?'<p class="notice">该密钥没有返回图片模型。请检查项目权限、开通模型，或添加已授权的接入点。</p>':'')+
 button('custom',icon('plus')+'自定义接入点','secondary wide');
}
function paramSheet(){
 const p=profile(),b=body();
 let controls=p.levels.length?group('分辨率',sel('level',p.levels,draft.level)):'<p class="notice">未知模型使用服务端默认尺寸。</p>';
 if(!draft.layers && p.levels.length)controls+=group('画面比例',sel('ratio',RATIOS,draft.ratio))+
 '<p class="sub">'+(draft.ratio===RATIOS[0]?'由模型按提示词选择比例。':e(b.size)+' 像素')+'</p>';
 if(!draft.layers)controls+='<div class="entry"><span>生成张数</span><div class="countstep">'+
 button('step','−','',-1,'aria-label="减少张数" '+(draft.count<=1?'disabled':''))+
 '<output>'+draft.count+'</output>'+button('step','+','',1,'aria-label="增加张数" '+(draft.count>=(p.batch?15-b.refs.length:15)?'disabled':''))+'</div></div>'+
 (draft.count>1?'<p class="sub">'+(p.batch?'关联组图，实际张数可能少于所设上限。':'将排队发送 '+draft.count+' 个独立单图请求，费用按每个请求计算。')+'</p>':'');
 if(p.layers && draft.mode==='ref')controls+=check('layers','将单张参考图拆分为图层',draft.layers);
 if(draft.layers && p.family==='55')controls+=group('最大输出张数（含底图，2–100）','<input type="number" id="layerMax" min="2" max="100" value="'+draft.layerMax+'">');
 if(p.format && !draft.layers)controls+=group('输出格式','<div class="choices">'+[['auto','默认'],['jpeg','JPEG'],['png','PNG']].map(x=>button('format',x[1],'choice'+(draft.format===x[0]?' selected':''),x[0])).join('')+'</div>');
 if(p.rgba && !draft.layers && (p.family==='55'||(draft.mode==='ref'&&draft.refs.length===1)))controls+=check('transparent','透明背景 PNG',draft.transparent)+(p.family==='55'?'':'<p class="sub">此模型需要一张带透明通道的 PNG 参考图。</p>');
 return controls+check('watermark','显示 AI 生成水印',draft.watermark)+button('apply','完成','primary wide');
}
function lines(b,r){const c=cost(b,r);return entry('参考图费用',c?money(c.input):'待核价')+entry(r?'输出图片':'最多输出',c?c.count+' 张 · '+money(c.output):'待核价')+entry(r?'按用量估算合计':'预计最高费用',c?money(c.total):'待核价');}
function taskSheet(j){
 if(!j)return '<p>记录已删除。</p>';
 const b=j.body,r=j.result,done=['done','partial','download_error','fetching'].includes(j.state),images=r.images||[];
 let info=entry('状态',stateNames[j.state])+entry('创建时间',date(j.created))+entry('模型',b.model)+entry('请求尺寸',b.layers?b.level:b.size||'默认');
 if(r.trace)info+=entry('方舟 Request ID',r.trace);
 if(r.httpStatus)info+=entry('HTTP 状态',r.httpStatus);
 if(j.state==='not_sent')info+='<p class="notice">本次生成请求体尚未发送，因此这条任务没有提交生图。</p>';
 if(j.state==='uncertain'||r.incomplete){
  const stage=r.phase==='awaiting'||r.phase==='response'?'手机已发送请求体，方舟可能已出图并计费。':
   r.phase==='uploading'?'上传期间连接中断，无法判断方舟是否收到完整请求。':'记录缺少完整的发送或响应阶段，请查方舟账单核对实际用量。';
  info+=entry('客户端请求编号',j.id)+'<p class="problem">'+stage+' 方舟图片 API 在原响应中返回图片。可按时间和模型核对账单，已有日志投递时可用请求编号查 TLS 日志。</p>'+
   '<div class="buttons">'+button('doc','查看方舟账单','secondary','usage')+button('doc','单请求对账指引','secondary','request-usage')+
   button('doc','查看日志指引','secondary','docs/log-collection')+button('doc','TOS 转存指引','secondary','tos')+'</div>';
  if(['uncertain','partial','recovered'].includes(j.state))info+='<div class="buttons">'+button('restorePhoto','导入找回的原图','secondary',j.id)+button('restoreLink','下载找回的链接','secondary',j.id)+'</div>';
 }
 if(j.state==='download_error')info+='<p class="problem">方舟已返回图片。本机可以在临时链接有效期内重新下载，补下载不会再次生成。</p>';
 if(r.message)info+='<p class="problem">'+e(r.message)+'</p>';
 (r.errors||[]).concat(images.filter(p=>p.downloadError).map(p=>p.downloadError)).forEach(err=>{info+='<p class="problem">'+e(err)+'</p>';});
 if(r.incomplete)info+=entry('费用','仅收到部分响应，请以方舟账单为准');else if(done||r.usage)info+=lines(b,r);else if(['cancelled','not_sent'].includes(j.state))info+=entry('费用','¥0.00');else info+=entry('提交前估算',money((cost(b,null)||{}).total));
 info+='<p class="summary">'+e(b.prompt||'按图片内容自动拆分图层')+'</p><div class="thumbs">'+images.filter(p=>p.ready).map(p=>button('photo','<img src="images/'+e(p.file)+'.thumb" alt="查看原图">','',p.file)).join('')+'</div><div class="buttons">';
 if(j.state==='queued')info+=button('cancel','取消排队','secondary',j.id);
 if(j.state==='download_error')info+=button('recover','补下载原图','primary',j.id);
 if(!active(j.state))info+=button('reuse','复用参数','secondary',j.id)+button('deleteAsk','删除记录','dangerbtn',j.id);
 return info+'</div>';
}
function ledger(){
 let sum=0,pending=0,confirmed=0;
 jobs.forEach(j=>{if(j.result.incomplete){pending++;return;}if(['done','partial','fetching','download_error'].includes(j.state)||j.result.usage){const c=cost(j.body,j.result);if(c){sum+=c.total;confirmed++;}else pending++;}else if(['uncertain','recovered'].includes(j.state))pending++;});
 return entry('已取得用量的任务',confirmed+' 个')+entry('按本机费率估算',money(sum))+entry('待确认结果或价格',pending+' 个')+'<p class="sub">汇总当前保留的记录。免费额度、折扣、套餐及账单调整请以方舟账单为准。</p>';
}
function modalContent(){
 switch(modal){
 case 'models':return ['选择模型',modelSheet()];
 case 'params':return ['画布与生成参数',paramSheet()];
 case 'cost':return ['费用明细',entry('模型',nameFor(draft.model))+lines(body(),null)+'<p class="sub">按北京地域标准按量价格估算。分档模型根据实际输出像素计算，账单可能受折扣、套餐影响。费率核对日期为 2026-11-18。</p>'+button('doc','查看官方定价 '+icon('upRight'),'textbtn','docs/model-pricing')];
 case 'samples':return ['提示词灵感库',hero()+'<div class="inspiration-layout">'+samples.map((s,i)=>row('sample',s.title,s.style,i)).join('')+'</div>'];
 case 'sample':{const s=samples[Number(context)||0];return [s.title,'<div class="sample"><img src="art/'+s.img+'.jpg" alt="'+e(s.title)+'"><p class="sub">'+e(s.style)+'</p><p>'+e(s.prompt)+'</p></div>'+button('useSample','使用这段提示词','primary wide',context)];}
 case 'key':return ['连接火山方舟','<p class="sub">填写北京地域标准按量 API Key。密钥由 Android Keystore 加密保存，请先在方舟开通所需图片模型。</p>'+group(config.hasKey?'更换 API Key':'API Key','<input id="secret" type="password" autocomplete="off" placeholder="'+(config.hasKey?'输入新的密钥':'粘贴 API Key')+'">')+'<p class="inlineerr" id="formError"></p>'+button('paste','粘贴密钥','textbtn','secret')+button('saveKey','保存并同步模型','primary wide')+'<div class="buttons">'+button('doc','获取 API Key '+icon('upRight'),'secondary','apikey')+(config.hasKey?button('forgetAsk','移除密钥','dangerbtn'):'')+'</div>'];
 case 'forgetAsk':return ['移除本机密钥','<p>下次生成图片时需要重新连接方舟。</p>'+button('forget','移除密钥','dangerbtn wide')];
 case 'custom':return ['自定义接入点',config.custom.map((c,i)=>row('removeCustom',c.name,'移除 '+c.id,i)).join('')+group('Model ID 或 Endpoint ID','<input id="customId" type="text" placeholder="ep-…">')+group('显示名称','<input id="customName" type="text" placeholder="例如：我的设计接入点">')+group('对应基础模型','<select id="family">'+['<option value="generic">其他模型 · 基础图片生成</option>'].concat(PROFILES.map(p=>'<option value="'+p.family+'">'+e(p.name)+'</option>')).join('')+'</select>')+group('其他模型输出单价（元/张，可留空）','<input id="customFee" type="number" step=".01" min="0" placeholder="未知时显示待核价">')+'<div class="sub">已知模型按对应官方规则估算。自填单价仅适用于参考图免费、输出按张统一收费的接入点。</div><p id="formError" class="inlineerr"></p>'+button('saveCustom','保存接入点','primary wide')];
 case 'task':return ['任务详情',taskSheet(jobs.find(j=>j.id===context))];
 case 'deleteAsk':return ['删除这条任务？','<p>将删除本机任务记录及其作品集原图。已导出至系统相册的副本可继续使用。</p>'+button('delete','删除任务与原图','dangerbtn wide',context)];
 case 'removeCustom':return ['移除接入点？','<p>将从本机模型选择列表中移除此接入点。</p>'+button('doRemove','移除接入点','dangerbtn wide',context)];
 case 'deleteBatchAsk':return ['删除 '+pickedTasks.size+' 条任务？','<p>将删除选中任务和对应的作品集原图。已保存到系统相册的副本会保留。</p>'+button('deleteBatch','确认删除','dangerbtn wide')];
 case 'restoreLink':return ['下载找回的链接','<p class="sub">粘贴 TLS 日志或 TOS 控制台里的原图链接，下载不会再次提交生图。</p>'+
 group('原图链接','<input id="recoveryUrl" type="text" inputmode="url" placeholder="https://…">')+
 '<p id="formError" class="inlineerr"></p>'+button('paste','粘贴链接','textbtn','recoveryUrl')+button('applyRestoreLink','开始下载','primary wide',context)];
 case 'refPhoto':return ['参考图预览',imageViewer('refs/'+context,'参考图')];
 case 'photo':{
  const it=photos().find(x=>x.p.file===context);
  if(!it)return ['作品预览','原图不可用'];
  return ['作品预览',imageViewer('images/'+context,'生成原图')+entry('尺寸',it.p.size)+entry('模型',it.j.body.name)+
   (it.p.name?entry('图层',it.p.name):'')+
   '<div class="buttons">'+button('editPhoto',icon('create')+'编辑图片','primary',context)+button('reuse',icon('sync')+'复用参数','secondary',it.j.id)+
   button('save',icon('save')+'保存到相册','secondary',context)+button('share',icon('share')+'分享原图','secondary',context)+'</div>'+
   '<details class="preview-meta"><summary>查看提示词和生成参数</summary>'+photoParams(it.j.body)+'</details>'];
 }
 case 'editPhoto':return ['编辑图片',group('编辑提示词','<textarea id="editPrompt" class="edit-prompt" placeholder="描述要修改的内容"></textarea>')+
 button('paste','粘贴提示词','textbtn','editPrompt')+button('applyEdit','带入创作台','primary wide',context)];
 case 'ledger':return ['本机费用统计',ledger()];
 }
 return ['映序',''];
}
function photoParams(b){
 return '<p class="summary">'+e(b.prompt||'按图片内容自动拆分图层')+'</p>'+
 entry('模型 ID',b.model)+entry('请求尺寸',b.layers?b.level:b.size||'模型默认')+
 entry('画面比例',b.layers?'参考图比例':b.ratio||'模型默认')+
 entry('生成方式',b.layers?'图层拆分':b.refs.length?'参考图生成':'文生图')+
 entry('参考图',b.refs.length+' 张')+entry('输出张数'+(b.layers?'上限':''),b.layers?b.layerMax:b.count)+
 entry('输出格式',b.format==='auto'||!b.format?'模型默认':b.format.toUpperCase())+
 entry('透明背景',b.transparent?'开启':'关闭')+entry('水印',b.watermark?'开启':'关闭');
}
function renderModal(){
 if(!modal)return;
 const m=modalContent();$('#sheet').innerHTML='<div class="sheethead"><h2>'+e(m[0])+'</h2>'+button('close',icon('close'),'circle','','aria-label="关闭"')+'</div>'+m[1];
 setupViewer($('#sheet .viewer'));
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
  const existing=new Set(photos().map(x=>x.p.file));
  jobRevision=fresh.revision;
  jobs=fresh.rows||[];
  const images=photos();
  if(galleryModel&&!images.some(x=>x.j.body.model===galleryModel))galleryModel='';
  if(taskModel&&!jobs.some(j=>j.body.model===taskModel))taskModel='';
  for(const id of pickedTasks)if(!jobs.some(j=>j.id===id&&!active(j.state)))pickedTasks.delete(id);
  if(page==='tasks'&&!firstLoad&&images.some(x=>!x.p.imported&&!existing.has(x.p.file)&&!x.p.seen)){
   galleryModel='';selecting=false;pickedTasks.clear();navigate('gallery');return;
  }
  if(page==='tasks'||page==='gallery')draw();else nav();
  if(['task','ledger'].includes(modal))renderModal();
 } catch(err){toast(err.message);}
 finally{refreshing=false;}
}
async function markViewed(file) {
 const found=photos().find(x=>x.p.file===file);
 if(!found||found.p.seen)return;
 await api('readPhoto',{file});
 for(const x of photos())if(x.p.file===file)x.p.seen=true;
 if(page==='gallery')draw();else nav();
}
function updateModels(result) {
 if(config.synced===result.synced)return;
 config.models=result.models;config.synced=result.synced;
 const list=options();
 if(!list.some(m=>m.id===draft.model))draft.model=list.length?list[0].id:'';
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
function changeModel(id){draft.model=id;draft.layers=false;draft.transparent=false;draft.format='auto';normalize();close();draw();saveDraft();}
window.addReferences=function(names,failed){
 draft.mode='ref';const n=Math.max(0,allowedRefs()-draft.refs.length);draft.refs=draft.refs.concat(names.slice(0,n));normalize();redraw();
 if(failed||names.length>n)toast(failed?'部分图片格式或宽高比不支持':'已达到当前模型参考图上限');
};
window.receiveReferences=async function(failed){try{const refs=await api('pendingRefs');if(refs.length||failed)window.addReferences(refs,failed);}catch(err){toast(err.message);}};
function idForRequest(){const bytes=new Uint8Array(16);crypto.getRandomValues(bytes);return Array.from(bytes,n=>n.toString(16).padStart(2,'0')).join('');}
async function generate(){
 if(submitting)return;
 normalize();
 if(!draft.prompt.trim()&&!draft.layers){toast('先写下想生成的画面');$('#prompt').focus();return;}
 if(!config.hasKey){show('key');return;}
 if(!draft.model || !options().some(o=>o.id===draft.model)){show('models');return;}
 if(draft.mode==='ref' && (!draft.refs.length || draft.refs.length>allowedRefs())){toast('请选取 1 至 '+allowedRefs()+' 张参考图');return;}
 submitting=true;draw();
 try{await api('draft',draft);const b=body();await api('submit',{id:idForRequest(),body:b});taskFilter='all';await refresh();toast('任务已加入队列');}
 finally{submitting=false;if(page==='create')draw();}
}
function studioBack(){
 if(modal){if(modal==='restoreLink'){show('task',context);return true;}if(modal==='editPhoto'){show('photo',context);return true;}if(modal==='photo'&&previousPhotoTask){show('task',previousPhotoTask);previousPhotoTask='';}else close();return true;}
 if(page==='tasks'&&selecting){selecting=false;pickedTasks.clear();draw();return true;}
 if(page!=='create'){navigate('create');return true;}return false;
}
window.studioBack=studioBack;
async function act(a,v){
 switch(a){
 case 'nav':navigate(v);break;
 case 'connection':show('key');break;
 case 'close':studioBack();break;
 case 'slide':sampleIndex=Number(v);renderModal();break;
 case 'mode':draft.mode=v;redraw();break;
 case 'useSample':draft.prompt=samples[Number(v)||0].prompt;close();redraw();toast('已填入画面描述');break;
 case 'models':case 'params':case 'key':case 'cost':case 'sample':case 'samples':case 'ledger':case 'deleteAsk':case 'custom':case 'removeCustom':case 'forgetAsk':show(a,v);break;
 case 'photo':previousPhotoTask=modal==='task'?context:'';show('photo',v);await markViewed(v);break;
 case 'refPhoto':case 'editPhoto':case 'restoreLink':case 'deleteBatchAsk':show(a,v);break;
 case 'galleryModel':galleryModel=v;draw();break;
 case 'taskModel':taskModel=v;pickedTasks.clear();draw();break;
 case 'multi':selecting=!selecting;pickedTasks.clear();draw();break;
 case 'task':if(selecting){toggleTask(v);}else show('task',v);break;
 case 'pickFailed':{
  taskFilter='issues';selecting=true;pickedTasks.clear();
  for(const item of filteredTasks())if(failedStates.includes(item.state))pickedTasks.add(item.id);
  draw();if(!pickedTasks.size)toast('这个模型筛选下没有失败任务');break;
 }
 case 'deleteBatch':{
  const removed=await api('deleteMany',{ids:Array.from(pickedTasks)});
  selecting=false;pickedTasks.clear();close();await refresh();draw();toast('已删除 '+removed+' 条任务');break;
 }
 case 'restorePhoto':await api('restorePhoto',{id:v});break;
 case 'applyRestoreLink':
  await api('restoreLink',{id:v,link:$('#recoveryUrl').value});show('task',v);await refresh();break;
 case 'applyEdit':{
  const prompt=$('#editPrompt').value.trim();
  if(!prompt)throw new Error('请描述要修改的内容');
  const item=photos().find(x=>x.p.file===v);
  if(!item)throw new Error('原图不可用');
  const ref=await api('editPhoto',{file:v});
  draft=Object.assign({},initial,item.j.body,{prompt,refs:[ref],mode:'ref',count:1,watermark:false,layers:false,transparent:false});
  navigate('create');redraw();$('#prompt').focus();break;
 }
 case 'paste':{
  const field=document.getElementById(v);if(!field)break;
  const contents=await api('paste');if(!contents)throw new Error('剪贴板里没有文字');
  if(!field.isConnected)break;
  const from=field.selectionStart==null?field.value.length:field.selectionStart;
  const to=field.selectionEnd==null?from:field.selectionEnd;
  field.setRangeText(contents,from,to,'end');field.dispatchEvent(new Event('input',{bubbles:true}));break;
 }
 case 'filter':taskFilter=v;pickedTasks.clear();draw();break;
 case 'level':case 'ratio':case 'format':draft[a]=v;if(a==='format'&&v!=='png')draft.transparent=false;redraw();break;
 case 'step':draft.count+=Number(v);redraw();break;
 case 'apply':close();redraw();break;
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
 case 'doRemove':{const list=config.custom.filter((_,i)=>i!==Number(v));await api('custom',{list:list});config.custom=list;if(!options().some(m=>m.id===draft.model))draft.model=(options()[0]||{}).id||'';show('custom');redraw();break;}
 case 'pick':await api('pick');break;
 case 'unref':draft.refs.splice(Number(v),1);redraw();break;
 case 'doc':await api('open',{page:v});break;
 case 'generate':await generate();break;
 case 'reuse':{const j=jobs.find(x=>x.id===v);if(!j)break;const b=j.body;draft=Object.assign({},initial,b,{refs:b.refs.slice(),mode:b.refs.length?'ref':'text'});navigate('create');redraw();toast('参数已填入，确认后可再次生成');break;}
 case 'save':await api('save',{file:v});toast('已保存到系统相册 · Pictures/映序');break;
 case 'share':await api('share',{file:v});break;
 case 'cancel':if(!await api('cancel',{id:v}))toast('任务已开始处理');await refresh();break;
 case 'recover':await api('recover',{id:v});await refresh();break;
 case 'delete':if(!await api('delete',{id:v}))throw new Error('任务仍在处理中或已删除');close();await refresh();break;
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
  selecting=true;toggleTask(id);
 },480);
});
document.addEventListener('pointermove',event=>{
 if(pressStart&&Math.hypot(event.clientX-pressStart[0],event.clientY-pressStart[1])>12)clearTimeout(pressTimer);
});
for(const type of ['pointerup','pointercancel'])document.addEventListener(type,()=>{clearTimeout(pressTimer);pressStart=null;});
for(const type of ['copy','cut','contextmenu','dragstart'])document.addEventListener(type,event=>event.preventDefault());
document.addEventListener('click',event=>{
 const b=event.target.closest('[data-act]');
 if(!b||b.disabled || b.dataset.act==='task'&&Date.now()<ignoreTaskUntil)return;
 act(b.dataset.act,b.dataset.val).catch(feedback);
});
window.restoredPhoto=async function(){await refresh();toast('原图已导入作品集');};
document.addEventListener('input',event=>{if(event.target.id==='prompt'){draft.prompt=event.target.value;$('#chars').textContent=draft.prompt.length+' 字';saveDraft();}});
document.addEventListener('change',event=>{
 const t=event.target,a=t.dataset.change;
 if(a){draft[a]=t.checked;redraw();}
 if(t.id==='layerMax'){draft.layerMax=Math.min(100,Math.max(2,Number(t.value)||2));redraw();}
});
document.addEventListener('keydown',event=>{if(event.key==='Escape'&&modal)studioBack();});
async function boot(){draw();try{const data=await api('init');config=Object.assign(config,data);draft=Object.assign({},initial,data.draft);if(!data.draft.defaultsV2){draft.watermark=false;draft.defaultsV2=true;saveDraft();}draw();await window.receiveReferences(0);await refresh();dailySync();}catch(err){toast(err.message);}}
setInterval(()=>{if(!document.hidden&&(jobs.some(j=>active(j.state))||page==='tasks'||page==='gallery'))refresh();},2000);
document.addEventListener('visibilitychange',()=>{if(!document.hidden){refresh();dailySync();}else if(draftTimer)api('draft',draft).catch(()=>{});});
setInterval(()=>{if(!document.hidden)dailySync();},60000);
boot();
