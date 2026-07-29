import{state,beat,beats}from'./state.js';
function ctx(){return state.audio||(state.audio=new(window.AudioContext||window.webkitAudioContext)())}
export async function unlockAudio(){const a=ctx();if(a.state==='suspended')await a.resume();return a}
export function click(accent=false,force=false){if(!state.metro&&!force)return;const a=ctx(),o=a.createOscillator(),g=a.createGain();o.frequency.value=accent?1250:850;g.gain.setValueAtTime(.16,a.currentTime);g.gain.exponentialRampToValueAtTime(.001,a.currentTime+.055);o.connect(g).connect(a.destination);o.start();o.stop(a.currentTime+.06)}
export function startMetronome(){stopMetronome();let n=0;click(true);state.timer=setInterval(()=>{n++;click(n%beats()===0)},beat()*1000)}
export function stopMetronome(){clearInterval(state.timer);state.timer=null}
export async function countIn(onTick){await unlockAudio();const total=beats();for(let n=0;n<total;n++){onTick?.(total-n);click(n===0,true);await new Promise(r=>setTimeout(r,beat()*1000))}onTick?.(null)}
