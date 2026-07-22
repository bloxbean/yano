import {blueprintYaml,compatibleCapabilityOptions,decodeDeepLink,encodeDeepLink,
  normalizeIntent} from './studio-core.mjs';

const form = document.querySelector('#intent-form');
const recipeSelect = document.querySelector('#recipe');
const releaseLabel = document.querySelector('#release');
const capabilityChooser = document.querySelector('#capability-chooser');
const answerFields = document.querySelector('#answer-fields');
let recipes=[]; let capabilities=[]; let release={recipes:[],yanoVersion:'unknown'}; let yaml='';

const load = path => fetch(path,{cache:'no-store'}).then(response => {
  if (!response.ok) throw new Error(`Could not load ${path}`); return response.json();
});

function readForm(forcedCapabilities) {
  const data=new FormData(form); const raw=Object.fromEntries(data.entries());
  raw.capabilities=forcedCapabilities || data.getAll('capability'); raw.answers={};
  answerFields.querySelectorAll('[data-answer]').forEach(input=>raw.answers[input.dataset.answer]=input.value);
  return raw;
}
function writeForm(values) {
  for (const [key,value] of Object.entries(values)) {
    if(['capabilities','answers'].includes(key)) continue;
    const field=form.elements.namedItem(key); if(field) field.value=value;
  }
}
function title(value) { return String(value).split(/[-:]/).map(part=>part[0]?.toUpperCase()+part.slice(1)).join(' '); }

function renderCapabilityChooser(intent) {
  const options=compatibleCapabilityOptions(
    recipes.find(recipe=>recipe.id===intent.recipe),capabilities,intent);
  const selected=new Set(intent.capabilities);
  const groups=new Map();
  for(const option of options) {
    const category=option.capability.category;
    if(!groups.has(category)) groups.set(category,[]);
    groups.get(category).push(option);
  }
  const fragments=[];
  for(const [category,items] of [...groups].sort(([a],[b])=>a.localeCompare(b))) {
    const group=document.createElement('fieldset'); group.className='capability-group';
    group.append(Object.assign(document.createElement('legend'),{textContent:title(category)}));
    for(const option of items) {
      const label=document.createElement('label'); label.className='capability-option';
      const checkbox=Object.assign(document.createElement('input'),{
        type:'checkbox',name:'capability',value:option.capability.id,
        checked:selected.has(option.capability.id),
        disabled:!option.compatible && !selected.has(option.capability.id)
      });
      const text=document.createElement('span');
      text.append(
        Object.assign(document.createElement('strong'),{textContent:option.capability.name}),
        Object.assign(document.createElement('small'),{textContent:
          `${option.capability.availability} · ${option.capability.maturity} · ${option.capability.scope || 'chain'}`})
      );
      if(option.reason) text.title=option.reason;
      label.append(checkbox,text); group.append(label);
    }
    fragments.push(group);
  }
  capabilityChooser.replaceChildren(...fragments);
}

function renderAnswers(required, values) {
  const current=values || {};
  answerFields.replaceChildren(...required.map(name=>{
    const label=document.createElement('label'); label.textContent=title(name);
    const input=Object.assign(document.createElement('input'),{
      value:current[name] || '',placeholder:`Non-secret ${name}`
    });
    input.dataset.answer=name; label.append(input); return label;
  }));
  answerFields.hidden=required.length===0;
}

function update(forcedCapabilities, forcedStateMachine) {
  const raw=readForm(forcedCapabilities);
  if(forcedStateMachine) raw.stateMachine=forcedStateMachine;
  let resolved=normalizeIntent(raw,recipes,release,capabilities); let intent=resolved.intent;
  renderCapabilityChooser(intent);
  renderAnswers(resolved.plan.requiredAnswers,intent.answers);
  resolved=normalizeIntent(readForm(),recipes,release,capabilities); intent=resolved.intent;
  const plan=resolved.plan;
  document.querySelector('#plan-title').textContent=resolved.recipe?.name || title(intent.recipe);
  document.querySelector('#recipe-note').textContent=resolved.recipe?.description || '';
  document.querySelector('#trust').textContent=resolved.recipe?.trustStatement || '';
  document.querySelector('#capabilities').replaceChildren(...plan.capabilityDetails.map(value=>{
    const chip=Object.assign(document.createElement('span'),{className:'chip',textContent:value.id});
    chip.title=`${value.availability} · ${value.maturity} · ${value.scope || 'chain'}`; return chip;
  }));
  document.querySelector('#artifacts').replaceChildren(...plan.artifacts.map(value=>
    Object.assign(document.createElement('li'),{textContent:value})));
  document.querySelector('#diagnostics').textContent=resolved.errors.join(' ');
  const status=document.querySelector('#validity'); status.textContent=resolved.errors.length?'Review':'Ready'; status.classList.toggle('error',Boolean(resolved.errors.length));
  yaml=resolved.errors.length?'':blueprintYaml(intent,release.yanoVersion);
  document.querySelector('#preview').textContent=yaml || '# Resolve the items above to preview the blueprint.';
  document.querySelector('#download').disabled=Boolean(resolved.errors.length);
  document.querySelector('#share').disabled=Boolean(resolved.errors.length);
  if (!resolved.errors.length) history.replaceState(null,'',encodeDeepLink(intent));
}

Promise.all([
  load('assets/appchain-recipe-catalog.json'),load('assets/appchain-capability-catalog.json'),
  load('assets/appchain-release-capability-index.json')
]).then(([recipeCatalog,capabilityCatalog,releaseIndex])=>{
  recipes=recipeCatalog.recipes; capabilities=capabilityCatalog.capabilities; release=releaseIndex;
  recipeSelect.replaceChildren(...recipes.map(recipe=>Object.assign(document.createElement('option'),{value:recipe.id,textContent:recipe.name})));
  releaseLabel.textContent=`Yano ${release.yanoVersion} · ${release.schemaStatus}`;
  const deepLink=decodeDeepLink(location.hash); writeForm(deepLink);
  update(deepLink.capabilities || [],deepLink.stateMachine);
}).catch(error=>{ document.querySelector('#diagnostics').textContent=error.message; });

form.addEventListener('input',()=>update());
window.addEventListener('hashchange',()=>{const values=decodeDeepLink(location.hash);writeForm(values);update(values.capabilities || [],values.stateMachine);});
document.querySelector('#download').addEventListener('click',()=>{
  const link=document.createElement('a'); link.href=URL.createObjectURL(new Blob([yaml],{type:'application/yaml'}));
  link.download='appchain.yaml'; link.click(); setTimeout(()=>URL.revokeObjectURL(link.href),0);
});
document.querySelector('#share').addEventListener('click',async event=>{
  const intent=normalizeIntent(readForm(),recipes,release,capabilities).intent;
  const url=`${location.origin}${location.pathname}${encodeDeepLink(intent)}`;
  await navigator.clipboard.writeText(url); event.currentTarget.textContent='Copied'; setTimeout(()=>event.currentTarget.textContent='Copy safe link',1200);
});
