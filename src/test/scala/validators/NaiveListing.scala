package validators

import scalus.compiler.Compile
import scalus.uplc.builtin.Data
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.*

/** THE VULNERABLE LISTING — in the tests only, to show the attack. `Buy` checks "some output pays the seller
  * `price`": two listings of one seller at one price, bought in ONE transaction, are both satisfied by ONE payment —
  * the buyer takes two items for the price of one (double satisfaction). Listing tags the payment with the listing's
  * reference.
  */
@Compile
object NaiveListing extends Validator {
    inline override def spend(datum: Option[Data], redeemer: Data, tx: TxInfo, ownRef: TxOutRef): Unit = {
        val d = datum.getOrFail("datum required").to[ListingDatum]
        redeemer.to[ListingAction] match
            case ListingAction.Buy =>
                val seller = Credential.PubKeyCredential(d.seller)
                require(tx.outputs.exists(o => o.address.credential === seller && o.value.getLovelace >= d.price),
                    "seller not paid")
            case ListingAction.Cancel =>
                require(tx.isSignedBy(d.seller), "seller must sign")
    }
}
