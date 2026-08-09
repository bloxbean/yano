(() => {
  'use strict';
  let session = null;
  let sequence = 0;
  let selected = null;
  const pending = new Map();
  const byId = id => document.getElementById(id);
  const showError = error => { const node=byId('error'); node.textContent=String(error?.message||error); node.style.display='block'; setTimeout(()=>node.style.display='none',5000); };
  const pretty = value => JSON.stringify(value,null,2);
  function call(method,payload={}) {
    if(!session) return Promise.reject(new Error('UI bridge is not ready'));
    const requestId=`history-${++sequence}`;
    return new Promise((resolve,reject)=>{pending.set(requestId,{resolve,reject});parent.postMessage({type:'yano-ui-request',uiApiVersion:1,sessionNonce:session.sessionNonce,chainId:session.chainId,method,requestId,payload},'*');});
  }
  window.addEventListener('message', event => {
    const message=event.data;
    if(event.source!==parent||!message||typeof message!=='object') return;
    if(message.type==='yano-ui-init'&&message.uiApiVersion===1){session=message;byId('connection').textContent=message.chainId;byId('connection').className='pill ok';loadOverview().catch(showError);return;}
    if(message.type==='yano-ui-response'&&session&&message.sessionNonce===session.sessionNonce){const request=pending.get(message.requestId);if(!request)return;pending.delete(message.requestId);message.ok?request.resolve(message.result):request.reject(new Error(message.error||'bridge request failed'));}
  });
  document.querySelectorAll('nav button').forEach(button=>button.addEventListener('click',()=>{document.querySelectorAll('nav button,.tab').forEach(node=>node.classList.remove('active'));button.classList.add('active');byId(button.dataset.tab).classList.add('active');}));
  async function domain(path,parameters={}){return call('app-chain.domain',{path,parameters});}
  async function loadOverview(){
    const [status,anchor,history,epochs]=await Promise.all([call('app-chain.status'),call('app-chain.anchor').catch(e=>({unavailable:e.message})),domain('status'),domain('epochs',{limit:'15'})]);
    const manifest=status.capabilityManifest||{};
    byId('summary').innerHTML=`<b>Chain</b><span>${escapeText(session.chainId)}</span><b>Application</b><span>${escapeText(history.applicationId||'—')}</span><b>Height</b><span>${escapeText(history.committedHeight)}</span><b>Latest epoch</b><span>${escapeText(history.latestEpoch??'not observed')}</span><b>State root</b><span>${escapeText(short(history.stateRoot))}</span>`;
    const capabilities=[...(manifest.components||[]).map(x=>x.id),...(manifest.crossCutting||[]).filter(x=>x.enabled).map(x=>x.capabilityId),...(manifest.proofSubjects||[]).map(x=>x.subjectId)];
    byId('capabilities').innerHTML=capabilities.length?capabilities.map(x=>`<span>${escapeText(x)}</span>`).join(''):'<span>parameters only</span>';
    byId('anchor').textContent=pretty(anchor);byId('epochs').innerHTML=(epochs.epochs||[]).map(x=>`<span>${x}</span>`).join('')||'<span>none yet</span>';
  }
  document.querySelectorAll('form[data-query]').forEach(form=>form.addEventListener('submit',async event=>{event.preventDefault();const data=Object.fromEntries(new FormData(form));let path,parameters={};const kind=form.dataset.query;
    if(kind==='parameters')path=`epochs/${data.epoch}/parameters`;
    if(kind==='stake')path=`epochs/${data.epoch}/stake/${data.type}/${data.hash}`;
    if(kind==='drep')path=`epochs/${data.epoch}/dreps/${data.type}/${data.hash}`;
    if(kind==='proposal'){path=`proposals/${data.tx}/${data.index}`;parameters.epoch=String(data.epoch);}
    const output=form.parentElement.querySelector('.result');output.textContent='Loading…';try{const result=await domain(path,parameters);output.textContent=pretty(result);selectProof(result);}catch(error){output.textContent=String(error.message||error);showError(error);}
  }));
  function selectProof(result){selected=result;const proof=result?.proof;const primary=proof?.physicalKey||proof?.factPhysicalKey;byId('generate').disabled=!primary;byId('proof-status').textContent=primary?'Primary proof coordinates ready. Open Proof lab to verify bindings.':'This dataset uses an authenticated snapshot or has no primary proof coordinate; export a bundle with the CLI.';}
  byId('generate').addEventListener('click',async()=>{const proof=selected?.proof;const key=proof?.physicalKey||proof?.factPhysicalKey;if(!key)return;byId('proof-result').textContent='Loading proof…';try{const envelope=await call('app-chain.proof',{keyHex:key,height:selected.committedHeight});const checks={chain:envelope.chainId===session.chainId,key:String(envelope.keyHex).toLowerCase()===String(key).toLowerCase(),height:Number(envelope.height)===Number(selected.committedHeight),root:String(envelope.stateRootHex||envelope.stateRoot).toLowerCase()===String(selected.stateRoot).toLowerCase(),profile:String(envelope.commitmentProfileId||'').startsWith('mpf-')};const valid=Object.values(checks).every(Boolean);byId('proof-status').textContent=valid?'PASS — proof envelope is bound to the selected chain, key, height, root and MPF profile. Full trie/L1 verification remains available in the CLI.':'FAIL — proof envelope binding mismatch.';byId('proof-result').textContent=pretty({checks,envelope});}catch(error){byId('proof-status').textContent='FAIL — '+error.message;showError(error);}});
  const short=value=>typeof value==='string'&&value.length>22?`${value.slice(0,12)}…${value.slice(-8)}`:value??'—';
  const escapeText=value=>String(value??'—').replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
})();
