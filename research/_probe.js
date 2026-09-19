const https = require("https");
const GOOGLE = "https://dl.google.com/dl/android/maven2/";
const CENTRAL = "https://repo1.maven.org/maven2/";
const g = p => GOOGLE + p + "/maven-metadata.xml";
const c = p => CENTRAL + p + "/maven-metadata.xml";
const A = [
["compose-bom", g("androidx/compose/compose-bom")],
["compose.ui:ui", g("androidx/compose/ui/ui")],
["compose.foundation:foundation", g("androidx/compose/foundation/foundation")],
["compose.runtime:runtime", g("androidx/compose/runtime/runtime")],
["compose.material3:material3", g("androidx/compose/material3/material3")],
["material-icons-core", g("androidx/compose/material/material-icons-core")],
["material-icons-extended", g("androidx/compose/material/material-icons-extended")],
["m3.adaptive:adaptive", g("androidx/compose/material3/adaptive/adaptive")],
["m3.adaptive:adaptive-layout", g("androidx/compose/material3/adaptive/adaptive-layout")],
["m3.adaptive:adaptive-navigation", g("androidx/compose/material3/adaptive/adaptive-navigation")],
["activity:activity-compose", g("androidx/activity/activity-compose")],
["navigation:navigation-compose", g("androidx/navigation/navigation-compose")],
["navigation:navigation-runtime-ktx", g("androidx/navigation/navigation-runtime-ktx")],
["navigation3:navigation3-runtime", g("androidx/navigation3/navigation3-runtime")],
["navigation3:navigation3-ui", g("androidx/navigation3/navigation3-ui")],
["lifecycle-viewmodel-navigation3", g("androidx/lifecycle/lifecycle-viewmodel-navigation3")],
["lifecycle-runtime-compose", g("androidx/lifecycle/lifecycle-runtime-compose")],
["lifecycle-viewmodel-compose", g("androidx/lifecycle/lifecycle-viewmodel-compose")],
["lifecycle-runtime-ktx", g("androidx/lifecycle/lifecycle-runtime-ktx")],
["core:core-ktx", g("androidx/core/core-ktx")],
["core:core-splashscreen", g("androidx/core/core-splashscreen")],
["datastore:datastore-preferences", g("androidx/datastore/datastore-preferences")],
["room:room-runtime", g("androidx/room/room-runtime")],
["room:room-compiler", g("androidx/room/room-compiler")],
["room:room-ktx", g("androidx/room/room-ktx")],
["androidx.hilt:hilt-navigation-compose", g("androidx/hilt/hilt-navigation-compose")],
["dagger:hilt-android", c("com/google/dagger/hilt-android")],
["dagger:hilt-android-compiler", c("com/google/dagger/hilt-android-compiler")],
["koin-android", c("io/insert-koin/koin-android")],
["koin-androidx-compose", c("io/insert-koin/koin-androidx-compose")],
["koin-compose-viewmodel", c("io/insert-koin/koin-compose-viewmodel")],
["profileinstaller", g("androidx/profileinstaller/profileinstaller")],
["baselineprofile", g("androidx/baselineprofile/baselineprofile")],
["tracing-permissions", g("androidx/tracing/tracing-permissions")],
["work:work-runtime-ktx", g("androidx/work/work-runtime-ktx")],
["media3-exoplayer", g("androidx/media3/media3-exoplayer")],
["media3-ui", g("androidx/media3/media3-ui")],
["media3-session", g("androidx/media3/media3-session")],
["media3-transformer", g("androidx/media3/media3-transformer")],
["test:runner", g("androidx/test/runner")],
["test.ext:junit", g("androidx/test/ext/junit")],
["espresso-core", g("androidx/test/espresso/espresso-core")],
["test:core", g("androidx/test/core")],
["test:rules", g("androidx/test/rules")],
["ui-test-junit4", g("androidx/compose/ui/ui-test-junit4")],
["ui-test-manifest", g("androidx/compose/ui/ui-test-manifest")],
["robolectric", c("org/robolectric/robolectric")],
["kotlin-stdlib", c("org/jetbrains/kotlin/kotlin-stdlib")],
["ksp:symbol-processing-api", c("com/google/devtools/ksp/symbol-processing-api")],
["AGP com.android.tools.build:gradle", g("com/android/tools/build/gradle")],
["kotlinx-coroutines-android", c("org/jetbrains/kotlinx/kotlinx-coroutines-android")],
["okhttp", c("com/squareup/okhttp3/okhttp")],
["gson", c("com/google/code/gson/gson")],
["kotlinx-serialization-json", c("org/jetbrains/kotlinx/kotlinx-serialization-json")]
];
const stable = v => !/(alpha|beta|rc|dev|snapshot|eap)/i.test(v);
function get(url){return new Promise(res=>{const r=https.get(url,{headers:{"User-Agent":"probe"}},s=>{if(s.statusCode!==200){s.resume();return res({err:"HTTP "+s.statusCode});}let d="";s.on("data",x=>d+=x);s.on("end",()=>res({body:d}));});r.on("error",e=>res({err:e.message}));r.setTimeout(45000,()=>{r.destroy();res({err:"timeout"});});});}
(async()=>{const out=[];for(const [n,u] of A){const r=await get(u);if(r.err){out.push({a:n,url:u,error:r.err});continue;}
const la=(r.body.match(/<latest>(.*?)<\/latest>/)||[])[1]||"";
const re=(r.body.match(/<release>(.*?)<\/release>/)||[])[1]||"";
const up=(r.body.match(/<lastUpdated>(.*?)<\/lastUpdated>/)||[])[1]||"";
const vs=[...r.body.matchAll(/<version>(.*?)<\/version>/g)].map(m=>m[1]);
const st=vs.filter(stable);
out.push({a:n,url:u,latest:la,release:re,newestStable:st[st.length-1]||"NONE",newestOverall:vs[vs.length-1]||"NONE",n:vs.length,tail:vs.slice(-9),up:up});}
require("fs").writeFileSync(process.argv[2],JSON.stringify(out,null,1));console.log("WROTE "+out.length);})();
