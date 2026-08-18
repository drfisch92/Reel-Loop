import{state,$,activeTracks,loopLength,beat,beats}from'./state.js';
import{ensureMedia,attachPreview}from'./camera.js';
import{saveTrack,loadTracks,deleteTrack,saveSettings,loadSettings}from'./storage.js';
import{startMetronome,stopMetronome,countIn,unlockAudio}from'./metronome.js';

const toast=m=>{const t=$('toast');t.textContent=m;t.classList.add('show');clearTimeout(toast.timer);toast.timer=setTimeout(()=>t.classList.remove('show'),1800)};
const selectedTrack=()=>state.tracks[state.selected];
const status=(text,mode='')=>{const e=$('engineStatus');e.textContent=text;e.className=`engineStatus ${mode}`};

function adaptiveLayout(){if(state.layout==='solo')return'solo';if(state.layout==='pip')return'pip';if(state.layout==='split'&&state.trackCount>2)return'raster';return state.layout}

function renderViewer(camera=false){
 const v=$('viewer');v.className=`viewer ${adaptiveLayout()} count-${state.trackCount}`;v.innerHTML='';
 activeTracks().forEach((t,i)=>{
  const c=document.createElement('div');c.className=`cell ${i===state.selected?'selected':''}`;
  c.innerHTML=`<video muted playsinline loop preload="auto"></video><div class="label">SPUR ${i+1}</div><div class="empty">SPUR ${i+1}</div><div class="volumeHUD">🔊 ${Math.round((t.volume??1)*100)}%</div>`;
  bindVolumeGesture(c,i);v.appendChild(c);
  const vid=c.querySelector('video'),empty=c.querySelector('.empty');
  if(t.url){vid.src=t.url;empty.hidden=true;vid.dataset.track=i;if(state.playing)syncVideo(vid,i,true)}
  else if(camera&&i===state.selected)attachPreview(vid).then(()=>empty.hidden=true).catch(()=>{})
 })
}

function renderTracks(){
 const root=$('tracks');root.innerHTML='';root.style.setProperty('--track-count',state.trackCount);
 activeTracks().forEach((t,i)=>{
  const r=document.createElement('div');r.className=`track ${i===state.selected?'selected':''}`;
  r.innerHTML=`<div class="trackSide"><div class="trackName">${t.name}</div><button class="m ${t.mute?'on':''}">M</button><button class="s ${t.solo?'on':''}">S</button><button class="d">⌫</button></div><div class="lane"><div class="clip ${t.url?'show':''}"><span>${t.url?`${t.name} · ${loopLength().toFixed(1)}s`:'Leer'}</span></div></div>`;
  r.onclick=()=>{state.selected=i;renderAll(true);status(`BEREIT · SPUR ${i+1}`)};
  r.querySelector('.m').onclick=e=>{e.stopPropagation();t.mute=!t.mute;applyAudibility();renderTracks()};
  r.querySelector('.s').onclick=e=>{e.stopPropagation();t.solo=!t.solo;applyAudibility();renderTracks()};
  r.querySelector('.d').onclick=async e=>{e.stopPropagation();if(t.url)URL.revokeObjectURL(t.url);Object.assign(t,{blob:null,url:null,mute:false,solo:false,duration:0,volume:1});await deleteTrack(i);renderAll(true);toast(`${t.name} gelöscht`)};
  root.appendChild(r)
 })
}

function audible(i){const soloed=activeTracks().some(t=>t.solo),t=state.tracks[i];return !!t.url&&!t.mute&&(t.volume??1)>0&&(!soloed||t.solo)}
function applyAudibility(){document.querySelectorAll('.cell video').forEach((v,i)=>{const t=state.tracks[i];if(!t?.url){v.muted=true;return}v.muted=!audible(i);v.volume=Math.max(0,Math.min(1,t.volume??1));v.style.opacity=(t.mute||((activeTracks().some(x=>x.solo))&&!t.solo))?.28:1})}
function bindVolumeGesture(cell,i){let startY=0,startVolume=1,moved=false;const hud=cell.querySelector('.volumeHUD');cell.addEventListener('pointerdown',e=>{startY=e.clientY;startVolume=state.tracks[i].volume??1;moved=false;cell.setPointerCapture?.(e.pointerId)});cell.addEventListener('pointermove',e=>{if(!cell.hasPointerCapture?.(e.pointerId))return;const dy=startY-e.clientY;if(Math.abs(dy)<6)return;moved=true;e.preventDefault();const value=Math.max(0,Math.min(1,startVolume+dy/180));state.tracks[i].volume=value;hud.textContent=`🔊 ${Math.round(value*100)}%`;hud.classList.add('show');applyAudibility()});cell.addEventListener('pointerup',e=>{cell.releasePointerCapture?.(e.pointerId);if(!moved){state.selected=i;renderAll(true);status(`BEREIT · SPUR ${i+1}`)}else{clearTimeout(hud.timer);hud.timer=setTimeout(()=>hud.classList.remove('show'),700);persistSettings()}});cell.addEventListener('pointercancel',()=>hud.classList.remove('show'))}
function renderAll(camera=false){renderViewer(camera);renderTracks();applyAudibility()}

function mediaRecorderFor(stream){
 const types=['video/webm;codecs=vp9,opus','video/webm;codecs=vp8,opus','video/webm'];
 const mime=types.find(x=>window.MediaRecorder?.isTypeSupported?.(x));
 return new MediaRecorder(stream,mime?{mimeType:mime,videoBitsPerSecond:5_000_000,audioBitsPerSecond:160_000}:undefined)
}

async function waitForVideos(videos){
 await Promise.all(videos.map(v=>new Promise(resolve=>{
  if(v.readyState>=2)return resolve();
  const done=()=>{v.removeEventListener('canplay',done);resolve()};
  v.addEventListener('canplay',done,{once:true});setTimeout(done,1200)
 })))
}
async function waitForAudioTime(audio,time){
 const ms=Math.max(0,(time-audio.currentTime)*1000);
 if(ms>1)await new Promise(resolve=>setTimeout(resolve,ms))
}

async function record(){
 if(state.recording||state.pendingRecord)return stopRecording(true);
 if(!window.MediaRecorder)return toast('Dieser Browser unterstützt keine Aufnahme');
 stop(true);state.pendingRecord=true;$('record').classList.add('queued');status(`EINZÄHLER · SPUR ${state.selected+1}`,'queued');
 let recorder=null;
 try{
  const trackIndex=state.selected,targetMs=Math.round(loopLength()*1000);
  const stream=await ensureMedia(),audio=await unlockAudio();
  const backingVideos=[...document.querySelectorAll('.cell video')].filter((v,i)=>i!==trackIndex&&state.tracks[i]?.url);
  backingVideos.forEach(v=>{v.pause();v.playbackRate=1;try{v.currentTime=0}catch{}});
  await waitForVideos(backingVideos);
  const chunks=[];recorder=mediaRecorderFor(stream);state.recorder=recorder;state.chunks=chunks;
  recorder.ondataavailable=e=>e.data.size&&chunks.push(e.data);
  recorder.onerror=e=>{console.error(e);toast('Aufnahmefehler')};
  recorder.onstop=async()=>{
   clearTimeout(state.recordStopTimer);
   const blob=new Blob(chunks,{type:recorder.mimeType||'video/webm'}),t=state.tracks[trackIndex];
   if(t.url)URL.revokeObjectURL(t.url);t.blob=blob;t.url=URL.createObjectURL(blob);t.duration=targetMs/1000;
   try{await saveTrack(trackIndex,blob)}catch(e){console.error(e);toast('Speicher voll – alte Spur löschen')}
   backingVideos.forEach(v=>v.pause());
   if(state.recorder===recorder)state.recorder=null;
   state.recording=false;state.pendingRecord=false;
   $('record').classList.remove('recording','queued');renderAll(false);status(`BEREIT · ${t.name}`);toast(`${t.name} gespeichert`)
  };
  stopMetronome();
  const recordStartAudio=await countIn(n=>{const el=$('countIn');el.hidden=!n;if(n)el.textContent=n});
  if(state.metro)startMetronome(recordStartAudio);
  await waitForAudioTime(audio,recordStartAudio);
  backingVideos.forEach(v=>v.play().catch(()=>{}));
  state.pendingRecord=false;$('record').classList.remove('queued');state.recording=true;$('record').classList.add('recording');
  status(`AUFNAHME · SPUR ${trackIndex+1}`,'recording');recorder.start();
  state.recordStopTimer=setTimeout(()=>stopRecording(false),targetMs)
 }catch(e){
  console.error(e);if(recorder?.state==='recording')recorder.stop();
  state.pendingRecord=false;state.recording=false;$('record').classList.remove('queued','recording');$('countIn').hidden=true;
  status(`BEREIT · SPUR ${state.selected+1}`);toast(e?.name==='NotAllowedError'?'Kamera/Mikrofon nicht erlaubt':'Kamera/Mikrofon nicht verfügbar')
 }
}
function stopRecording(manual){
 const recorder=state.recorder;
 if(recorder?.state==='recording'){clearTimeout(state.recordStopTimer);recorder.stop();if(manual)toast('Aufnahme beendet')}
}

function syncVideo(v,i,hard=false){
 if(!state.playing||!state.tracks[i].url)return;
 const desired=((performance.now()-state.start)/1000)%loopLength();
 const current=v.currentTime||0,delta=desired-current;
 if(hard||Math.abs(delta)>.28){try{v.currentTime=desired}catch{};v.playbackRate=1}
 else v.playbackRate=Math.max(.985,Math.min(1.015,1+delta*.025));
 v.muted=!audible(i);v.volume=Math.max(0,Math.min(1,state.tracks[i].volume??1));if(v.paused)v.play().catch(()=>{})
}
async function play(){
 if(!activeTracks().some(t=>t.url))return toast('Noch keine Spur aufgenommen');
 const audio=await unlockAudio();
 stop(false);status('WIEDERGABE','playing');
 const videos=[...document.querySelectorAll('.cell video')].filter((v,i)=>state.tracks[i]?.url);
 videos.forEach(v=>{v.pause();v.playbackRate=1;try{v.currentTime=0}catch{}});
 await waitForVideos(videos);
 const startAudio=audio.currentTime+.12;
 if(state.metro)startMetronome(startAudio);
 await waitForAudioTime(audio,startAudio);
 state.start=performance.now();state.playing=true;
 await Promise.all(videos.map(v=>v.play().catch(()=>{})));
 videos.forEach((v,i)=>syncVideo(v,i,true));
 state.playSyncTimer=setInterval(()=>document.querySelectorAll('.cell video').forEach((v,i)=>syncVideo(v,i)),350);
 animate()
}
function stop(reset=true){
 state.playing=false;clearInterval(state.playSyncTimer);cancelAnimationFrame(state.animationId);
 document.querySelectorAll('.cell video').forEach(v=>{if(v.src){v.pause();if(reset)try{v.currentTime=0}catch{}}});
 if(reset){$('counter').textContent='1.1';$('playhead').style.left='var(--side)';status(`BEREIT · SPUR ${state.selected+1}`)}
}
function animate(){
 if(!state.playing)return;const elapsed=Math.max(0,performance.now()-state.start)/1000,p=elapsed%loopLength(),b=Math.floor(p/beat()),bar=Math.min(Number($('bars').value),Math.floor(b/beats())+1),bt=b%beats()+1;
 $('counter').textContent=`${bar}.${bt}`;const w=$('timeline').clientWidth-82;$('playhead').style.left=`${82+p/loopLength()*w}px`;state.animationId=requestAnimationFrame(animate)
}

async function persistSettings(){await saveSettings({trackCount:state.trackCount,layout:state.layout,bpm:$('bpm').value,meter:$('meter').value,bars:$('bars').value,metro:state.metro,volumes:state.tracks.map(t=>t.volume??1)})}
function setTrackCount(n){state.trackCount=Math.max(1,Math.min(6,n));if(state.selected>=state.trackCount)state.selected=state.trackCount-1;$('trackCount').value=String(state.trackCount);renderAll(true);status(`BEREIT · SPUR ${state.selected+1}`);persistSettings()}

document.querySelectorAll('.layoutbar button').forEach(b=>b.onclick=()=>{state.layout=b.dataset.layout;document.querySelectorAll('.layoutbar button').forEach(x=>x.classList.toggle('on',x===b));renderViewer(true);applyAudibility();persistSettings()});
$('trackCount').onchange=e=>setTrackCount(Number(e.target.value));
['bpm','meter','bars'].forEach(id=>$(id).onchange=()=>{if(state.playing)stop(true);renderTracks();persistSettings()});
$('permission').onclick=()=>ensureMedia().then(()=>{renderViewer(true);status(`BEREIT · SPUR ${state.selected+1}`)}).catch(()=>toast('Berechtigung verweigert'));
$('record').onclick=record;$('play').onclick=play;$('stop').onclick=()=>{if(state.recording)stopRecording(true);else stop(true)};
$('metro').onclick=async()=>{state.metro=!state.metro;$('metro').classList.toggle('active',state.metro);if(state.metro){await unlockAudio();startMetronome()}else stopMetronome();persistSettings();toast(state.metro?'Metronom an':'Metronom aus')};

window.addEventListener('load',async()=>{
 const settings=await loadSettings().catch(()=>null);if(settings){state.trackCount=Number(settings.trackCount)||6;state.layout=settings.layout||'raster';$('trackCount').value=state.trackCount;$('bpm').value=settings.bpm||90;$('meter').value=settings.meter||'4/4';$('bars').value=settings.bars||4;state.metro=!!settings.metro;if(Array.isArray(settings.volumes))settings.volumes.forEach((v,i)=>{if(state.tracks[i])state.tracks[i].volume=Math.max(0,Math.min(1,Number(v)||0))});$('metro').classList.toggle('active',state.metro);document.querySelectorAll('.layoutbar button').forEach(x=>x.classList.toggle('on',x.dataset.layout===state.layout))}
 const blobs=await loadTracks().catch(()=>[]);blobs.forEach((b,i)=>{if(b){state.tracks[i].blob=b;state.tracks[i].url=URL.createObjectURL(b)}});renderAll(true);status('BEREIT · SPUR 1');
 if('serviceWorker'in navigator)navigator.serviceWorker.register('sw.js').catch(()=>{})
});
