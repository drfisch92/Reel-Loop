export const state={tracks:Array.from({length:6},(_,i)=>({name:`Spur ${i+1}`,blob:null,url:null,mute:false,solo:false})),selected:0,layout:'raster',stream:null,recorder:null,chunks:[],recording:false,playing:false,metro:false,start:0,timer:null,audio:null};
export const $=id=>document.getElementById(id);
export const beat=()=>60/Math.max(30,Number($('bpm').value)||90);
export const beats=()=>Number($('meter').value.split('/')[0])||4;
export const loopLength=()=>beat()*beats()*(Number($('bars').value)||4);
