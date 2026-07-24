// Thin routing shim — logic in
// src/credit/edge/loan_endpoints.cljc, compiled by
// shadow-cljs :edge-api into functions/edge/loan-edge-core.js.
// Regenerate with: npx shadow-cljs release edge-api
//
// GET /api/loan/{id} — CACAO-gated + caller-allow-list-gated (see
// loan_endpoints.cljc's get-application-core docstring for why this is
// gated, unlike commitment-ledger's public GET).

export { applicationOnRequestGet as onRequestGet } from "../../edge/loan-edge-core.js";
