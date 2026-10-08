const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const vendor = path.resolve(process.argv[2] || 'vendor/mainsail');
const ts = require(path.join(vendor, 'node_modules/typescript'));
const source = fs.readFileSync(path.join(__dirname, '../mainsail-battery.ts'), 'utf8');
function setup(request) {
    let now = 100000;
    const timers = new Map();
    const sandbox = { exports: {}, AbortController,
        setTimeout: (fn) => { const id = {}; timers.set(id, fn); return id; },
        clearTimeout: (id) => timers.delete(id) };
    vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, sandbox);
    return { sample: sandbox.exports.createBatterySampler(request, () => now),
        advance: (ms) => { now += ms; }, timeout: () => [...timers.values()].forEach(fn => fn()) };
}
const flush = () => new Promise(resolve => setImmediate(resolve));
const response = level => ({ ok: true, json: async () => ({ present: true, level }) });
test('battery appears after first sample; zero and full remain valid', async () => {
    for (const level of [0, 55, 100]) {
        const { sample } = setup(async () => response(level));
        assert.equal(sample(), null);
        await flush();
        assert.equal(sample(), level / 100);
    }
});
test('polling never blocks chart and allows only one request at a time', async () => {
    let calls = 0;
    const s = setup(() => { calls++; return new Promise(() => {}); });
    for (let i = 0; i < 10; i++) { assert.equal(s.sample(), null); s.advance(1000); }
    assert.equal(calls, 1);
    s.timeout(); await flush(); s.sample();
    assert.equal(calls, 2);
});
test('transient failure retains recent value but stale values expire', async () => {
    let fail = false;
    const s = setup(async () => { if (fail) throw Error('offline'); return response(70); });
    s.sample(); await flush(); fail = true;
    s.advance(5000); assert.equal(s.sample(), .7); await flush();
    s.advance(30001); assert.equal(s.sample(), null); await flush();
});
test('invalid or absent battery is a gap, never a fabricated percentage', async () => {
    for (const level of [null, '50', -1, 101, NaN]) {
        const s = setup(async () => response(level)); s.sample(); await flush(); assert.equal(s.sample(), null);
    }
    const s = setup(async () => ({ok:true, json:async () => ({present:false, level:50})}));
    s.sample(); await flush(); assert.equal(s.sample(), null);
});
test('late timed-out response cannot overwrite a newer value', async () => {
    let finish;
    let calls = 0;
    const s = setup(() => ++calls === 1 ? new Promise(resolve => finish = resolve) : Promise.resolve(response(80)));
    s.sample(); s.timeout(); await flush(); s.advance(5000); s.sample(); await flush();
    finish(response(10)); await flush(); assert.equal(s.sample(), .8);
});
test('patched chart collects battery without printer temperature sensors', async () => {
    const code = fs.readFileSync(path.join(vendor, 'src/store/printer/tempHistory/actions.ts'), 'utf8');
    const sandbox = {exports:{}, require: () => ({createBatterySampler: () => () => .5, datasetTypes:[], datasetTypesInPercents:[]})};
    vm.runInNewContext(ts.transpileModule(code, {compilerOptions:{module:ts.ModuleKind.CommonJS}}).outputText, sandbox);
    const commits = [];
    await sandbox.exports.actions.updateSource({commit:(...args) => commits.push(args), rootState:{}, rootGetters:{},
        state:{series:[{name:'androidklipper-battery'}], source:[]}});
    assert.equal(commits[0][1].data['androidklipper-battery'], .5);
});
