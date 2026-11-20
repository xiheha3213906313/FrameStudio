const test = require('node:test');
const assert = require('node:assert/strict');
const {referenceLabel, promptEdit, shiftReferences, relabelReferences, insertReference, standaloneAt,
  promptSnapshot} = require('../app/src/main/assets/prompt-references.js');
const {macResolutions, supportedMacNumber} = require('../app/src/main/assets/mac.js');

test('a new reference replaces a middle @ and merges adjacent spaces around the caret', () => {
  const prompt = '将  @  换成蓝色';
  const next = insertReference(prompt, [], 3, 'b', ['a', 'b'], 'ark');
  assert.equal(next.prompt, '将 @图二 换成蓝色');
  assert.equal(next.prompt.slice(0, next.caret), '将 @图二 ');
  const onlyReference = insertReference('@', [], 0, 'b', ['a', 'b'], 'ark');
  assert.deepEqual(promptSnapshot(onlyReference.prompt, onlyReference.promptRefs),
    {prompt: ' @图二 ', promptRefs: [{ref: 'b', start: 1, end: 4}]});
});

test('source changes, reordering and deletion preserve the bound file', () => {
  let state = insertReference('@', [], 0, 'b', ['a', 'b'], 'ark');
  state = relabelReferences(state.prompt, state.promptRefs, ['b', 'a'], 'mac');
  assert.equal(state.prompt, ' @image 1 ');
  state = relabelReferences(state.prompt, state.promptRefs, ['a'], 'mac');
  assert.equal(state.prompt, ' @〔参考图已失效〕 ');
  assert.equal(state.promptRefs[0].ref, 'b');
  assert.equal(referenceLabel('ark', 12), '@图十二');
});

test('a typed email or adjacent @ does not activate selection', () => {
  assert.equal(standaloneAt('artist@example.com', 6), false);
  assert.equal(standaloneAt('写给@ ，然后', 2), true);
  assert.equal(standaloneAt('@@', 1), false);
});

test('editing regular text shifts managed references while editing a marker unbinds it', () => {
  const binding = [{ref: 'one', start: 2, end: 5}];
  const before = '改 @图一 封面';
  const after = '把 @图一 封面';
  assert.deepEqual(shiftReferences(binding, promptEdit(before, after, [0, 1])), binding);
  assert.deepEqual(shiftReferences(binding, promptEdit(after, '把 @图 封面', [5, 5])), []);
});

test('repair size candidates match the Mac grid for exact and coprime input ratios', () => {
  const presets = [512, 768, 1024, 1280, 1536, 2048];
  assert.ok(macResolutions(1000, 500, 16, presets, true).some(([w, h]) => w === 992 && h === 496));
  assert.ok(macResolutions(1379, 971, 16, presets, true).some(([w, h]) => w === 1376 && h === 976));
  assert.ok(macResolutions(128, 64, 16, presets, true).some(([w, h]) => w === 128 && h === 64));
});

test('Mac numbers clamp and round to supported bounds and steps', () => {
  assert.equal(supportedMacNumber(-0.5, 0, 2), 0);
  assert.equal(supportedMacNumber(2.6, 0, 2), 2);
  assert.equal(supportedMacNumber(0.75, 0, 2), 0.75);
  assert.equal(supportedMacNumber(4294967296, 0, 4294967295, 1), 4294967295);
  assert.equal(supportedMacNumber(4.8, 1, 50, 1), 5);
  assert.equal(supportedMacNumber(1001, 16, 4096, 16), 1008);
  assert.equal(supportedMacNumber(19, 8, 19, 4), 16);
  assert.equal(supportedMacNumber('', 0, 2), undefined);
});
