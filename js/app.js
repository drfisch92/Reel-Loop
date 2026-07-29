import{state,$,activeTracks,loopLength,beat,beats}from'./state.js';
import{ensureMedia,attachPreview}from'./camera.js';
import{saveTrack,loadTracks,deleteTrack,saveSettings,loadSettings}from'./storage.js';
import{startMetronome,stopMetronome,countIn,unlockAudio}from'./metronome.js';

const toast=m=>{const t=$('toast');t.textContent=m;t.classList.add('show');clearTimeout(toast.timer);toast.timer=setTimeout(()=>t.classList.remove('show'),1800)};
const selectedTrack=()=>state.tracks[state.selected];

function adaptiveLayout(){
  if(state.layout==='solo')return 'solo';
  if(state.layout==='pip')return 'pip';
  if(state.layout==='split'&&state.trackCount>2)return 'raster';
  return state.layout;
}

function renderViewer(camera=false){
  const v=$('viewer');v.className=`viewer ${adaptiveLayout()} count-${state.trackCount}`;v.innerHTML='';
  activeTracks().forEach((t,i)=>{
    const c=document.createElement('div');c.className=`cell ${i===state.selected?'selected':''}`;
    c.innerHTML=`<video muted playsinline loop></video><div class="label">SPUR ${i+1}</div><div class="empty">SPUR ${i+1}</div>`;
    c.onclick=()=>{state.selected=i;renderAll(true)};v.appendChild(c);
    const vid=c.querySelector('video'),empty=c.querySelector('.empty');
    if(t.url){vid.src=t.url;empty.hidden=true;if(state.playing&&!t.mute)vid.play().catch(()=>{})}
    else if(camera&&i===state.selected)attachPreview(vid).then(()=>empty.hidden=true).catch(()=>{});
  });
}

function renderTracks(){
  const root=$('tracks');root.innerHTML='';root.style.setProperty('--track-count',state.trackCount);
  activeTracks().forEach((t,i)=>{
    const r=document.createElement('div');r.className=`track ${i===state.selected?'selected':''}`;
    r.innerHTML=`<div class="trackSide"><div class="trackName">${t.name}</div><button class="m ${t.mute?'on':''}" aria-label="Mute">M</button><button class="s ${t.solo?'on':''}" aria-label="Solo">S</button><button class="d" aria-label="Löschen">⌫</button></div><div class="lane"><div class="clip ${t.url?'show':''}">${t.url?`<video src="${t.url}" muted playsinline></video>`:''}<span>${t.url?t.name:'Leer'}</span></div></div>`;
    r.onclick=()=>{state.selected=i;renderAll(true)};
    r.querySelector('.m').onclick=e=>{e.stopPropagation();t.mute=!t.mute;renderAll(false)};
    r.querySelector('.s').onclick=e=>{e.stopPropagation();t.solo=!t.solo;renderAll(false)};
    r.querySelector('.d').onclick=async e=>{e.stopPropagation();if(t.url)URL.revokeObjectURL(t.url);Object.assign(t,{blob:null,url:null,mute:false,solo:false});await deleteTrack(i);renderAll(true);toast(`${t.name} gelöscht`)};
    root.appendChild(r)
  })
}

function applyAudibility(){
  const soloed=activeTracks().some(t=>t.solo);
  document.querySelectorAll('.cell video').forEach((v,i)=>{
    const t=state.tracks[i];v.muted=true;v.style.opacity=(t.mute||(soloed&&!t.solo))?.35:1;
  });
}
function renderAll(camera=false){renderViewer(camera);renderTracks();applyAudibility()}

function mediaRecorderFor(stream){
  const types=['video/webm;codecs=vp9,opus','video/webm;codecs=vp8,opus','video/webm'];
  const mime=types.find(x=>window.MediaRecorder?.isTypeSupported?.(x));
  return new MediaRecorder(stream,mime?{mimeType:mime,videoBitsPerSecond:4_000_000}:undefined)
}

async function record(){
  if(state.recording||state.pendingRecord)return;
  if(!window.MediaRecorder)return toast('Dieser Browser unterstützt keine Aufnahme');
  state.pendingRecord=true;$('record').classList.add('queued');
  try{
    const stream=await ensureMedia();await unlockAudio();
    await countIn(n=>{const el=$('countIn');el.hidden=!n;if(n)el.textContent=n});
    state.chunks=[];state.recorder=mediaRecorderFor(stream);
    state.recorder.ondataavailable=e=>e.data.size&&state.chunks.push(e.data);
    state.recorder.onerror=e=>{console.error(e);toast('Aufnahmefehler')};
    state.recorder.onstop=async()=>{
      const blob=new Blob(state.chunks,{type:state.recorder.mimeType||'video/webm'}),t=selectedTrack();
      if(t.url)URL.revokeObjectURL(t.url);t.blob=blob;t.url=URL.createObjectURL(blob);
      await saveTrack(state.selected,blob);state.recording=false;state.pendingRecord=false;
      $('record').classList.remove('recording','queued');renderAll(false);toast(`${t.name} gespeichert`)
    };
    state.pendingRecord=false;$('record').classList.remove('queued');state.recording=true;$('record').classList.add('recording');
    state.recorder.start(200);toast(`Aufnahme ${selectedTrack().name}`);
    setTimeout(()=>{if(state.recorder?.state==='recording')state.recorder.stop()},Math.round(loopLength()*1000));
  }catch(e){console.error(e);state.pendingRecord=false;state.recording=false;$('record').classList.remove('queued','recording');$('countIn').hidden=true;toast(e?.name==='NotAllowedError'?'Kamera/Mikrofon nicht erlaubt':'Kamera/Mikrofon nicht verfügbar')}
}

function play(){
  if(!activeTracks().some(t=>t.url))return toast('Noch keine Spur aufgenommen');
  stop(false);state.playing=true;state.start=performance.now();
  const soloed=activeTracks().some(t=>t.solo);
  document.querySelectorAll('.cell video').forEach((v,i)=>{const t=state.tracks[i];if(t.url&&!t.mute&&(!soloed||t.solo)){v.currentTime=0;v.play().catch(()=>{})}});
  startMetronome();animate()
}
function stop(reset=true){
  state.playing=false;stopMetronome();cancelAnimationFrame(state.animationId);
  document.querySelectorAll('.cell video').forEach(v=>{if(v.src){v.pause();if(reset)try{v.currentTime=0}catch{}}});
  if(reset){$('counter').textContent='1.1';$('playhead').style.left='var(--side)'}
}
function animate(){
  if(!state.playing)return;const p=((performance.now()-state.start)/1000)%loopLength(),b=Math.floor(p/beat()),bar=Math.floor(b/beats())+1,bt=b%beats()+1;
  $('counter').textContent=`${bar}.${bt}`;const w=$('timeline').clientWidth-82;$('playhead').style.left=`${82+p/loopLength()*w}px`;state.animationId=requestAnimationFrame(animate)
}

async function persistSettings(){await saveSettings({trackCount:state.trackCount,layout:state.layout,bpm:$('bpm').value,meter:$('meter').value,bars:$('bars').value,metro:state.metro})}
function setTrackCount(n){state.trackCount=Math.max(1,Math.min(6,n));if(state.selected>=state.trackCount)state.selected=state.trackCount-1;$('trackCount').value=String(state.trackCount);renderAll(true);persistSettings()}

document.querySelectorAll('.layoutbar button').forEach(b=>b.onclick=()=>{state.layout=b.dataset.layout;document.querySelectorAll('.layoutbar button').forEach(x=>x.classList.toggle('on',x===b));renderViewer(true);applyAudibility();persistSettings()});
$('trackCount').onchange=e=>setTrackCount(Number(e.target.value));
['bpm','meter','bars'].forEach(id=>$(id).onchange=persistSettings);
$('permission').onclick=()=>ensureMedia().then(()=>renderViewer(true)).catch(()=>toast('Berechtigung verweigert'));
$('record').onclick=record;$('play').onclick=play;$('stop').onclick=()=>stop(true);
$('metro').onclick=async()=>{state.metro=!state.metro;$('metro').classList.toggle('active',state.metro);if(state.metro)await unlockAudio();persistSettings();toast(state.metro?'Metronom an':'Metronom aus')};

window.addEventListener('load',async()=>{
  const settings=await loadSettings().catch(()=>null);if(settings){state.trackCount=Number(settings.trackCount)||6;state.layout=settings.layout||'raster';$('trackCount').value=state.trackCount;$('bpm').value=settings.bpm||90;$('meter').value=settings.meter||'4/4';$('bars').value=settings.bars||4;state.metro=!!settings.metro;$('metro').classList.toggle('active',state.metro);document.querySelectorAll('.layoutbar button').forEach(x=>x.classList.toggle('on',x.dataset.layout===state.layout))}
  const blobs=await loadTracks().catch(()=>[]);blobs.forEach((b,i)=>{if(b){state.tracks[i].blob=b;state.tracks[i].url=URL.createObjectURL(b)}});renderAll(true);
  if('serviceWorker'in navigator)navigator.serviceWorker.register('sw.js').catch(()=>{})
});
