const ALLOWED = Object.freeze([
  'recipe','network','members','finality','sequencing','runtime','deployment',
  'name','chainId','stateMachine'
]);
const ENUMS = Object.freeze({
  network:['devnet','preview','preprod','mainnet'],
  finality:['majority','two-thirds','all'],
  sequencing:['fixed','rotating'], runtime:['jvm','native'],
  deployment:['host','docker-compose']
});
const SAFE_ID = /^[a-z][a-z0-9-]{1,62}$/;
const SAFE_COMPONENT = /^[A-Za-z0-9._-]{3,160}$/;

export function normalizeIntent(raw, recipes, release) {
  const source = raw || {};
  const intent = {
    recipe: String(source.recipe || 'audit-log'), network:String(source.network || 'devnet'),
    members:Number(source.members || 3), finality:String(source.finality || 'two-thirds'),
    sequencing:String(source.sequencing || 'fixed'), runtime:String(source.runtime || 'jvm'),
    deployment:String(source.deployment || 'host'), name:String(source.name || 'my-appchain'),
    chainId:String(source.chainId || source.name || 'my-appchain'),
    stateMachine:String(source.stateMachine || '')
  };
  const recipe = recipes.find(value => value.id === intent.recipe);
  const errors = [];
  if (!recipe || !release.recipes.includes(intent.recipe)) errors.push('Recipe is not supported by this release.');
  for (const [key, values] of Object.entries(ENUMS)) if (!values.includes(intent[key])) errors.push(`${key} is invalid.`);
  if (!Number.isInteger(intent.members) || intent.members < 1 || intent.members > 32) errors.push('Members must be from 1 to 32.');
  if (!SAFE_ID.test(intent.name) || !SAFE_ID.test(intent.chainId)) errors.push('Project name and chain ID must be safe lowercase identifiers.');
  if (recipe && !recipe.runtimeTypes.includes(intent.runtime)) errors.push(`${recipe.id} does not support the ${intent.runtime} runtime.`);
  if (recipe && !recipe.deploymentTargets.includes(intent.deployment)) errors.push(`${recipe.id} does not support ${intent.deployment}.`);
  if (intent.recipe === 'custom-plugin' && !SAFE_COMPONENT.test(intent.stateMachine)) errors.push('Custom plugins require a reviewed state-machine ID.');
  return {intent, recipe, errors};
}

export function encodeDeepLink(intent) {
  const params = new URLSearchParams();
  for (const key of ALLOWED) {
    const value = intent[key];
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value));
  }
  return `#${params.toString()}`;
}

export function decodeDeepLink(fragment) {
  const params = new URLSearchParams(String(fragment || '').replace(/^#/, ''));
  const result = {};
  for (const key of ALLOWED) if (params.has(key)) result[key] = params.get(key);
  return result;
}

function quote(value) { return JSON.stringify(String(value)); }

export function blueprintYaml(intent, yanoVersion) {
  const answer = intent.recipe === 'custom-plugin'
    ? `\n      answers:\n        stateMachine: ${quote(intent.stateMachine)}` : '\n      answers: {}';
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
      capabilities: []${answer}
      topology:
        members: ${intent.members}
        memberKeys: []
        nodeHosts: []
        finality: ${quote(intent.finality)}
        sequencing: ${quote(intent.sequencing)}
        membership: "static"
`;
}

export function resolvePresentation(recipe, capabilities) {
  const indexed = new Map(capabilities.map(capability => [capability.id, capability]));
  const selected = new Set();
  const visit = id => {
    if (selected.has(id)) return;
    const capability = indexed.get(id);
    if (!capability) return;
    selected.add(id);
    (capability.implies || []).forEach(visit);
  };
  (recipe?.capabilities || []).forEach(visit);
  const artifacts = [...new Set([...selected].flatMap(id => indexed.get(id)?.artifacts || []))].sort();
  return {capabilities:[...selected].sort(), artifacts};
}
