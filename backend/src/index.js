// No client-supplied URL, NEIS query, credential, or redirect is forwarded upstream.
export const OFFICES = new Set(['B10','C10','D10','E10','F10','G10','H10','I10','J10','K10','M10','N10','P10','Q10','R10','S10','T10']);
export class ApiError extends Error {
  constructor(code, status = 502) { super(code); this.code = code; this.status = status; }
}
const number = (value, fallback, max = 2592000) => {
  if (value === undefined) return fallback;
  if (!/^\d+$/.test(String(value)) || +value > max) throw new ApiError('CONFIGURATION', 503);
  return +value;
};
export function seoulMonth(now) {
  const p = new Intl.DateTimeFormat('en-CA', {timeZone:'Asia/Seoul', year:'numeric', month:'2-digit'}).formatToParts(new Date(now));
  return p.find(x => x.type === 'year').value + p.find(x => x.type === 'month').value;
}
export function monthRange(ym) {
  if (!/^\d{4}(0[1-9]|1[0-2])$/.test(ym)) throw new ApiError('INVALID_MONTH', 400);
  const y = +ym.slice(0,4), m = +ym.slice(4);
  return [ym+'01', ym+String(new Date(Date.UTC(y,m,0)).getUTCDate()).padStart(2,'0')];
}
const ordinal = ym => +ym.slice(0,4)*12 + +ym.slice(4)-1;
export function ttlFor(ym, empty, env, now) {
  if (empty) return number(env.TTL_EMPTY,900);
  const current = seoulMonth(now);
  return ym < current ? number(env.TTL_HISTORY,604800) : ym === current ? number(env.TTL_CURRENT,3600) : number(env.TTL_NEXT,21600);
}
// Decode text only, never produce HTML. Allergen annotations use the existing Android convention.
export function plain(value) {
  return String(value ?? '').replace(/&lt;br\s*\/?&gt;/gi,'\n').replace(/<br\s*\/?>|<\/(?:p|div|li)\s*>/gi,'\n')
    .replace(/<[^>]*>/g,'').replace(/&#(x[0-9a-f]+|\d+);/gi,(_,n) => {
      const c = n[0].toLowerCase()==='x' ? parseInt(n.slice(1),16) : +n;
      return c>0 && c<=0x10ffff ? String.fromCodePoint(c) : '';
    }).replace(/&(amp|lt|gt|quot|apos|nbsp);/g,(_,n) => ({amp:'&',lt:'<',gt:'>',quot:'"',apos:"'",nbsp:' '})[n]).trim();
}
export function dishes(raw) {
  const seen = new Set();
  return plain(raw).split(/[\r\n]+/).flatMap(line => {
    const original=line.replace(/\s+/g,' ').trim();
    const annotations=[...original.matchAll(/\(\s*(\d{1,2}(?:[.,\s]+\d{1,2})*[.]?)\s*\)/g),...original.matchAll(/(?:\s+|(?<=[가-힣]))(\d{1,2}(?:\.\d{1,2})+\.?)\s*$/g)];
    const allergens=[...new Set(annotations.flatMap(x => x[1].match(/\d+/g).map(Number)).filter(x => x>=1 && x<=19))].sort((a,b)=>a-b);
    const name=original.replace(/\(\s*\d{1,2}(?:[.,\s]+\d{1,2})*[.]?\s*\)/g,'').replace(/(?:\s+|(?<=[가-힣]))\d{1,2}(?:\.\d{1,2})+\.?\s*$/g,'').replace(/\s+/g,' ').trim();
    const id=JSON.stringify([name,allergens]);
    if (!name || seen.has(id)) return [];
    seen.add(id); return [{name,allergens,original}];
  });
}
export function parsePage(root) {
  if(!root || typeof root!=='object' || Array.isArray(root)) throw new ApiError('UPSTREAM_INVALID');
  function result(r) {
    if (!r || !['INFO-000','INFO-200'].includes(r.CODE)) {
      if (['INFO-100','ERROR-290'].includes(r?.CODE)) throw new ApiError('UPSTREAM_AUTH',503);
      if (r?.CODE==='ERROR-337') throw new ApiError('UPSTREAM_LIMIT',503);
      throw new ApiError('UPSTREAM_ERROR');
    }
  }
  if (root.RESULT) { result(root.RESULT); if (root.RESULT.CODE==='INFO-200') return {total:0,rows:[]}; throw new ApiError('UPSTREAM_INVALID'); }
  const parts=root.mealServiceDietInfo;
  if (!Array.isArray(parts) || parts.some(x=>!x || typeof x!=='object')) throw new ApiError('UPSTREAM_INVALID');
  const head=parts.flatMap(x=>x.head ?? []), codes=head.filter(x=>x.RESULT);
  if (!codes.length) throw new ApiError('UPSTREAM_INVALID');
  codes.forEach(x=>result(x.RESULT));
  const total=head.find(x=>x.list_total_count !== undefined)?.list_total_count;
  const rows=parts.flatMap(x=>x.row ?? []);
  if (!Number.isInteger(total) || total<0 || total>1000 || (total>0 && !rows.length) || rows.length>total) throw new ApiError('UPSTREAM_INVALID');
  return {total,rows};
}
async function boundedJson(response, limit) {
  if (!response.body) throw new ApiError('UPSTREAM_INVALID');
  const reader=response.body.getReader(); let size=0; const chunks=[];
  try {
    for (;;) { const {done,value}=await reader.read(); if(done) break; size+=value.length; if(size>limit) throw new ApiError('UPSTREAM_INVALID'); chunks.push(value); }
  } finally { await reader.cancel().catch(()=>{}); }
  const bytes=new Uint8Array(size); let at=0; for(const c of chunks) {bytes.set(c,at);at+=c.length;}
  try {return JSON.parse(new TextDecoder().decode(bytes));} catch {throw new ApiError('UPSTREAM_INVALID');}
}
export async function fetchMonth(fetcher, env, office, school, ym, now) {
  if (!env.NEIS_API_KEY?.trim()) throw new ApiError('CONFIGURATION',503);
  const [from,to]=monthRange(ym), rows=[], pages=new Set(); let total;
  // One logical month request; bounded pagination, total budget includes body reads.
  const controller=new AbortController(), timeout=setTimeout(()=>controller.abort(),12000);
  try {
    for(let index=1;index<=10;index++) {
      const u=new URL('https://open.neis.go.kr/hub/mealServiceDietInfo');
      u.search=new URLSearchParams({KEY:env.NEIS_API_KEY,Type:'json',pIndex:String(index),pSize:'100',ATPT_OFCDC_SC_CODE:office,SD_SCHUL_CODE:school,MLSV_FROM_YMD:from,MLSV_TO_YMD:to}).toString();
      // NEIS uses Type=json; requesting application/json via Accept triggers HTTP 500.
      const response=await fetcher(u.toString(),{signal:controller.signal,redirect:'manual',headers:{Accept:'*/*'}});
      if(!response.ok) throw new ApiError(response.status===429 ? 'UPSTREAM_LIMIT' : 'UPSTREAM_ERROR');
      const page=parsePage(await boundedJson(response,2*1024*1024));
      if(total!==undefined && total!==page.total) throw new ApiError('UPSTREAM_INVALID');
      total=page.total;
      const fingerprint=JSON.stringify(page.rows);
      if(page.rows.length && pages.has(fingerprint)) throw new ApiError('UPSTREAM_INVALID');
      pages.add(fingerprint); rows.push(...page.rows);
      if(rows.length===total) break;
      if(rows.length>total || index===10) throw new ApiError('UPSTREAM_INVALID');
    }
  } catch(e) { if(e instanceof ApiError) throw e; throw new ApiError(controller.signal.aborted ? 'UPSTREAM_TIMEOUT' : 'UPSTREAM_UNAVAILABLE',504); }
  finally {clearTimeout(timeout);}
  const unique=new Map();
  for(const r of rows) {
    if(!r || typeof r!=='object') throw new ApiError('UPSTREAM_INVALID');
    const date=String(r.MLSV_YMD ?? ''), type=({'1':'BREAKFAST','2':'LUNCH','3':'DINNER'})[r.MMEAL_SC_CODE];
    if(r.ATPT_OFCDC_SC_CODE!==office || r.SD_SCHUL_CODE!==school || !type || !/^\d{8}$/.test(date) || date<from || date>to || typeof r.DDISH_NM!=='string') throw new ApiError('UPSTREAM_INVALID');
    const day=+date.slice(6); if(day<1 || day>+to.slice(6)) throw new ApiError('UPSTREAM_INVALID');
    const meal={date:`${date.slice(0,4)}-${date.slice(4,6)}-${date.slice(6)}`,type,dishes:dishes(r.DDISH_NM),raw:r.DDISH_NM,calories:String(r.CAL_INFO ?? ''),nutrition:plain(r.NTR_INFO),origin:plain(r.ORPLC_INFO)};
    const key=date+type, prior=unique.get(key);
    if(prior && JSON.stringify(prior)!==JSON.stringify(meal)) throw new ApiError('UPSTREAM_INVALID');
    unique.set(key,meal);
  }
  const meals=[...unique.values()].sort((a,b)=>a.date.localeCompare(b.date) || ['BREAKFAST','LUNCH','DINNER'].indexOf(a.type)-['BREAKFAST','LUNCH','DINNER'].indexOf(b.type));
  const ttl=ttlFor(ym,meals.length===0,env,now), swr=number(env.SWR_SECONDS,300), fallback=number(env.STALE_IF_ERROR_SECONDS,86400);
  if(ttl+fallback>31*86400) throw new ApiError('CONFIGURATION',503);
  const body=JSON.stringify({schemaVersion:1,officeCode:office,schoolCode:school,yearMonth:ym,fetchedAt:new Date(now).toISOString(),freshUntil:new Date(now+ttl*1000).toISOString(),staleUntil:new Date(now+(ttl+fallback)*1000).toISOString(),meals});
  if(body.includes(env.NEIS_API_KEY)) throw new ApiError('UPSTREAM_INVALID');
  return {body,fetchedAt:now,freshUntil:now+ttl*1000,swrUntil:now+(ttl+Math.min(swr,fallback))*1000,staleUntil:now+(ttl+fallback)*1000};
}

export function createHandler({fetcher=(...a)=>fetch(...a), clock=()=>Date.now(), cacheProvider=()=>caches.default, logger=x=>console.warn(JSON.stringify(x))}={}) {
  // Bounded, best-effort isolate-local coalescing. Only immutable text/metadata is shared, never Request/Response bodies.
  const inFlight=new Map();
  return {
    async fetch(request,env,ctx) {
      const start=clock(), url=new URL(request.url), origin=request.headers.get('Origin');
      let allowed=[];
      try {allowed=JSON.parse(env.ALLOWED_ORIGINS ?? '[]');if(!Array.isArray(allowed) || allowed.some(x=>new URL(x).origin!==x || !x.startsWith('https://'))) throw 0;} catch {return Response.json({error:{code:'CONFIGURATION'}},{status:503});}
      const cors=origin && allowed.includes(origin) ? {'Access-Control-Allow-Origin':origin,'Vary':'Origin','Access-Control-Expose-Headers':'X-Meal-Cache, X-Meal-Stale, Retry-After'} : {};
      const fail=(code,status)=>Response.json({error:{code}},{status,headers:{...cors,'Cache-Control':'no-store',...(status===429 || status===503 ? {'Retry-After':'60'}:{})}});
      if(origin && !allowed.includes(origin)) return fail('ORIGIN_DENIED',403);
      const match=/^\/v1\/meals\/([A-Z]\d{2})\/([A-Z0-9]{7})\/(\d{6})$/.exec(url.pathname);
      if(!match || url.search || !OFFICES.has(match?.[1])) return fail('INVALID_REQUEST',400);
      const [,office,school,ym]=match;
      try {monthRange(ym);if(+ym.slice(0,4)<number(env.MIN_YEAR,2020,9999) || ordinal(ym)>ordinal(seoulMonth(start))+number(env.MAX_FUTURE_MONTHS,2,24)) return fail('INVALID_MONTH',400);} catch(e) {return fail(e.code ?? 'INVALID_MONTH',e.status ?? 400);}
      if(request.method==='OPTIONS') return new Response(null,{status:204,headers:{...cors,'Access-Control-Allow-Methods':'GET, HEAD, OPTIONS','Access-Control-Max-Age':'86400'}});
      if(!['GET','HEAD'].includes(request.method)) return fail('METHOD_NOT_ALLOWED',405);
      if(!env.CLIENT_LIMITER || !env.UPSTREAM_LIMITER) return fail('CONFIGURATION',503);
      // Anonymous public data: CORS is not authentication. NAT users share a generous per-IP/location budget.
      let permitted;
      try {permitted=await env.CLIENT_LIMITER.limit({key:request.headers.get('CF-Connecting-IP') ?? 'anonymous'});} catch {return fail('RATE_LIMIT_UNAVAILABLE',503);}
      if(!permitted.success) return fail('RATE_LIMITED',429);
      const key=new Request(`${url.origin}/__cache/v1/meals/${office}/${school}/${ym}`), cache=cacheProvider();
      let cached;
      try {const hit=await cache.match(key);if(hit) cached=await hit.json();} catch { /* eviction or corrupt cache is a miss */ }
      const send=(data,state,stale=false)=>new Response(request.method==='HEAD' ? null : data.body,{headers:{...cors,'Content-Type':'application/json; charset=utf-8','X-Content-Type-Options':'nosniff','Cache-Control':'no-store','X-Meal-Cache':state,'X-Meal-Stale':String(stale)}});
      if(cached?.freshUntil>start) return send(cached,'HIT');
      const renew=()=>{
        const id=key.url;
        if(inFlight.has(id)) return inFlight.get(id);
        if(inFlight.size>=64) return Promise.reject(new ApiError('BUSY',503));
        const task=(async()=>{
          const permitted=await env.UPSTREAM_LIMITER.limit({key:'neis-month-misses'});
          if(!permitted.success) throw new ApiError('UPSTREAM_BUDGET',503);
          const data=await fetchMonth(fetcher,env,office,school,ym,clock());
          // Retain beyond freshness; SWR and stale-if-error are implemented here, not assumed from Cache API headers.
          try {await cache.put(key,new Response(JSON.stringify(data),{headers:{'Content-Type':'application/json','Cache-Control':`public, max-age=${Math.max(1,Math.ceil((data.staleUntil-clock())/1000))}`}}));} catch {logger({event:'cache_write_failed'});}
          return data;
        })().finally(()=>inFlight.delete(id));
        inFlight.set(id,task);return task;
      };
      const log=e=>logger({event:'meal_refresh_failed',code:e instanceof ApiError ? e.code : 'INTERNAL',office,school,month:ym,elapsedMs:clock()-start});
      if(cached?.swrUntil>start && cached.staleUntil>start) {ctx.waitUntil(renew().catch(log));return send(cached,'STALE_REVALIDATING',true);}
      try {const data=await renew();return send(data,'MISS');}
      catch(e) {
        log(e);
        // Do not hide authentication/configuration failures with a stale success.
        const transient=!(e instanceof ApiError) || ['UPSTREAM_ERROR','UPSTREAM_LIMIT','UPSTREAM_TIMEOUT','UPSTREAM_UNAVAILABLE','UPSTREAM_BUDGET','BUSY'].includes(e.code);
        if(cached?.staleUntil>clock() && transient) return send(cached,'STALE_IF_ERROR',true);
        return fail(e instanceof ApiError ? e.code : 'INTERNAL',e instanceof ApiError ? e.status : 503);
      }
    }
  };
}
export default createHandler();
