import {blueprintYaml,decodeDeepLink,encodeDeepLink,normalizeIntent,resolvePresentation} from './studio-core.mjs';

const form = document.querySelector('#intent-form');
const recipeSelect = document.querySelector('#recipe');
const releaseLabel = document.querySelector('#release');
const customField = document.querySelector('#custom-machine-field');
let recipes=[]; let capabilities=[]; let release={recipes:[],yanoVersion:'unknown'}; let yaml='';

const load = path => fetch(path,{cache:'no-store'}).then(response => {
  if (!response.ok) throw new Error(`Could not load ${path}`); return response.json();
});

function readForm() { return Object.fromEntries(new FormData(form).entries()); }
function writeForm(values) { for (const [key,value] of Object.entries(values)) { const field=form.elements.namedItem(key); if(field) field.value=value; } }
function title(value) { return value.split('-').map(part=>part[0].toUpperCase()+part.slice(1)).join(' '); }

function update() {
  const resolved=normalizeIntent(readForm(),recipes,release); const intent=resolved.intent;
  customField.hidden=intent.recipe!=='custom-plugin';
  const plan=resolvePresentation(resolved.recipe,capabilities);
  document.querySelector('#plan-title').textContent=title(intent.recipe);
  document.querySelector('#recipe-note').textContent=resolved.recipe?.description || '';
  document.querySelector('#trust').textContent=resolved.recipe?.trustStatement || '';
  document.querySelector('#capabilities').replaceChildren(...plan.capabilities.map(value=>Object.assign(document.createElement('span'),{className:'chip',textContent:value})));
  document.querySelector('#artifacts').replaceChildren(...plan.artifacts.map(value=>Object.assign(document.createElement('li'),{textContent:value})));
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
  recipeSelect.replaceChildren(...recipes.map(recipe=>Object.assign(document.createElement('option'),{value:recipe.id,textContent:title(recipe.id)})));
  releaseLabel.textContent=`Yano ${release.yanoVersion} · ${release.schemaStatus}`;
  writeForm(decodeDeepLink(location.hash)); update();
}).catch(error=>{ document.querySelector('#diagnostics').textContent=error.message; });

form.addEventListener('input',update); window.addEventListener('hashchange',()=>{writeForm(decodeDeepLink(location.hash));update();});
document.querySelector('#download').addEventListener('click',()=>{
  const link=document.createElement('a'); link.href=URL.createObjectURL(new Blob([yaml],{type:'application/yaml'}));
  link.download='appchain.yaml'; link.click(); setTimeout(()=>URL.revokeObjectURL(link.href),0);
});
document.querySelector('#share').addEventListener('click',async event=>{
  const url=`${location.origin}${location.pathname}${encodeDeepLink(normalizeIntent(readForm(),recipes,release).intent)}`;
  await navigator.clipboard.writeText(url); event.currentTarget.textContent='Copied'; setTimeout(()=>event.currentTarget.textContent='Copy safe link',1200);
});
