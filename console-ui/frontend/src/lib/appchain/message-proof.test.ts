import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { decodeFinalizedBlockMessageRecord, verifyMessageAgainstBlockRecord,
  verifyMessageInclusionProof, type BrowserMessageInclusionProof } from './message-proof';

interface Vectors {
  treeId: string;
  leafCount: number;
  messagesRoot: string;
  vectors: Array<{ index: number; messageId: string; siblings: string[] }>;
}

describe('ADR-037 browser message proof vectors', () => {
  const document = JSON.parse(readFileSync(resolve(process.cwd(),
    '../../appchain/test-vectors/adr037-message-proof-v1.json'), 'utf8')) as Vectors;

  it('verifies every shared odd-tree path and rejects mutations', () => {
    for (const vector of document.vectors) {
      const proof: BrowserMessageInclusionProof = {
        schemaVersion: 1,
        treeId: document.treeId,
        chainId: 'vectors',
        blockHeight: 1,
        blockHash: '00'.repeat(32),
        messagesRoot: document.messagesRoot,
        messageId: vector.messageId,
        messageIndex: vector.index,
        leafCount: document.leafCount,
        siblings: vector.siblings
      };
      expect(verifyMessageInclusionProof(proof)).toBe(true);
      expect(verifyMessageInclusionProof({ ...proof, messagesRoot: `00${proof.messagesRoot.slice(2)}` }))
        .toBe(false);
      expect(verifyMessageInclusionProof({ ...proof, siblings: [
        `00${proof.siblings[0].slice(2)}`, ...proof.siblings.slice(1)
      ] })).toBe(false);
    }
  });

  it('strictly decodes and binds the authenticated block record', () => {
    const vector = document.vectors[0];
    const proof: BrowserMessageInclusionProof = {
      schemaVersion: 1, treeId: document.treeId, chainId: 'vectors', blockHeight: 7,
      blockHash: '00'.repeat(32), messagesRoot: document.messagesRoot,
      messageId: vector.messageId, messageIndex: vector.index,
      leafCount: document.leafCount, siblings: vector.siblings
    };
    const record = decodeFinalizedBlockMessageRecord(
      `8401075820${document.messagesRoot}05`);
    expect(verifyMessageAgainstBlockRecord(proof, record)).toBe(true);
    expect(verifyMessageAgainstBlockRecord(proof, { ...record, messageCount: 4 })).toBe(false);
    expect(() => decodeFinalizedBlockMessageRecord(`841801075820${document.messagesRoot}05`))
      .toThrow(/canonical/);
    expect(() => decodeFinalizedBlockMessageRecord(`8401075820${document.messagesRoot}0500`))
      .toThrow(/Invalid/);
  });
});
