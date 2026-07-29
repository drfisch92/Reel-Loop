import{state,beat,beats}from'./state.js';
function ctx(){return state.audio||(state.audio=new(window.AudioContext||window.webkitAudioContext)())}
export function click(accent=false){if(!state.metro)return;const a=ctx(),o=a.createOscillator(),g=a.createGain();o.frequency.value=accent?1200:850;g.gain.setValueAtTime(.12,a.currentTime);g.gain.exponentialRampToValueAtTime(.001,a.currentTime+.05);o.connect(g).connect(a.destination);o.start();o.stop(a.currentTime+.06)}
export function startMetronome(){stopMetronome();let n=0;click(true);state.timer=setInterval(()=>{n++;click(n%beats()===0)},beat()*1000)}
export function stopMetronome(){clearInterval(state.timer);state.timer=null}
