const ALLOWED = Object.freeze([
  'recipe','network','members','finality','sequencing','membership','runtime','deployment',
  'name','chainId','capabilities','stateMachine'
]);
const ENUMS = Object.freeze({
  network:['devnet','preview','preprod','mainnet'],
  finality:['majority','two-thirds','all'],
  sequencing:['fixed','rotating'], membership:['static','governed'],
  runtime:['jvm','native'], deployment:['host','docker-compose']
});
const SAFE_ID = /^[a-z][a-z0-9-]{1,62}$/;

function list(value) {
  if (Array.isArray(value)) return [...new Set(value.map(String).filter(Boolean))].sort();
  return [...new Set(String(value || '').split(',').map(item=>item.trim()).filter(Boolean))].sort();
}

function answers(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  return Object.fromEntries(Object.entries(value).map(([key,item])=>[String(key),String(item)]));
}

export function resolvePresentation(recipe, capabilities, additional=[], intent={}) {
  const indexed = new Map(capabilities.map(capability => [capability.id, capability]));
  const requested = new Set((recipe?.capabilities || []).filter(id=>
    !id.startsWith('sequencer:') && !id.startsWith('membership:')));
  requested.add(`sequencer:${intent.sequencing || 'fixed'}`);
  requested.add(`membership:${intent.membership || 'static'}`);
  list(additional).forEach(id=>requested.add(id));
  const selected = new Set(); const implied = new Set(); const errors=[];
  const queue=[...requested];
  while(queue.length) {
    const id=queue.shift(); if(selected.has(id)) continue;
    const capability=indexed.get(id);
    if(!capability) { errors.push(`Unknown capability: ${id}.`); continue; }
    if(list(additional).includes(id) && capability.selectable === false) {
      errors.push(`${id} is derived from the distribution and cannot be selected.`); continue;
    }
    if(intent.runtime && !(capability.runtimeTypes || []).includes(intent.runtime))
      errors.push(`${id} does not support the ${intent.runtime} runtime.`);
    if(intent.deployment && !(capability.deploymentTargets || []).includes(intent.deployment))
      errors.push(`${id} does not support ${intent.deployment}.`);
    selected.add(id);
    [...(capability.requires || []),...(capability.implies || [])].forEach(dependency=>{
      if(!requested.has(dependency)) implied.add(dependency); queue.push(dependency);
    });
  }
  const providers=new Map();
  for(const id of selected) {
    const capability=indexed.get(id); if(!capability) continue;
    for(const conflict of capability.conflicts || []) if(selected.has(conflict))
      errors.push(`${id} conflicts with ${conflict}.`);
    for(const contract of capability.provides || []) {
      const prior=providers.get(contract);
      if(prior && prior!==id) errors.push(`${prior} and ${id} both provide ${contract}.`);
      else providers.set(contract,id);
    }
  }
  const details=[...selected].sort().map(id=>indexed.get(id)).filter(Boolean);
  const artifacts=[...new Set(details.flatMap(capability=>capability.artifacts || []))].sort();
  const requiredAnswers=[...new Set([
    ...(recipe?.nonSecretAnswers || []),
    ...details.flatMap(capability=>capability.nonSecretAnswers || [])
  ])].sort();
  return {capabilities:details.map(value=>value.id),capabilityDetails:details,
    implied:[...implied].sort(),artifacts,requiredAnswers,errors:[...new Set(errors)]};
}

export function compatibleCapabilityOptions(recipe, capabilities, intent) {
  const base=resolvePresentation(recipe,capabilities,[],intent);
  return capabilities.filter(capability=>capability.selectable !== false
      && !['sequencer','membership'].includes(capability.category)
      && !base.capabilities.includes(capability.id))
    .map(capability=>{
      const trial=resolvePresentation(recipe,capabilities,[capability.id],intent);
      return {capability,compatible:trial.errors.length===0,reason:trial.errors[0] || ''};
    });
}

export function normalizeIntent(raw, recipes, release, capabilities=[]) {
  const source = raw || {};
  const members = source.members === undefined || source.members === null || source.members === ''
    ? 3 : Number(source.members);
  const intent = {
    recipe:String(source.recipe || 'audit-log'), network:String(source.network || 'devnet'),
    members, finality:String(source.finality || 'two-thirds'),
    sequencing:String(source.sequencing || 'fixed'), membership:String(source.membership || 'static'),
    runtime:String(source.runtime || 'jvm'), deployment:String(source.deployment || 'host'),
    name:String(source.name || 'my-appchain'), chainId:String(source.chainId || source.name || 'my-appchain'),
    capabilities:list(source.capabilities), answers:answers(source.answers)
  };
  if(source.stateMachine && !intent.answers.stateMachine)
    intent.answers.stateMachine=String(source.stateMachine);
  const recipe = recipes.find(value => value.id === intent.recipe);
  const errors = [];
  if (!recipe || !release.recipes.includes(intent.recipe)) errors.push('Recipe is not supported by this release.');
  for (const [key, values] of Object.entries(ENUMS)) if (!values.includes(intent[key])) errors.push(`${key} is invalid.`);
  if (!Number.isInteger(intent.members) || intent.members < 1 || intent.members > 32) errors.push('Members must be from 1 to 32.');
  if (!SAFE_ID.test(intent.name) || !SAFE_ID.test(intent.chainId)) errors.push('Project name and chain ID must be safe lowercase identifiers.');
  if (recipe && !recipe.runtimeTypes.includes(intent.runtime)) errors.push(`${recipe.id} does not support the ${intent.runtime} runtime.`);
  if (recipe && !recipe.deploymentTargets.includes(intent.deployment)) errors.push(`${recipe.id} does not support ${intent.deployment}.`);
  const plan=resolvePresentation(recipe,capabilities,intent.capabilities,intent);
  errors.push(...plan.errors);
  for(const name of plan.requiredAnswers) if(!intent.answers[name]?.trim())
    errors.push(`Provide the non-secret ${name} value.`);
  return {intent, recipe, plan, errors:[...new Set(errors)]};
}

export function encodeDeepLink(intent) {
  const params = new URLSearchParams();
  for (const key of ALLOWED) {
    const raw=key==='stateMachine' ? intent.answers?.stateMachine : intent[key];
    const value=key==='capabilities' ? list(raw).join(',') : raw;
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value));
  }
  return `#${params.toString()}`;
}

export function decodeDeepLink(fragment) {
  const params = new URLSearchParams(String(fragment || '').replace(/^#/, ''));
  const result = {};
  for (const key of ALLOWED) if (params.has(key)) result[key] =
    key==='capabilities' ? list(params.get(key)) : params.get(key);
  return result;
}

function quote(value) { return JSON.stringify(String(value)); }

export function blueprintYaml(intent, yanoVersion) {
  const selected=list(intent.capabilities);
  const capabilityYaml=selected.length
    ? `\n${selected.map(value=>`        - ${quote(value)}`).join('\n')}` : ' []';
  const answerEntries=Object.entries(answers(intent.answers)).sort(([left],[right])=>left.localeCompare(right));
  const answerYaml=answerEntries.length
    ? `\n${answerEntries.map(([key,value])=>`        ${key}: ${quote(value)}`).join('\n')}` : ' {}';
  return `# yaml-language-server: $schema=./schema/appchain-blueprint.schema.json
apiVersion: yano.bloxbean.com/v1alpha1
kind: AppChainProject
metadata:
  name: ${quote(intent.name)}
spec:
  yanoVersion: ${quote(yanoVersion)}
  network: ${quote(intent.network)}
  runtime:
    type: ${quote(intent.runtime)}
  deployment:
    target: ${quote(intent.deployment)}
  chains:
    - chainId: ${quote(intent.chainId)}
      recipe: ${quote(intent.recipe)}
      capabilities:${capabilityYaml}
      answers:${answerYaml}
      topology:
        members: ${intent.members}
        memberKeys: []
        nodeHosts: []
        finality: ${quote(intent.finality)}
        sequencing: ${quote(intent.sequencing)}
        membership: ${quote(intent.membership || 'static')}
`;
}
