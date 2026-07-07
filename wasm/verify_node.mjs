// Node-hosted (no JVM) execution of affordability.wasm through
// kotoba-lang/wasm-webcomponent's actor-host.js port of the actor:host ABI.
//
// Why this exists: kototama.tender (JVM/Chicory, see test/wasm/affordability_test.clj)
// is the more mature host, but the murakumo fleet's Mac-mini nodes have no
// JVM installed (only the pinned Rust kotoba/kotoba-server binaries + a
// native XMRig miner) -- confirmed directly, 2026-07-07: `java -version` on
// asher/naphtali/judah/zebulun/issachar all report "Unable to locate a Java
// Runtime" (ADR-2607072530). Node.js has no such gap here, and
// kotoba-lang/wasm-webcomponent already ships a real, tested, dependency-free
// actor:host port that runs under plain `node`.
//
// affordability.wasm needs zero host imports (pure arithmetic, no
// capabilities) -- simpler than the isic-6511 precedent this pattern is
// copied from (underwriting_decision.wasm needs log-write + llm-infer).
//
// Assumes the fixed sibling-checkout layout this monorepo's west manifest
// always uses (orgs/<org>/<repo>) -- not a portable npm dependency.
//
// Run: node wasm/verify_node.mjs

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { hostCaps, actorHostImports } from '../../../kotoba-lang/wasm-webcomponent/src/actor-host.js';

const here = path.dirname(fileURLToPath(import.meta.url));

async function run() {
  const wasmBytes = await readFile(path.join(here, 'affordability.wasm'));
  const memoryBox = {};
  const importObject = { kotoba: actorHostImports([], hostCaps(), memoryBox) };

  const { instance } = await WebAssembly.instantiate(wasmBytes, importObject);
  memoryBox.memory = instance.exports.memory;

  const raw = instance.exports.main();
  const code = typeof raw === 'bigint' ? Number(raw) : raw;

  console.log(JSON.stringify({
    result: code,
    ok: code === 1,
  }, null, 2));

  if (code !== 1) process.exit(1);
}

await run();
