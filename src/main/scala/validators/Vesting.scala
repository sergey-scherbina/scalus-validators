package validators

import scalus.compiler.Compile
import scalus.uplc.builtin.{Data, FromData, ToData}
import scalus.cardano.onchain.plutus.v3.*
import scalus.cardano.onchain.plutus.prelude.*

/** Funds locked for a beneficiary until a deadline; the owner may take them back before it. */
case class VestingDatum(owner: PubKeyHash, beneficiary: PubKeyHash, deadline: PosixTime)
    derives FromData,
      ToData

enum VestingAction derives FromData, ToData:
    case Claim
    case Cancel

/** Spend validator.
  *
  *   - `Claim`: signed by the beneficiary, the whole validity range at or after the deadline.
  *   - `Cancel`: signed by the owner, the whole validity range before the deadline.
  *
  * The ledger rejects a transaction outside its validity range, so the range is the only clock a validator
  * has. `validFrom` is inclusive and `validTo` exclusive.
  */
@Compile
object Vesting extends Validator {

    inline override def spend(
        datum: Option[Data],
        redeemer: Data,
        tx: TxInfo,
        ownRef: TxOutRef
    ): Unit = {
        val d = datum.getOrFail(NoDatum).to[VestingDatum]
        redeemer.to[VestingAction] match
            case VestingAction.Claim =>
                require(d.deadline <= tx.validFromOrFail(Unbounded), TooEarly)
                require(tx.isSignedBy(d.beneficiary), BeneficiaryMustSign)
            case VestingAction.Cancel =>
                require(tx.validToOrFail(Unbounded) <= d.deadline, TooLate)
                require(tx.isSignedBy(d.owner), OwnerMustSign)
    }

    inline val NoDatum = "Datum required"
    inline val Unbounded = "Validity range must be bounded"
    inline val TooEarly = "Claim before the deadline"
    inline val TooLate = "Cancel after the deadline"
    inline val BeneficiaryMustSign = "Beneficiary must sign"
    inline val OwnerMustSign = "Owner must sign"
}
