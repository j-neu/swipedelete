const https=require("https");
function get(u){return new Promise(r=>{https.get(u,{headers:{"User-Agent":"probe"}},s=>{if(s.statusCode!==200){s.resume();return r({e:"HTTP "+s.statusCode})}let d="";s.on("data",c=>d+=c);s.on("end",()=>r({b:d}))}).on("error",x=>r({e:x.message}))})}
(async()=>{
const bom=process.argv[2];
const r=await get("https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/"+bom+"/compose-bom-"+bom+".pom");
if(r.e){console.log("ERR "+r.e);return}
const deps=r.b.split("</dependency>");
console.log("=== BOM "+bom+" dependencyManagement ("+deps.length+" blocks)");
for(const d of deps){
  const a=(d.match(/<artifactId>(.*?)<\/artifactId>/)||[])[1];
  const g=(d.match(/<groupId>(.*?)<\/groupId>/)||[])[1];
  const v=(d.match(/<version>(.*?)<\/version>/)||[])[1];
  if(a&&v&&g&&g.startsWith("androidx")) console.log((g+":"+a).padEnd(58)+v);
}
})();
