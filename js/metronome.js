import{state,beat,beats}from'./state.js';

function ctx(){return state.audio||(state.audio=new(window.AudioContext||window.webkitAudioContext)())}
export async function unlockAudio(){const a=ctx();if(a.state==='suspended')await a.resume();return a}

function scheduleClick(time,accent=false,force=false){
  if(!state.metro&&!force)return;
  const a=ctx(),o=a.createOscillator(),g=a.createGain();
  o.frequency.setValueAtTime(accent?1320:880,time);
  g.gain.setValueAtTime(.0001,time);
  g.gain.exponentialRampToValueAtTime(accent?.22:.14,time+.004);
  g.gain.exponentialRampToValueAtTime(.0001,time+.06);
  o.connect(g).connect(a.destination);o.start(time);o.stop(time+.065)
}

export function startMetronome(startAt=null){
  stopMetronome();
  const a=ctx(),step=beat(),ahead=.12;
  state.metroNext=startAt??a.currentTime+.06;state.metroBeat=0;
  const tick=()=>{
    while(state.metroNext<a.currentTime+ahead){
      scheduleClick(state.metroNext,state.metroBeat%beats()===0);
      state.metroBeat++;state.metroNext+=step
    }
  };
  tick();state.timer=setInterval(tick,25)
}
export function stopMetronome(){clearInterval(state.timer);state.timer=null}

export async function countIn(onTick){
  const a=await unlockAudio(),step=beat(),total=beats(),start=a.currentTime+.12;
  for(let n=0;n<total;n++)scheduleClick(start+n*step,n===0,true);
  const began=performance.now();
  for(let n=0;n<total;n++){
    onTick?.(total-n);
    const target=began+(n+1)*step*1000;
    await new Promise(r=>setTimeout(r,Math.max(0,target-performance.now())))
  }
  onTick?.(null);return start+total*step
}
