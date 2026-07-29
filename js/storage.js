const DB='reelloop4',TRACKS='tracks',SETTINGS='settings';
function open(){return new Promise((res,rej)=>{const q=indexedDB.open(DB,2);q.onupgradeneeded=()=>{const db=q.result;if(!db.objectStoreNames.contains(TRACKS))db.createObjectStore(TRACKS);if(!db.objectStoreNames.contains(SETTINGS))db.createObjectStore(SETTINGS)};q.onsuccess=()=>res(q.result);q.onerror=()=>rej(q.error)})}
async function put(store,key,value){const db=await open();return new Promise((res,rej)=>{const tx=db.transaction(store,'readwrite');tx.objectStore(store).put(value,key);tx.oncomplete=res;tx.onerror=()=>rej(tx.error)})}
async function get(store,key){const db=await open();return new Promise(res=>{const q=db.transaction(store).objectStore(store).get(key);q.onsuccess=()=>res(q.result??null);q.onerror=()=>res(null)})}
export const saveTrack=(i,blob)=>put(TRACKS,i,blob);
export async function loadTracks(){const out=[];for(let i=0;i<6;i++)out[i]=await get(TRACKS,i);return out}
export async function deleteTrack(i){const db=await open();db.transaction(TRACKS,'readwrite').objectStore(TRACKS).delete(i)}
export const saveSettings=settings=>put(SETTINGS,'project',settings);
export const loadSettings=()=>get(SETTINGS,'project');
