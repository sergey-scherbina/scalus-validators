package validators

import scalus.compiler.Compile
import scalus.uplc.builtin.{Data, FromData, ToData}
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.*

/** A treasury that any `threshold` of `signers` may spend. */
case class MultisigDatum(signers: List[PubKeyHash], threshold: BigInt) derives FromData, ToData

/** Spend validator: N-of-M signatures.
  *
  * Signatures are counted over the transaction's signatories, which the ledger keeps unique, so a key listed
  * twice in the datum still counts once. A threshold of zero or less would let anyone spend: rejected.
  */
@Compile
object Multisig extends Validator {

    inline override def spend(
        datum: Option[Data],
        redeemer: Data,
        tx: TxInfo,
        ownRef: TxOutRef
    ): Unit = {
        val d = datum.getOrFail(NoDatum).to[MultisigDatum]
        require(d.threshold > BigInt(0), BadThreshold)
        val signed = tx.signatories.count(d.signers.contains)
        require(signed >= d.threshold, NotEnoughSignatures)
    }

    inline val NoDatum = "Datum required"
    inline val BadThreshold = "Threshold must be positive"
    inline val NotEnoughSignatures = "Not enough signatures"
}
