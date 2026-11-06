const UA='Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';
async function get(u){const r=await fetch(u,{headers:{'User-Agent':UA,'Accept-Language':'tr-TR,tr;q=0.9'}});const t=await r.text();return {status:r.status,t};}
const {t}=await get('https://okutoon.com/solo-leveling');
const i=t.indexOf('chapter-item');
console.log('--- chapter-item civarı ---');
console.log(t.slice(Math.max(0,i-500), i+900));
