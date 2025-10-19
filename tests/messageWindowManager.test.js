#!/usr/bin/env node
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const filePath = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'www', 'index.html');
const source = fs.readFileSync(filePath, 'utf8');
const start = source.indexOf('class MessageWindowManager');
const end = source.indexOf('// ===== APIManager', start);
if (start === -1 || end === -1) {
  throw new Error('Unable to locate MessageWindowManager definition');
}
const classCode = source.slice(start, end) + '\nthis.__EXPORTED_MWM = MessageWindowManager;\n';

const sandbox = {
  CONFIG: {
    MSG_ESTIMATED_HEIGHT: 160,
    MSG_WINDOW_OVERSCAN: 240,
    MSG_VLIST_THRESHOLD: 50,
    MSG_LOAD_CHUNK: 40
  }
};
vm.createContext(sandbox);
vm.runInContext(classCode, sandbox);

const MessageWindowManager = sandbox.__EXPORTED_MWM;
if (typeof MessageWindowManager !== 'function') {
  throw new Error('MessageWindowManager not exported');
}

const manager = new MessageWindowManager();
const heightCache = new Map();
manager.attachHeightCache(heightCache);

const heights = [100, 200, 150, 300, 120];
const history = heights.map((_, i) => ({ id: `m${i}` }));
heights.forEach((h, i) => heightCache.set(`m${i}`, h));
const totalHeight = heights.reduce((sum, h) => sum + h, 0);

// Virtualization disabled when threshold is high
sandbox.CONFIG.MSG_VLIST_THRESHOLD = 10;
let win = manager.evaluate(history, { scrollTop: 0, viewportHeight: 400 });
assert.strictEqual(win.enabled, false, 'Window should be disabled below threshold');
assert.strictEqual(win.start, 0);
assert.strictEqual(win.end, history.length - 1);
manager.refreshWindowMetrics(history);
assert.strictEqual(manager.totalHeight, totalHeight);

// Enable virtualization by lowering threshold
sandbox.CONFIG.MSG_VLIST_THRESHOLD = 3;
sandbox.CONFIG.MSG_LOAD_CHUNK = 2;
win = manager.evaluate(history, { scrollTop: 0, viewportHeight: 200 });
assert.strictEqual(win.enabled, true, 'Window should enable virtualization');
assert.strictEqual(win.start, 0);
assert.strictEqual(win.end, 2);
manager.refreshWindowMetrics(history);
assert.strictEqual(win.padTop, 0);
assert.strictEqual(win.visibleHeight, 450);

// Scroll near the bottom of the list
win = manager.evaluate(history, { scrollTop: 700, viewportHeight: 200 });
assert.strictEqual(win.start, 3);
assert.strictEqual(win.end, 4);
assert.strictEqual(win.padTop, 450);

// Request earlier messages and ensure the pending window expands upward
const requested = manager.loadEarlier();
assert.strictEqual(requested, true);
const adjusted = manager.evaluate(history, { scrollTop: 700, viewportHeight: 200 });
assert.strictEqual(adjusted.start, 1);
assert.ok(adjusted.end >= adjusted.start);

// Update a cached height and ensure refresh recalculates metrics
const prevPad = manager.window.padTop;
heightCache.set('m0', 180);
const metrics = manager.refreshWindowMetrics(history);
assert.strictEqual(manager.totalHeight, totalHeight - 100 + 180);
assert.strictEqual(manager.window.padTop, 180);
assert.strictEqual(metrics.padDelta, 180 - prevPad);

console.log('MessageWindowManager tests passed.');
