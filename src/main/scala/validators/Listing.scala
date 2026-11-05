package validators

import scalus.compiler.Compile
import scalus.uplc.builtin.{Data, FromData, ToData}
import scalus.uplc.builtin.Data.toData
import scalus.cardano.onchain.plutus.v2.OutputDatum
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.*

/** A listing: the seller locks an NFT at the script; whoever pays `price` lovelace to the seller takes it. */
case class ListingDatum(seller: PubKeyHash, price: BigInt) derives FromData, ToData

enum ListingAction derives FromData, ToData:
    case Buy
    case Cancel

/** A marketplace listing (spend). `Buy`: the seller is paid the price. Double satisfaction: the payment for a listing
  * must carry that listing's UTxO reference as its INLINE DATUM: a payment names the one listing it pays for, so N
  * listings need N payments. (The other common fix: allow one script input
  * per transaction — simpler, but it forbids buying several items at once.)
  */
@Compile
object Listing extends Validator {
    inline override def spend(datum: Option[Data], redeemer: Data, tx: TxInfo, ownRef: TxOutRef): Unit = {
        val d = datum.getOrFail(NoDatum).to[ListingDatum]
        redeemer.to[ListingAction] match
            case ListingAction.Buy =>
                val seller = Credential.PubKeyCredential(d.seller)
                val tag = ownRef.toData
                val paid = tx.outputs.exists { o =>
                    o.address.credential === seller
                    && o.value.getLovelace >= d.price
                    && (o.datum match
                        case OutputDatum.OutputDatum(t) => t == tag
                        case _                          => false)
                }
                require(paid, SellerNotPaid)
            case ListingAction.Cancel =>
                require(tx.isSignedBy(d.seller), SellerMustSign)
    }

    inline val NoDatum = "datum required"
    inline val SellerNotPaid = "the seller is paid the price, tagged with this listing's reference"
    inline val SellerMustSign = "only the seller cancels"
}
