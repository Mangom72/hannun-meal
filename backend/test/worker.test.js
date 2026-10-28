import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHandler,monthRange,seoulMonth,ttlFor,dishes,fetchMonth} from '../src/index.js';
const now=Date.parse('2026-10-26T00:00:00Z');
const path='https://api.example/v1/meals/B10/7010057/202610';
const row=(code='2')=>({ATPT_OFCDC_SC_CODE:'B10',SD_SCHUL_CODE:'7010057',MLSV_YMD:'20261026',MMEAL_SC_CODE:code,DDISH_NM:'쌀밥<br/>국 (5.6.)<br>사과 &amp; 배',NTR_INFO:'단백질<br>20g',CAL_INFO:'700 Kcal'});
const page=(rows,total=rows.length)=>({mealServiceDietInfo:[{head:[{list_total_count:total},{RESULT:{CODE:'INFO-000'}}]},{row:rows}]});
const env=()=>({NEIS_API_KEY:'private-unit-key-never-echo',ALLOWED_ORIGINS:'["https://school.example"]',CLIENT_LIMITER:{limit:async()=>({success:true})},UPSTREAM_LIMITER:{limit:async()=>({success:true})}});
test('official alphanumeric school codes retain identity and monthly edge caching',async()=>{
 let calls=0;
 const handler=createHandler({clock:()=>now,cacheProvider:()=>cache,fetcher:async url=>{
  calls++;assert.equal(new URL(url).searchParams.get('SD_SCHUL_CODE'),'C035903');
  return Response.json(page([{...row(),ATPT_OFCDC_SC_CODE:'N10',SD_SCHUL_CODE:'C035903'}]));
 }}),cache=new Cache();
 const request=()=>handler.fetch(new Request(path.replace('B10/7010057','N10/C035903')),env(),{waitUntil(){}});
 const first=await request();assert.equal(first.status,200);assert.equal((await first.json()).schoolCode,'C035903');
 assert.equal((await request()).headers.get('X-Meal-Cache'),'HIT');assert.equal(calls,1);
});
test('NEIS Type=json uses compatible Accept without upstream HTTP 500',async()=>{
 let calls=0;
 const fetcher=async(url,options)=>{
  calls++;assert.equal(new URL(url).searchParams.get('Type'),'json');
  if(options.headers.Accept==='application/json')return new Response(null,{status:500});
  assert.equal(options.headers.Accept,'*/*');
  return Response.json(page([row()]));
 };
 const result=await fetchMonth(fetcher,env(),'B10','7010057','202610',now);
 assert.equal(JSON.parse(result.body).meals.length,1);assert.equal(calls,1);
});
class Cache {data=new Map();async match(key){return this.data.get(key.url)?.clone();} async put(key,value){this.data.set(key.url,value.clone());}}
function setup(){let time=now,calls=0,mode='ok';const cache=new Cache(),tasks=[],logs=[];
 const fetcher=async(url,options)=>{calls++;const u=new URL(url);assert.equal(u.origin,'https://open.neis.go.kr');assert.equal(u.searchParams.get('KEY'),env().NEIS_API_KEY);assert.equal(u.searchParams.get('MLSV_FROM_YMD'),'20261001');assert.equal(u.searchParams.get('MLSV_TO_YMD'),'20261031');assert.equal(options.redirect,'manual');await new Promise(r=>setTimeout(r,5));if(mode==='network')throw Error('upstream echoed private-unit-key-never-echo');return Response.json(mode==='empty'?{RESULT:{CODE:'INFO-200'}}:mode==='auth'?{RESULT:{CODE:'ERROR-290',MESSAGE:env().NEIS_API_KEY}}:page([row('1'),row('2'),row('3')]));};
 const handler=createHandler({fetcher,clock:()=>time,cacheProvider:()=>cache,logger:x=>logs.push(x)});
 return {env:env(),cache,tasks,logs,get calls(){return calls;},set time(v){time=v;},set mode(v){mode=v;},request:(url=path,opts={})=>handler.fetch(new Request(url,opts),env(),{waitUntil:p=>tasks.push(p)})};}
test('month boundaries, leap year, and Seoul UTC boundary',()=>{assert.deepEqual(monthRange('202402'),['20240201','20240229']);assert.throws(()=>monthRange('202613'));assert.equal(seoulMonth(Date.parse('2026-10-31T15:00:00Z')),'202611');assert.equal(ttlFor('202610',false,{},now),3600);assert.equal(ttlFor('202611',false,{},now),21600);assert.equal(ttlFor('202609',false,{},now),604800);assert.equal(ttlFor('202610',true,{},now),900);assert.equal(ttlFor('202610',false,{TTL_CURRENT:'5'},now),5);});
test('markup, allergens, original punctuation, and duplicates',()=>{const d=dishes('<b>국</b> (5.6.)<br>국 (5.6.)<br>사과 &amp; 배<br>고기 1.5.6.10.');assert.equal(d.length,3);assert.deepEqual(d[0].allergens,[5,6]);assert.equal(d[1].name,'사과 & 배');assert.deepEqual(d[2].allergens,[1,5,6,10]);});
test('one uncached month, all meal types, edge hit avoids NEIS',async()=>{const s=setup();const a=await s.request();assert.equal(a.status,200);const payload=await a.json();assert.deepEqual(payload.meals.map(x=>x.type),['BREAKFAST','LUNCH','DINNER']);assert.equal(payload.meals[1].nutrition,'단백질\n20g');assert.equal(s.calls,1);const hit=await s.request();assert.equal(hit.headers.get('X-Meal-Cache'),'HIT');assert.equal(s.calls,1);assert.ok(!JSON.stringify(payload).includes(s.env.NEIS_API_KEY));});
test('simultaneous identical cache misses coalesce',async()=>{const s=setup();const responses=await Promise.all(Array.from({length:20},()=>s.request()));assert.ok(responses.every(x=>x.status===200));assert.equal(s.calls,1);});
test('valid empty is cached for only fifteen minutes',async()=>{const s=setup();s.mode='empty';const p=await (await s.request()).json();assert.deepEqual(p.meals,[]);assert.equal(Date.parse(p.freshUntil)-Date.parse(p.fetchedAt),900000);await s.request();assert.equal(s.calls,1);});
test('SWR serves stale immediately and explicitly revalidates once',async()=>{const s=setup();await s.request();s.time=now+3600001;const r=await s.request();assert.equal(r.headers.get('X-Meal-Cache'),'STALE_REVALIDATING');assert.equal(r.headers.get('X-Meal-Stale'),'true');await Promise.all(s.tasks);assert.equal(s.calls,2);assert.equal((await s.request()).headers.get('X-Meal-Cache'),'HIT');});
test('bounded stale-if-error never retimestamps an old success',async()=>{const s=setup();const original=await (await s.request()).json();s.mode='network';s.time=now+3900001;const stale=await s.request();assert.equal(stale.status,200);assert.equal(stale.headers.get('X-Meal-Cache'),'STALE_IF_ERROR');assert.equal((await stale.json()).fetchedAt,original.fetchedAt);s.time=Date.parse(original.staleUntil)+1;const expired=await s.request();assert.equal(expired.status,504);assert.ok(!JSON.stringify(s.logs).includes(s.env.NEIS_API_KEY));});
test('upstream authentication failures remain errors and never empty successes',async()=>{const s=setup();s.mode='auth';const r=await s.request();assert.equal(r.status,503);assert.equal((await r.json()).error.code,'UPSTREAM_AUTH');assert.equal(s.cache.data.size,0);});
test('validation, fixed destination, CORS, and methods before upstream access',async()=>{const s=setup();for(const url of [path+'?KEY=attacker',path.replace('202610','202613'),path.replace('B10','Z99'),path.replace('7010057','../../http://evil'),path.replace('202610','209910')])assert.equal((await s.request(url)).status,400);assert.equal((await s.request(path,{method:'POST'})).status,405);assert.equal((await s.request(path,{headers:{Origin:'https://evil.example'}})).status,403);const r=await s.request(path,{method:'OPTIONS',headers:{Origin:'https://school.example'}});assert.equal(r.status,204);assert.equal(r.headers.get('Access-Control-Allow-Origin'),'https://school.example');assert.equal(s.calls,0);});
test('rate limiter rejects without upstream fetch',async()=>{const e=env();e.CLIENT_LIMITER.limit=async()=>({success:false});let calls=0;const h=createHandler({fetcher:async()=>{calls++;},clock:()=>now,cacheProvider:()=>new Cache()});const r=await h.fetch(new Request(path),e,{waitUntil(){}});assert.equal(r.status,429);assert.equal(calls,0);});
test('pagination includes all pages and rejects truncated or repeated pages',async()=>{let calls=0;const fetcher=async()=>Response.json(page([row(String(++calls))],3));const d=await fetchMonth(fetcher,env(),'B10','7010057','202610',now);assert.equal(JSON.parse(d.body).meals.length,3);assert.equal(calls,3);await assert.rejects(fetchMonth(async()=>Response.json(page([row()],3)),env(),'B10','7010057','202610',now),/UPSTREAM_INVALID/);});
test('malformed, wrong-school, invalid-day, and conflicting rows cannot poison cache',async()=>{for(const payload of [null,[],{},page([{...row(),SD_SCHUL_CODE:'9999999'}]),page([{...row(),MLSV_YMD:'20261032'}]),page([row(),{...row(),DDISH_NM:'different'}])]){await assert.rejects(fetchMonth(async()=>Response.json(payload),env(),'B10','7010057','202610',now));}});

test('redirects and reflected credentials are not followed, returned, or cached',async()=>{
 let calls=0;
 await assert.rejects(fetchMonth(async()=>{calls++;return new Response(null,{status:302,headers:{Location:'https://evil.example/?KEY=private-unit-key-never-echo'}});},env(),'B10','7010057','202610',now));
 assert.equal(calls,1);
 await assert.rejects(fetchMonth(async()=>Response.json(page([{...row(),DDISH_NM:env().NEIS_API_KEY}])),env(),'B10','7010057','202610',now),/UPSTREAM_INVALID/);
});
