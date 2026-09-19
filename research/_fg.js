const https = require("https");
function get(url){return new Promise(res=>{const r=https.get(url,{headers:{"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140 Safari/537.36","Accept":"text/html,application/xhtml+xml,*/*"}},s=>{
 if(s.statusCode>=300&&s.statusCode<400&&s.headers.location){return resolve(get(new URL(s.headers.location,url).href));}
 if(s.statusCode!==200){s.resume();return res({err:"HTTP "+s.statusCode});}
 let d="";s.on("data",x=>d+=x);s.on("end",()=>res({body:d}));});
 r.on("error",e=>res({err:e.message}));r.setTimeout(60000,()=>{r.destroy();res({err:"timeout"});});});}
function toText(h){
 return h.replace(/<script[\s\S]*?<\/script>/gi," ").replace(/<style[\s\S]*?<\/style>/gi," ")
  .replace(/<\/(p|div|tr|li|h1|h2|h3|h4|h5|table|section|article|pre)>/gi,"\n")
  .replace(/<br\s*\/?>/gi,"\n").replace(/<\/t[dh]>/gi," | ")
  .replace(/<[^>]+>/g," ")
  .replace(/&nbsp;/g," ").replace(/&amp;/g,"&").replace(/&lt;/g,"<").replace(/&gt;/g,">").replace(/&quot;/g,'"').replace(/&#39;/g,"'")
  .replace(/[ \t]+/g," ").replace(/\n{2,}/g,"\n").trim();
}
(async()=>{
 const url=process.argv[2]; const pat=process.argv[3]; const win=parseInt(process.argv[4]||"420",10);
 const mode=process.argv[5]||"text";
 const r=await get(url);
 if(r.err){console.log("FETCH ERROR "+r.err+" :: "+url);return;}
 let t = mode==="raw" ? r.body.replace(/\n\s*/g,"\n") : toText(r.body);
 console.log("### URL "+url+"  mode="+mode+" len="+t.length);
 if(!pat){console.log(t.slice(0,6000));return;}
 let re; try{re=new RegExp(pat,"gi");}catch(e){console.log("BAD REGEX "+e.message);return;}
 let m,count=0;
 while((m=re.exec(t))!==null&&count<40){
   const s=Math.max(0,m.index-win), e=Math.min(t.length,m.index+m[0].length+win);
   console.log("--- @"+m.index+" ["+m[0]+"]");
   console.log(t.slice(s,e).replace(/\n/g," | "));
   count++;
   if(re.lastIndex===m.index) re.lastIndex++;
 }
 console.log("### matches shown: "+count);
})();
