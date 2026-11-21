'use strict';

function imageViewer(src,description,neighbors,actions='') {
 const previous=neighbors&&neighbors.previous;
 const next=neighbors&&neighbors.next;
 const slide=(file,side)=>file?'<img class="viewer-neighbor '+side+'" src="images/'+e(file)+'.thumb" alt="" aria-hidden="true" draggable="false">':'';
 return '<div class="viewer-frame"><div class="viewer'+(neighbors?' viewer-near-fit':'')+'" tabindex="0" aria-label="'+e(description)+'，支持双指缩放和拖动">'+
  '<img class="viewer-current" src="'+e(src)+'" alt="'+e(description)+'" draggable="false">'+
  slide(previous,'previous')+slide(next,'next')+'</div>'+actions+'</div>';
}

function setupViewer(viewport,onSwipe) {
 if(!viewport)return;
 const image=viewport.querySelector('.viewer-current');
 if(onSwipe&&!image.complete){
  image.style.backgroundImage='url("'+image.getAttribute('src')+'.thumb")';
  image.addEventListener('load',()=>{image.style.backgroundImage='';},{once:true});
 }
 const neighbors={
  '-1':viewport.querySelector('.viewer-neighbor.previous'),
  '1':viewport.querySelector('.viewer-neighbor.next')
 };
 const points=new Map();
 const nearFit=()=>onSwipe&&scale<=1.15;
 let scale=1,x=0,y=0,swipe=null,settling=false,travel=0;
 function render() {
  const width=viewport.clientWidth,height=viewport.clientHeight;
  const fit=Math.min(width/(image.naturalWidth||width||1),height/(image.naturalHeight||height||1));
  const maxX=Math.max(0,((image.naturalWidth||width)*fit*scale-width)/2);
  const maxY=Math.max(0,((image.naturalHeight||height)*fit*scale-height)/2);
  x=Math.max(-maxX,Math.min(maxX,x));y=Math.max(-maxY,Math.min(maxY,y));
  viewport.classList.toggle('viewer-near-fit',!!nearFit());
  image.style.transform='translate('+(x+travel)+'px,'+y+'px) scale('+scale+')';
  for(const direction of [-1,1]) {
   const neighbor=neighbors[direction];
   if(neighbor)neighbor.style.transform='translateX('+(direction*(width+8)+travel)+'px)';
  }
 }
 function zoom(next,px=0,py=0) {
  const previous=scale;
  scale=Math.min(6,Math.max(1,next));
  x=px+(x-px)*scale/previous;y=py+(y-py)*scale/previous;
  render();
 }
 function offset(px,py) {
  const r=viewport.getBoundingClientRect();
  return [px-r.left-r.width/2,py-r.top-r.height/2];
 }
 function settle(direction) {
  if(settling)return;
  settling=true;
  const target=direction?-direction*(viewport.clientWidth+8):0;
  const reduced=matchMedia('(prefers-reduced-motion: reduce)').matches;
  if(!reduced)viewport.classList.add('viewer-settling');
  travel=target;
  render();
  const finish=()=>{
   settling=false;
   viewport.classList.remove('viewer-settling');
   if(direction&&viewport.isConnected)onSwipe(direction);
  };
  if(reduced)finish();else setTimeout(finish,185);
 }
 viewport.addEventListener('keydown',event=>{
  if(settling)return;
  if(nearFit()&&['ArrowLeft','ArrowRight'].includes(event.key)){
   event.preventDefault();
   const direction=event.key==='ArrowLeft'?-1:1;
   if(neighbors[direction])settle(direction);
   return;
  }
  if(!['+','=','-','0'].includes(event.key))return;
  event.preventDefault();
  zoom(event.key==='0'?1:scale*(event.key==='-'?1/1.5:1.5));
 });
 viewport.addEventListener('dblclick',event=>{
  if(!settling)zoom(scale>1?1:2,...offset(event.clientX,event.clientY));
 });
 viewport.addEventListener('pointerdown',event=>{
  if(settling||points.size>=2)return;
  if(nearFit()&&!points.size)swipe={id:event.pointerId,x:event.clientX,y:event.clientY,axis:''};
  else {
   swipe=null;
   travel=0;
   render();
  }
  viewport.setPointerCapture(event.pointerId);
  points.set(event.pointerId,{x:event.clientX,y:event.clientY});
 });
 viewport.addEventListener('pointermove',event=>{
  const old=points.get(event.pointerId);
  if(!old)return;
  const other=Array.from(points.entries()).find(([key])=>key!==event.pointerId);
  const next={x:event.clientX,y:event.clientY};
  points.set(event.pointerId,next);
  if(other){
   const pivot=other[1];
   const before=Math.hypot(old.x-pivot.x,old.y-pivot.y);
   const after=Math.hypot(next.x-pivot.x,next.y-pivot.y);
   if(before>1)zoom(scale*after/before,...offset(pivot.x,pivot.y));
  }else if(swipe&&swipe.id===event.pointerId){
   const dx=next.x-swipe.x,dy=next.y-swipe.y;
   if(!swipe.axis&&Math.max(Math.abs(dx),Math.abs(dy))>=8)
    swipe.axis=Math.abs(dx)>Math.abs(dy)?'x':'y';
   if(swipe.axis!=='x')return;
   travel=neighbors[dx<0?1:-1]?dx:dx*.23;
   render();
  }else{
   x+=next.x-old.x;y+=next.y-old.y;render();
  }
 });
 viewport.addEventListener('pointerup',event=>{
  points.delete(event.pointerId);
  if(!swipe||swipe.id!==event.pointerId)return;
  const dx=event.clientX-swipe.x;
  const direction=dx<0?1:-1;
  const dragged=swipe.axis==='x';
  swipe=null;
  if(dragged)settle(nearFit()&&neighbors[direction]&&Math.abs(dx)>=48?direction:0);
 });
 for(const type of ['pointercancel','lostpointercapture'])
  viewport.addEventListener(type,event=>{
   points.delete(event.pointerId);
   if(!swipe||swipe.id!==event.pointerId)return;
   swipe=null;
   if(travel)settle(0);
  });
 image.addEventListener('load',render);
 render();
}
