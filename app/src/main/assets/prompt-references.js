'use strict';

function referenceLabel(source, number) {
 if(number===0)return '@〔参考图已失效〕';
 if(source==='mac')return '@image '+number;
 const digits='零一二三四五六七八九';
 return '@图'+(number<10?digits[number]:'十'+(number===10?'':digits[number-10]));
}

function promptEdit(before, after, selection) {
 const [from,to]=selection||[];
 const inserted=after.length-before.length+(to-from);
 if(Number.isInteger(inserted)&&inserted>=0&&before.slice(0,from)===after.slice(0,from)&&
    before.slice(to)===after.slice(from+inserted))return {start:from,end:to,text:after.slice(from,from+inserted)};
 let start=0,end=before.length,last=after.length;
 while(start<end&&start<last&&before[start]===after[start])start++;
 while(end>start&&last>start&&before[end-1]===after[last-1]){end--;last--;}
 return {start,end,text:after.slice(start,last)};
}

function shiftReferences(bindings, edit) {
 const delta=edit.text.length-(edit.end-edit.start);
 return (bindings||[]).flatMap(ref=>ref.end<=edit.start?[ref]:ref.start>=edit.end?
  [{...ref,start:ref.start+delta,end:ref.end+delta}]:[]);
}

function relabelReferences(prompt, bindings, refs, source) {
 let text='', end=0;
 const updated=[];
 for(const binding of bindings||[]) {
  if(binding.start<end||binding.start>=binding.end||binding.end>prompt.length)continue;
  text+=prompt.slice(end,binding.start);
  const start=text.length;
  text+=referenceLabel(source,refs.indexOf(binding.ref)+1);
  updated.push({ref:binding.ref,start,end:text.length});
  end=binding.end;
 }
 return {prompt:text+prompt.slice(end),promptRefs:updated};
}

function insertReference(prompt, bindings, at, ref, refs, source) {
 let start=at,end=at+1;
 while(start>0&&prompt[start-1]===' ')start--;
 while(prompt[end]===' ')end++;
 const label=referenceLabel(source,refs.indexOf(ref)+1),text=' '+label+' ';
 const updated=shiftReferences(bindings,{start,end,text});
 updated.push({ref,start:start+1,end:start+1+label.length});
 updated.sort((a,b)=>a.start-b.start);
 return {prompt:prompt.slice(0,start)+text+prompt.slice(end),promptRefs:updated,caret:start+text.length};
}

function standaloneAt(text, position) {
 return text[position]==='@'&&!/[A-Za-z0-9._%+@-]/.test(text[position-1]||'')&&text[position+1]!=='@';
}

function promptSnapshot(prompt, bindings) {
 return bindings?.length?{prompt,promptRefs:bindings.map(x=>({...x}))}:{prompt:prompt.trim(),promptRefs:[]};
}

if(typeof module!=='undefined')module.exports={referenceLabel,promptEdit,shiftReferences,relabelReferences,insertReference,standaloneAt,promptSnapshot};
