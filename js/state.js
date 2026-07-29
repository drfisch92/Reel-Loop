export const MAX_TRACKS=6;
export const state={
  tracks:Array.from({length:MAX_TRACKS},(_,i)=>({name:`Spur ${i+1}`,blob:null,url:null,mute:false,solo:false,duration:0,volume:1})),
  trackCount:6,selected:0,layout:'raster',stream:null,recorder:null,chunks:[],recording:false,
  pendingRecord:false,playing:false,metro:false,start:0,timer:null,audio:null,animationId:null,
  recordStopTimer:null,playSyncTimer:null,metroNext:0,metroBeat:0
};
export const $=id=>document.getElementById(id);
export const activeTracks=()=>state.tracks.slice(0,state.trackCount);
export const beat=()=>60/Math.max(30,Number($('bpm').value)||90);
export const beats=()=>Number($('meter').value.split('/')[0])||4;
export const loopLength=()=>beat()*beats()*(Number($('bars').value)||4);
