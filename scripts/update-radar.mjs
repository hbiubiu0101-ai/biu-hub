import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdir,writeFile,rename} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const run=promisify(execFile);
const root=fileURLToPath(new URL('../',import.meta.url));
const clean=x=>String(x??'').replace(/[<>\u0000-\u001f]/g,'').slice(0,500);
const city=clean(process.env.RADAR_CITY||'广州');
const dest=clean(process.env.RADAR_DESTINATION||'北京');
const day=n=>new Date(Date.now()+8*3600000+n*86400000).toISOString().slice(0,10);
const checkin=process.env.RADAR_CHECKIN||day(0),checkout=process.env.RADAR_CHECKOUT||day(1);
for(const d of [checkin,checkout])if(!/^\d{4}-\d{2}-\d{2}$/.test(d))throw Error('日期格式应为YYYY-MM-DD');
if(checkout<=checkin)throw Error('离店日期必须晚于入住日期');
function link(raw,domains){try{const u=new URL(raw);return ['https:','http:'].includes(u.protocol)&&!u.username&&!u.password&&u.pathname!=='/'&&domains.some(d=>u.hostname===d||u.hostname.endsWith('.'+d))?u.href:''}catch{return ''}}
const flyLink=s=>link(s,['feizhu.com','fliggy.com','alitrip.com','taobao.com']);
function amount(s){return /^\d+(\.\d+)?$/.test(String(s).replace(/^¥/,''))?Number(String(s).replace(/^¥/,'')):null}
async function query(args){
 const bin=process.env.FLYAI_BIN||'flyai';
 const command=bin.endsWith('.cjs')?process.execPath:bin,argv=bin.endsWith('.cjs')?[bin,...args]:args;
 const {stdout}=await run(command,argv,{timeout:55000,maxBuffer:4*1024*1024,env:process.env});
 const j=JSON.parse(stdout.trim());
 if(j.status!==0||!Array.isArray(j.data?.itemList))throw Error('平台未返回成功的商品列表');
 return j;
}
const snapshot={version:2,generatedAt:new Date().toISOString(),city,destination:dest,checkin,checkout,providers:{},items:{hotel:[],flight:[],meituan:[],taobao:[]}};
async function hotels(){
 try{
  const j=await query(['search-hotel','--dest-name',city,'--check-in-date',checkin,'--check-out-date',checkout]);
  const capturedAt=new Date().toISOString();
  snapshot.items.hotel=j.data.itemList.filter(x=>flyLink(x.detailUrl)).map(x=>({id:'flyhotel_'+clean(x.shId),name:clean(x.name),tier:({'经济型':'经济','舒适型':'舒适','高档型':'高档','豪华型':'豪华'})[x.star]||'未分类',type:'未提供',rating:null,dist:clean(x.interestsPoi||'未提供'),address:clean(x.address),price:amount(x.price),priceText:clean(x.price),original:null,tag:'接口查询快照',accent:'#38BDF8',detailUrl:flyLink(x.detailUrl),realSnapshot:true,sourceName:'飞猪官方接口',capturedAt,date:checkin,checkout}));
  snapshot.providers.hotel={state:'ok',note:clean(j.systemMessage),capturedAt};
 }catch(e){snapshot.providers.hotel={state:'error',note:'酒店接口查询失败：'+clean(e.message)}}
}
async function flights(){
 const errors=[];let successes=0;
 const days=Math.min(7,Math.max(1,Number(process.env.RADAR_FLIGHT_DAYS)||2));
 for(let i=0;i<days;i++){
  const date=day(i);
  try{
   const j=await query(['search-flight','--origin',city,'--destination',dest,'--dep-date',date]);
   const capturedAt=new Date().toISOString();successes++;
   snapshot.items.flight.push(...j.data.itemList.filter(x=>flyLink(x.jumpUrl)).map((x,index)=>{
    const segments=x.journeys?.flatMap(j=>j.segments||[])||[],a=segments[0]||{},b=segments.at(-1)||{};
    return {id:'flyflight_'+date+'_'+clean(a.marketingTransportNo)+'_'+index,name:clean(segments.map(t=>t.marketingTransportNo).join('+'))+' '+clean(a.depCityName||city)+'→'+clean(b.arrCityName||dest),airline:clean(a.marketingTransportName||'未提供'),from:clean(a.depCityName||city),to:clean(b.arrCityName||dest),dep:clean(a.depDateTime?.slice(11,16)||'未提供'),arr:clean(b.arrDateTime?.slice(11,16)||'未提供'),date:clean(a.depDateTime?.slice(0,10)||date),arrDate:clean(b.arrDateTime?.slice(0,10)),price:amount(x.ticketPrice),priceText:clean(x.ticketPrice),original:null,discount:'未提供',tag:(segments.length>1?'含中转 · ':'直达 · ')+'税费以订单为准',connections:segments.length-1,routeSegments:segments.map(t=>clean(t.depCityName)+' '+clean(t.depDateTime)+' → '+clean(t.arrCityName)+' '+clean(t.arrDateTime)),jumpUrl:flyLink(x.jumpUrl),realSnapshot:true,sourceName:'飞猪官方接口',capturedAt};
   }));
  }catch(e){errors.push(date+' 查询失败')}
 }
 snapshot.providers.flight={state:successes?(errors.length?'partial':'ok'):'error',note:'查询'+city+'→'+dest+'，覆盖'+days+'天；体验模式结果可能受限。'+errors.join('；')};
}
await hotels();await flights();
snapshot.items.meituan=[{id:'share_51abksqz',name:'茉莉奶白｜茉莉奶白（中杯）',category:'美食',price:11.99,priceText:'11.99',original:null,discount:'未核验',dist:'未提供',sales:'未知',tag:'用户分享 · 价格未核验',shareUrl:'http://dpurl.cn/51abksqz',source:'user_share',realSnapshot:true,sourceName:'用户分享（非接口报价）',capturedAt:'2026-10-23T14:32:00Z'}];
snapshot.providers.meituan={state:'not_configured',note:'自动商品接口未授权；只保留用户提供的商品分享，不代表已接通美团商品查询。'};
snapshot.providers.taobao={state:'not_configured',note:'淘宝联盟商品查询尚未授权，没有可展示的接口商品。'};
snapshot.generatedAt=new Date().toISOString();
const dir=root+'tools/saving-radar/data';await mkdir(dir,{recursive:true});
await writeFile(dir+'/quotes.json.tmp',JSON.stringify(snapshot,null,2));await rename(dir+'/quotes.json.tmp',dir+'/quotes.json');
console.log(JSON.stringify({hotel:snapshot.items.hotel.length,flight:snapshot.items.flight.length,providers:snapshot.providers},null,2));
if(snapshot.providers.hotel.state==='error'&&snapshot.providers.flight.state==='error')process.exitCode=1;
