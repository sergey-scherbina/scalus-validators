package validators

import scalus.compiler.Compile
import scalus.uplc.builtin.{Data, FromData, ToData}
import scalus.uplc.builtin.Data.toData
import scalus.cardano.onchain.plutus.v2.OutputDatum
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.*

/** The buyer locks a payment for the seller. */
case class EscrowDatum(seller: PubKeyHash, buyer: PubKeyHash, price: BigInt, deadline: PosixTime)
    derives FromData,
      ToData

enum EscrowAction derives FromData, ToData:
    case Release
    case Refund

/** Spend validator.
  *
  *   - `Release`: the buyer signs and the transaction pays the seller at least `price` lovelace.
  *   - `Refund`: the buyer signs after the deadline and takes the funds back.
  *
  * Double satisfaction: if two escrow UTxOs for the same seller and price are spent in one transaction, a
  * check "some output pays the seller `price`" would be satisfied by ONE payment for both. So the payment
  * must carry the spent UTxO's reference as its inline datum: each escrow needs its own output.
  */
@Compile
object Escrow extends Validator {

    inline override def spend(
        datum: Option[Data],
        redeemer: Data,
        tx: TxInfo,
        ownRef: TxOutRef
    ): Unit = {
        val d = datum.getOrFail(NoDatum).to[EscrowDatum]
        require(tx.isSignedBy(d.buyer), BuyerMustSign)
        redeemer.to[EscrowAction] match
            case EscrowAction.Release =>
                val sellerCred = Credential.PubKeyCredential(d.seller)
                val tag = ownRef.toData
                val paid = tx.outputs.exists { out =>
                    out.address.credential === sellerCred
                    && out.value.getLovelace >= d.price
                    && (out.datum match
                        case OutputDatum.OutputDatum(d) => d == tag
                        case _                          => false)
                }
                require(paid, SellerNotPaid)
            case EscrowAction.Refund =>
                require(d.deadline <= tx.validFromOrFail(Unbounded), TooEarly)
    }

    inline val NoDatum = "Datum required"
    inline val Unbounded = "Validity range must be bounded"
    inline val BuyerMustSign = "Buyer must sign"
    inline val SellerNotPaid = "Seller must be paid the price, tagged with the escrow reference"
    inline val TooEarly = "Refund before the deadline"
}
