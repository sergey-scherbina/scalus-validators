package validators

import java.nio.file.{Files, Path}
import scalus.uplc.builtin.{ByteString, Data}
import scalus.uplc.builtin.Data.toData
import scalus.cardano.onchain.plutus.v1.PubKeyHash
import scalus.cardano.onchain.plutus.v3.{TxId, TxOutRef}
import scalus.uplc.PlutusV3

/** Writes the scripts as cardano-cli text envelopes, and examples of datums and redeemers in cardano-cli's
  * detailed JSON schema, into `artifacts/`.
  *
  * {{{
  * sbt "runMain validators.Export"                     # the three spend validators
  * sbt "runMain validators.Export <txHash>#<index>"    # and the NFT policy for that seed UTxO
  * }}}
  */
@main def Export(seed: String*): Unit = {
    val out = Path.of("artifacts")
    Files.createDirectories(out)

    def envelope(name: String, cborHex: String, hash: String): Unit = {
        val json =
            s"""{
               |  "type": "PlutusScriptV3",
               |  "description": "$name, script hash $hash",
               |  "cborHex": "$cborHex"
               |}
               |""".stripMargin
        Files.writeString(out.resolve(s"$name.plutus"), json)
        println(s"$name.plutus  hash $hash")
    }

    def save(name: String, compiled: PlutusV3[?]): Unit =
        envelope(name, compiled.program.doubleCborHex, compiled.script.scriptHash.toHex)

    save("vesting", VestingContract.compiled)
    save("escrow", EscrowContract.compiled)
    save("multisig", MultisigContract.compiled)

    seed.headOption.foreach { s =>
        val Array(hash, index) = s.split('#')
        val ref = TxOutRef(TxId(ByteString.fromHex(hash)), BigInt(index))
        save("one-shot-nft", OneShotNftContract.compiled(ref.toData))
    }

    // Examples for the off-chain side: what a datum and a redeemer look like on the wire.
    def example(name: String, data: Data): Unit =
        Files.writeString(out.resolve(s"$name.json"), Data.toJson(data, 2))
    val pkh1 = ByteString.fromHex("11" * 28)
    val pkh2 = ByteString.fromHex("22" * 28)
    example("vesting-datum", VestingDatum(PubKeyHash(pkh1), PubKeyHash(pkh2), BigInt(1767225600000L)).toData)
    example("vesting-claim", VestingAction.Claim.toData)
    val escrow = EscrowDatum(PubKeyHash(pkh1), PubKeyHash(pkh2), BigInt(50_000_000), BigInt(1767225600000L))
    example("escrow-datum", escrow.toData)
    example("escrow-release", EscrowAction.Release.toData)
    example("nft-mint", NftAction.Mint(ByteString.fromString("artwork-1")).toData)
    println(s"examples in $out")
}
