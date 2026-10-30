package validators

import scalus.cardano.blueprint.{Blueprint, Contract}
import scalus.cardano.onchain.plutus.v3.TxOutRef
import scalus.compiler.Options
import scalus.uplc.builtin.Data
import scalus.uplc.PlutusV3

// Each Contract gets a CIP-57 blueprint: `sbt blueprint`, pinned at ./plutus.json by `sbt blueprintPin`.

object VestingContract extends Contract {
    private given Options = Options.release
    lazy val compiled = PlutusV3.compile(Vesting.validate)
    lazy val blueprint = Blueprint.plutusV3[VestingDatum, VestingAction](
      title = "Vesting",
      description = "The beneficiary claims after the deadline; the owner cancels before it.",
      version = "0.1.0",
      license = Some("Apache-2.0"),
      compiled = compiled
    )
}

object EscrowContract extends Contract {
    private given Options = Options.release
    lazy val compiled = PlutusV3.compile(Escrow.validate)
    lazy val blueprint = Blueprint.plutusV3[EscrowDatum, EscrowAction](
      title = "Escrow",
      description = "The buyer releases the price to the seller, or takes it back after the deadline.",
      version = "0.1.0",
      license = Some("Apache-2.0"),
      compiled = compiled
    )
}

object MultisigContract extends Contract {
    private given Options = Options.release
    lazy val compiled = PlutusV3.compile(Multisig.validate)
    lazy val blueprint = Blueprint.plutusV3[MultisigDatum, Unit](
      title = "Multisig",
      description = "A treasury spent by any threshold of its signers.",
      version = "0.1.0",
      license = Some("Apache-2.0"),
      compiled = compiled
    )
}

object OneShotNftContract extends Contract {
    private given Options = Options.release
    lazy val compiled = PlutusV3.compile(OneShotNft.validate)
    lazy val blueprint = Blueprint.plutusV3[TxOutRef, Unit, NftAction](
      title = "One-shot NFT",
      description = "A minting policy parameterized by a seed UTxO: it can mint one token, once.",
      version = "0.1.0",
      license = Some("Apache-2.0"),
      // The seed is applied as Data, so `compiled` is `Data => Data => Unit`; the cast only relabels the
      // phantom type so the blueprint describes the parameter as a TxOutRef. Script and hash are unchanged.
      compiled = compiled.asInstanceOf[PlutusV3[TxOutRef => Data => Unit]]
    )
}
