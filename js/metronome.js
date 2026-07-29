import{state,beat,beats}from'./state.js';

function ctx(){return state.audio||(state.audio=new(window.AudioContext||window.webkitAudioContext)())}
export async function unlockAudio(){const a=ctx();if(a.state==='suspended')await a.resume();return a}

function destroyNode(node){
  try{node.osc.stop()}catch{}
  try{node.osc.disconnect()}catch{}
  try{node.gain.disconnect()}catch{}
  state.metroNodes.delete(node)
}

function scheduleClick(time,accent=false,force=false,generation=state.metroGeneration){
  if((!state.metro&&!force)||generation!==state.metroGeneration)return;
  const a=ctx(),osc=a.createOscillator(),gain=a.createGain();
  const node={osc,gain,generation};state.metroNodes.add(node);
  osc.frequency.setValueAtTime(accent?1320:880,time);
  gain.gain.setValueAtTime(.0001,time);
  gain.gain.exponentialRampToValueAtTime(accent?.22:.14,time+.004);
  gain.gain.exponentialRampToValueAtTime(.0001,time+.06);
  osc.connect(gain).connect(a.destination);osc.start(time);osc.stop(time+.065);
  osc.onended=()=>destroyNode(node)
}

export function startMetronome(startAt=null){
  stopMetronome();
  if(!state.metro)return;
  const a=ctx(),step=beat(),ahead=.10,generation=state.metroGeneration;
  state.metroNext=startAt??a.currentTime+.06;state.metroBeat=0;
  const tick=()=>{
    if(generation!==state.metroGeneration||!state.metro)return;
    while(state.metroNext<a.currentTime+ahead){
      scheduleClick(state.metroNext,state.metroBeat%beats()===0,false,generation);
      state.metroBeat++;state.metroNext+=step
    }
  };
  tick();state.timer=setInterval(tick,25)
}

export function stopMetronome(){
  clearInterval(state.timer);state.timer=null;state.metroGeneration++;
  for(const node of [...state.metroNodes]){
    try{
      const now=ctx().currentTime;
      node.gain.gain.cancelScheduledValues(now);
      node.gain.gain.setValueAtTime(.0001,now)
    }catch{}
    destroyNode(node)
  }
  state.metroNodes.clear()
}

export async function countIn(onTick){
  const a=await unlockAudio(),step=beat(),total=beats(),start=a.currentTime+.12;
  const generation=state.metroGeneration;
  if(state.metro){for(let n=0;n<total;n++)scheduleClick(start+n*step,n===0,false,generation)}
  const began=performance.now();
  for(let n=0;n<total;n++){
    onTick?.(total-n);
    const target=began+(n+1)*step*1000;
    await new Promise(r=>setTimeout(r,Math.max(0,target-performance.now())))
  }
  onTick?.(null);return start+total*step
}
