import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {blueprintYaml,decodeDeepLink,encodeDeepLink,normalizeIntent,resolvePresentation}
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
      stateMachine:recipe.id==='custom-plugin'?'com.example.test-machine':''};
    const result=normalizeIntent(raw,recipes,release);
    assert.deepEqual(result.errors,[],recipe.id);
    const yaml=blueprintYaml(result.intent,release.yanoVersion);
    assert.match(yaml,/apiVersion: yano\.bloxbean\.com\/v1alpha1/);
    assert.match(yaml,/kind: AppChainProject/);
    assert.match(yaml,new RegExp(`recipe: "${recipe.id}"`));
    assert.doesNotMatch(yaml,/private|secret|signing-key|01010101/i);
    const presentation=resolvePresentation(recipe,capabilities);
    assert.ok(presentation.capabilities.length>0);
    assert.ok(presentation.artifacts.length>0);
  }
});

test('deep links round trip only explicitly safe non-secret fields',()=>{
  const intent={recipe:'audit-log',network:'devnet',members:3,finality:'all',
    sequencing:'fixed',runtime:'jvm',deployment:'host',name:'safe-chain',
    chainId:'safe-chain',signingKey:'must-not-appear',password:'must-not-appear'};
  const encoded=encodeDeepLink(intent);
  assert.doesNotMatch(encoded,/signing|password|must-not-appear/i);
  assert.equal(decodeDeepLink(encoded).chainId,'safe-chain');
});

test('every tutorial deep link is accepted by the pinned release',()=>{
  const tutorials=path.join(repo,'docs/appchain/tutorials');
  for(const name of fs.readdirSync(tutorials).filter(value=>/^\d\d-.*\.md$/.test(value))){
    const text=fs.readFileSync(path.join(tutorials,name),'utf8');
    const match=text.match(/appchain-studio\/src\/main\/web\/index\.html(#.*?)\)/);
    assert.ok(match,`${name} has an initializer link`);
    const decoded=decodeDeepLink(match[1]);
    assert.deepEqual(normalizeIntent(decoded,recipes,release).errors,[],name);
    assert.doesNotMatch(match[1],/secret|private|signing|password|token/i);
  }
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
