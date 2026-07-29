const DB='reelloop4',STORE='tracks';
function open(){return new Promise((res,rej)=>{const q=indexedDB.open(DB,1);q.onupgradeneeded=()=>q.result.createObjectStore(STORE);q.onsuccess=()=>res(q.result);q.onerror=()=>rej(q.error)})}
export async function saveTrack(i,blob){const db=await open();return new Promise((res,rej)=>{const tx=db.transaction(STORE,'readwrite');tx.objectStore(STORE).put(blob,i);tx.oncomplete=res;tx.onerror=()=>rej(tx.error)})}
export async function loadTracks(){const db=await open();const out=[];for(let i=0;i<6;i++)out[i]=await new Promise(res=>{const q=db.transaction(STORE).objectStore(STORE).get(i);q.onsuccess=()=>res(q.result||null);q.onerror=()=>res(null)});return out}
export async function deleteTrack(i){const db=await open();db.transaction(STORE,'readwrite').objectStore(STORE).delete(i)}
