'use strict';

function imageViewer(src,description) {
 return '<div class="viewer" tabindex="0" aria-label="'+e(description)+'，支持双指缩放和拖动">'+
  '<img src="'+e(src)+'" alt="'+e(description)+'" draggable="false"></div>';
}

function setupViewer(viewport,onSwipe) {
 if(!viewport)return;
 const image=viewport.querySelector('img');
 const points=new Map();
 let scale=1,x=0,y=0,swipe=null;
 function render() {
  const width=viewport.clientWidth,height=viewport.clientHeight;
  const fit=Math.min(width/(image.naturalWidth||width||1),height/(image.naturalHeight||height||1));
  const maxX=Math.max(0,((image.naturalWidth||width)*fit*scale-width)/2);
  const maxY=Math.max(0,((image.naturalHeight||height)*fit*scale-height)/2);
  x=Math.max(-maxX,Math.min(maxX,x));y=Math.max(-maxY,Math.min(maxY,y));
  image.style.transform='translate('+x+'px,'+y+'px) scale('+scale+')';
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
 viewport.addEventListener('keydown',event=>{
  if(onSwipe&&scale===1&&['ArrowLeft','ArrowRight'].includes(event.key)){
   event.preventDefault();
   onSwipe(event.key==='ArrowLeft'?-1:1);
   return;
  }
  if(!['+','=','-','0'].includes(event.key))return;
  event.preventDefault();
  zoom(event.key==='0'?1:scale*(event.key==='-'?1/1.5:1.5));
 });
 viewport.addEventListener('dblclick',event=>zoom(scale>1?1:2,...offset(event.clientX,event.clientY)));
 viewport.addEventListener('pointerdown',event=>{
  if(points.size>=2)return;
  if(onSwipe&&scale===1&&!points.size)swipe={id:event.pointerId,x:event.clientX,y:event.clientY};
  else swipe=null;
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
  }else{
   x+=next.x-old.x;y+=next.y-old.y;render();
  }
 });
 viewport.addEventListener('pointerup',event=>{
  points.delete(event.pointerId);
  if(!swipe||swipe.id!==event.pointerId)return;
  const dx=event.clientX-swipe.x,dy=event.clientY-swipe.y;
  swipe=null;
  if(scale===1&&Math.abs(dx)>=48&&Math.abs(dx)>Math.abs(dy)*1.5)onSwipe(dx<0?1:-1);
 });
 for(const type of ['pointercancel','lostpointercapture'])
  viewport.addEventListener(type,event=>{
   points.delete(event.pointerId);
   if(swipe&&swipe.id===event.pointerId)swipe=null;
  });
 image.addEventListener('load',render);
}
