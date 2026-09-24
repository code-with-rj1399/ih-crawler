'use client';

import {useEffect,useState} from 'react';

type Seed={id:number;slug:string;name:string;url:string;sourceKind:string;enabled:boolean;rateLimitRpm:number;crawlDelayMs:number};
type Crawl={id:number;status:string;trigger:string;lookbackHours:number;createdAt:string;startedAt?:string;finishedAt?:string;sourcesPlanned:number;sourcesOk:number;sourcesFailed:number;pagesFetched:number;pagesSkipped:number;postsExtracted:number;questionsUpserted:number;blockedCount:number};
type SubRun={id:number;sourceId:number;sourceSlug:string;status:string;startedAt:string;finishedAt?:string;pagesFetched:number;pagesSkipped:number;postsExtracted:number;questionsUpserted:number;modelCalls:number;blockedCount:number;errorSummary?:string};
const API=process.env.NEXT_PUBLIC_HQ_API_URL||'http://localhost:8080';

async function api(path:string,init?:RequestInit){const r=await fetch(`${API}${path}`,{...init,headers:{'Content-Type':'application/json',...(init?.headers||{})},cache:'no-store'});if(!r.ok)throw new Error(await r.text());return r.json();}
export default function Admin(){
 const [config,setConfig]=useState<any>(null),[seeds,setSeeds]=useState<Seed[]>([]),[crawls,setCrawls]=useState<Crawl[]>([]),[selected,setSelected]=useState<{crawl:Crawl;subRuns:SubRun[]}|null>(null),[error,setError]=useState('');
 const [newSeed,setNewSeed]=useState({slug:'',name:'',url:'',sourceKind:'GENERIC'});
 const load=async()=>{try{setError('');const [c,s,craw]=await Promise.all([api('/admin/crawl/config'),api('/admin/seeds'),api('/admin/crawls')]);setConfig(c);setSeeds(s);setCrawls(craw);}catch(e){setError(String(e));}};
 useEffect(()=>{load()},[]);
 const saveConfig=async()=>{try{await api('/admin/crawl/config',{method:'PUT',body:JSON.stringify({cronEnabled:config.cronEnabled,lookbackHours:config.lookbackHours,cronIntervalMinutes:config.values?.['cron.interval_minutes']?Number(config.values['cron.interval_minutes']):60})});await load()}catch(e){setError(String(e));}};
 const toggleSeed=async(s:Seed)=>{try{await api(`/admin/seeds/${s.id}/enabled`,{method:'PUT',body:JSON.stringify({enabled:!s.enabled})});await load()}catch(e){setError(String(e));}};
 const addSeed=async()=>{try{await api('/admin/seeds',{method:'POST',body:JSON.stringify(newSeed)});setNewSeed({slug:'',name:'',url:'',sourceKind:'GENERIC'});await load()}catch(e){setError(String(e));}};
 const openCrawl=async(c:Crawl)=>{try{const d=await api(`/admin/crawls/${c.id}`);setSelected(d)}catch(e){setError(String(e));}};
 if(!config)return <main className="page"><h1>InterviewHQ Admin</h1><p>Loading…</p>{error&&<p className="danger">{error}</p>}</main>;
 return <main className="page">
  <header className="header"><div><h1>InterviewHQ Admin</h1><div className="muted">Crawler control plane</div></div><button className="secondary" onClick={load}>Refresh</button></header>
  {error&&<div className="card danger">{error}</div>}
  <section className="grid">
   <div className="card"><div className="muted">Scheduler</div><div className="metric">{config.cronEnabled?'Enabled':'Disabled'}</div></div>
   <div className="card"><div className="muted">Lookback</div><div className="metric">{config.lookbackHours}h</div></div>
   <div className="card"><div className="muted">Enabled seeds</div><div className="metric">{seeds.filter(s=>s.enabled).length}/{seeds.length}</div></div>
   <div className="card"><div className="muted">Recent crawls</div><div className="metric">{crawls.length}</div></div>
  </section>
  <section className="card section"><h2>Scheduler configuration</h2><div className="row">
   <label className="control"><span>Scheduled crawling</span><button className={config.cronEnabled?'primary':'secondary'} onClick={()=>setConfig({...config,cronEnabled:!config.cronEnabled})}>{config.cronEnabled?'Enabled':'Disabled'}</button></label>
   <label className="control"><span>Lookback hours</span><input type="number" min="1" max="720" value={config.lookbackHours} onChange={e=>setConfig({...config,lookbackHours:Number(e.target.value)})}/></label>
   <button className="primary" onClick={saveConfig}>Save configuration</button>
  </div></section>
  <section className="card section"><h2>Add seed</h2><div className="row">
   <label className="control"><span>Slug</span><input value={newSeed.slug} onChange={e=>setNewSeed({...newSeed,slug:e.target.value})}/></label>
   <label className="control"><span>Name</span><input value={newSeed.name} onChange={e=>setNewSeed({...newSeed,name:e.target.value})}/></label>
   <label className="control" style={{minWidth:340}}><span>URL</span><input value={newSeed.url} onChange={e=>setNewSeed({...newSeed,url:e.target.value})}/></label>
   <label className="control"><span>Source kind</span><input value={newSeed.sourceKind} onChange={e=>setNewSeed({...newSeed,sourceKind:e.target.value})}/></label>
   <button className="primary" onClick={addSeed}>Add seed</button>
  </div></section>
  <section className="card section"><h2>Seeds</h2><table className="table"><thead><tr><th>Name</th><th>URL</th><th>Kind</th><th>Status</th><th/></tr></thead><tbody>{seeds.map(s=><tr key={s.id}><td>{s.name}<br/><span className="muted">{s.slug}</span></td><td>{s.url}</td><td>{s.sourceKind}</td><td><span className="pill">{s.enabled?'Enabled':'Disabled'}</span></td><td><button className="secondary" onClick={()=>toggleSeed(s)}>{s.enabled?'Disable':'Enable'}</button></td></tr>)}</tbody></table></section>
  <section className="card section"><h2>Crawl history</h2>{crawls.length===0?<div className="empty">No crawl runs recorded yet.</div>:<table className="table"><thead><tr><th>Run</th><th>Status</th><th>Trigger</th><th>Lookback</th><th>Seeds</th><th>Pages</th><th>Questions</th><th/></tr></thead><tbody>{crawls.map(c=><tr key={c.id}><td>#{c.id}<br/><span className="muted">{new Date(c.createdAt).toLocaleString()}</span></td><td><span className="pill">{c.status}</span></td><td>{c.trigger}</td><td>{c.lookbackHours}h</td><td>{c.sourcesOk}/{c.sourcesPlanned}</td><td>{c.pagesFetched}</td><td>{c.questionsUpserted}</td><td><button className="secondary" onClick={()=>openCrawl(c)}>Details</button></td></tr>)}</tbody></table>}</section>
  {selected&&<section className="card section"><div className="header"><h2>Crawl #{selected.crawl.id} sub-runs</h2><button className="secondary" onClick={()=>setSelected(null)}>Close</button></div><table className="table"><thead><tr><th>Seed</th><th>Status</th><th>Pages</th><th>Posts</th><th>Questions</th><th>Model calls</th><th>Blocked</th></tr></thead><tbody>{selected.subRuns.map(s=><tr key={s.id}><td>{s.sourceSlug}</td><td>{s.status}</td><td>{s.pagesFetched}/{s.pagesSkipped} skipped</td><td>{s.postsExtracted}</td><td>{s.questionsUpserted}</td><td>{s.modelCalls}</td><td>{s.blockedCount}</td></tr>)}</tbody></table></section>}
 </main>
}
