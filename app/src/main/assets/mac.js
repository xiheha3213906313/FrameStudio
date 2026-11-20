'use strict';

const macOperations={txt2img:'文生图',img2img:'参考图编辑',inpaint:'局部编辑',upscale:'高清放大'};
const macRatios=['1:1','4:3','3:4','3:2','2:3','5:4','4:5','16:9','9:16','21:9','9:21'];
const macModelKey=(server,id)=>'mac:'+server.toLowerCase()+':'+id.toLowerCase();
let macBusy=false, macNotice='', macError='', maskEdit=null, upscaleSource=null;
let macRefreshTimer, macAutoSyncing=false, macRefreshFailures=0, macRefreshPending=false;
let macServices=[], macPairTarget=null, macManualAddress='', macPairAttempt=null, macPairLocked=false;
let maskCanvas=null;
const macSizes=new Map();

function macCatalog(){return config.mac?.catalog;}
function macModels(){return macCatalog()?.models||[];}
function isMac(body=draft){return body.source==='mac';}
function macModel(id=draft.modelId,server=draft.serverId){
 if(!id||server?.toLowerCase()!==config.mac?.serverId?.toLowerCase())return undefined;
 return macModels().find(m=>m.id.toLowerCase()===id.toLowerCase());
}
function macOp(){return draft.operation==='inpaint'?'inpaint':draft.refs.length?'img2img':'txt2img';}
function capability(model,op){return model?.operations.find(x=>x.id===op);}
function macAvailable(op){return macModels().filter(m=>m.ready&&capability(m,op));}
function selectMac(model){
 const server=config.mac.serverId;
 const same=isMac()&&server===draft.serverId&&model.id.toLowerCase()===draft.modelId?.toLowerCase();
 if(!same){draft.count=1;draft.macOptions={};draft.macEdge='';draft.macRatio='';}
 draft.source='mac';draft.serverId=server;draft.modelId=model.id;draft.model=macModelKey(server,model.id);
 draft.name=model.name;draft.layers=false;
}
function macBody(){
 const operation=macOp(), data=promptSnapshot(draft.prompt,draft.promptRefs);
 const cap=capability(macModel(),operation), dims=macDraftSize(cap,operation);
 const macOptions={...(draft.macOptions||{})};
 delete macOptions.width;
 delete macOptions.height;
 if(dims){[macOptions.width,macOptions.height]=dims;}
 return {source:'mac',serverId:draft.serverId,modelId:draft.modelId,model:draft.model,name:macModel()?.name||draft.name,
  operation,refs:draft.refs.slice(),macBase:draft.macBase,strokes:operation==='inpaint'?draft.strokes:undefined,
  macBaseSize:operation==='inpaint'?draft.macBaseSize:undefined,macRatio:draft.macRatio,macEdge:draft.macEdge,
  macWidth:draft.macWidth,macHeight:draft.macHeight,macOptions,size:dims?dims.join('x'):'',
  count:draft.count,separate:true,...data};
}

// GenerationSize uses exact ratios when the grid fits and rounds each axis otherwise.
function macResolutions(w,h,step,presets,preferSource,original=preferSource){
 if(!(w>0&&h>0&&step>0))return [];
 let a=w,b=h;
 while(b){[a,b]=[b,a%b];}
 const ew=w/a*step,eh=h/a*step,ee=Math.max(ew,eh),edge=Math.max(w,h),max=2048;
 const result=new Map();
 function add(x,y){result.set(x+'x'+y,[x,y]);}
 for(const long of preferSource?[...presets,Math.min(max,edge)]:presets) {
  if(ee<=max/2){
   const factor=Math.max(1,Math.min(Math.floor(max/ee),Math.round(long/ee)));
   add(ew*factor,eh*factor);
  }else{
   const scale=long/edge/step;
   add(Math.max(1,Math.round(w*scale))*step,Math.max(1,Math.round(h*scale))*step);
  }
 }
 if(original&&edge<=max&&w%step===0&&h%step===0)add(w,h);
 return [...result.values()].sort((l,r)=>Math.max(...l)-Math.max(...r)||l[0]-r[0]||l[1]-r[1]);
}
function macInputSize(op){
 return op==='inpaint'?draft.macBaseSize:macSizes.get(draft.refs[0]);
}
function macSizeChoices(cap,op){
 if(!cap?.size)return [];
 const ratio=op==='inpaint'||op==='img2img'&&!draft.macRatio?'原图':draft.macRatio;
 const aspect=ratio==='原图'?macInputSize(op):(ratio||'1:1').split(':').map(Number);
 return aspect?macResolutions(...aspect,cap.size.step,cap.size.presets,op==='inpaint',ratio==='原图'): [];
}
function macDraftSize(cap,op){
 if(!cap?.size)return null;
 if(draft.macRatio==='自由'&&op!=='inpaint'){
  const {step,maxEdge}=cap.size;
  const w=supportedMacNumber(draft.macWidth,step,maxEdge,step),h=supportedMacNumber(draft.macHeight,step,maxEdge,step);
  return w==null||h==null?null:[w,h];
 }
 const choices=macSizeChoices(cap,op);
 const match=choices.find(x=>x.join('x')===draft.macEdge);
 if(match)return match;
 if(!draft.macRatio||draft.macRatio==='原图'||op==='inpaint')return null;
 return choices.reduce((best,x)=>!best||Math.abs(Math.max(...x)-768)<Math.abs(Math.max(...best)-768)?x:best,null);
}
async function prepareMacDimensions(){
 const ref=draft.refs[0];
 if(!isMac()||!ref||macSizes.has(ref))return;
 const size=await api('macReferenceSize',{ref});
 macSizes.set(ref,[size.width,size.height]);
 if(modal==='params'&&draft.refs[0]===ref&&!$('#sheet').contains(document.activeElement))renderModal();
}

function macModelSheet(){
 const connected=!!config.mac?.endpoint;
 return '<div class="section-title"><h3>我的 Mac</h3></div>'+
 (connected?button('macRefresh',macBusy?'正在连接…':'刷新 Mac 模型','secondary wide','',macBusy?'disabled':''):
  row('macConnect','连接 MFlux Studio','通过局域网使用 Mac 上已安装的模型'))+
 (connected&&macError?'<p class="notice">'+e(macError)+'</p>':'')+
 macModels().map(m=>{
  const operations=m.operations.map(o=>macOperations[o.id]).filter(Boolean).join(' · ');
  return row('chooseMac',m.name,(m.ready?'已就绪':'未就绪')+(operations?' · '+operations:'')+
   (m.variant?' · '+m.variant:''),m.id,isMac()&&macModel()?.id===m.id);
 }).join('')+
 (connected&&!macModels().length?'<p class="sub">目录暂无模型时，请在 Mac 安装模型并刷新。</p>':'')+
 '<div class="section-title"><h3>火山方舟</h3></div>';
}
function macServiceRows(){
 return macServices.length?macServices.map(service=>row('macSelectService',service.name,service.endpoint,service.endpoint)).join(''):
  '<p class="sub">正在查找同一局域网内的 Mac。也可手动输入 Mac 显示的服务地址。</p>';
}

function macConnectionSheet(){
 const endpoint=config.mac?.endpoint;
 let form;
 if(endpoint){
  form=entry('已配对 Mac',endpoint)+group('更新局域网地址',
   '<input id="macEndpoint" type="text" inputmode="url" value="'+e(endpoint)+'">')+
   '<div class="buttons fill-action-row">'+button('macRefresh','测试连接','secondary','',macBusy?'disabled':'')+
   button('macAddress','验证并更新地址','secondary','',macBusy?'disabled':'')+button('macForgetAsk','忘记配对','dangerbtn')+'</div>';
 }else if(macPairTarget){
  const disabled=macBusy?'disabled':'';
  const address=macPairAttempt?.endpoint||macPairTarget.endpoint||macManualAddress;
  form=macPairTarget.manual&&!macPairAttempt&&!macPairLocked?group('局域网服务地址',
   '<input id="macManualAddress" type="text" inputmode="url" value="'+e(macManualAddress)+'" placeholder="https://192.168.1.20:42817">'):
   entry(macPairTarget.name||'局域网服务地址',address);
  let action;
  if(macPairLocked){
   action=button('macPairInspect','已生成新码，继续','primary','',disabled);
  }else if(!macPairAttempt){
   action=button('macPairInspect',macBusy?'正在读取指纹…':'查看证书指纹','primary','',disabled);
  }else if(!macPairAttempt.confirmed){
   form+='<p class="sub">请和 Mac「连接映序」里的「证书指纹」逐位比对。</p>'+
    '<code class="mac-fingerprint">'+e(macPairAttempt.fingerprint)+'</code>';
   action=button('macPairConfirm','指纹一致','primary','',disabled);
  }else{
   form+=group('输入六位配对码','<input id="macPairing" type="password" inputmode="numeric" maxlength="6" pattern="[0-9]{6}" '+
    'autocomplete="off" placeholder="000000" '+disabled+'>');
   action=button('macPair',macBusy?'正在配对…':'配对','primary','',disabled);
  }
  form+='<div class="buttons fill-action-row">'+button('macSelectAgain',
   macPairAttempt&&!macPairAttempt.confirmed?'指纹不一致，返回':'重新选择 Mac','secondary','',disabled)+action+'</div>';
 }else{
  form='<p class="sub">在 Mac 打开 MFlux Studio 的「连接映序」，开启服务后选择这台 Mac。</p>'+
   '<div id="macServiceList" aria-live="polite">'+macServiceRows()+'</div>'+
   '<div class="buttons fill-action-row">'+button('macDiscover','重新查找','secondary')+
   button('macManual','手动输入地址','secondary')+'</div>';
 }
 const message=macError||macNotice;
 return form+'<p class="inlineerr" id="formError"></p>'+(message?'<p class="notice">'+e(message)+'</p>':'');
}

function setMacServices(services){
 const endpoints=new Set(macServices.map(service=>service.endpoint));
 macServices=Array.isArray(services)?services:[];
 const list=$('#macServiceList');
 if(list&&modal==='macConnect')list.innerHTML=macServiceRows();
 if(config.mac?.endpoint&&macServices.some(service=>service.endpoint!==config.mac.endpoint&&!endpoints.has(service.endpoint)))
  scheduleMacRefresh(250);
}
if(typeof window!=='undefined')window.macServicesChanged=setMacServices;

function scheduleMacRefresh(delay){
 clearTimeout(macRefreshTimer);
 if(!config.mac?.endpoint||document.hidden)return;
 const interval=macRefreshFailures?Math.min(60000,5000*2**macRefreshFailures):30000;
 macRefreshTimer=setTimeout(refreshMacAutomatically,delay??interval);
}
if(typeof window!=='undefined')window.macNetworkChanged=()=>scheduleMacRefresh(0);

async function refreshMacAutomatically(){
 if(!config.mac?.endpoint||document.hidden)return;
 if(macBusy){scheduleMacRefresh();return;}
 if(macAutoSyncing){macRefreshPending=true;return;}
 macAutoSyncing=true;
 const previous=config.mac,previousError=macError;
 try{
  const status=await api('macRefresh');
  if(macBusy||config.mac!==previous)return;
  config.mac=status;macError='';macRefreshFailures=0;
 }catch(error){
  if(!macBusy&&config.mac===previous){
   macError=error.message;
   macRefreshFailures=Math.min(macRefreshFailures+1,4);
  }
 }finally{
  macAutoSyncing=false;
  const pending=macRefreshPending;
  macRefreshPending=false;
  scheduleMacRefresh(pending?0:undefined);
  const endpointChanged=previous.endpoint!==config.mac?.endpoint;
  if(endpointChanged&&page==='settings')draw();
  if(!macBusy&&modal==='models'&&(macError!==previousError||
    JSON.stringify(previous.catalog)!==JSON.stringify(config.mac?.catalog)))renderModal();
  const addressInput=$('#macEndpoint');
  if(!macBusy&&modal==='macConnect'&&(macError!==previousError||endpointChanged)&&addressInput&&
    document.activeElement!==addressInput&&addressInput.value===previous.endpoint)renderModal();
 }
}

function clearMacPairing(){
 const id=macPairAttempt?.attemptId;
 const code=$('#macPairing');
 if(code)code.value='';
 macPairAttempt=null;macPairLocked=false;macPairTarget=null;
 if(id)api('macPairCancel',{attemptId:id}).catch(()=>{});
}

async function advanceMacPairing(action,data){
 if(macBusy||!macPairTarget)return;
 const target=macPairTarget;
 macBusy=true;macError='';macNotice='';
 renderModal();
 try{
  const attempt=await api(action,data);
  if(macPairTarget!==target){
   if(attempt.attemptId)api('macPairCancel',{attemptId:attempt.attemptId}).catch(()=>{});
   return;
  }
  macPairAttempt=attempt;macPairLocked=false;
 }catch(error){
  if(macPairTarget===target)macError=error.message;
 }finally{
  macBusy=false;
  if(modal==='macConnect')renderModal();
 }
}

async function updateMacConnection(action,data){
 if(macBusy)return;
 endReferencePick();
 macBusy=true;
 macError='';macNotice='';
 if(action==='macPair'&&modal==='macConnect')renderModal();
 try{
  const status=await api(action,data);
  if(action==='macPair'&&status.pairingLocked){
   if(macPairTarget){macPairAttempt=null;macPairLocked=true;macError=status.message;}
   return;
  }
  config.mac=status;
  if(action==='macPair'){
   macPairAttempt=null;macPairTarget=null;macPairLocked=false;macManualAddress='';
   macNotice='已配对。正在读取 Mac 模型目录。';
   if(modal==='macConnect')renderModal();
   try{config.mac=await api('macRefresh');}
   catch(error){macNotice='已配对';macError='已配对。'+error.message;return;}
  }
  macRefreshFailures=0;
  macNotice=action==='macForget'?'已移除本机配对资料。':'连接成功';
 }catch(error){macError=error.message;}
 finally{
  macBusy=false;
  scheduleMacRefresh();
  if(modal==='macConnect'||modal==='models')renderModal();
  if(page==='settings')draw();
  if(modal==='macForgetAsk')show('macConnect');
 }
}

function supportedMacNumber(value,min,max,step){
 if(value==null||value===''||!Number.isFinite(Number(value)))return undefined;
 const bounded=Math.max(min,Math.min(max,Number(value)));
 if(!(step>0))return bounded;
 return min+Math.min(Math.floor((max-min)/step),Math.round((bounded-min)/step))*step;
}

function macInputNumber(field,writeBack=false){
 const min=field.min===''?0:Number(field.min),max=field.max===''?Infinity:Number(field.max);
 const value=supportedMacNumber(field.value===''?field.dataset.defaultStrength:field.valueAsNumber,
  min,max,field.step==='any'?0:Number(field.step||1));
 if(writeBack)field.value=value??'';
 return value;
}

function storeMacDraftNumber(field,writeBack=false){
 if(field.disabled)return;
 const value=macInputNumber(field,writeBack);
 if(field.dataset.macDimension){
  draft[field.dataset.macDimension==='width'?'macWidth':'macHeight']=value??'';
  $('#macSizeHint').textContent=macSizeHint();
 }else if(field.dataset.macStrength){
  const lora=draft.macOptions?.loras?.find(x=>x.id===field.dataset.macStrength);
  if(lora&&value!=null)lora.strength=value;
 }else if(field.dataset.macOption){
  const options=draft.macOptions??={},key=field.dataset.macOption;
  if(value==null)delete options[key];
  else options[key]=value;
 }
 saveDraft();
}
const macDraftNumbers='input[type=number][data-mac-option],input[data-mac-dimension],input[data-mac-strength]';

function macNumber(key,title,range,value,step='any'){
 return group(title,'<input data-mac-option="'+key+'" type="number" step="'+step+'" min="'+e(range.min??0)+'" '+
  (range.max==null?'':'max="'+e(range.max)+'" ')+
  'value="'+e(value??'')+'" placeholder="'+e(range.default??'自动')+'">');
}
function macSelect(attribute,choices,selected){
 return '<select '+attribute+'>'+choices.map(([value,label])=>'<option value="'+e(value)+'"'+
  (String(value)===String(selected??'')?' selected':'')+'>'+e(label)+'</option>').join('')+'</select>';
}
function macParamSheet(){
 const op=macOp(),model=macModel(),cap=capability(model,op);
 let top=entry('操作',macOperations[op]);
 if(op==='inpaint')top+=entry('正在修复',draft.refs.includes(draft.macBase)?'参考图 '+(draft.refs.indexOf(draft.macBase)+1):'底图已移除')+
  '<div class="buttons">'+button('maskOpen','调整涂抹','secondary',draft.macBase||'')+
  button('maskExit','退出局部编辑','secondary')+'</div>';
 if(!cap||!model.ready)return top+'<p class="notice">当前 Mac 模型未就绪或不支持此操作。</p>';
 const size=cap.size, selected=draft.macOptions||{};
 const dimensions=draft.macRatio==='自由'&&op!=='inpaint'?[]:macSizeChoices(cap,op);
 const labels=op==='txt2img'?['自动',...macRatios,'自由']:['自动','原图',...macRatios,'自由'];
 if(op==='inpaint')top+=entry('画面比例','跟随原图');
 else top+=group('画面比例',sel('macRatio',labels,draft.macRatio||'自动'));
 if(draft.macRatio==='自由'&&op!=='inpaint'){
  top+='<div class="ratio-fields">'+['width','height'].map((key,i)=>'<label><span>'+['宽','高'][i]+'</span>'+
   '<input type="number" inputmode="numeric" min="'+size.step+'" max="'+size.maxEdge+'" step="'+size.step+
   '" data-mac-dimension="'+key+'" value="'+e(draft['mac'+(i?'Height':'Width')]??'')+'"></label>').join('')+'</div>';
 }else top+=group('分辨率',sel('macEdge',['自动',...dimensions.map(x=>x.join('x'))],draft.macEdge||'自动'));
 top+='<p id="macSizeHint" class="sub">'+e(macSizeHint())+'</p>'+
  '<div class="entry"><span>生成张数</span><div class="countstep">'+
  button('step','−','',-1,'aria-label="减少张数" '+(draft.count<=1?'disabled':''))+
  '<output>'+draft.count+'</output>'+button('step','+','',1,'aria-label="增加张数" '+(draft.count>=15?'disabled':''))+'</div></div>';
 const limit=cap.maxInputs===1?'单张参考图':cap.maxInputs>1?'最多 '+cap.maxInputs+' 张参考图':'无需参考图';
 top+='<p class="sub">'+e(limit)+'</p>';
 let advanced='';
 if(cap.steps)advanced+=macNumber('steps','步数',cap.steps,selected.steps,1);
 if(cap.seed)advanced+=macNumber('seed','种子',cap.seed,selected.seed,1);
 if(cap.guidance)advanced+=macNumber('guidance','CFG',cap.guidance,selected.guidance);
 if(cap.fixedGuidance!=null)advanced+=entry('CFG',cap.fixedGuidance+' · 固定');
 if(cap.samplers?.length)advanced+=group('采样器',macSelect('data-mac-option="sampler"',
  [['',cap.samplerDefaultLabel||'自动'],...cap.samplers.map(x=>[x.id,x.label])],selected.sampler));
 else if(cap.samplerDefaultLabel)advanced+=entry('采样器',cap.samplerDefaultLabel);
 if(cap.negativePrompt)advanced+=group('负面提示词','<textarea class="mac-text" data-mac-option="negativePrompt">'+e(selected.negativePrompt)+'</textarea>');
 if(cap.lowRAM)advanced+=check('macLowRAM','低内存',selected.lowRAM);
 if(cap.imageStrength)advanced+=macNumber('imageStrength','重绘强度',cap.imageStrength,selected.imageStrength);
 if(cap.loras?.length)advanced+='<div class="group"><div class="mac-lora mac-lora-head">'+
  '<span class="fieldtitle">LoRA</span><span class="fieldtitle">强度</span></div>'+cap.loras.map(lora=>{
   const used=selected.loras?.find(x=>x.id===lora.id);
   return '<div class="mac-lora"><label title="'+e(lora.name)+'"><input type="checkbox" data-mac-lora="'+e(lora.id)+'" '+
    (used?'checked':'')+'><span>'+e(lora.name)+'</span></label><input type="number" min="0" max="2" step="any" '+
    'data-mac-strength="'+e(lora.id)+'" data-default-strength="'+e(lora.defaultStrength)+'" aria-label="'+e(lora.name)+' 强度" '+
    'value="'+e(used?.strength??lora.defaultStrength)+'"'+(used?'':' disabled')+'></div>';
  }).join('')+'</div>';
 return top+'<details class="mac-advanced"'+(draft.macAdvanced?' open':'')+'><summary>高级参数</summary>'+advanced+'</details>';
}
function macSizeHint(){
 const op=macOp(),cap=capability(macModel(),op);
 const size=macDraftSize(cap,op);
 if(size)return size.join(' × ')+' 像素';
 if(draft.macRatio==='自由'&&cap?.size)return '宽高需为 '+cap.size.step+' 的倍数，最长边不超过 '+cap.size.maxEdge+' 像素。';
 const choices=macSizeChoices(cap,op);
 const input=macInputSize(op),edge=input?Math.max(...input):0;
 const target=op==='inpaint'?edge:op==='img2img'?Math.min(1024,edge):768;
 const automatic=choices.reduce((best,x)=>!best||Math.abs(Math.max(...x)-target)<Math.abs(Math.max(...best)-target)?x:best,null);
 return automatic?'自动 · '+automatic.join(' × ')+' 像素':op==='inpaint'?'由 Mac 根据原图选择尺寸。':'自动选择尺寸';
}
function macPhotoOptions(kind,id){
 return '<div class="buttons">'+button('maskOpen','局部编辑','secondary',id,'data-kind="'+kind+'"')+
  button('upscaleOpen','高清放大','secondary',id,'data-kind="'+kind+'"')+'</div>';
}
async function openMacImage(mode,kind,id){
 const operation=mode==='mask'?'inpaint':'upscale';
 if(!config.mac?.endpoint){show('macConnect');return;}
 if(!macAvailable(operation).length)throw new Error('Mac 上没有就绪的'+macOperations[operation]+'模型，请检查并刷新目录。');
 const source=await api('macImage',{[kind]:id});
 if(mode==='mask'){
  maskEdit={source,strokes:source.ref===draft.macBase?(draft.strokes||[]).map(s=>({...s,points:s.points.slice()})):[],
   erases:false,pan:false,diameter:0.08};
  show('mask');
 }else{upscaleSource=source;show('upscale');}
}
function maxUpscaleShortEdge(cap,source){
 return Math.floor(cap.maxOutputEdge*Math.min(source.width,source.height)/Math.max(source.width,source.height));
}
function upscaleSheet(){
 const models=macAvailable('upscale');
 if(!upscaleSource||!models.length)return '<p class="notice">请选择原图并确认 Mac 的 SeedVR2 模型已就绪。</p>';
 const model=models.find(x=>x.id===upscaleSource.model)||models[0],cap=capability(model,'upscale');
 upscaleSource.model=model.id;
 return entry('原图尺寸',upscaleSource.width+' × '+upscaleSource.height)+
  group('SeedVR2 模型',macSelect('id="upscaleModel"',models.map(m=>[m.id,m.name]),model.id))+
  group('放大方式',macSelect('id="upscaleScale"',cap.scales.map(x=>[x,x]).concat([['edge','目标短边']]),upscaleSource.scale||cap.defaultScale))+
  '<div id="upscaleShort"'+(upscaleSource.scale==='edge'?'':' hidden')+'>'+group('目标短边（像素）',
   '<input type="number" id="upscaleEdge" min="1" step="1" max="'+maxUpscaleShortEdge(cap,upscaleSource)+'" value="'+e(upscaleSource.edge)+'">')+'</div>'+
  macNumber('softness','柔化',cap.softness,upscaleSource.softness)+macNumber('seed','种子',cap.seed,upscaleSource.seed,1)+
  '<p id="formError" class="inlineerr"></p>';
}
async function submitUpscale(){
 if(submitting||!upscaleSource)return;
 const model=macAvailable('upscale').find(x=>x.id===$('#upscaleModel').value);
 if(!model)throw new Error('SeedVR2 模型已不可用，请刷新模型列表。');
 const selected={},scale=$('#upscaleScale').value;
 if(scale==='edge'){
  const input=$('#upscaleEdge');
  input.max=maxUpscaleShortEdge(capability(model,'upscale'),upscaleSource);
  const edge=macInputNumber(input,true);
  if(edge==null)throw new Error('请填写目标短边像素。');
  selected.shortEdge=edge;
 }else selected.scale=scale;
 for(const key of ['softness','seed']){
  const input=$('[data-mac-option="'+key+'"]');
  const value=macInputNumber(input,true);
  if(value!=null)selected[key]=value;
 }
 const server=config.mac.serverId;
 submitting=true;
 try{
  await api('submit',{body:{source:'mac',serverId:server,modelId:model.id,model:macModelKey(server,model.id),name:model.name,
   operation:'upscale',refs:[upscaleSource.ref],macBase:upscaleSource.ref,macBaseSize:[upscaleSource.width,upscaleSource.height],
   macOptions:selected,count:1,separate:true,prompt:''}});
  close();
  await refresh();
  toast('高清放大已加入任务队列');
 }finally{submitting=false;}
}
if(typeof window!=='undefined')window.receiveUpscale=function(source){upscaleSource=source;show('upscale');};
function macTaskState(j){
 switch(j.state){
 case 'mac_remote':return j.cancelRequested?'正在确认取消':j.result.remote?.state==='queued'?'Mac 排队中':'Mac 推理中';
 case 'mac_waiting':return j.result.downloadFailure==='incomplete_body'?'等待重试下载':'等待连接 Mac';
 case 'mac_fetching':return '保存中';
 case 'mac_blocked':return '需恢复 Mac 连接';
 default:return stateNames[j.state];
 }
}
function macParameterDetails(b,r){
 const o=r?.remote?.options||b.macOptions||{};
 const cap=capability(macModel(b.modelId,b.serverId),b.operation);
 const lora=o.loras?.map(x=>(b.macLoraNames?.[x.id]||cap?.loras?.find(l=>l.id===x.id)?.name||x.id)+' × '+x.strength).join('，');
 const output=r?.remote?.output;
 const params=[['尺寸',o.width&&o.height?o.width+' × '+o.height:output?output.width+' × '+output.height:undefined],['步数',o.steps],['实际种子',o.seed],
  ['CFG',o.guidance??b.macFixedCfg??cap?.fixedGuidance],['采样器',o.sampler??b.macSamplerDefault],['负面提示词',o.negativePrompt],
  ['低内存',o.lowRAM==null?undefined:o.lowRAM?'开启':'关闭'],['重绘强度',o.imageStrength],['LoRA',lora],
  ['倍率',o.scale],['目标短边',o.shortEdge],['柔化',o.softness]];
 return entry('来源','我的 Mac · 本地推理')+entry('操作',macOperations[b.operation]||b.operation)+entry('模型',r?.remote?.modelName||b.name)+
  entry('模型 ID',b.modelId)+params.filter(x=>x[1]!=null&&x[1]!=='').map(x=>entry(...x)).join('');
}

if(typeof module!=='undefined')module.exports={macResolutions,supportedMacNumber};

function maskSheet(){
 const source=maskEdit.source;
 return '<canvas id="maskCanvas" class="mask-canvas" aria-label="局部编辑底图，拖动涂抹，两指缩放"></canvas>'+
  '<p class="sub">'+e(source.width+' × '+source.height)+' · 一指涂抹，双指缩放拖动</p>'+
  '<div class="buttons mask-tools">'+button('maskTool','画笔','choice selected','paint')+button('maskTool','擦除','choice','erase')+
  button('maskTool','拖动','choice','pan')+button('maskUndo','撤销','secondary')+button('maskClear','清空','secondary')+'</div>'+
  '<label class="mask-range"><span>笔刷大小</span><input id="maskBrush" type="range" min="0.005" max="0.4" step="0.005" value="'+maskEdit.diameter+'"></label>'+
  '<div class="buttons">'+button('maskZoom','−','secondary',0.75,'aria-label="缩小"')+
  button('maskZoom','+','secondary',1.33,'aria-label="放大"')+button('maskZoom','适合画布','secondary',0)+'</div>';
}
function setupMask(canvas){
 if(!canvas||!maskEdit)return;
 const edit=maskEdit,source=edit.source,picture=new Image(),layer=document.createElement('canvas');
 const ctx=canvas.getContext('2d'),ink=layer.getContext('2d'),fingers=new Map();
 let scale=1,dx=0,dy=0,stroke=null,mode='',scheduled=0,frame;
 let total=edit.strokes.reduce((sum,s)=>sum+s.points.length,0);
 function render(){
  scheduled=0;
  if(!canvas.isConnected)return;
  const width=canvas.clientWidth,height=canvas.clientHeight,pixel=Math.min(devicePixelRatio||1,2);
  if(!width||!height)return;
  if(canvas.width!==Math.round(width*pixel)||canvas.height!==Math.round(height*pixel)){
   canvas.width=Math.round(width*pixel);canvas.height=Math.round(height*pixel);
   layer.width=canvas.width;layer.height=canvas.height;
  }
  const fit=Math.min(width/source.width,height/source.height)*scale;
  const w=source.width*fit,h=source.height*fit;
  dx=Math.max(-Math.max(0,(w-width)/2),Math.min(Math.max(0,(w-width)/2),dx));
  dy=Math.max(-Math.max(0,(h-height)/2),Math.min(Math.max(0,(h-height)/2),dy));
  const x=(width-w)/2+dx,y=(height-h)/2+dy;
  frame={x,y,w,h};
  ctx.setTransform(pixel,0,0,pixel,0,0);
  ink.setTransform(pixel,0,0,pixel,0,0);
  ctx.clearRect(0,0,width,height);ink.clearRect(0,0,width,height);
  if(!picture.naturalWidth)return;
  ctx.drawImage(picture,x,y,w,h);
  ink.save();
  ink.beginPath();ink.rect(x,y,w,h);ink.clip();
  ink.lineJoin='round';ink.lineCap='round';
  ink.fillStyle=ink.strokeStyle='rgba(176,68,43,.55)';
  for(const path of stroke?[...edit.strokes,stroke]:edit.strokes){
   const first=path.points[0],d=path.diameter*Math.min(w,h);
   ink.globalCompositeOperation=path.erases?'destination-out':'source-over';
   ink.lineWidth=d;
   ink.beginPath();
   ink.moveTo(x+first.x*w,y+first.y*h);
   for(const point of path.points.slice(1))ink.lineTo(x+point.x*w,y+point.y*h);
   if(path.points.length>1)ink.stroke();
   else {ink.arc(x+first.x*w,y+first.y*h,d/2,0,Math.PI*2);ink.fill();}
  }
  ink.restore();
  ctx.drawImage(layer,0,0,width,height);
 }
 function schedule(){if(!scheduled)scheduled=requestAnimationFrame(render);}
 function point(event){
  const bounds=canvas.getBoundingClientRect();
  return {x:event.clientX-bounds.left,y:event.clientY-bounds.top};
 }
 function normalized(p){
  const fixed=value=>Math.round(Math.max(0,Math.min(1,value))*1000000)/1000000;
  return {x:fixed((p.x-frame.x)/frame.w),y:fixed((p.y-frame.y)/frame.h)};
 }
 function zoom(next,before,after=before){
  const previous=scale;
  scale=Math.max(1,Math.min(8,next));
  const cx=canvas.clientWidth/2,cy=canvas.clientHeight/2;
  dx=after.x-cx-(before.x-cx-dx)*scale/previous;
  dy=after.y-cy-(before.y-cy-dy)*scale/previous;
  render();
 }
 canvas.addEventListener('pointerdown',event=>{
  if(!frame||!picture.naturalWidth||fingers.size===2)return;
  const p=point(event);
  canvas.setPointerCapture(event.pointerId);
  fingers.set(event.pointerId,p);
  if(fingers.size===2){stroke=null;mode='pan';schedule();return;}
  if(edit.pan){mode='pan';return;}
  if(p.x<frame.x||p.y<frame.y||p.x>frame.x+frame.w||p.y>frame.y+frame.h)return;
  if(edit.strokes.length>=512||total>=50000){toast('已达到遮罩笔迹上限');return;}
  mode='draw';
  stroke={diameter:edit.diameter,erases:edit.erases,points:[normalized(p)]};
  schedule();
 });
 canvas.addEventListener('pointermove',event=>{
  const old=fingers.get(event.pointerId);
  if(!old)return;
  const p=point(event);
  const other=[...fingers.entries()].find(([id])=>id!==event.pointerId)?.[1];
  fingers.set(event.pointerId,p);
  if(other){
   const start=Math.hypot(other.x-old.x,other.y-old.y),end=Math.hypot(other.x-p.x,other.y-p.y);
   if(start>1)zoom(scale*end/start,{x:(other.x+old.x)/2,y:(other.y+old.y)/2},{x:(other.x+p.x)/2,y:(other.y+p.y)/2});
  }else if(mode==='pan'){dx+=p.x-old.x;dy+=p.y-old.y;render();}
  else if(stroke&&total+stroke.points.length<50000){
   const next=normalized(p),last=stroke.points[stroke.points.length-1];
   if(next.x!==last.x||next.y!==last.y)stroke.points.push(next);
   schedule();
  }
 });
 function release(event){
  if(!fingers.delete(event.pointerId))return;
  if(event.type==='pointerup'&&stroke){edit.strokes.push(stroke);total+=stroke.points.length;}
  stroke=null;
  if(!fingers.size)mode='';
  schedule();
 }
 for(const type of ['pointerup','pointercancel','lostpointercapture'])canvas.addEventListener(type,release);
 picture.onload=render;
 picture.src='refs/'+source.preview;
 maskCanvas={
  zoom(factor){
   if(!factor){scale=1;dx=0;dy=0;render();}
   else zoom(scale*factor,{x:canvas.clientWidth/2,y:canvas.clientHeight/2});
  },
  remove(clear){
   if(clear)edit.strokes=[];
   else edit.strokes.pop();
   total=edit.strokes.reduce((sum,s)=>sum+s.points.length,0);render();
  },
  observer:new ResizeObserver(schedule)
 };
 maskCanvas.observer.observe(canvas);
 render();
}
function applyMask(){
 if(!maskEdit?.strokes.some(s=>!s.erases))throw new Error('请先涂出要修复的区域。');
 const model=macAvailable('inpaint').find(m=>m.id===macModel()?.id)||macAvailable('inpaint')[0];
 if(!model)throw new Error('Mac 上没有就绪的局部编辑模型。');
 const source=maskEdit.source,strokes=maskEdit.strokes;
 selectMac(model);
 if(!draft.refs.includes(source.ref))draft.refs=draft.refs.length<14?[...draft.refs,source.ref]:[...draft.refs.slice(0,13),source.ref];
 draft.operation='inpaint';draft.macBase=source.ref;draft.macBaseSize=[source.width,source.height];draft.strokes=strokes;
 draft.macRatio='';draft.macEdge='';
 navigate('create');redraw();
 toast('局部编辑底图已选定，请填写修改提示词。');
}
