import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('../src/sw.js', import.meta.url), 'utf8');

function loadServiceWorker(fetchImpl = fetch) {
  const listeners = new Map();
  const cacheLookups = [];
  const caches = {
    match: async key => {
      cacheLookups.push(String(key));
      return String(key) === '/offline.html' ? new Response('offline shell') : undefined;
    },
    open: async () => ({ addAll: async () => {}, put: async () => {} }),
    keys: async () => [],
    delete: async () => true
  };
  const self = {
    location: { origin: 'https://shop.example.test' },
    clients: { claim: async () => {} },
    addEventListener: (name, handler) => listeners.set(name, handler),
    skipWaiting: async () => {}
  };
  vm.runInNewContext(source, { self, caches, fetch: fetchImpl, URL, Promise });
  return { listeners, cacheLookups };
}

function fetchEvent(handler, request) {
  let response;
  handler({ request, respondWith: value => { response = value; } });
  return response;
}

test('does not intercept API, GraphQL or actuator requests, including authenticated requests', () => {
  const { listeners } = loadServiceWorker();
  const handler = listeners.get('fetch');

  for (const path of ['/api/v1/cart', '/api/v1/admin/users', '/graphql', '/actuator/health']) {
    const request = new Request(`https://shop.example.test${path}`, {
      headers: { Authorization: 'Bearer sensitive-session-token' }, credentials: 'include'
    });
    assert.equal(fetchEvent(handler, request), undefined, `${path} must stay outside the service-worker cache policy`);
  }
});

test('uses only the generic offline page when a private navigation cannot reach the network', async () => {
  const { listeners, cacheLookups } = loadServiceWorker(async () => { throw new TypeError('offline'); });
  const request = { url: 'https://shop.example.test/orders', method: 'GET', mode: 'navigate' };
  const response = await fetchEvent(listeners.get('fetch'), request);

  assert.equal(await response.text(), 'offline shell');
  assert.deepEqual(cacheLookups, ['/offline.html']);
  assert.equal(cacheLookups.includes('/orders'), false);
});

test('does not intercept cross-origin resources or non-GET requests', () => {
  const { listeners } = loadServiceWorker();
  const handler = listeners.get('fetch');

  assert.equal(fetchEvent(handler, new Request('https://cdn.example.test/assets/app.js')), undefined);
  assert.equal(fetchEvent(handler, new Request('https://shop.example.test/api/v1/cart', { method: 'POST', body: '{}' })), undefined);
});
