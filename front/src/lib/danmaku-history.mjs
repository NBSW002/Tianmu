export function createDanmakuHistoryLoader(fetchPage, receive, notify=()=>{}) {
  let stopped=false, active=null;
  const loaded=new Map(), retryAt=new Map(), seen=new Set();
  function accept(items) { if(stopped)return []; return items.filter(item=>{const id=String(item.bulletId||'');if(!id||seen.has(id))return false;seen.add(id);return true;}); }
  async function bucket(index,controller) {
    let afterTime=index*60,afterId='0';
    for (;;) {
      const page=await fetchPage(index,afterTime,afterId,controller.signal);
      if(stopped||controller.signal.aborted)return;
      receive(accept(page.items.map(item=>({...item,time:item.playbackTime}))));
      if(!page.hasMore)return;
      if(page.nextTime<afterTime || (page.nextTime===afterTime && BigInt(page.nextId)<=BigInt(afterId)))throw Error('弹幕分页未前进');
      afterTime=page.nextTime;afterId=page.nextId;
    }
  }
  function ensure(seconds) {
    if(stopped||!Number.isFinite(seconds)||seconds<0)return;
    const index=Math.floor(seconds/60);
    if(loaded.has(index))return loaded.get(index);
    if(Date.now()<(retryAt.get(index)||0))return;
    if(active){active.controller.abort();loaded.delete(active.index);}
    const controller=new AbortController(); const task={index,controller}; active=task;
    const promise=bucket(index,controller).then(()=>{if(controller.signal.aborted && loaded.get(index)===promise)loaded.delete(index);})
      .catch(error=>{if(loaded.get(index)===promise)loaded.delete(index);if(!stopped&&!controller.signal.aborted){retryAt.set(index,Date.now()+5000);notify(error);}})
      .finally(()=>{if(active===task)active=null;});
    loaded.set(index,promise);return promise;
  }
  return {accept,ensure,stop(){stopped=true;active?.controller.abort();loaded.clear();seen.clear();}};
}
