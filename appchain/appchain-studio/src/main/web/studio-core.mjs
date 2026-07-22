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
  if(list(additional).some(id=>(indexed.get(id)?.provides || []).includes('state-machine')
      && indexed.get(id)?.catalogSource === 'external')) requested.delete('state:custom-plugin');
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
  intent.componentCatalogs=Array.isArray(source.componentCatalogs)
    ? source.componentCatalogs.map(value=>({...value})) : [];
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
  const catalogs=Array.isArray(intent.componentCatalogs) ? intent.componentCatalogs : [];
  const catalogYaml=catalogs.length ? `  componentCatalogs:\n${catalogs.map(value=>
    `    - path: ${quote(value.path)}\n      trustedKeyId: ${quote(value.trustedKeyId)}\n      trustedPublicKey: ${quote(value.trustedPublicKey)}`).join('\n')}\n` : '';
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
${catalogYaml}  chains:
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

const CATALOG_ID=/^[a-z][a-z0-9.-]{0,127}$/;
const CAPABILITY_ID=/^[a-z][a-z0-9.-]*(?::[a-z][a-z0-9.-]*)?$/;
const SHA256=/^[0-9a-f]{64}$/;
const PUBLIC_KEY=/^[0-9a-fA-F]{64}$/;

function exactKeys(value, required, label) {
  if(!value || typeof value!=='object' || Array.isArray(value)) throw new Error(`${label} must be an object.`);
  const keys=Object.keys(value);
  for(const key of required) if(!keys.includes(key)) throw new Error(`${label} is missing ${key}.`);
  for(const key of keys) if(!required.includes(key)) throw new Error(`${label} has unsupported field ${key}.`);
}
function bytesFromBase64(value,label) {
  if(typeof value!=='string' || value.length>2*1024*1024) throw new Error(`${label} is oversized.`);
  try {
    if(typeof Buffer!=='undefined') return new Uint8Array(Buffer.from(value,'base64'));
    const decoded=atob(value); return Uint8Array.from(decoded,character=>character.charCodeAt(0));
  } catch { throw new Error(`${label} is not valid base64.`); }
}
function bytesFromHex(value) {
  return Uint8Array.from(value.match(/../g).map(part=>Number.parseInt(part,16)));
}
function hex(bytes) { return [...new Uint8Array(bytes)].map(value=>value.toString(16).padStart(2,'0')).join(''); }
function utf8(value) { return new TextEncoder().encode(value); }

export async function importComponentCatalogSnapshot(
  text, publisherPublicKey, builtCapabilities, builtArtifacts=[], cryptoApi=globalThis.crypto) {
  if(typeof text!=='string' || !text.length || text.length>4*1024*1024)
    throw new Error('Component catalog snapshot is empty or oversized.');
  if(!PUBLIC_KEY.test(String(publisherPublicKey || '')))
    throw new Error('Publisher public key must be 64 hexadecimal characters.');
  let snapshot;
  try { snapshot=JSON.parse(text); } catch { throw new Error('Component catalog snapshot is not valid JSON.'); }
  exactKeys(snapshot,['schemaVersion','kind','artifactFileName','artifactSha256','catalogBase64',
    'runtimeManifestBase64','configurationMetadataBase64','trust'],'Snapshot');
  if(snapshot.schemaVersion!==1 || snapshot.kind!=='AppChainComponentCatalogSnapshot'
      || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,255}$/.test(snapshot.artifactFileName || '')
      || !SHA256.test(snapshot.artifactSha256 || '')) throw new Error('Snapshot identity is invalid.');
  const trust=snapshot.trust;
  exactKeys(trust,['schemaVersion','algorithm','keyId','bundleId','bundleVersion','catalogSha256',
    'runtimeManifestSha256','configurationMetadataSha256','signature'],'Trust envelope');
  if(trust.schemaVersion!==1 || trust.algorithm!=='Ed25519'
      || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(trust.keyId || '')
      || !CATALOG_ID.test(trust.bundleId || '') || !String(trust.bundleVersion || '').length
      || ![trust.catalogSha256,trust.runtimeManifestSha256,
        trust.configurationMetadataSha256].every(value=>SHA256.test(value || '')))
    throw new Error('Trust envelope is invalid.');
  const catalogBytes=bytesFromBase64(snapshot.catalogBase64,'Component catalog');
  const manifestBytes=bytesFromBase64(snapshot.runtimeManifestBase64,'Runtime manifest');
  const metadataBytes=bytesFromBase64(snapshot.configurationMetadataBase64,'Configuration metadata');
  for(const [label,bytes,expected] of [
    ['component catalog',catalogBytes,trust.catalogSha256],
    ['runtime manifest',manifestBytes,trust.runtimeManifestSha256],
    ['configuration metadata',metadataBytes,trust.configurationMetadataSha256]]) {
    const actual=hex(await cryptoApi.subtle.digest('SHA-256',bytes));
    if(actual!==expected) throw new Error(`Signed ${label} digest does not match.`);
  }
  let catalog; let manifest;
  try { catalog=JSON.parse(new TextDecoder().decode(catalogBytes));
    manifest=JSON.parse(new TextDecoder().decode(manifestBytes)); }
  catch { throw new Error('Signed catalog or runtime manifest is not valid JSON.'); }
  exactKeys(catalog,['schemaVersion','catalogId','bundleId','bundleVersion','artifact','capabilities'],
    'Component catalog');
  if(catalog.schemaVersion!=='v1alpha1' || !CATALOG_ID.test(catalog.catalogId || '')
      || catalog.bundleId!==trust.bundleId || catalog.bundleVersion!==trust.bundleVersion
      || manifest?.id!==catalog.bundleId || manifest?.version!==catalog.bundleVersion)
    throw new Error('Catalog, manifest, and trust identities do not match.');
  exactKeys(catalog.artifact,['id','availability','bundleId','nativePosture','runtimeTypes',
    'deploymentTargets'],'Artifact');
  if(!CATALOG_ID.test(catalog.artifact.id || '') || catalog.artifact.bundleId!==catalog.bundleId
      || !['REFERENCE','EXPERIMENTAL'].includes(catalog.artifact.availability)
      || catalog.artifact.nativePosture!=='unsupported'
      || JSON.stringify(catalog.artifact.runtimeTypes)!=='["jvm"]')
    throw new Error('Custom artifact cannot claim bundled, stable, or native support.');
  if(!Array.isArray(catalog.capabilities) || !catalog.capabilities.length
      || catalog.capabilities.length>64) throw new Error('Catalog capabilities are invalid.');
  const builtIds=new Set(builtCapabilities.map(value=>value.id));
  const builtArtifactIds=new Set(builtArtifacts.map(value=>value.id));
  const builtBundleIds=new Set(builtArtifacts.map(value=>value.bundleId));
  const ids=new Set();
  const capabilities=catalog.capabilities.map(capability=>{
    exactKeys(capability,['id','name','category','availability','maturity','scope','selectable',
      'trustStatement','description','provides','requires','implies','conflicts','runtimeTypes',
      'deploymentTargets','artifacts','nativePosture','externalPrerequisites',
      'bootstrapRequirements','nonSecretAnswers','secretReferences','properties','documentation',
      'acceptanceScenario'],'Capability');
    if(!CAPABILITY_ID.test(capability?.id || '') || ids.has(capability.id)
        || builtIds.has(capability.id)) throw new Error('Custom capability ID collides or is invalid.');
    ids.add(capability.id);
    if(!['REFERENCE','EXPERIMENTAL'].includes(capability.availability)
        || !['preview','experimental'].includes(capability.maturity)
        || capability.selectable!==true || capability.scope==='distribution'
        || capability.nativePosture!=='unsupported'
        || JSON.stringify(capability.runtimeTypes)!=='["jvm"]'
        || JSON.stringify(capability.artifacts)!==JSON.stringify([catalog.artifact.id]))
      throw new Error(`${capability.id} makes an unsupported trust or runtime claim.`);
    return {...capability,catalogSource:'external'};
  });
  if(builtArtifactIds.has(catalog.artifact.id) || builtBundleIds.has(catalog.bundleId))
    throw new Error('Custom artifact or bundle ID collides with the release.');
  const payload=`yano-appchain-component-catalog-trust-v1\n${catalog.catalogId}\n${trust.bundleId}\n${trust.bundleVersion}\n${trust.keyId}\n${trust.catalogSha256}\n${trust.runtimeManifestSha256}\n${trust.configurationMetadataSha256}\n`;
  const key=await cryptoApi.subtle.importKey('raw',bytesFromHex(publisherPublicKey),
    {name:'Ed25519'},false,['verify']);
  const verified=await cryptoApi.subtle.verify('Ed25519',key,
    bytesFromBase64(trust.signature,'Signature'),utf8(payload));
  if(!verified) throw new Error('Component catalog publisher signature is invalid.');
  return {catalog,capabilities,artifact:catalog.artifact,
    reference:{path:`component-catalogs/${catalog.catalogId}.json`,trustedKeyId:trust.keyId,
      trustedPublicKey:publisherPublicKey.toLowerCase()},snapshotText:text};
}
