const test = require('node:test');
const assert = require('node:assert/strict');
const {PROFILES, GENERIC, cost, modelProfile, known, unit, requestSize} = require('../app/src/main/assets/model.js');
function request(rate, refs = 0, count = 1) {
    return {rate, refs:Array(refs).fill('ref'), count, size:'2048x2048', layers:false};
}
test('5.5 estimates 2K output and each input in integer fen', () => {
    assert.equal(cost(request(PROFILES[0],2), null).total,33);
});
test('actual usage and per-image dimensions determine the amount', () => {
    const body=request(PROFILES[0],2);
    assert.deepEqual(cost(body,{usage:{input_images:1,generated_images:1},images:[{size:'1024x1024'}]}),
        {input:5,output:13,count:1,total:18});
    assert.equal(unit(PROFILES[0],'2610x1000',false),13);
    assert.equal(unit(PROFILES[0],'2611x1000',false),23);
});
test('5.0 Pro first input is free and layer rates are per output', () => {
    const body=request(PROFILES[2],3); assert.equal(cost(body,null).total,64);
    body.layers=true;body.layerMax=17;body.refs=['ref'];
    assert.equal(cost(body,null).total,510);
    assert.equal(cost(body,{images:[{size:'2048x2048'},{size:'200x400'}],usage:{generated_images:2,input_images:1}}).total,45);
});
test('partial group billed from successful count even if download failed', () => {
    const body=request(PROFILES[3],1,4);
    assert.equal(cost(body,null).total,88);
    assert.equal(cost(body,{usage:{generated_images:2},images:[{ready:false},{ready:true}]}).total,44);
    assert.equal(cost(body,{usage:{generated_images:0},images:[]}).total,0);
});
test('unpriced models and missing tier data stay unknown', () => {
    assert.equal(cost(request(modelProfile('ep-private',[])),null),null);
    assert.equal(cost(Object.assign(request(PROFILES[0]),{layers:true}),{usage:{generated_images:2},images:[{size:'1024x1024'}]}),null);
    assert.equal(known('doubao-seedream-5-0-260128').family,'lite');
    assert.equal(modelProfile('ep-x',[{id:'ep-x',family:'generic',cents:17}]).high,17);
});

test('official family aliases and catalog metadata keep the published price', () => {
    assert.equal(known('Doubao-Seedream-5.5-261028').high,23);
    assert.equal(known('doubao-seedream-5-0-261231').low,22);
    assert.equal(modelProfile('variant-id',[],[{id:'variant-id',name:'Doubao-Seedream-5.0-Flash'}]).low,12);
    assert.equal(modelProfile('unknown-id',[],[{id:'unknown-id',name:'Image v8'}]).low,null);
});
test('intelligent aspect ratio passes a tier and separate jobs count each reference', () => {
    const body=request(PROFILES[0],1,3);
    body.size=requestSize(PROFILES[0],'1.5K','智能比例');
    assert.equal(body.size,'1.5K');
    body.separate=true;
    assert.deepEqual(cost(body,null),{input:15,output:39,count:3,total:54});
    assert.equal(requestSize(PROFILES[3],'2K','16:9'),'2848x1600');
});
