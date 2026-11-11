import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { uploadVideo, postUploadPart, CHUNK_BYTES } from '../src/lib/upload-video.mjs';

const file = chunks => ({ name: 'video.MOV', size: chunks * CHUNK_BYTES, slice: (from, to) => ({ from, to }) });
function fixture(overrides = {}) {
  const calls = [], progress = [];
  const api = {
    start: async request => { calls.push(['start', request]); return { uploadId: 'session', status: 'UPLOADING' }; },
    progress: async () => [],
    urls: async (id, offset, limit) => { calls.push(['urls', offset, limit]); return Array.from({ length: limit }, (_, index) => offset + index); },
    put: async part => { calls.push(['put', part]); },
    merge: async id => { calls.push(['merge', id]); return 'media-url'; },
    ...overrides,
  };
  const controller = new AbortController();
  return { calls, progress, api, controller, run: input => uploadVideo(input, 'a'.repeat(32), api, controller.signal, value => progress.push(value)) };
}

test('resumes all uploaded parts and still merges before reporting completion', async () => {
  const f = fixture({ progress: async () => [0, 1] });
  assert.equal(await f.run(file(2)), 'media-url');
  assert.deepEqual(f.calls.map(call => call[0]), ['start', 'merge']);
  assert.deepEqual(f.progress, [95, 100]);
  assert.equal(f.calls[0][1].fileType, 'mov');
});

test('signs bounded windows and only uploads missing parts', async () => {
  const f = fixture({ progress: async () => [0, 32] });
  await f.run(file(34));
  assert.deepEqual(f.calls.filter(call => call[0] === 'urls'), [['urls', 0, 32], ['urls', 32, 2]]);
  assert.equal(f.calls.filter(call => call[0] === 'put').length, 32);
  assert.equal(f.progress.at(-2), 95);
  assert.equal(f.progress.at(-1), 100);
});

test('merge failure preserves incomplete progress and retry can reuse the session', async () => {
  const f = fixture({ progress: async () => [0], merge: async () => { throw new Error('merge failed'); } });
  await assert.rejects(f.run(file(1)), /merge failed/);
  assert.ok(!f.progress.includes(100));
  f.api.merge = async () => 'recovered';
  assert.equal(await f.run(file(1)), 'recovered');
});

test('replacement during a delayed part upload prevents merge and stale progress', async () => {
  let release;
  const f = fixture({ put: () => new Promise(resolve => { release = resolve; }) });
  const pending = f.run(file(1));
  while (!release) await new Promise(resolve => setImmediate(resolve));
  f.controller.abort();
  release();
  await assert.rejects(pending, { name: 'AbortError' });
  assert.ok(!f.calls.some(call => call[0] === 'merge'));
  assert.deepEqual(f.progress, [0]);
});

test('invalid files never create sessions and completed sessions skip storage', async () => {
  const f = fixture();
  await assert.rejects(f.run({ name: 'bad.exe', size: 1 }), /MP4/);
  assert.equal(f.calls.length, 0);
  f.api.start = async () => ({ status: 'COMPLETE', fileUrl: 'existing' });
  assert.equal(await f.run(file(1)), 'existing');
  assert.deepEqual(f.progress, [100]);
});

test('policy POST excludes login credentials and checks storage HTTP status', async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async (url, options) => {
      assert.equal(url, '/storage');
      assert.equal(options.method, 'POST');
      assert.equal(options.credentials, 'omit');
      assert.equal(options.headers, undefined);
      assert.deepEqual([...options.body.keys()], ['key', 'policy', 'file']);
      return { ok: false };
    };
    await assert.rejects(postUploadPart({ url: '/storage', fields: { key: 'chunk', policy: 'signed' } }, new Blob(['x'])), /分片上传失败/);
  } finally { globalThis.fetch = original; }
});

test('upload page reaches session routes through the application request adapter', async () => {
  const requests = [];
  const source = fs.readFileSync(new URL('../src/api/platform.js', import.meta.url), 'utf8')
    .replace('import request from "@/lib/request"', 'const request = async options => { globalThis.__uploadRequests.push(options); return { data: "ok" }; }')
    .replace('import { postUploadPart } from "@/lib/upload-video.mjs"', 'const postUploadPart = () => {};');
  globalThis.__uploadRequests = requests;
  try {
    const { uploadApi } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
    await uploadApi.start({ sizeBytes: 1 });
    await uploadApi.progress('session');
    await uploadApi.urls('session', 32, 2);
    await uploadApi.merge('session');
    assert.deepEqual(requests.map(item => item.url), ['/file/uploads', '/file/uploads/session/progress', '/file/uploads/session/urls', '/file/uploads/session/merge']);
    assert.deepEqual(requests[2].params, { offset: 32, limit: 2 });
  } finally { delete globalThis.__uploadRequests; }
});
