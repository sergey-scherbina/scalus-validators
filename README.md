# scalus-validators

Cardano smart contracts in Scala 3 with [Scalus](https://scalus.org) 1.4: five spend validators, one minting
policy and a state machine that is both, Plutus V3, covered by unit tests, ScalaCheck properties and end-to-end
transactions on Scalus's in-memory ledger.

Step through them in the browser: [okay-uplc](https://okay-uplc.okay-effects.workers.dev), a debugger for Cardano
scripts built on Scalus — these validators are its bundled examples.

| Validator | Purpose | What it checks | Size |
|---|---|---|---|
| [Vesting](src/main/scala/validators/Vesting.scala) | spend | `Claim`: beneficiary signs, validity range at or after the deadline. `Cancel`: owner signs, range before it | 280 B |
| [Escrow](src/main/scala/validators/Escrow.scala) | spend | `Release`: buyer signs, the seller is paid at least the price **in an output tagged with the escrow's own reference** (no double satisfaction). `Refund`: buyer signs after the deadline (protects the buyer only: see ArbitratedEscrow) | 361 B |
| [Multisig](src/main/scala/validators/Multisig.scala) | spend | N-of-M: at least `threshold` of `signers` sign; signatures counted over the ledger's unique signatories; threshold must be positive | 206 B |
| [OneShotNft](src/main/scala/validators/OneShotNft.scala) | mint | parameterized by a seed UTxO: `Mint(name)` needs the seed spent and mints exactly one token, nothing else; `Burn` only burns | 410 B |
| [StateCounter](src/main/scala/validators/StateCounter.scala) | mint + spend | a state machine guarded by a state NFT (one script: the policy id is the script hash). `Init` mints the one NFT from a seed into the script with count 0; `Increment`: the input carries the NFT, one script input and one output, same full address, only ada and the NFT, ada kept, count + 1, owner signs; `Close` burns the NFT | 1286 B |
| [Listing](src/main/scala/validators/Listing.scala) | spend | a marketplace listing. `Buy`: the seller is paid the price **in an output tagged with the listing's reference**, so one payment cannot buy two listings; `Cancel`: the seller signs | 322 B |
| [ArbitratedEscrow](src/main/scala/validators/ArbitratedEscrow.scala) | spend | escrow with an arbiter, 2 of 3: any two **distinct** keys of buyer, seller, arbiter sign; `Release` pays the seller, `Refund` the buyer, **all the escrow holds**, tagged with its reference; a key in two roles counts once | 451 B |

## Build and test

```sh
sbt test            # 68 tests: unit, property-based, emulator, a case file for the debugger
sbt blueprintCheck  # plutus.json matches the code
```

Requires a JDK (17+) and sbt. CI for GitHub Actions: `ci/github-actions.yml` (copy it to `.github/workflows/`). The Scalus compiler plugin turns every `@Compile` object into Untyped Plutus Core.

## Tests

Three levels, the same for every validator:

- **Unit** ([VestingSpec](src/test/scala/validators/VestingSpec.scala), …): a `ScriptContext` built by hand,
  holding only the fields the validator reads, run on the Plutus VM with error traces; failures are asserted by
  their message, so a test cannot pass for the wrong reason.
- **Property-based** (ScalaCheck): the validator agrees with the rule written in plain Scala for any input,
  e.g. *a claim passes iff the beneficiary signs and `from >= deadline`*, *a multisig spend passes iff at least
  `threshold` members sign, whatever outsiders also sign*, *a redeemer that is neither action is rejected*.
- **End to end** ([EmulatorSpec](src/test/scala/validators/EmulatorSpec.scala)): real transactions built with
  `TxBuilder` and submitted to the `Emulator` ledger: lock and claim a vesting; mint an NFT from a seed, then
  show that the same policy can never mint again.

Security cases have their own tests: double satisfaction (one payment settling two escrows), the same attack
succeeding on a naive listing ([NaiveListing](src/test/scala/validators/NaiveListing.scala), tests only) and
refused by Listing, every attack on the state machine (forged state without the NFT, NFT taken out, token dust, ada
drained, another stake credential, a wrong transition, a second script input, close without the burn), a burn that
secretly mints, a key listed twice in a multisig datum, a seller named also as the arbiter (counted once), a
refund an arbiter and seller pay to themselves, a zero threshold, a missing datum, an unbounded validity range.

## Working on them in okay-uplc

The contracts are developed with [okay-uplc](https://okay-uplc.okay-effects.workers.dev), a debugger for Cardano
scripts, through its sbt plugin (`project/plugins.sbt`: `addSbtPlugin("io.github.okay-effects" % "sbt-okay-uplc" %
"0.1.0")`):

```sh
sbt okayUplcServe        # the contracts' debug builds as a workspace, served with the page on http://127.0.0.1:8790
open "http://127.0.0.1:8790/?workspace=http://127.0.0.1:8790"
sbt okayUplcVerify       # every scenario in okay-uplc/scenarios as it expects; a wrong one fails with its link
```

Or, in Chrome or Edge, with no server: `sbt okayUplcWorkspace`, then on the site "Connect your project…" → "Choose the
project's folder…" and pick this folder. The page reads the workspace and the scenarios from it and saves new
scenarios into it. "Builds…" compiles a contract again from its SIR, right in the page, and compares the builds;
"SIR…" shows the SIR itself.

In an sbt shell, `okayUplcStart` serves in the background and `~okayUplcWorkspace` writes the workspace again on
every change; the page sees it and shows the budgets that moved. From the site itself
(`https://okay-uplc.okay-effects.workers.dev/?workspace=http://127.0.0.1:8790`) the browser first asks whether the
page may reach this machine.

- `Workbench` writes the workspace (each contract's debug build: error traces, Scala positions, schemas) and runs
  every scenario in `okay-uplc/scenarios/` — one call of a validator each (datum, redeemer, the transaction's
  fields) with what it should do. The page lists them, steps through any at its Scala line, and saves new ones into
  the folder; the tests keep them true. A scenario that is not as it expects fails the test with a link that opens
  its run in the debugger at the step before the end — the Scala line where it failed.
- `CaseFilesSpec` writes a single failing case as a file the page opens ("A case file…").

## For the off-chain side (cardano-cli)

```sh
sbt "runMain validators.Export"                    # artifacts/*.plutus: text envelopes for cardano-cli
sbt "runMain validators.Export <txHash>#<index>"   # also the NFT policy for that seed UTxO
```

`artifacts/` also holds examples of datums and redeemers in cardano-cli's detailed JSON schema
(`--tx-out-inline-datum-file`, `--tx-in-redeemer-file`); [plutus.json](plutus.json) is the CIP-57 blueprint
with the schemas and script hashes. Notes for building transactions:

- Times are POSIX milliseconds in datums; in cardano-cli the validity range is in slots:
  `--invalid-before` for `Claim` and `Refund`, `--invalid-hereafter` for `Cancel`. Without the bound the script
  rejects the transaction.
- Signatures are the transaction's required signers: `--required-signer-hash`.
- `Release`: the seller's output needs `--tx-out-inline-datum-value` equal to the escrow UTxO's reference
  (`TxOutRef` as `{"constructor":0,"fields":[{"bytes":"<txId>"},{"int":<index>}]}`).

## Layout

```
src/main/scala/validators/   Vesting, Escrow, Multisig, OneShotNft, Contracts (blueprints), Export
src/test/scala/validators/   Fixtures, *Spec (unit + property), EmulatorSpec
plutus.json, blueprints/     pinned CIP-57 blueprints (sbt blueprintPin)
artifacts/                   cardano-cli envelopes and JSON examples
```

## License

Apache-2.0
