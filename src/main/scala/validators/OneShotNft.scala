package validators

import scalus.compiler.Compile
import scalus.uplc.builtin.{ByteString, Data, FromData, ToData}
import scalus.cardano.onchain.plutus.v1.PolicyId
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.*

enum NftAction derives FromData, ToData:
    case Mint(name: ByteString)
    case Burn

/** Mint validator (minting policy), parameterized by a seed UTxO.
  *
  *   - `Mint(name)`: the seed is spent and exactly one token `name` is minted under this policy, nothing else.
  *     A UTxO can be spent only once, so the policy can mint only once: the token is a true NFT.
  *   - `Burn`: only burns tokens of this policy.
  *
  * The seed is a parameter, so it is part of the script and of its hash: every seed gives its own policy id.
  */
@Compile
object OneShotNft extends DataParameterizedValidator {

    inline override def mint(seedData: Data, redeemer: Data, policyId: PolicyId, tx: TxInfo): Unit = {
        val seed = seedData.to[TxOutRef]
        redeemer.to[NftAction] match
            case NftAction.Mint(name) =>
                tx.findInputOrFail(seed, SeedNotSpent)
                require(tx.mint.hasOnly(policyId, name, 1), MintExactlyOne)
            case NftAction.Burn =>
                require(tx.mint.tokens(policyId).forall((_, qty) => qty < BigInt(0)), OnlyBurn)
    }

    inline val SeedNotSpent = "Seed UTxO must be spent"
    inline val MintExactlyOne = "Must mint exactly one token of this policy"
    inline val OnlyBurn = "Burn must not mint"
}
