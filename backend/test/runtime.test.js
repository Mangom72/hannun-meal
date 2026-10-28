import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
import {fileURLToPath} from 'node:url';

test('real workerd runtime: coalescing, Cache API hit, and request counts',async t=>{
 let upstreamCalls=0;
 const mf=new Miniflare(convertV4MiniflareOptions({
  modules:true,scriptPath:fileURLToPath(new URL('../src/index.js',import.meta.url)),compatibilityDate:'2026-10-27',
  bindings:{NEIS_API_KEY:'runtime-fixture-key',ALLOWED_ORIGINS:'[]'},
  ratelimits:{CLIENT_LIMITER:{namespace_id:'72001',simple:{limit:120,period:60}},UPSTREAM_LIMITER:{namespace_id:'72002',simple:{limit:20,period:60}}},
  outboundService:async request=>{
   upstreamCalls++;
   const u=new URL(request.url);assert.equal(u.origin,'https://open.neis.go.kr');assert.equal(u.searchParams.get('KEY'),'runtime-fixture-key');assert.equal(request.headers.get('Accept'),'*/*');
   await new Promise(r=>setTimeout(r,30));
   return Response.json({RESULT:{CODE:'INFO-200'}});
  }
 }));
 try {
  await mf.ready;
  const now=new Date(Date.now()+9*3600000),ym=`${now.getUTCFullYear()}${String(now.getUTCMonth()+1).padStart(2,'0')}`;
  const url=`https://api.example/v1/meals/B10/7010057/${ym}`;
  const coldStart=performance.now();const first=await mf.dispatchFetch(url);const coldMs=performance.now()-coldStart;
  assert.equal(first.status,200);assert.equal(first.headers.get('X-Meal-Cache'),'MISS');assert.deepEqual((await first.json()).meals,[]);
  const warmStart=performance.now();const warm=await mf.dispatchFetch(url);const warmMs=performance.now()-warmStart;
  assert.equal(warm.headers.get('X-Meal-Cache'),'HIT');assert.equal(upstreamCalls,1);
  const other=url.replace('7010057','7010058');
  const simultaneous=await Promise.all(Array.from({length:8},()=>mf.dispatchFetch(other)));
  assert.ok(simultaneous.every(r=>r.status===200));assert.equal(upstreamCalls,2);
  t.diagnostic(JSON.stringify({fixtureLatencyMs:{cold:+coldMs.toFixed(2),warm:+warmMs.toFixed(2)},httpRequests:10,neisFixtureCalls:upstreamCalls,note:'local workerd, simulated upstream delay 30ms; not production latency'}));
 } finally {await mf.dispose();}
});
