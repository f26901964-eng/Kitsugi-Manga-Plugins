import fs from 'node:fs';
import {chromium} from 'playwright';
const source = fs.readFileSync('keiyoushi-extensions-tr/src/tr/sadscans/src/eu/kanade/tachiyomi/extension/tr/sadscans/SadscansFrontend.kt','utf8');
const templates = [...source.matchAll(/return """([\s\S]*?)"""\.trimIndent\(\)/g)].map(x=>x[1]);
const report={passed:false,scope:'Chromium frontend integration, not Android WebView',steps:[],network:[],workers:0};
let browser;
try {
  browser=await chromium.launch({headless:true});
  const page=await browser.newPage();
  page.on('worker',()=>report.workers++);
  page.on('response',response=>{
    const url=new URL(response.url());
    if(url.hostname==='sadscans.net' && url.pathname.startsWith('/api/')) report.network.push({path:url.pathname,status:response.status()});
  });
  async function read(stage,url,script){
    const step={stage,url};report.steps.push(step);
    await page.goto(url,{waitUntil:'domcontentloaded',timeout:45000});
    const deadline=Date.now()+35000;
    while(Date.now()<deadline){
      const raw=await page.evaluate(script);
      if(raw){const result=JSON.parse(raw);if(result.error)throw new Error(result.error);return result;}
      await page.waitForTimeout(1000);
    }
    step.title=await page.title();step.preview=(await page.locator('body').innerText()).slice(0,1200);
    throw new Error(stage+': frontend timeout');
  }
  const base='https://sadscans.net';
  const listing=(query,page=1)=>templates[0].replace('$options',JSON.stringify({query,sort:'views',type:null,status:null,page}));
  const popular=await read('popular',base+'/seriler',listing(''));
  report.steps.at(-1).count=popular.items.length;
  const item=popular.items[0];if(!item)throw new Error('No popular series');
  const search=await read('search',base+'/seriler',listing(item.name));
  if(!search.items.some(x=>x.href===item.href))throw new Error('Search did not return selected title');
  report.steps.at(-1).count=search.items.length;
  const detail=await read('details',base+item.href,templates[1].replace('$page','1'));
  report.steps.at(-1).chapterCount=detail.chapters.length;
  report.steps.at(-1).totalPages=detail.pagination.totalPages;
  const chapter=detail.chapters[0];if(!chapter)throw new Error('No chapters');
  const pages=await read('reader',base+chapter.href,templates[2].replace('$id',JSON.stringify(chapter.chap_id)));
  report.steps.at(-1).count=pages.urls.length;
  if(!pages.urls.length)throw new Error('No reader pages');
  const image=await page.request.get(pages.urls[0],{headers:{Referer:base+chapter.href}});
  report.steps.push({stage:'first-image',status:image.status(),bytes:(await image.body()).length});
  if(!image.ok())throw new Error('Image request failed');
  report.passed=true;
}catch(error){report.error=String(error);}
finally{
  await browser?.close();fs.mkdirSync('reports',{recursive:true});
  fs.writeFileSync('reports/sadscans-browser-live.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
}
process.exitCode=report.passed?0:1;
