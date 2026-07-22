import assert from 'node:assert/strict';
import {createHash,generateKeyPairSync,sign,webcrypto} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {blueprintYaml,compatibleCapabilityOptions,decodeDeepLink,encodeDeepLink,
  importComponentCatalogSnapshot,normalizeIntent,resolvePresentation}
  from '../main/web/studio-core.mjs';

const repo=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../../..');
const metadata=path.join(repo,'appchain/appchain-devtools/src/main/resources/appchain-dx/v1alpha1');
const recipes=JSON.parse(fs.readFileSync(path.join(metadata,'appchain-recipe-catalog.json'))).recipes;
const capabilities=JSON.parse(fs.readFileSync(path.join(metadata,'appchain-capability-catalog.json'))).capabilities;
const release=JSON.parse(fs.readFileSync(path.join(metadata,'appchain-release-capability-index.json')));

test('every release recipe produces safe release-pinned intent',()=>{
  for(const recipe of recipes){
    const raw={recipe:recipe.id,network:'devnet',members:3,finality:'two-thirds',
      sequencing:'fixed',runtime:recipe.runtimeTypes[0],deployment:recipe.deploymentTargets[0],
      name:`test-${recipe.id}`,chainId:`test-${recipe.id}`,
      membership:'static',answers:recipe.id==='custom-plugin'
        ? {stateMachine:'com.example.test-machine'}:{}};
    const result=normalizeIntent(raw,recipes,release,capabilities);
    assert.deepEqual(result.errors,[],recipe.id);
    const yaml=blueprintYaml(result.intent,release.yanoVersion);
    assert.match(yaml,/apiVersion: yano\.bloxbean\.com\/v1alpha1/);
    assert.match(yaml,/kind: AppChainProject/);
    assert.match(yaml,new RegExp(`recipe: "${recipe.id}"`));
    assert.doesNotMatch(yaml,/private|secret|signing-key|01010101/i);
    const presentation=resolvePresentation(recipe,capabilities,[],result.intent);
    assert.ok(presentation.capabilities.length>0);
    assert.ok(presentation.artifacts.length>0);
  }
});

test('deep links round trip only explicitly safe non-secret fields',()=>{
  const intent={recipe:'audit-log',network:'devnet',members:3,finality:'all',
    sequencing:'fixed',membership:'governed',runtime:'jvm',deployment:'host',name:'safe-chain',
    capabilities:['anchor:metadata'],
    chainId:'safe-chain',signingKey:'must-not-appear',password:'must-not-appear'};
  const encoded=encodeDeepLink(intent);
  assert.doesNotMatch(encoded,/signing|password|must-not-appear/i);
  assert.equal(decodeDeepLink(encoded).chainId,'safe-chain');
  assert.equal(decodeDeepLink(encoded).membership,'governed');
  assert.deepEqual(decodeDeepLink(encoded).capabilities,['anchor:metadata']);
});

test('zero members remains invalid instead of being coerced to the default',()=>{
  const result=normalizeIntent({members:0},recipes,release,capabilities);
  assert.equal(result.intent.members,0);
  assert.ok(result.errors.includes('Members must be from 1 to 32.'));
});

test('every tutorial deep link is accepted by the pinned release',()=>{
  const tutorials=path.join(repo,'docs/appchain/tutorials');
  for(const name of fs.readdirSync(tutorials).filter(value=>/^\d\d-.*\.md$/.test(value))){
    const text=fs.readFileSync(path.join(tutorials,name),'utf8');
    const match=text.match(/appchain-studio\/src\/main\/web\/index\.html(#.*?)\)/);
    assert.ok(match,`${name} has an initializer link`);
    const decoded=decodeDeepLink(match[1]);
    assert.deepEqual(normalizeIntent(decoded,recipes,release,capabilities).errors,[],name);
    assert.doesNotMatch(match[1],/secret|private|signing|password|token/i);
  }
});

test('compatible selection exposes optional bundles and rejects derived distribution entries',()=>{
  const intent=normalizeIntent({recipe:'approval-workflow',network:'devnet',members:3,
    finality:'two-thirds',sequencing:'fixed',membership:'static',runtime:'jvm',
    deployment:'host',name:'approval-test',chainId:'approval-test'},
  recipes,release,capabilities).intent;
  const options=compatibleCapabilityOptions(
    recipes.find(recipe=>recipe.id==='approval-workflow'),capabilities,intent);
  assert.equal(options.find(option=>option.capability.id==='executor:kafka').compatible,true);
  assert.equal(options.some(option=>option.capability.id==='ui:console'),false);
  const selected=normalizeIntent({...intent,capabilities:['executor:kafka']},
    recipes,release,capabilities);
  assert.deepEqual(selected.errors,[]);
  assert.ok(selected.plan.artifacts.includes('appchain-kafka'));
});

test('static shell has a strict policy and no secret inputs or telemetry',()=>{
  const html=fs.readFileSync(path.join(repo,'appchain/appchain-studio/src/main/web/index.html'),'utf8');
  const app=fs.readFileSync(path.join(repo,'appchain/appchain-studio/src/main/web/app.mjs'),'utf8');
  assert.match(html,/Content-Security-Policy/);
  assert.match(html,/script-src 'self'/);
  assert.doesNotMatch(html,/type="password"/i);
  const inputNames=[...html.matchAll(/<(?:input|select)[^>]+name="([^"]+)"/g)]
    .map(match=>match[1]).join(',');
  assert.doesNotMatch(inputNames,/private|secret|signing|password|token/i);
  assert.doesNotMatch(app,/analytics|telemetry|localStorage|sessionStorage/i);
});

function signedSnapshot(overrides={}) {
  const catalog={schemaVersion:'v1alpha1',catalogId:'sample-plugin',
    bundleId:'plugin-bundle.sample',bundleVersion:'1.0.0',
    artifact:{id:'plugin.sample',availability:'REFERENCE',bundleId:'plugin-bundle.sample',
      nativePosture:'unsupported',runtimeTypes:['jvm'],deploymentTargets:['host']},
    capabilities:[{id:'state:sample',name:'Sample',category:'state',availability:'REFERENCE',
      maturity:'experimental',scope:'chain',selectable:true,trustStatement:'Operator owned.',
      description:'Sample custom state.',provides:['state-machine'],requires:[],implies:[],
      conflicts:[],runtimeTypes:['jvm'],deploymentTargets:['host'],artifacts:['plugin.sample'],
      nativePosture:'unsupported',externalPrerequisites:['reviewed-plugin'],
      bootstrapRequirements:[],nonSecretAnswers:[],secretReferences:{},
      properties:{'state-machine':'sample'},documentation:'README.md',
      acceptanceScenario:'operator-owned'}],...overrides};
  const manifest={schemaVersion:1,id:catalog.bundleId,version:catalog.bundleVersion,
    yanoApi:{min:1,max:1,minLevel:1},dependencies:[],contributions:[{
      kind:'app-state-machine',name:'sample',provider:'example.Sample'}]};
  const catalogBytes=Buffer.from(JSON.stringify(catalog));
  const manifestBytes=Buffer.from(JSON.stringify(manifest)); const metadataBytes=Buffer.alloc(0);
  const digest=value=>createHash('sha256').update(value).digest('hex');
  const trust={schemaVersion:1,algorithm:'Ed25519',keyId:'test-publisher',
    bundleId:catalog.bundleId,bundleVersion:catalog.bundleVersion,
    catalogSha256:digest(catalogBytes),runtimeManifestSha256:digest(manifestBytes),
    configurationMetadataSha256:digest(metadataBytes),signature:''};
  const payload=`yano-appchain-component-catalog-trust-v1\n${catalog.catalogId}\n${trust.bundleId}\n${trust.bundleVersion}\n${trust.keyId}\n${trust.catalogSha256}\n${trust.runtimeManifestSha256}\n${trust.configurationMetadataSha256}\n`;
  const keys=generateKeyPairSync('ed25519'); trust.signature=sign(null,Buffer.from(payload),keys.privateKey).toString('base64');
  const publicKey=keys.publicKey.export({type:'spki',format:'der'}).subarray(-32).toString('hex');
  return {text:JSON.stringify({schemaVersion:1,kind:'AppChainComponentCatalogSnapshot',
    artifactFileName:'sample.jar',artifactSha256:'ab'.repeat(32),
    catalogBase64:catalogBytes.toString('base64'),runtimeManifestBase64:manifestBytes.toString('base64'),
    configurationMetadataBase64:metadataBytes.toString('base64'),trust}),publicKey};
}

test('local custom catalog import verifies trust and stays out of safe links',async()=>{
  const fixture=signedSnapshot();
  const imported=await importComponentCatalogSnapshot(
    fixture.text,fixture.publicKey,capabilities,[],webcrypto);
  assert.equal(imported.capabilities[0].catalogSource,'external');
  const intent={recipe:'custom-plugin',network:'devnet',members:1,finality:'all',
    sequencing:'fixed',membership:'static',runtime:'jvm',deployment:'host',name:'custom-test',
    chainId:'custom-test',capabilities:['state:sample'],answers:{},
    componentCatalogs:[imported.reference]};
  const yaml=blueprintYaml(intent,release.yanoVersion);
  assert.match(yaml,/componentCatalogs:/);
  assert.match(yaml,/component-catalogs\/sample-plugin\.json/);
  assert.match(yaml,new RegExp(fixture.publicKey));
  assert.doesNotMatch(encodeDeepLink(intent),/publisher|public|component-catalogs|test-publisher/i);
  const plan=resolvePresentation(recipes.find(value=>value.id==='custom-plugin'),
    [...capabilities,...imported.capabilities],intent.capabilities,intent);
  assert.deepEqual(plan.errors,[]);
  assert.ok(!plan.capabilities.includes('state:custom-plugin'));
});

test('local custom catalog import rejects tampering collisions and tier escalation',async()=>{
  const fixture=signedSnapshot(); const tampered=JSON.parse(fixture.text);
  tampered.catalogBase64=Buffer.from('tampered').toString('base64');
  await assert.rejects(importComponentCatalogSnapshot(
    JSON.stringify(tampered),fixture.publicKey,capabilities,[],webcrypto),/digest does not match/);
  const collision=signedSnapshot({capabilities:[{
    ...JSON.parse(Buffer.from(JSON.parse(fixture.text).catalogBase64,'base64')).capabilities[0],
    id:'state:ordered-log'}]});
  await assert.rejects(importComponentCatalogSnapshot(
    collision.text,collision.publicKey,capabilities,[],webcrypto),/collides or is invalid/);
  const elevatedCatalog=JSON.parse(Buffer.from(JSON.parse(fixture.text).catalogBase64,'base64'));
  const elevated=signedSnapshot({capabilities:[{
    ...elevatedCatalog.capabilities[0],availability:'BUNDLED',maturity:'stable',
    runtimeTypes:['jvm','native'],nativePosture:'bundled'}]});
  await assert.rejects(importComponentCatalogSnapshot(
    elevated.text,elevated.publicKey,capabilities,[],webcrypto),/unsupported trust or runtime claim/);
});
