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
// ABI: main is 0-arity; the three real i32 inputs (existing-debt,
// requested-amount, annual-income -- all cents) are written into the
// guest's exported linear memory at offsets 0/4/8 before calling main().
// WASM linear memory is little-endian.
//
// Assumes the fixed sibling-checkout layout this monorepo's west manifest
// always uses (orgs/<org>/<repo>) -- not a portable npm dependency.
//
// Run: node wasm/verify_node.mjs <scenario>
//   scenarios: approve | reject | zero-income
//   or: node wasm/verify_node.mjs <existing-debt> <requested-amount> <annual-income>

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { hostCaps, actorHostImports } from '../../../kotoba-lang/wasm-webcomponent/src/actor-host.js';

const here = path.dirname(fileURLToPath(import.meta.url));

const SCENARIOS = {
  approve: [500000, 2000000, 6000000],
  reject: [3000000, 3000000, 6000000],
  'zero-income': [0, 0, 0],
};

function resolveInputs(argv) {
  if (argv.length === 3) return argv.map(Number);
  const scenario = argv[0];
  if (SCENARIOS[scenario]) return SCENARIOS[scenario];
  console.error(`usage: node ${path.basename(import.meta.url)} <scenario|existing-debt requested-amount annual-income>\nscenarios: ${Object.keys(SCENARIOS).join(', ')}`);
  process.exit(64);
}

async function run(inputs) {
  const wasmBytes = await readFile(path.join(here, 'affordability.wasm'));
  const memoryBox = {};
  const importObject = { kotoba: actorHostImports([], hostCaps(), memoryBox) };

  const { instance } = await WebAssembly.instantiate(wasmBytes, importObject);
  memoryBox.memory = instance.exports.memory;

  const view = new DataView(memoryBox.memory.buffer);
  const [existingDebt, requestedAmount, annualIncome] = inputs;
  view.setInt32(0, existingDebt, true);
  view.setInt32(4, requestedAmount, true);
  view.setInt32(8, annualIncome, true);

  const raw = instance.exports.main();
  const code = typeof raw === 'bigint' ? Number(raw) : raw;

  console.log(JSON.stringify({
    input: { existingDebt, requestedAmount, annualIncome },
    result: code,
    affordable: code === 1,
  }, null, 2));
}

await run(resolveInputs(process.argv.slice(2)));
