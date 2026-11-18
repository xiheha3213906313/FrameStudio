'use strict';
const PROFILES = [
 {id:'doubao-seedream-5-5-261028',family:'55',name:'Seedream 5.5',levels:['1K','1.5K','2K'],refs:10,rgba:true,layers:100,format:true,low:13,high:23,input:5,free:0},
 {id:'doubao-seedream-5-0-flash-260915',family:'flash',name:'Seedream 5.0 Flash',levels:['1K','1.5K','2K'],refs:10,rgba:true,layers:17,format:true,low:12,high:12,input:0,free:0},
 {id:'doubao-seedream-5-0-pro-260628',family:'pro',name:'Seedream 5.0 Pro',levels:['1K','1.5K','2K'],refs:10,rgba:true,layers:17,format:true,low:30,high:60,input:2,free:1},
 {id:'doubao-seedream-5-0-lite-260128',family:'lite',name:'Seedream 5.0 Lite',levels:['2K','3K','4K'],refs:14,batch:true,format:true,low:22,high:22,input:0,free:0},
 {id:'doubao-seedream-4-5-251128',family:'45',name:'Seedream 4.5',levels:['2K','4K'],refs:14,batch:true,low:25,high:25,input:0,free:0},
 {id:'doubao-seedream-4-0-250828',family:'40',name:'Seedream 4.0',levels:['1K','2K','4K'],refs:14,batch:true,low:20,high:20,input:0,free:0}
];
const GENERIC={family:'generic',levels:[],name:'自定义模型',refs:1,low:null,high:null,input:null,free:0};
const RATIOS=['智能比例','1:1','4:3','3:4','16:9','9:16','3:2','2:3','21:9','自定义'];
const SIZES={
 '1K':['1024x1024','1152x864','864x1152','1424x800','800x1424','1248x832','832x1248','1568x672'],
 '1.5K':['1536x1536','1792x1344','1344x1792','2048x1152','1152x2048','1872x1248','1248x1872','2352x1008'],
 '2K':['2048x2048','2368x1776','1776x2368','2816x1584','1584x2816','2496x1664','1664x2496','3136x1344'],
 '3K':['3072x3072','3456x2592','2592x3456','4096x2304','2304x4096','3744x2496','2496x3744','4704x2016'],
 '4K':['4096x4096','4704x3520','3520x4704','5504x3040','3040x5504','4992x3328','3328x4992','6240x2656']
};
const LEGACY_1K=['1024x1024','1152x864','864x1152','1280x720','720x1280','1248x832','832x1248','1512x648'];
const LEGACY_2K=['2048x2048','2304x1728','1728x2304','2848x1600','1600x2848','2496x1664','1664x2496','3136x1344'];
function modelFamilyKey(value) {
 return String(value||'').trim().toLowerCase().replace(/^doubao[ -]/,'').replace(/[. ]/g,'-').replace(/-\d{6}$/,'');
}
function known(id) {
 const key=modelFamilyKey(id);
 if(key==='seedream-5-0')return PROFILES[3];
 return PROFILES.find(p=>modelFamilyKey(p.id)===key);
}
function modelProfile(id,custom,visible=[]) {
 const item=custom.find(m=>m.id===id);
 const metadata=visible.find(m=>m.id===id);
 const published=known(id)||(metadata&&known(metadata.name));
 if(item) {
  const p=PROFILES.find(m=>m.family===item.family)||published||GENERIC;
  if(p===GENERIC && Number.isInteger(item.cents)) return Object.assign({},p,{low:item.cents,high:item.cents,input:0});
  return p;
 }
 return published||GENERIC;
}
function priceLabel(profile) {
 if(profile.low==null)return '待核价';
 const out=money(profile.low)+(profile.high!==profile.low?'–'+money(profile.high):'')+'/张';
 return profile.input?out+' · '+(profile.free?'首张参考图免费，之后 ':'参考图 ')+money(profile.input)+'/张':out+' · 参考图免费';
}
function requestSize(profile,level,ratio,width=1,height=1) {
 if(!profile.levels.includes(level))return '';
 if(ratio===RATIOS[0])return level;
 if(ratio==='自定义'){
  let w=Number(width),h=Number(height);
  if(!Number.isSafeInteger(w)||!Number.isSafeInteger(h)||w<1||h<1||w/h<1/16||w/h>16)return '';
  let a=w,b=h;
  while(b!==0){const next=a%b;a=b;b=next;}
  w/=a;h/=a;
  const min=['lite','45'].includes(profile.family)?3686400:921600;
  const max=profile.batch?16777216:4624220;
  const area=w*h;
  const smallest=Math.ceil(Math.sqrt(min/area)),largest=Math.floor(Math.sqrt(max/area));
  if(smallest>largest)return '';
  const multiple=Math.max(smallest,Math.min(largest,Math.round(Math.sqrt(pixels(SIZES[level][0])/area))));
  return (w*multiple)+'x'+(h*multiple);
 }
 const slot=RATIOS.indexOf(ratio)-1;
 if(slot<0)return '';
 if(level==='1K' && profile.family==='40')return LEGACY_1K[slot];
 if(level==='2K' && profile.batch)return LEGACY_2K[slot];
 return SIZES[level][slot]||'';
}
function pixels(size){const parts=String(size||'').split(/[x×]/).map(Number);return parts.length===2 && parts.every(n=>n>0)?parts[0]*parts[1]:0;}
function unit(rule,size,layers){
 if(!rule || rule.low==null)return null;
 const area=pixels(size);
 if(!area && rule.low!==rule.high)return null;
 let fee=area>2610000?rule.high:rule.low;
 if(layers && rule.family==='pro')fee=fee/2;
 return fee;
}
function cost(body,result){
 const rule=body.rate;
 if(!rule || rule.low==null)return null;
 const usage=result&&result.usage||{};
 const inputs=Number.isInteger(usage.input_images)?usage.input_images:body.refs.length;
 const input=Math.max(0,inputs-rule.free)*rule.input*(result||!body.separate?1:body.count);
 const images=(result && result.images || []).filter(image=>!image.imported);
 const count=result?(Number.isInteger(usage.generated_images)?usage.generated_images:images.length):(body.layers?body.layerMax:body.count);
 if(result && images.length>count)return null;
 let output=0;
 for(let i=0;i<count;i++){
  const tier=SIZES[body.layers?body.level:body.size];
  const estimateSize=tier?tier[0]:body.size;
  const u=unit(rule,result?(images[i] && images[i].size):estimateSize,body.layers);
  if(u==null)return null;
  output+=u;
 }
 return {input:input,output:output,count:count,total:input+output};
}
function money(fen){return fen==null?'待核价':'¥'+(fen/100).toFixed(2);}
if(typeof module!=='undefined')module.exports={PROFILES,GENERIC,known,modelProfile,pixels,unit,cost,money,RATIOS,SIZES,requestSize,priceLabel};
